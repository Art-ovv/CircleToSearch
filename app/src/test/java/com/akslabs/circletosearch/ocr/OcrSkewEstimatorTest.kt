package com.akslabs.circletosearch.ocr

import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrSkewEstimatorTest {
    @Test
    fun returnsNullForStraightHorizontalTextEdges() {
        assertNull(OcrSkewEstimator.estimateDegrees(stripedImage(angle = 0), 360, 220))
    }

    @Test
    fun estimatesDominantSmallTextAngle() {
        val estimated = OcrSkewEstimator.estimateDegrees(stripedImage(angle = 6), 360, 220)

        assertEquals(6f, checkNotNull(estimated), 1f)
    }

    @Test
    fun rejectsImagesWithoutEnoughEdges() {
        val pixels = IntArray(100 * 100) { gray(240) }
        assertNull(OcrSkewEstimator.estimateDegrees(pixels, 100, 100))
    }

    private fun stripedImage(angle: Int): IntArray {
        val width = 360
        val height = 220
        val pixels = IntArray(width * height) { gray(245) }
        val slope = tan(Math.toRadians(angle.toDouble()))
        listOf(45, 90, 135, 180).forEach { baseY ->
            for (x in 8 until width - 8) {
                val lineY = (baseY + slope * (x - width / 2)).toInt()
                for (offset in 0..3) {
                    val y = lineY + offset
                    if (y in 0 until height) pixels[y * width + x] = gray(20)
                }
            }
        }
        return pixels
    }

    private fun gray(value: Int): Int =
        0xFF000000.toInt() or (value shl 16) or (value shl 8) or value
}
