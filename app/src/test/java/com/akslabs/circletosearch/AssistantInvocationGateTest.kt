package com.akslabs.circletosearch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantInvocationGateTest {
    @Test
    fun sessionCanTakeOverRecoveryBeforeRecoveryCommits() {
        val sessionId = 10_001L
        val lease = AssistantInvocationGate.prepare(sessionId)

        assertTrue(AssistantInvocationGate.claimRecovery(lease))
        assertNotNull(AssistantInvocationGate.claimSession(sessionId))
        assertTrue(AssistantInvocationGate.isSessionOwner(lease))
        assertFalse(AssistantInvocationGate.commitRecovery(lease))
    }

    @Test
    fun committedRecoveryRejectsLateSession() {
        val sessionId = 10_002L
        val lease = AssistantInvocationGate.prepare(sessionId)

        assertTrue(AssistantInvocationGate.claimRecovery(lease))
        assertTrue(AssistantInvocationGate.commitRecovery(lease))
        assertNull(AssistantInvocationGate.claimSession(sessionId))
        assertTrue(AssistantInvocationGate.isRecoveryCommitted(lease))
    }

    @Test
    fun staleCallbacksCannotReplaceNewInvocation() {
        val staleId = 10_003L
        val currentId = 10_004L
        AssistantInvocationGate.prepare(staleId)
        val currentLease = AssistantInvocationGate.prepare(currentId)

        assertNull(AssistantInvocationGate.claimSession(staleId))
        assertNull(AssistantInvocationGate.leaseForFailure(staleId))
        assertNotNull(AssistantInvocationGate.claimSession(currentId))
        assertTrue(AssistantInvocationGate.isSessionOwner(currentLease))
    }

    @Test
    fun completedSessionIdCanStartANewGeneration() {
        val sessionId = 10_005L
        val firstLease = AssistantInvocationGate.prepare(sessionId)
        assertNotNull(AssistantInvocationGate.claimSession(sessionId))
        assertTrue(AssistantInvocationGate.complete(firstLease))

        val secondLease = AssistantInvocationGate.prepare(sessionId)

        assertTrue(secondLease.generation > firstLease.generation)
        assertNotNull(AssistantInvocationGate.claimSession(sessionId))
    }

    @Test
    fun abandoningOldLeaseDoesNotClearNewInvocation() {
        val oldLease = AssistantInvocationGate.prepare(10_006L)
        val newLease = AssistantInvocationGate.prepare(10_007L)

        assertFalse(AssistantInvocationGate.abandon(oldLease))
        assertNotNull(AssistantInvocationGate.claimSession(newLease.sessionId))
        assertTrue(AssistantInvocationGate.isSessionOwner(newLease))
    }

    @Test
    fun externalWatchdogCannotRecoverCompletedInvocation() {
        val sessionId = 10_008L
        val lease = AssistantInvocationGate.prepare(sessionId)
        assertNotNull(AssistantInvocationGate.claimSession(sessionId))
        assertTrue(AssistantInvocationGate.complete(lease))

        assertNull(AssistantInvocationGate.beginExternalRecovery(sessionId))
    }

    @Test
    fun externalRecoveryCanBeTakenOverByLateRealSession() {
        val sessionId = 10_009L
        val resetLease = AssistantInvocationGate.prepare(sessionId - 1L)
        assertTrue(AssistantInvocationGate.abandon(resetLease))
        val lease = AssistantInvocationGate.beginExternalRecovery(sessionId)
        assertNotNull(lease)

        assertNotNull(AssistantInvocationGate.claimSession(sessionId))
        assertTrue(AssistantInvocationGate.isSessionOwner(checkNotNull(lease)))
    }

    @Test
    fun staleExternalWatchdogCannotReplaceNewerSession() {
        val currentId = 10_011L
        val currentLease = AssistantInvocationGate.prepare(currentId)
        assertNotNull(AssistantInvocationGate.claimSession(currentId))

        assertNull(AssistantInvocationGate.beginExternalRecovery(10_010L))
        assertTrue(AssistantInvocationGate.isSessionOwner(currentLease))
    }

    @Test
    fun newerRealSessionReplacesCompletedPreviousInvocation() {
        val previousId = 10_012L
        val previousLease = AssistantInvocationGate.prepare(previousId)
        assertNotNull(AssistantInvocationGate.claimSession(previousId))
        assertTrue(AssistantInvocationGate.complete(previousLease))

        val nextLease = AssistantInvocationGate.claimSession(previousId + 1L)

        assertNotNull(nextLease)
        assertTrue(AssistantInvocationGate.isSessionOwner(checkNotNull(nextLease)))
    }

    @Test
    fun lateRecoveryFailureCannotAbandonSessionThatTookOver() {
        val resetLease = AssistantInvocationGate.prepare(20_000L)
        assertTrue(AssistantInvocationGate.abandon(resetLease))
        val recoveryLease = checkNotNull(AssistantInvocationGate.beginExternalRecovery(20_001L))

        assertNotNull(AssistantInvocationGate.claimSession(recoveryLease.sessionId))
        assertFalse(AssistantInvocationGate.abandonRecovery(recoveryLease))
        assertTrue(AssistantInvocationGate.isSessionOwner(recoveryLease))
    }
}
