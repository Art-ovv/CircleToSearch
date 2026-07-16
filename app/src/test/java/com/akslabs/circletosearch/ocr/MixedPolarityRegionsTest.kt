package com.akslabs.circletosearch.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MixedPolarityRegionsTest {
    @Test
    fun detectsAndCanonicalizesLightTextInsideDarkPanel() {
        val width = 200
        val height = 120
        val pixels = IntArray(width * height) { color(255) }
        fill(pixels, width, 20, 20, 180, 105, color(18))
        fill(pixels, width, 42, 42, 158, 48, color(245))
        fill(pixels, width, 42, 66, 142, 72, color(245))

        val regions = MixedPolarityRegions.detect(
            pixels,
            width,
            height,
            requestedCellSize = 20,
        )

        val region = regions.first { it.left <= 42 && it.right >= 158 && it.top <= 42 && it.bottom >= 72 }
        val canonical = MixedPolarityRegions.canonicalize(pixels, width, height, region, border = 4)
        val background = grayAt(canonical, 30 - region.left + 4, 30 - region.top + 4)
        val foreground = grayAt(canonical, 60 - region.left + 4, 44 - region.top + 4)
        assertTrue(background >= 215)
        assertTrue(foreground <= 80)
    }

    @Test
    fun ignoresOrdinaryDarkTextOnWhitePage() {
        val width = 160
        val height = 80
        val pixels = IntArray(width * height) { color(255) }
        fill(pixels, width, 20, 30, 140, 35, color(20))

        assertTrue(
            MixedPolarityRegions.detect(
                pixels,
                width,
                height,
                requestedCellSize = 20,
            ).isEmpty()
        )
    }

    @Test
    fun detectsWhiteOnLightGrayLowContrastPanel() {
        val width = 180
        val height = 100
        val pixels = IntArray(width * height) { color(255) }
        fill(pixels, width, 20, 20, 160, 80, color(220))
        fill(pixels, width, 35, 45, 145, 51, color(255))

        val regions = MixedPolarityRegions.detect(
            pixels,
            width,
            height,
            requestedCellSize = 20,
        )

        assertTrue(regions.any { it.left <= 35 && it.right >= 145 && it.top <= 45 && it.bottom >= 51 })
    }

    @Test
    fun capsFallbackRegionCount() {
        val width = 300
        val height = 160
        val pixels = IntArray(width * height) { color(255) }
        listOf(10, 110, 210).forEach { left ->
            fill(pixels, width, left, 20, left + 80, 140, color(10))
            fill(pixels, width, left + 10, 60, left + 70, 66, color(250))
        }

        val regions = MixedPolarityRegions.detect(
            pixels,
            width,
            height,
            maxRegions = 2,
            requestedCellSize = 20,
        )

        assertEquals(2, regions.size)
    }

    private fun fill(
        pixels: IntArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        color: Int,
    ) {
        for (y in top until bottom) {
            for (x in left until right) pixels[y * width + x] = color
        }
    }

    private fun color(gray: Int): Int =
        0xFF000000.toInt() or (gray shl 16) or (gray shl 8) or gray

    private fun grayAt(image: CanonicalOcrPixels, x: Int, y: Int): Int =
        image.pixels[y * image.width + x] and 0xFF
}
