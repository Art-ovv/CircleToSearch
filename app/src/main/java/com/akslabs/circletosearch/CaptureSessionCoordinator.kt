package com.akslabs.circletosearch

internal enum class CaptureSource {
    ACCESSIBILITY,
    SYSTEM_SCREENSHOT,
}

/** Thread-safe per-invocation gate used by the two asynchronous screenshot sources. */
internal class CaptureSessionCoordinator {
    private var invocationId: Long? = null
    private var accessibilityAttempted = false
    private var winner: CaptureSource? = null

    @Synchronized
    fun begin(newInvocationId: Long): Boolean {
        if (invocationId == newInvocationId) return false
        invocationId = newInvocationId
        accessibilityAttempted = false
        winner = null
        return true
    }

    @Synchronized
    fun shouldStartAccessibility(expectedInvocationId: Long): Boolean {
        if (invocationId != expectedInvocationId || accessibilityAttempted || winner != null) {
            return false
        }
        accessibilityAttempted = true
        return true
    }

    @Synchronized
    fun tryComplete(expectedInvocationId: Long, source: CaptureSource): Boolean {
        if (invocationId != expectedInvocationId || winner != null) return false
        winner = source
        return true
    }

    @Synchronized
    fun hasWinner(expectedInvocationId: Long): Boolean =
        invocationId == expectedInvocationId && winner != null

    @Synchronized
    fun cancel(expectedInvocationId: Long) {
        if (invocationId != expectedInvocationId) return
        invocationId = null
        accessibilityAttempted = false
        winner = null
    }
}
