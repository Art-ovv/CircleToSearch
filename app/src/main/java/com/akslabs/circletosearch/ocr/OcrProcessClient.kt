package com.akslabs.circletosearch.ocr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.IBinder
import android.os.SharedMemory
import com.paddle.ocr.model.OCRBox
import com.paddle.ocr.model.OCRError
import com.paddle.ocr.model.OCRResult
import com.paddle.ocr.model.OCRRunResult
import com.paddle.ocr.model.OCRTextSpan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** All public operations are serialized by PaddleOcrEngine's mutex, including model mutations. */
internal class OcrProcessClient(context: Context) {
    private val context = context.applicationContext
    private var connection: Connection? = null
    private val sequence = AtomicLong()

    private class Reply(val status: Int, val memory: SharedMemory?) : AutoCloseable {
        override fun close() { memory?.close() }
    }

    private inner class Connection : ServiceConnection {
        val ready = CompletableDeferred<IOcrWorker>()
        val died = CompletableDeferred<Unit>()
        val pending = AtomicReference<OcrReplySlot<Reply>?>(null)
        @Volatile var worker: IOcrWorker? = null
        var bound = false // Main thread only.
        val deathRecipient = IBinder.DeathRecipient {
            died.complete(Unit)
            fail()
        }

        fun fail() {
            val error = IOException("OCR process disconnected")
            ready.completeExceptionally(error)
            pending.get()?.fail(error)
        }

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            // A framework reconnect must not replace a dead generation underneath its waiter.
            if (!bound || worker != null) return
            try {
                binder.linkToDeath(deathRecipient, 0)
                val service = IOcrWorker.Stub.asInterface(binder)
                worker = service
                ready.complete(service)
            } catch (_: android.os.RemoteException) {
                died.complete(Unit)
                fail()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) { fail() }
        override fun onBindingDied(name: ComponentName) { fail() }
        override fun onNullBinding(name: ComponentName) { fail() }
    }

    private suspend fun connect(): Pair<Connection, IOcrWorker> {
        var current = connection
        if (current?.died?.isCompleted == true) {
            close()
            current = null
        }
        if (current == null) {
            current = Connection()
            connection = current
            val created = current
            withContext(Dispatchers.Main.immediate) {
                // Android requires unbind even when bindService returns false or throws SecurityException.
                created.bound = true
                val accepted = context.bindService(
                    Intent(context, OcrWorkerService::class.java), created, Context.BIND_AUTO_CREATE,
                )
                check(accepted) { "Unable to bind OCR process" }
            }
        }
        return current to withTimeout(10_000L) { current.ready.await() }
    }

    suspend fun warmUp(packId: String) { request(packId, null) }

    suspend fun recognize(packId: String, bitmap: Bitmap): OCRRunResult {
        val wire = checkNotNull(request(packId, bitmap))
        fun box(points: List<Float>) = OCRBox(points.chunked(2).map { PointF(it[0], it[1]) })
        return OCRRunResult(
            results = wire.lines.map { line ->
                OCRResult(
                    box = box(line.box), text = line.text, confidence = line.confidence,
                    recognitionBox = box(line.recognitionBox),
                    textSpans = line.spans.map { OCRTextSpan(it.start, it.end, it.left, it.right) },
                )
            },
            detectionTimeMs = 0, recognitionTimeMs = 0, totalTimeMs = wire.elapsedMs,
            lineCount = wire.lines.size,
        )
    }

    private suspend fun request(packId: String, bitmap: Bitmap?): OcrWireResult? = withContext(Dispatchers.IO) {
        val slot = OcrReplySlot<Reply>()
        val id = sequence.incrementAndGet()
        var active: Connection? = null
        var service: IOcrWorker? = null
        try {
            val (connected, remote) = connect()
            active = connected
            service = remote
            connected.pending.set(slot)
            val callback = object : IOcrWorkerCallback.Stub() {
                override fun complete(requestId: Long, status: Int, result: SharedMemory?) {
                    val reply = Reply(status, result)
                    if (requestId != id) reply.close() else slot.complete(reply)
                }
            }
            val transfer = bitmap?.let(OcrSharedMemory::fromBitmap)
            try {
                remote.submit(id, packId, transfer?.memory, transfer?.width ?: 0,
                    transfer?.height ?: 0, transfer?.rowBytes ?: 0, callback)
            } finally { transfer?.close() }
            val reply = withTimeout(90_000L) { slot.await() }
            when (reply.status) {
                OcrWorkerStatus.OK -> if (bitmap == null) null else
                    OcrWireCodec.decode(OcrSharedMemory.toBytes(checkNotNull(reply.memory)))
                OcrWorkerStatus.TOO_COMPLEX -> throw OCRError.InputTooComplex("detector limit reached")
                OcrWorkerStatus.CANCELLED -> throw CancellationException("OCR request cancelled")
                else -> throw IOException("OCR worker failed")
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                val finished = if (service == null) false else try {
                    service.cancel(id)
                    withTimeoutOrNull(2_000L) { slot.await(); true } == true
                } catch (_: Exception) { false }
                // Never let a cancelled native run overlap the next request or a model deletion.
                if (!finished) close()
            }
            throw error
        } catch (error: OCRError.InputTooComplex) {
            throw error
        } catch (error: Exception) {
            close()
            throw error
        } finally {
            active?.pending?.compareAndSet(slot, null)
            slot.close()
        }
    }

    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        val current = connection ?: return@withContext
        connection = null
        val worker = current.worker
        try {
            // Drop BIND_AUTO_CREATE first, otherwise Android can restart the intentionally killed worker.
            withContext(Dispatchers.Main.immediate) {
                if (current.bound) {
                    current.bound = false
                    try { context.unbindService(current) } catch (_: IllegalArgumentException) {
                        // Binding failed before the framework registered this connection.
                    }
                }
            }
        } finally {
            if (worker != null && !current.died.isCompleted) {
                try { worker.shutdown() } catch (_: android.os.RemoteException) { }
            }
        }
        if (worker != null) {
            // Binder death is the acknowledgement that native mappings no longer own model files.
            withTimeout(5_000L) { current.died.await() }
            worker.asBinder().unlinkToDeath(current.deathRecipient, 0)
        }
    }
}
