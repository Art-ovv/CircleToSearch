package com.akslabs.circletosearch

import android.graphics.PointF
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akslabs.circletosearch.ocr.PaddleOcrEngine
import com.paddle.ocr.model.OCRBox
import com.paddle.ocr.model.OCRResult
import com.paddle.ocr.model.OCRTextSpan
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaddleWordGeometryInstrumentedTest {
    @Test
    fun moderatelyTallUnrotatedCropProjectsWordsHorizontally() {
        for (height in listOf(50f, 59f)) {
            val corners = corners(height)
            assertEquals(RectF(0f, 0f, 20f, height), firstWord(corners, corners))
        }
    }

    @Test
    fun counterClockwiseCropProjectsWordsFromTopToBottom() {
        for (height in listOf(60f, 61f)) {
            val corners = corners(height)
            val rotated = listOf(corners[1], corners[2], corners[3], corners[0])
            assertEquals(RectF(0f, 0f, 40f, height / 2), firstWord(corners, rotated))
        }
    }

    @Test
    fun usesActualCropGeometryWithRegionScaleAndOffset() {
        val detector = corners(50f)
        val crop = listOf(PointF(2f, 4f), PointF(38f, 4f), PointF(38f, 48f), PointF(2f, 48f))
        assertEquals(RectF(11f, 22f, 20f, 44f), firstWord(detector, crop, 0.5f, 10f, 20f))
    }

    private fun corners(height: Float) =
        listOf(PointF(0f, 0f), PointF(40f, 0f), PointF(40f, height), PointF(0f, height))

    private fun firstWord(
        detector: List<PointF>,
        crop: List<PointF>,
        scale: Float = 1f,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ): RectF = PaddleOcrEngine.mapResultsToTextNodes(
        results = listOf(OCRResult(
            box = OCRBox(detector),
            text = "one two",
            confidence = 1f,
            textSpans = listOf(OCRTextSpan(0, 3, 0f, 0.5f), OCRTextSpan(4, 7, 0.5f, 1f)),
            recognitionBox = OCRBox(crop),
        )),
        sourceWidth = 200,
        sourceHeight = 200,
        scaleX = scale,
        scaleY = scale,
        offsetX = offsetX,
        offsetY = offsetY,
    ).single().words.first().bounds
}
