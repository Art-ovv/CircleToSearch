package com.akslabs.circletosearch.ocr

import android.content.ComponentCallbacks2
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

@Suppress("DEPRECATION")
class OcrLifecycleTest {
    private val dispatcher = QueuedDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val engineMutex = Mutex()
    private var timer = CompletableDeferred<Unit>()
    private var timeout: Long? = null
    private var releaseCount = 0
    private val failures = mutableListOf<Exception>()
    private var release: suspend () -> Unit = { releaseCount++ }
    private val coordinator = OcrLifecycleCoordinator(
        scope = scope,
        idleTimeoutMs = 45_000L,
        releaseMutex = engineMutex,
        delayFn = {
            timeout = it
            timer.await()
        },
        releaseAction = { release() },
        onReleaseFailure = { failures.add(it) },
    )

    @After
    fun tearDown() {
        scope.cancel()
        dispatcher.runCurrent()
    }

    @Test
    fun releasesAfterStoppedOverlayAndGracePeriod() {
        val lease = coordinator.acquireSession()
        dispatcher.runCurrent()
        assertEquals(0, releaseCount)
        lease.close()
        dispatcher.runCurrent()
        assertEquals(45_000L, timeout)
        assertEquals(0, releaseCount)
        expireTimer()
        assertEquals(1, releaseCount)
    }

    @Test
    fun fastReopeningAndRepeatedCloseCannotReleaseNewSession() {
        val oldLease = coordinator.acquireSession()
        oldLease.close()
        dispatcher.runCurrent()
        val newLease = coordinator.acquireSession()
        oldLease.close()
        expireTimer()
        assertEquals(0, releaseCount)
        timer = CompletableDeferred()
        newLease.close()
        dispatcher.runCurrent()
        expireTimer()
        assertEquals(1, releaseCount)
    }

    @Test
    fun overlappingOverlayRecreationKeepsEngineWarm() {
        val oldLease = coordinator.acquireSession()
        val newLease = coordinator.acquireSession()
        oldLease.close()
        coordinator.requestReleaseIfIdle()
        expireTimer()
        assertEquals(0, releaseCount)
        newLease.close()
        dispatcher.runCurrent()
        assertEquals(1, releaseCount)
    }

    @Test
    fun queuedReleaseRechecksSessionAfterWaitingForEngineMutex() {
        assertTrue(engineMutex.tryLock())
        coordinator.acquireSession().close()
        expireTimer()
        val lease = coordinator.acquireSession()
        engineMutex.unlock()
        dispatcher.runCurrent()
        assertEquals(0, releaseCount)
        lease.close()
        dispatcher.runCurrent()
        assertEquals(1, releaseCount)
    }

    @Test
    fun idleRequestReturnsBeforeNativeCleanupAndWaitsForMutex() {
        val cleanupFinished = CompletableDeferred<Unit>()
        release = {
            cleanupFinished.await()
            releaseCount++
        }
        assertTrue(engineMutex.tryLock())
        coordinator.requestReleaseIfIdle()
        dispatcher.runCurrent()
        assertEquals(0, releaseCount)
        engineMutex.unlock()
        dispatcher.runCurrent()
        assertTrue(engineMutex.isLocked)
        assertEquals(0, releaseCount)
        cleanupFinished.complete(Unit)
        dispatcher.runCurrent()
        assertFalse(engineMutex.isLocked)
        assertEquals(1, releaseCount)
    }

    @Test
    fun newInferenceWaitsForCommittedCleanupWithoutCancellingIt() {
        val cleanupFinished = CompletableDeferred<Unit>()
        release = {
            cleanupFinished.await()
            releaseCount++
        }
        coordinator.requestReleaseIfIdle()
        dispatcher.runCurrent()
        val lease = coordinator.acquireSession()
        var inferenceRan = false
        scope.launch {
            coordinator.withInFlightOperation {
                engineMutex.withLock { inferenceRan = true }
            }
        }
        dispatcher.runCurrent()
        assertFalse(inferenceRan)
        cleanupFinished.complete(Unit)
        dispatcher.runCurrent()
        assertEquals(1, releaseCount)
        assertTrue(inferenceRan)
        expireTimer()
        assertEquals(1, releaseCount)
        lease.close()
    }

