package com.akslabs.circletosearch.ocr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class OcrReplySlotTest {
    private class Resource : AutoCloseable {
        var closes = 0
        override fun close() { closes++ }
    }

    @Test fun successfulReplyIsReleasedOnceAfterConsumption() = runBlocking {
        val slot = OcrReplySlot<Resource>()
        val resource = Resource()
        slot.complete(resource)
        assertSame(resource, slot.await())
        assertEquals(0, resource.closes)
        slot.close()
        slot.close()
        assertEquals(1, resource.closes)
    }

    @Test fun lateAndDuplicateRepliesAreReleasedWithoutReplacingResult() = runBlocking {
        val slot = OcrReplySlot<Resource>()
        val first = Resource()
        val duplicate = Resource()
        val late = Resource()
        slot.complete(first)
        slot.complete(duplicate)
        assertSame(first, slot.await())
        assertEquals(1, duplicate.closes)
        slot.close()
        slot.complete(late)
        assertEquals(1, late.closes)
        assertEquals(1, first.closes)
    }

    @Test fun deathBeforeReplyFailsWaiterAndReleasesLateResource() = runBlocking {
        val slot = OcrReplySlot<Resource>()
        slot.fail(IOException("synthetic death"))
        try { slot.await(); fail("Expected failure") } catch (_: IOException) {}
        val late = Resource()
        slot.complete(late)
        slot.close()
        assertEquals(1, late.closes)
    }

    @Test fun cancellationBetweenReplyAndResumptionDoesNotLeak() = runBlocking {
        val slot = OcrReplySlot<Resource>()
        val resource = Resource()
        var consumed = false
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            try { slot.await(); consumed = true } finally { slot.close() }
        }
        slot.complete(resource)
        waiter.cancel()
        waiter.join()
        assertFalse(consumed)
        assertEquals(1, resource.closes)
    }

    @Test fun closedOldSessionCannotConsumeNewSessionsReply() = runBlocking {
        val old = OcrReplySlot<Resource>()
        old.close()
        val fresh = OcrReplySlot<Resource>()
        val stale = Resource()
        val current = Resource()
        old.complete(stale)
        fresh.complete(current)
        assertSame(current, fresh.await())
        assertEquals(1, stale.closes)
        assertEquals(0, current.closes)
        fresh.close()
    }

    @Test fun closingBeforeReplyCancelsWaiter() = runBlocking {
        val slot = OcrReplySlot<Resource>()
        slot.close()
        try { slot.await(); fail("Expected cancellation") } catch (_: CancellationException) {}
    }
}
