package com.akslabs.circletosearch.ui

import org.junit.Assert.*
import org.junit.Test

class SearchTabCacheTest {
    private data class Tab(var history: String, var destroyed: Boolean = false)
    private val destroyed = mutableListOf<Tab>()
    private val cache = SearchTabCache<String, Tab, String>(
        2,
        { check(!it.destroyed); it.history },
        { check(!it.destroyed); it.destroyed = true; destroyed += it },
    )
    private fun open(key: String) = cache.acquire(key) { Tab(it ?: key) }

    @Test fun thirdTabEvictsLeastRecentlySelectedAndRestoresHistory() {
        val first = open("a").apply { history = "a > detail" }
        val second = open("b")
        assertSame(second, open("b"))
        val third = open("c")
        assertEquals(listOf(first), destroyed)
        assertEquals(listOf(second, third), cache.values)
        val restored = open("a")
        assertNotSame(first, restored)
        assertEquals("a > detail", restored.history)
        assertTrue(second.destroyed)
        assertFalse(third.destroyed)
    }

    @Test fun returningToLiveTabRefreshesItsRecency() {
        val first = open("a")
        val second = open("b")
        assertSame(first, open("a"))
        open("c")
        assertEquals(listOf(second), destroyed)
        assertFalse(first.destroyed)
    }

    @Test fun backgroundKeepsCurrentTabAndSavesOtherForLater() {
        val first = open("a").apply { history = "detail" }
        val current = open("b")
        cache.keepOnly("b")
        cache.keepOnly("b")
        assertEquals(listOf(first), destroyed)
        assertEquals(listOf(current), cache.values)
        assertEquals("detail", open("a").history)
    }

    @Test fun closeOrNewSearchClearsLiveAndSavedStateIdempotently() {
        open("a").history = "old history"
        open("b")
        open("c")
        cache.clear()
        cache.clear()
        assertEquals(3, destroyed.size)
        assertTrue(cache.values.isEmpty())
        assertEquals("a", open("a").history)
    }

    @Test fun failedCreationDoesNotLoseSavedHistoryOrExceedCapacity() {
        open("a").history = "detail"
        open("b")
        open("c")
        try {
            cache.acquire("a") { throw IllegalStateException("synthetic failure") }
            fail("Expected creation failure")
        } catch (_: IllegalStateException) {}
        assertTrue(cache.values.size < 2)
        assertEquals("detail", open("a").history)
    }

    @Test fun backgroundWithNoCurrentViewDestroysAllLiveTabs() {
        open("a")
        open("b")
        cache.keepOnly("not loaded yet")
        assertTrue(cache.values.isEmpty())
        assertEquals(2, destroyed.size)
    }
}
