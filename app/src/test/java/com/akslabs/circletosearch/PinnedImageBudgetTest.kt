package com.akslabs.circletosearch

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PinnedImageBudgetTest {
    @Test fun limitRejectsNewPinsWithoutEvictingExistingOnes() {
        val budget = PinnedImageBudget(100)
        val first = budget.reserve(60)!!
        assertNull(budget.reserve(41))
        val second = budget.reserve(40)!!
        assertNull(budget.reserve(1))
        first.close()
        assertNotNull(budget.reserve(60))
        second.close()
        assertNotNull(budget.reserve(40))
    }

    @Test fun deletedPinRemainsAccountedUntilSaveAndShareComplete() {
        val budget = PinnedImageBudget(100)
        val pin = budget.reserve(100)!!
        val save = pin.retain()!!
        val share = pin.retain()!!
        pin.close()
        assertNull(budget.reserve(1))
        save.close()
        assertNull(budget.reserve(1))
        share.close()
        assertNotNull(budget.reserve(100))
    }

    @Test fun closingOperationBeforePinDoesNotReleaseVisibleImage() {
        val budget = PinnedImageBudget(100)
        val pin = budget.reserve(100)!!
        pin.retain()!!.close()
        assertNull(budget.reserve(1))
        pin.close()
        assertNotNull(budget.reserve(100))
    }

    @Test fun repeatedCloseCannotUnderCountAndClosedLeaseCannotRetain() {
        val budget = PinnedImageBudget(100)
        val pin = budget.reserve(100)!!
        pin.close()
        pin.close()
        assertNull(pin.retain())
        assertNotNull(budget.reserve(100))
        assertNull(budget.reserve(1))
    }

    @Test fun oversizedAllocationDoesNotConsumeBudgetOrOverflow() {
        val budget = PinnedImageBudget(100)
        assertNull(budget.reserve(Long.MAX_VALUE))
        assertNotNull(budget.reserve(100))
    }

    @Test fun cancelledBeforeExecutionStillReleasesOperationLease() {
        val budget = PinnedImageBudget(100)
        val pin = budget.reserve(100)!!
        val operation = pin.retain()!!
        val cancelledScope = CoroutineScope(Job().apply { cancel() } + Dispatchers.Unconfined)
        var executed = false
        cancelledScope.launch { executed = true }.invokeOnCompletion { operation.close() }
        assertFalse(executed)
        pin.close()
        assertNotNull(budget.reserve(100))
    }
}
