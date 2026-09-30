package com.akslabs.circletosearch.ocr

import java.lang.ref.WeakReference

/** A single screenshot's result, invalidated even if its OCR has not finished yet. */
internal class OcrTextCache<K : Any, V : Any> {
    private var source = WeakReference<K>(null)
    private var generation = 0L
    private var value: V? = null

    @Synchronized
    fun begin(key: K): Long {
        if (source.get() !== key) {
            clear()
            source = WeakReference(key)
        }
        return generation
    }

    @Synchronized
    fun get(key: K, ticket: Long): V? =
        value.takeIf { source.get() === key && generation == ticket }

    @Synchronized
    fun putIfCurrent(key: K, ticket: Long, result: V) {
        if (source.get() === key && generation == ticket) value = result
    }

    @Synchronized
    fun clear(key: K) {
        if (source.get() === key) clear()
    }

    @Synchronized
    fun clear() {
        generation++
        source.clear()
        value = null
    }
}
