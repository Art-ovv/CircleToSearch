package com.akslabs.circletosearch.ocr

import android.graphics.RectF
import com.akslabs.circletosearch.ui.components.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrCandidateFilterTest {
    @Test
    fun rejectsPunctuationAndIsolatedLowConfidenceShortShapes() {
        val candidates = listOf(
            candidate("••", 99f, 0, 0f, 0f, 20f, 20f),
            candidate("O", 80f, 1, 40f, 0f, 54f, 18f),
        )

        assertTrue(filterOcrCandidates(candidates, density = 1f).isEmpty())
    }

    @Test
    fun acceptsLongLowContrastTextAtAdjustedConfidenceFloor() {
        val candidates = listOf(
            candidate("visible", 55f, 0, 0f, 0f, 70f, 18f),
            candidate("hidden", 54f, 1, 0f, 30f, 60f, 48f),
        )

        assertEquals(listOf("visible"), filterOcrCandidates(candidates, 1f).map { it.text })
    }

    @Test
    fun sameLineTextCorroboratesAShortWord() {
        val candidates = listOf(
            candidate("I", 62f, 3, 0f, 0f, 8f, 18f),
            candidate("agree", 84f, 3, 12f, 0f, 62f, 18f),
        )

        assertEquals(listOf("I", "agree"), filterOcrCandidates(candidates, 1f).map { it.text })
    }

    @Test
    fun unrelatedLineDoesNotCorroborateAShortShape() {
        val candidates = listOf(
            candidate("O", 70f, 2, 0f, 0f, 16f, 18f),
            candidate("Settings", 90f, 3, 20f, 40f, 100f, 58f),
        )

        assertEquals(listOf("Settings"), filterOcrCandidates(candidates, 1f).map { it.text })
    }

    @Test
    fun rejectsLargeSquareSingleCharacterEvenAtHighConfidence() {
        val candidates = listOf(candidate("8", 99f, 0, 0f, 0f, 32f, 32f))

        assertTrue(filterOcrCandidates(candidates, density = 1f).isEmpty())
    }

    @Test
    fun rejectsCompactMultiCharacterIconHallucination() {
        val candidates = listOf(candidate("wifi", 96f, 0, 0f, 0f, 32f, 32f))

        assertTrue(filterOcrCandidates(candidates, density = 1f).isEmpty())
    }

    @Test
    fun nearbyTextDoesNotRescueALargeSquareIcon() {
        val candidates = listOf(
            candidate("O", 95f, 2, 0f, 0f, 30f, 30f),
            candidate("Settings", 92f, 2, 36f, 5f, 116f, 25f),
        )

        assertEquals(listOf("Settings"), filterOcrCandidates(candidates, 1f).map { it.text })
    }

    @Test
    fun sameLinePeerPreservesLegitimateLargeShortLabel() {
        val candidates = listOf(
            candidate("OPEN", 96f, 4, 0f, 0f, 32f, 25f),
            candidate("Document", 94f, 4, 38f, 2f, 118f, 22f),
        )

        assertEquals(listOf("OPEN", "Document"), filterOcrCandidates(candidates, 1f).map { it.text })
    }

    @Test
    fun geometricPeerPreservesLargeCyrillicShortWordAcrossSparseTextLines() {
        val candidates = listOf(
            candidate("Да", 72f, 7, 0f, 0f, 45f, 60f),
            candidate("как", 88f, 8, 52f, 2f, 115f, 60f),
        )

        assertEquals(listOf("Да", "как"), filterOcrCandidates(candidates, 3f).map { it.text })
    }

    @Test
    fun veryConfidentIsolatedCyrillicShortWordSurvives() {
        val candidates = listOf(candidate("Да", 95f, 0, 0f, 0f, 45f, 60f))

        assertEquals(listOf("Да"), filterOcrCandidates(candidates, 3f).map { it.text })
    }

    @Test
    fun veryConfidentIsolatedLargeOpenLabelSurvives() {
        val candidates = listOf(candidate("OPEN", 99f, 0, 0f, 0f, 60f, 45f))

        assertEquals(listOf("OPEN"), filterOcrCandidates(candidates, 2f).map { it.text })
    }

    @Test
    fun strongGeometricPeerRescuesLowConfidenceLongWord() {
        val candidates = listOf(
            candidate("проверь", 48f, 10, 0f, 0f, 100f, 30f),
            candidate("информацию", 91f, 11, 108f, 1f, 250f, 31f),
        )

        assertEquals(listOf("проверь", "информацию"), filterOcrCandidates(candidates, 1f).map { it.text })
    }

    @Test
    fun acceptsVeryConfidentCompactShortText() {
        val candidates = listOf(candidate("OK", 92f, 0, 0f, 0f, 24f, 16f))

        assertEquals(listOf("OK"), filterOcrCandidates(candidates, 1f).map { it.text })
    }

    private fun candidate(
        text: String,
        confidence: Float,
        textLine: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) = OcrCandidate(
        word = Word(
            text = text,
            index = 0,
            startIndex = 0,
            endIndex = text.length,
            bounds = RectF().apply {
                this.left = left
                this.top = top
                this.right = right
                this.bottom = bottom
            },
        ),
        confidence = confidence,
        textLine = textLine,
    )
}
