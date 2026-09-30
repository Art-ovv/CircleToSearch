package com.akslabs.circletosearch.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrTextCacheTest {
    private val cache = OcrTextCache<Any, List<String>>()

    @Test
    fun sameScreenshotReusesResultIncludingEmptyResult() {
        val source = Any()
        val ticket = cache.begin(source)
        cache.putIfCurrent(source, ticket, emptyList())
        assertEquals(emptyList<String>(), cache.get(source, cache.begin(source)))
    }

    @Test
    fun closedScreenshotCannotPublishLateResult() {
        val source = Any()
        val ticket = cache.begin(source)
        cache.clear(source)
        cache.putIfCurrent(source, ticket, listOf("synthetic"))
        assertNull(cache.get(source, ticket))
        assertNull(cache.get(source, cache.begin(source)))
    }

    @Test
    fun oldOverlayCleanupAndLateResultDoNotEraseNewScreenshot() {
        val oldSource = Any()
        val oldTicket = cache.begin(oldSource)
        val newSource = Any()
        val newTicket = cache.begin(newSource)
        cache.putIfCurrent(newSource, newTicket, listOf("new synthetic result"))
        cache.clear(oldSource)
        cache.putIfCurrent(oldSource, oldTicket, listOf("old synthetic result"))
        assertEquals(listOf("new synthetic result"), cache.get(newSource, newTicket))
    }

    @Test
    fun engineOrLanguagePackInvalidationRejectsPreviousResult() {
        val source = Any()
        val oldTicket = cache.begin(source)
        cache.putIfCurrent(source, oldTicket, listOf("previous pack"))
        cache.clear()
        val newTicket = cache.begin(source)
        cache.putIfCurrent(source, oldTicket, listOf("late previous pack"))
        assertNull(cache.get(source, newTicket))
        cache.putIfCurrent(source, newTicket, listOf("current pack"))
        assertEquals(listOf("current pack"), cache.get(source, newTicket))
    }
}
