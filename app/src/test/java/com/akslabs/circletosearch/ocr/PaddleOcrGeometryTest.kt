package com.akslabs.circletosearch.ocr

import com.paddle.ocr.model.OCRTextSpan
import org.junit.Assert.assertEquals
import org.junit.Test

class PaddleOcrGeometryTest {
    @Test
    fun usesCtcSpansAfterLeadingWhitespaceWasTrimmed() {
        val fractions = PaddleOcrEngine.resolveWordFractions(
            fullText = "😀 foo",
            matchStart = 3,
            matchEnd = 6,
            rawMatchStart = 5,
            rawMatchEnd = 8,
            spans = listOf(
                OCRTextSpan(5, 6, 0.55f, 0.65f),
                OCRTextSpan(6, 7, 0.65f, 0.75f),
                OCRTextSpan(7, 8, 0.75f, 0.85f),
            ),
        )

        assertEquals(0.55f, fractions.first, 0.0001f)
        assertEquals(0.85f, fractions.second, 0.0001f)
    }

    @Test
    fun unicodeFallbackCountsCodePointsInsteadOfUtf16Units() {
        val fractions = PaddleOcrEngine.resolveWordFractions(
            fullText = "😀 a",
            matchStart = 0,
            matchEnd = 2,
            rawMatchStart = 0,
            rawMatchEnd = 2,
            spans = emptyList(),
        )

        assertEquals(0f, fractions.first, 0.0001f)
        assertEquals(1f / 3f, fractions.second, 0.0001f)
    }
}
