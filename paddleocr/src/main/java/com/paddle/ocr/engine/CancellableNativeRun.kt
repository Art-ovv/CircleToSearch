package com.paddle.ocr.engine

import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Bridges a blocking native call to prompt coroutine cancellation.
 *
 * [run] still executes on the caller's dispatcher. Cancellation can concurrently invoke
 * [terminate], allowing the native call to return without closing its reusable session.
 */
internal suspend fun <T : Any> runPromptCancellable(
    terminate: () -> Unit,
    run: (isActive: () -> Boolean) -> T?,
): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        try {
            terminate()
        } catch (_: Exception) {
            // The original coroutine cancellation remains the primary outcome.
        }
    }
    if (!continuation.isActive) return@suspendCancellableCoroutine

    try {
        val value = run { continuation.isActive }
        if (value != null && continuation.isActive) {
            continuation.resumeWith(Result.success(value))
        } else if (value == null && continuation.isActive) {
            continuation.resumeWith(
                Result.failure(IllegalStateException("Native run returned no result while active")),
            )
        }
    } catch (error: Error) {
        throw error
    } catch (error: Exception) {
        if (continuation.isActive) {
            continuation.resumeWith(Result.failure(error))
        }
    }
}
