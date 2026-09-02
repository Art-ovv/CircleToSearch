package com.akslabs.circletosearch

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionCoordinatorTest {
    @Test
    fun cannotStartOrCompleteBeforeBegin() {
        val coordinator = CaptureSessionCoordinator()

        assertFalse(coordinator.shouldStartAccessibility(Long.MIN_VALUE))
        assertFalse(coordinator.tryComplete(Long.MIN_VALUE, CaptureSource.ACCESSIBILITY))
    }

    @Test
    fun sameInvocationIsIdempotentAndNewInvocationResetsState() {
        val coordinator = CaptureSessionCoordinator()

        assertTrue(coordinator.begin(10L))
        assertFalse(coordinator.begin(10L))
        assertTrue(coordinator.shouldStartAccessibility(10L))
        assertFalse(coordinator.shouldStartAccessibility(10L))

        assertTrue(coordinator.begin(11L))
        assertTrue(coordinator.shouldStartAccessibility(11L))
    }

    @Test
    fun accessibilityAttemptDoesNotConsumeSystemFallback() {
        val coordinator = CaptureSessionCoordinator()
        coordinator.begin(42L)

        assertTrue(coordinator.shouldStartAccessibility(42L))
        assertTrue(coordinator.tryComplete(42L, CaptureSource.SYSTEM_SCREENSHOT))
        assertFalse(coordinator.tryComplete(42L, CaptureSource.ACCESSIBILITY))
    }

    @Test
    fun staleInvocationCannotWin() {
        val coordinator = CaptureSessionCoordinator()
        coordinator.begin(1L)
        coordinator.shouldStartAccessibility(1L)
        coordinator.begin(2L)

        assertFalse(coordinator.tryComplete(1L, CaptureSource.ACCESSIBILITY))
        assertTrue(coordinator.tryComplete(2L, CaptureSource.SYSTEM_SCREENSHOT))
    }

    @Test
    fun concurrentCompletionsHaveExactlyOneWinner() {
        val coordinator = CaptureSessionCoordinator()
        coordinator.begin(7L)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val ready = CountDownLatch(8)
            val start = CountDownLatch(1)
            val attempts = (0 until 8).map { index ->
                Callable {
                    ready.countDown()
                    start.await()
                    coordinator.tryComplete(
                        7L,
                        if (index % 2 == 0) CaptureSource.ACCESSIBILITY else CaptureSource.SYSTEM_SCREENSHOT,
                    )
                }
            }

            val futures = attempts.map(executor::submit)
            ready.await()
            start.countDown()
            val winners = futures.count { it.get() }
            assertEquals(1, winners)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun cancelRejectsLateCompletion() {
        val coordinator = CaptureSessionCoordinator()
        coordinator.begin(99L)
        coordinator.cancel(99L)

        assertFalse(coordinator.tryComplete(99L, CaptureSource.SYSTEM_SCREENSHOT))
    }

    @Test
    fun transientFailureCanReleaseAndRetryAccessibilityAttempt() {
        val coordinator = CaptureSessionCoordinator()
        coordinator.begin(100L)

        assertTrue(coordinator.shouldStartAccessibility(100L))
        assertTrue(coordinator.releaseAccessibilityAttempt(100L))
        assertTrue(coordinator.shouldStartAccessibility(100L))
    }

    @Test
    fun winningCaptureCannotBeReleasedForRetry() {
        val coordinator = CaptureSessionCoordinator()
        coordinator.begin(101L)

        assertTrue(coordinator.shouldStartAccessibility(101L))
        assertTrue(coordinator.tryComplete(101L, CaptureSource.SYSTEM_SCREENSHOT))
        assertFalse(coordinator.releaseAccessibilityAttempt(101L))
    }
}
