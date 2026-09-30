package com.akslabs.circletosearch.ocr

import kotlinx.coroutines.CompletableDeferred

/** Owns even a reply delivered at the same instant its awaiting caller is cancelled. */
internal class OcrReplySlot<T : AutoCloseable> : AutoCloseable {
    private val ready = CompletableDeferred<T>()
    private var reply: T? = null
    private var closed = false

    @Synchronized fun complete(value: T) {
        if (closed || ready.isCompleted) {
            value.close()
        } else {
            reply = value
            ready.complete(value)
        }
    }

    @Synchronized fun fail(error: Throwable) {
        ready.completeExceptionally(error)
    }

    suspend fun await(): T = ready.await()

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        reply?.close()
        reply = null
        ready.cancel()
    }
}
