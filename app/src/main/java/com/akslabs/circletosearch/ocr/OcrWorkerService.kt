package com.akslabs.circletosearch.ocr

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.SharedMemory
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.OCRError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** The only component allowed to load ONNX/OpenCV; it owns only transient OCR state. */
class OcrWorkerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requestLock = Any()
    private var activeId: Long? = null
    private var activeJob: Job? = null
    private var engine: PaddleOCR? = null
    private var enginePackId: String? = null

    private fun checkCaller() {
        if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Private OCR service")
    }

    private val binder = object : IOcrWorker.Stub() {
        override fun submit(id: Long, packId: String, pixels: SharedMemory?, width: Int, height: Int,
                            rowBytes: Int, callback: IOcrWorkerCallback) {
            try {
                checkCaller()
                require(OcrLanguageCatalog.getPack(packId) != null)
                if (pixels != null) OcrSharedMemory.validatePixels(width, height, rowBytes, pixels.size)
                synchronized(requestLock) {
                    check(activeId == null) { "OCR request already running" }
                    var result: SharedMemory? = null
                    var status = OcrWorkerStatus.CANCELLED
                    val job = scope.launch(start = CoroutineStart.LAZY) {
                        try {
                            val current = ensureEngine(packId)
                            if (pixels != null) {
                                val bitmap = OcrSharedMemory.toBitmap(pixels, width, height, rowBytes)
                                pixels.close()
                                val run = try { current.recognize(bitmap) } finally { bitmap.recycle() }
                                val wire = OcrWireResult(run.totalTimeMs, run.results.map { line ->
                                    OcrWireLine(
                                        line.text, line.confidence,
                                        line.box.points.flatMap { listOf(it.x, it.y) },
                                        line.recognitionBox.points.flatMap { listOf(it.x, it.y) },
                                        line.textSpans.map { OcrWireSpan(it.startIndex, it.endIndex, it.startFraction, it.endFraction) },
                                    )
                                })
                                result = OcrSharedMemory.fromBytes(OcrWireCodec.encode(wire))
                            }
                            status = OcrWorkerStatus.OK
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: OCRError.InputTooComplex) {
                            status = OcrWorkerStatus.TOO_COMPLEX
                        } catch (_: Exception) {
                            status = OcrWorkerStatus.FAILED
                        }
                    }
                    activeId = id
                    activeJob = job
                    job.invokeOnCompletion { error ->
                        pixels?.close()
                        synchronized(requestLock) {
                            activeId = null
                            activeJob = null
                        }
                        try {
                            callback.complete(id, if (error != null) OcrWorkerStatus.CANCELLED else status, result)
                        } catch (_: android.os.RemoteException) {
                            // The requesting process disappeared; no screen content is logged.
                        } finally {
                            result?.close()
                        }
                    }
                    job.start()
                }
            } catch (error: Exception) {
                pixels?.close()
                throw error
            }
        }

        override fun cancel(id: Long) {
            checkCaller()
            synchronized(requestLock) { if (activeId == id) activeJob?.cancel() }
        }

        override fun shutdown() {
            checkCaller()
            terminateWorker()
        }
    }

    override fun onCreate() {
        super.onCreate()
        check(Application.getProcessName() == "$packageName:ocr")
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        // Losing the sole app binding (including parent process death) must not leave native heaps cached.
        terminateWorker()
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
        terminateWorker()
    }

    private fun terminateWorker() {
        check(Application.getProcessName() == "$packageName:ocr")
        Process.killProcess(Process.myPid())
    }

    private suspend fun ensureEngine(packId: String): PaddleOCR {
        engine?.let { if (enginePackId == packId) return it }
        engine?.release()
        engine = null
        enginePackId = null
        check(com.paddle.ocr.util.OpenCVUtils.init(this)) { "OpenCV initialization failed" }
        val pack = requireNotNull(OcrLanguageCatalog.getPack(packId))
        val config = PaddleOCRConfig(
            detMaxSideLimit = 2560, detMaxPixelCount = 2_500_000,
            detBoxThresh = 0.5f, detMaxCandidates = 512, recScoreThresh = 0.35f, recBatchSize = 4,
        )
        val loaded = if (pack.isBundled) {
            PaddleOCR.create(this, config, EngineConfig(numThreads = 4),
                "paddleocr/det/inference.onnx", "paddleocr/rec/inference.onnx", "paddleocr/rec/inference.yml")
        } else {
            val storage = OcrLanguageStorage(java.io.File(noBackupFilesDir ?: filesDir, "ocr_models"))
            val files = storage.getInstalledModelFiles(packId)
                ?: throw java.io.FileNotFoundException("OCR model is not installed")
            PaddleOCR.create(this, config, EngineConfig(numThreads = 4),
                "paddleocr/det/inference.onnx", files.modelFile, files.configFile)
        }
        engine = loaded
        enginePackId = packId
        return loaded
    }
}

internal object OcrWorkerStatus {
    const val OK = 0
    const val FAILED = 1
    const val TOO_COMPLEX = 2
    const val CANCELLED = 3
}