    @Test
    fun newOcrCancelsOldTimerAndStartsFreshGracePeriodAfterCompletion() {
        coordinator.acquireSession().close()
        dispatcher.runCurrent()
        val oldTimer = timer
        val inferenceFinished = CompletableDeferred<Unit>()
        scope.launch {
            coordinator.withInFlightOperation { inferenceFinished.await() }
        }
        dispatcher.runCurrent()
        oldTimer.complete(Unit)
        dispatcher.runCurrent()
        assertEquals(0, releaseCount)
        timer = CompletableDeferred()
        inferenceFinished.complete(Unit)
        dispatcher.runCurrent()
        assertEquals(0, releaseCount)
        expireTimer()
        assertEquals(1, releaseCount)
    }

    @Test
    fun closingOverlayWaitsForItsRemainingOcrBeforeStartingTimer() {
        val lease = coordinator.acquireSession()
        val inferenceFinished = CompletableDeferred<Unit>()
        scope.launch {
            coordinator.withInFlightOperation { inferenceFinished.await() }
        }
        dispatcher.runCurrent()
        lease.close()
        coordinator.requestReleaseIfIdle()
        dispatcher.runCurrent()
        assertEquals(null, timeout)
        assertEquals(0, releaseCount)
        inferenceFinished.complete(Unit)
        dispatcher.runCurrent()
        assertEquals(45_000L, timeout)
        expireTimer()
        assertEquals(1, releaseCount)
    }

    @Test
    fun cancelledOcrWithoutOverlayStillSchedulesReleaseAfterFinally() {
        val inferenceFinished = CompletableDeferred<Unit>()
        val operation = scope.launch {
            coordinator.withInFlightOperation { inferenceFinished.await() }
        }
        dispatcher.runCurrent()
        coordinator.requestReleaseIfIdle()
        expireTimer()
        assertEquals(0, releaseCount)
        timer = CompletableDeferred()
        operation.cancel()
        dispatcher.runCurrent()
        assertTrue(operation.isCancelled)
        assertEquals(45_000L, timeout)
        expireTimer()
        assertEquals(1, releaseCount)
    }

    @Test
    fun failedOcrWithoutOverlaySchedulesRelease() {
        scope.launch {
            try {
                coordinator.withInFlightOperation { error("inference failed") }
            } catch (_: IllegalStateException) {
                // The caller handles the original OCR failure.
            }
        }
        dispatcher.runCurrent()
        expireTimer()
        assertEquals(1, releaseCount)
    }

    @Test
    fun overlappingOperationsOnlyScheduleAfterLastCompletion() {
        val firstDone = CompletableDeferred<Unit>()
        val secondDone = CompletableDeferred<Unit>()
        scope.launch { coordinator.withInFlightOperation { firstDone.await() } }
        scope.launch { coordinator.withInFlightOperation { secondDone.await() } }
        dispatcher.runCurrent()
        firstDone.complete(Unit)
        dispatcher.runCurrent()
        expireTimer()
        assertEquals(0, releaseCount)
        secondDone.complete(Unit)
        dispatcher.runCurrent()
        assertEquals(1, releaseCount)
    }

    @Test
    fun releaseFailureDoesNotLeaveMutexLockedOrPreventLaterRelease() {
        val failure = IllegalStateException("close failed")
        release = { throw failure }
        coordinator.requestReleaseIfIdle()
        dispatcher.runCurrent()
        assertEquals(1, failures.size)
        assertEquals(failure.javaClass, failures.single().javaClass)
        assertEquals(failure.message, failures.single().message)
        assertFalse(engineMutex.isLocked)
        release = { releaseCount++ }
        coordinator.acquireSession().close()
        expireTimer()
        assertEquals(1, releaseCount)
    }

    @Test
    fun supportedTrimSignalsReleaseIdleEngineWithoutWaitingForGracePeriod() {
        val levels = listOf(
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        )
        levels.forEachIndexed { index, level ->
            coordinator.acquireSession().close()
            dispatcher.runCurrent()
            coordinator.onTrimMemory(level)
            assertEquals(index, releaseCount)
            dispatcher.runCurrent()
            assertEquals(index + 1, releaseCount)
        }
        expireTimer()
        assertEquals(levels.size, releaseCount)
    }

