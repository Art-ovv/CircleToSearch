package com.akslabs.circletosearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantLifecyclePolicyTest {
    @Test
    fun roleWithoutActiveVoiceServiceIsDisconnected() {
        assertEquals(
            AssistantActivationState.DISCONNECTED,
            classifyAssistantActivation(activeService = false, roleHeld = true),
        )
    }

    @Test
    fun activeVoiceServiceWinsOverRoleSnapshot() {
        assertEquals(
            AssistantActivationState.ACTIVE,
            classifyAssistantActivation(activeService = true, roleHeld = false),
        )
    }

    @Test
    fun missingShowCallbackMakesMatchingPrepareTerminal() {
        assertTrue(
            shouldFinishUnshownAssistantSession(
                expectedInvocationId = 42L,
                activeInvocationId = 42L,
                shown = false,
                finishRequested = false,
                destroyed = false,
            ),
        )
    }

    @Test
    fun prepareWatchdogCannotFinishNewerOrShownSession() {
        assertFalse(
            shouldFinishUnshownAssistantSession(
                expectedInvocationId = 41L,
                activeInvocationId = 42L,
                shown = false,
                finishRequested = false,
                destroyed = false,
            ),
        )
        assertFalse(
            shouldFinishUnshownAssistantSession(
                expectedInvocationId = 42L,
                activeInvocationId = 42L,
                shown = true,
                finishRequested = false,
                destroyed = false,
            ),
        )
    }
}
