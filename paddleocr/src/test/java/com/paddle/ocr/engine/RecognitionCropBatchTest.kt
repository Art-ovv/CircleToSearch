package com.paddle.ocr.engine

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class RecognitionCropBatchTest {
    @Test
    fun cancellationBetweenCropsReleasesPreparedCrop() {
        val released = mutableListOf<Int>()
        val cancellation = CancellationException("cancel")
        var checks = 0
        try {
            withRecognitionCropBatch(listOf(0, 1), 0, 2,
                cancellationCheck = { if (++checks == 2) throw cancellation },
                createCrop = { it }, isUsable = { true }, release = { released += it },
                recognize = { _, _ -> fail("Must not recognize incomplete cancelled batch") })
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
        assertEquals(listOf(0), released)
    }

    @Test
    fun secondCropFailureReleasesFirstCrop() {
        val released = mutableListOf<Int>()
        val failure = IllegalStateException("crop failed")
        try {
            withRecognitionCropBatch(listOf(0, 1), 0, 2, {},
                createCrop = { if (it == 1) throw failure else it },
                isUsable = { true }, release = { released += it },
                recognize = { _, _ -> fail("Must not recognize") })
            fail("Expected failure")
        } catch (error: IllegalStateException) {
            assertSame(failure, error)
        }
        assertEquals(listOf(0), released)
    }

    @Test
    fun unusableCropIsReleasedAndIndicesStayAligned() {
        val released = mutableListOf<Int>()
        val next = withRecognitionCropBatch(listOf(3, 2, 1, 0), 0, 2, {}, { it },
            isUsable = { it != 2 }, release = { released += it }) { crops, indices ->
            assertEquals(listOf(3, 1), crops)
            assertEquals(listOf(3, 1), indices)
            assertTrue(released.isEmpty())
        }
        assertEquals(3, next)
        assertEquals(listOf(3, 2, 1), released)
    }

    @Test
    fun inferenceFailureReleasesEveryCropExactlyOnce() {
        val released = mutableListOf<Int>()
        val failure = IllegalStateException("inference failed")
        try {
            withRecognitionCropBatch(listOf(1, 2), 0, 2, {}, { it }, { true }, { released += it }) { _, _ ->
                throw failure
            }
            fail("Expected failure")
        } catch (error: IllegalStateException) {
            assertSame(failure, error)
        }
        assertEquals(listOf(1, 2), released)
    }

    @Test
    fun validationFailureAlsoReleasesNewCrop() {
        val released = mutableListOf<Int>()
        try {
            withRecognitionCropBatch(listOf(1), 0, 1, {}, { it },
                isUsable = { throw IllegalStateException("invalid") }, release = { released += it }) { _, _ ->
                fail("Must not recognize")
            }
            fail("Expected failure")
        } catch (_: IllegalStateException) {
            assertEquals(listOf(1), released)
        }
    }
}
