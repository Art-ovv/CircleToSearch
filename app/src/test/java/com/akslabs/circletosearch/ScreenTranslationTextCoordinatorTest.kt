package com.akslabs.circletosearch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTranslationTextCoordinatorTest {
    @Test
    fun waitsForFinalAnalysisAndReturnsItsNodes() = runBlocking {
        val coordinator = ScreenTranslationTextCoordinator()
        val awaitingSnapshot = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.awaitSnapshot()
        }

        coordinator.publish(listOf(node("intermediate")), analysisComplete = false)
        assertFalse(awaitingSnapshot.isCompleted)
        assertFalse(coordinator.isReady())

        val finalNodes = listOf(node("final"))
        coordinator.publish(finalNodes, analysisComplete = true)

        assertEquals(finalNodes, awaitingSnapshot.await())
        assertTrue(coordinator.isReady())
    }

    @Test
    fun completedEmptyAnalysisDoesNotRemainPending() = runBlocking {
        val coordinator = ScreenTranslationTextCoordinator()

        coordinator.publish(emptyList(), analysisComplete = true)

        assertEquals(emptyList<ScreenTranslationNode>(), coordinator.awaitSnapshot())
        assertTrue(coordinator.isReady())
    }

    @Test
    fun cancellationReleasesPendingWaiterWithoutPublishingStaleNodes() = runBlocking {
        val coordinator = ScreenTranslationTextCoordinator()
        val awaitingSnapshot = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.awaitSnapshot()
        }
        coordinator.publish(listOf(node("stale")), analysisComplete = false)

        coordinator.cancel()

        assertFalse(coordinator.isReady())
        try {
            awaitingSnapshot.await()
            throw AssertionError("Pending snapshot unexpectedly completed")
        } catch (_: CancellationException) {
            // Expected: a replacement screenshot must cancel the old translation wait.
        }
    }

    private fun node(text: String) = ScreenTranslationNode(
        text = text,
        left = 1,
        top = 2,
        right = 3,
        bottom = 4,
    )
}
