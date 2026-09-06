package com.paddle.ocr.postprocess

import org.junit.Assert.assertEquals
import org.junit.Test

class CropOrientationTest {
    @Test
    fun moderatelyTallCropKeepsHorizontalRecognitionAxis() {
        assertOrientation(40, 50, false)
    }

    @Test
    fun rotationUsesInclusiveOnePointFiveThreshold() {
        assertOrientation(40, 59, false)
        assertOrientation(40, 60, true)
        assertOrientation(40, 61, true)
    }

    @Test
    fun squareAndWideCropsKeepHorizontalRecognitionAxis() {
        assertOrientation(40, 40, false)
        assertOrientation(80, 40, false)
    }

    private fun assertOrientation(width: Int, height: Int, rotated: Boolean) {
        val orientation = CropOrientation(width, height)
        assertEquals(rotated, orientation.rotatedCounterClockwise)
        val original = listOf("TL", "TR", "BR", "BL")
        assertEquals(
            if (rotated) listOf("TR", "BR", "BL", "TL") else original,
            orientation.recognitionCorners(original),
        )
    }
}
