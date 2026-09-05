package com.akslabs.circletosearch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * Activity-owned handoff between the screen OCR pipeline and translation.
 *
 * Readiness is separate from list emptiness so translation can distinguish an in-flight scan
 * from a completed scan that legitimately found no text. The volatile snapshot makes later
 * AssistStructure enrichment visible even after the one-shot readiness signal has completed.
 */
internal class ScreenTranslationTextCoordinator {
    @Volatile
    private var snapshot: List<ScreenTranslationNode> = emptyList()
    private val ready = CompletableDeferred<Unit>()

    fun publish(nodes: List<ScreenTranslationNode>, analysisComplete: Boolean) {
        snapshot = nodes
        if (analysisComplete) ready.complete(Unit)
    }

    suspend fun awaitSnapshot(): List<ScreenTranslationNode> {
        ready.await()
        return snapshot
    }

    fun isReady(): Boolean = ready.isCompleted && !ready.isCancelled

    fun cancel() {
        ready.cancel(CancellationException("Screen text analysis was replaced"))
        snapshot = emptyList()
    }
}
