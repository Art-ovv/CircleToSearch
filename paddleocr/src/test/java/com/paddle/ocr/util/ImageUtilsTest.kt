package com.paddle.ocr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageUtilsTest {
    @Test
    fun upscalesSmallSideAndKeepsMultiplesOf32() {
        val dimensions = ImageUtils.calculateResizeDimensions(
            sourceHeight = 20,
            sourceWidth = 100,
            limitSideLen = 64,
            limitType = "min",
            maxSideLimit = 2560,
            maxPixelCount = 2_500_000,
        )

        assertEquals(64, dimensions.height)
        assertEquals(320, dimensions.width)
    }

    @Test
    fun boundsLargeInputBySideAndPixelBudgetAfterRounding() {
        val dimensions = ImageUtils.calculateResizeDimensions(
            sourceHeight = 4000,
            sourceWidth = 4000,
            limitSideLen = 64,
            limitType = "min",
            maxSideLimit = 2560,
            maxPixelCount = 2_500_000,
        )

        assertEquals(0, dimensions.height % 32)
        assertEquals(0, dimensions.width % 32)
        assertTrue(maxOf(dimensions.height, dimensions.width) <= 2560)
        assertTrue(dimensions.height.toLong() * dimensions.width <= 2_500_000L)
    }
}
