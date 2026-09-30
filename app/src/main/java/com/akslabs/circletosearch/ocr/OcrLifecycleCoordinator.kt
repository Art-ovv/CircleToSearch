package com.akslabs.circletosearch.ocr

import android.content.ComponentCallbacks2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal class OcrLifecycleCoordinator(
    private val scope: CoroutineScope,
    private val idleTimeoutMs: Long,
    private val releaseMutex: Mutex,
    private val releaseAction: suspend () -> Unit,
    private val onReleaseFailure: (Exception) -> Unit,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val releaseImmediatelyOnUiHidden: Boolean = true,
) {
    private val stateLock = Any()
    private var activeSessions = 0
    private var inFlightOperations = 0
    private var scheduledReleaseJob: Job? = null
    private var releaseGeneration = 0L
    private var releaseWhenIdleRequested = false

    fun acquireSession(): PaddleOcrEngine.OcrSessionLease {
        synchronized(stateLock) {
            activeSessions++
            releaseWhenIdleRequested = false
            cancelScheduledReleaseLocked()
        }
        return object : PaddleOcrEngine.OcrSessionLease {
            private val closed = AtomicBoolean(false)

            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                synchronized(stateLock) {
                    activeSessions--
                    scheduleReleaseIfIdleLocked()
                }
            }
        }
    }

    suspend fun <T> withInFlightOperation(block: suspend () -> T): T {
        synchronized(stateLock) {
            inFlightOperations++
            cancelScheduledReleaseLocked()
        }
        try {
            return block()
        } finally {
            synchronized(stateLock) {
                inFlightOperations--
                scheduleReleaseIfIdleLocked()
            }
        }
    }

    fun requestReleaseIfIdle() {
        synchronized(stateLock) {
            if (isIdleLocked()) scheduleReleaseLocked(0L)
        }
    }

    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        if (
            level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN &&
            level != ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL &&
            level < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) return

        synchronized(stateLock) {
            // A delayed UI-hidden callback must not evict a newly visible overlay's engine.
            if (activeSessions > 0) return
            if (level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || releaseImmediatelyOnUiHidden) {
                releaseWhenIdleRequested = true
            }
            scheduleReleaseIfIdleLocked()
        }
    }

    private fun scheduleReleaseIfIdleLocked() {
        if (isIdleLocked()) {
            scheduleReleaseLocked(if (releaseWhenIdleRequested) 0L else idleTimeoutMs)
        }
    }

    private fun isIdleLocked() = activeSessions == 0 && inFlightOperations == 0

    private fun cancelScheduledReleaseLocked() {
        scheduledReleaseJob?.cancel()
        scheduledReleaseJob = null
        releaseGeneration++
    }

    private fun scheduleReleaseLocked(timeoutMs: Long) {
        cancelScheduledReleaseLocked()
        val generation = releaseGeneration
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (timeoutMs > 0) delayFn(timeoutMs)
            releaseMutex.withLock {
                val claimed = synchronized(stateLock) {
                    if (generation != releaseGeneration || !isIdleLocked()) {
                        false
                    } else {
                        // Reopening can cancel a pending release, but not native cleanup already
                        // committed under the engine mutex. New inference waits for that cleanup.
                        scheduledReleaseJob = null
                        releaseWhenIdleRequested = false
                        true
                    }
                }
                if (claimed) {
                    try {
                        withContext(NonCancellable) { releaseAction() }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        onReleaseFailure(error)
                    }
                }
            }
        }
        scheduledReleaseJob = job
        job.start()
    }
}
