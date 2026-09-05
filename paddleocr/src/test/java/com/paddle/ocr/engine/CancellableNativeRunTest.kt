package com.paddle.ocr.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CancellableNativeRunTest {
    @Test
    fun cancellationTerminatesBlockingRunExactlyOnce() = runBlocking {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val terminateCalls = AtomicInteger(0)
        val completedNormally = AtomicBoolean(false)

        val job = launch(Dispatchers.Default) {
            runPromptCancellable<String>(
                terminate = {
                    terminateCalls.incrementAndGet()
                    unblock.countDown()
                },
                run = {
                    entered.countDown()
                    check(unblock.await(5, TimeUnit.SECONDS))
                    "late result"
                },
            )
            completedNormally.set(true)
        }

        assertTrue(entered.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()

        assertEquals(1, terminateCalls.get())
        assertFalse(completedNormally.get())
        assertTrue(job.isCancelled)
    }

    @Test
    fun successfulRunDoesNotInvokeTerminate() = runBlocking {
        val terminateCalls = AtomicInteger(0)

        val result = runPromptCancellable(
            terminate = { terminateCalls.incrementAndGet() },
            run = { "done" },
        )

        assertEquals("done", result)
        assertEquals(0, terminateCalls.get())
    }

    @Test
    fun terminationExceptionDoesNotReplaceCancellation() = runBlocking {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)

        val job = launch(Dispatchers.Default) {
            runPromptCancellable<String>(
                terminate = {
                    unblock.countDown()
                    throw IllegalStateException("expected test failure")
                },
                run = {
                    entered.countDown()
                    check(unblock.await(5, TimeUnit.SECONDS))
                    "late result"
                },
            )
        }

        assertTrue(entered.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }
}
