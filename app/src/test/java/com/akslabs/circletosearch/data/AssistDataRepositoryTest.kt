package com.akslabs.circletosearch.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistDataRepositoryTest {
    @After
    fun tearDown() {
        AssistDataRepository.clearAll()
    }

    @Test
    fun stalePublisherCannotOverwriteNewCapture() {
        AssistDataRepository.begin("old")
        AssistDataRepository.begin("new")

        assertFalse(AssistDataRepository.publish("old", emptyList(), 100, 200))
        assertEquals("new", AssistDataRepository.snapshot.value.token)
        assertFalse(AssistDataRepository.snapshot.value.ready)
    }

    @Test
    fun matchingPublisherCompletesSnapshot() {
        AssistDataRepository.begin("capture")

        assertTrue(AssistDataRepository.publish("capture", emptyList(), 1080, 2400))
        assertTrue(AssistDataRepository.snapshot.value.ready)
        assertEquals(1080, AssistDataRepository.snapshot.value.coordinateWidth)
        assertEquals(2400, AssistDataRepository.snapshot.value.coordinateHeight)
    }

    @Test
    fun oldOverlayCannotClearNewCapture() {
        AssistDataRepository.begin("new")

        AssistDataRepository.clear("old")

        assertEquals("new", AssistDataRepository.snapshot.value.token)
    }
}