    @Test
    fun uiHiddenDuringOcrReleasesOnlyAfterLastOperationCompletes() {
        val firstDone = CompletableDeferred<Unit>()
        val secondDone = CompletableDeferred<Unit>()
        scope.launch { coordinator.withInFlightOperation { firstDone.await() } }
        scope.launch { coordinator.withInFlightOperation { secondDone.await() } }
        dispatcher.runCurrent()
        coordinator.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        firstDone.complete(Unit)
        dispatcher.runCurrent()
        assertEquals(0, releaseCount)
        secondDone.complete(Unit)
        dispatcher.runCurrent()
        assertEquals(1, releaseCount)
        assertEquals(null, timeout)
    }

    @Test
    fun backgroundRequestSurvivesCancellationOfRemainingOcr() {
        val operation = scope.launch {
            coordinator.withInFlightOperation { CompletableDeferred<Unit>().await() }
        }
        dispatcher.runCurrent()
        coordinator.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        dispatcher.runCurrent()
        assertEquals(0, releaseCount)
        operation.cancel()
        dispatcher.runCurrent()
        assertEquals(1, releaseCount)
    }

    @Test
    fun delayedUiHiddenSignalDoesNotEvictVisibleOverlayOrShortenItsGracePeriod() {
        val lease = coordinator.acquireSession()
        coordinator.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        dispatcher.runCurrent()
        assertEquals(0, releaseCount)
        lease.close()
        dispatcher.runCurrent()
        assertEquals(0, releaseCount)
        assertEquals(45_000L, timeout)
        expireTimer()
        assertEquals(1, releaseCount)
    }

    @Test
    fun reopeningCancelsTrimReleaseWaitingForEngineMutex() {
        assertTrue(engineMutex.tryLock())
        coordinator.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        dispatcher.runCurrent()
        val lease = coordinator.acquireSession()
        engineMutex.unlock()
        dispatcher.runCurrent()
        assertEquals(0, releaseCount)
        lease.close()
        dispatcher.runCurrent()
        assertEquals(0, releaseCount)
        expireTimer()
        assertEquals(1, releaseCount)
    }

    @Test
    fun newOverlayCancelsDeferredBackgroundReleaseDuringOcr() {
        val operationDone = CompletableDeferred<Unit>()
        scope.launch { coordinator.withInFlightOperation { operationDone.await() } }
        dispatcher.runCurrent()
        coordinator.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        val lease = coordinator.acquireSession()
        operationDone.complete(Unit)
        dispatcher.runCurrent()
        assertEquals(0, releaseCount)
        lease.close()
        dispatcher.runCurrent()
        assertEquals(0, releaseCount)
        expireTimer()
        assertEquals(1, releaseCount)
    }

    @Test
    fun inferenceStartingAfterTrimRequestKeepsDeferredRelease() {
        val operationDone = CompletableDeferred<Unit>()
        scope.launch { coordinator.withInFlightOperation { operationDone.await() } }
        coordinator.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        dispatcher.runCurrent()
        assertEquals(0, releaseCount)
        operationDone.complete(Unit)
        dispatcher.runCurrent()
        assertEquals(1, releaseCount)
    }

    @Test
    fun unhandledTrimLevelsKeepNormalIdleTimeout() {
        coordinator.acquireSession().close()
        dispatcher.runCurrent()
        listOf(0, ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW).forEach(coordinator::onTrimMemory)
        dispatcher.runCurrent()
        assertEquals(0, releaseCount)
        expireTimer()
        assertEquals(1, releaseCount)
    }

    private fun expireTimer() {
        timer.complete(Unit)
        dispatcher.runCurrent()
    }

    @Test
    fun workerKeepsShortGraceOnUiHiddenButReleasesOnMemoryPressure() {
        val worker = OcrLifecycleCoordinator(
            scope, 15_000L, engineMutex, { releaseCount++ }, { failures.add(it) },
            delayFn = { timeout = it; timer.await() }, releaseImmediatelyOnUiHidden = false,
        )
        worker.acquireSession().close()
        worker.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        dispatcher.runCurrent()
        assertEquals(15_000L, timeout)
        assertEquals(0, releaseCount)
        val reopened = worker.acquireSession()
        expireTimer()
        assertEquals(0, releaseCount)
        timer = CompletableDeferred()
        reopened.close()
        worker.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        dispatcher.runCurrent()
        assertEquals(1, releaseCount)
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun runCurrent() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }
}
