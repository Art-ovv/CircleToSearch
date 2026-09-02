package com.akslabs.circletosearch.ocr

import android.graphics.Rect
import android.graphics.RectF
import com.akslabs.circletosearch.ui.components.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TesseractEnginePolicyTest {
    @Test
    fun newerRequestStopsActiveOlderRequestAndInvalidatesItsGeneration() {
        val stopped = mutableListOf<String>()
        val gate = LatestOcrRequestGate<String> { stopped += it }
        val firstGeneration = gate.begin()
        val firstToken = checkNotNull(gate.activate(firstGeneration, "first"))

        val secondGeneration = gate.begin()

        assertEquals(listOf("first"), stopped)
        assertFalse(gate.isLatest(firstGeneration))
        assertTrue(gate.isLatest(secondGeneration))
        gate.deactivate(firstToken)
        assertTrue(gate.activate(secondGeneration, "second") != null)
    }

    @Test
    fun staleRequestCannotEnterNativeRecognition() {
        val gate = LatestOcrRequestGate<String> { }
        val staleGeneration = gate.begin()
        gate.begin()

        assertTrue(gate.activate(staleGeneration, "stale") == null)
    }

    @Test
    fun targetedTransformMapsRecognitionBoundsBackToGlobalScreenshot() {
        val transform = TesseractEngine.RecognitionTransform(
            recognitionScaleX = 1f,
            recognitionScaleY = 1f,
            border = 0f,
            processedOffsetX = 200f,
            processedOffsetY = 400f,
            processedScale = 2f,
            sourceWidth = 1080,
            sourceHeight = 2400,
        )

        val recognitionBounds = Rect().apply {
            left = 20
            top = 40
            right = 120
            bottom = 80
        }
        val mapped = transform.map(recognitionBounds)

        assertEquals(110f, mapped.left, 0.001f)
        assertEquals(220f, mapped.top, 0.001f)
        assertEquals(160f, mapped.right, 0.001f)
        assertEquals(240f, mapped.bottom, 0.001f)
    }

    @Test
    fun cleaningPreservesCodeOperatorsAndMachineIdentifiers() {
        assertEquals(
            "value < limit && value > 0",
            TesseractEngine.cleanRecognizedWordText(
                " value < limit && value > 0 ",
                lineDominantCyrillic = true,
            ),
        )
        assertEquals(
            "SN:FCW1915B1AE",
            TesseractEngine.cleanRecognizedWordText(
                "SN:FCW1915B1AE",
                lineDominantCyrillic = true,
            ),
        )
        assertEquals(
            "AB12345",
            TesseractEngine.cleanRecognizedWordText(
                "AB12345",
                lineDominantCyrillic = true,
            ),
        )
        assertEquals(
            "https://example.com/A0",
            TesseractEngine.cleanRecognizedWordText(
                "https://example.com/A0",
                lineDominantCyrillic = true,
            ),
        )
    }

    @Test
    fun cleaningStillRepairsLookalikesInOrdinaryWords() {
        assertEquals(
            "Россия",
            TesseractEngine.cleanRecognizedWordText(
                "Рoccия",
                lineDominantCyrillic = true,
            ),
        )
    }

    @Test
    fun strongDistributedPrimaryResultSkipsGlobalDeskew() {
        val words = (0 until 12).map { index ->
            ranked(
                text = "word$index",
                confidence = 88f,
                pass = OcrPass.PRIMARY,
                passIdentity = 0,
                left = 20f + (index % 3) * 180f,
                top = 20f + (index / 3) * 180f,
                right = 150f + (index % 3) * 180f,
                bottom = 60f + (index / 3) * 180f,
            )
        }

        val quality = assessPrimaryOcrQuality(words, imageWidth = 600, imageHeight = 800)

        assertFalse(shouldRunGlobalDeskew(quality))
    }

    @Test
    fun localOrWeakPrimaryResultKeepsEnhancementEnabled() {
        val words = listOf(
            ranked("one", 92f, OcrPass.PRIMARY, 0, 10f, 10f, 80f, 40f),
            ranked("two", 91f, OcrPass.PRIMARY, 0, 90f, 10f, 160f, 40f),
        )

        assertTrue(
            shouldRunGlobalDeskew(
                assessPrimaryOcrQuality(words, imageWidth = 1080, imageHeight = 2400),
            ),
        )
    }

    @Test
    fun fallbackIsSkippedOnlyWhenPrimaryAlreadyCoversItsRegionStrongly() {
        val region = OcrFallbackRegion(0, 0, 600, 300, score = 100)
        val covered = (0 until 6).map { index ->
            ranked(
                text = "text$index",
                confidence = 90f,
                pass = OcrPass.PRIMARY,
                passIdentity = 0,
                left = 10f + index * 90f,
                top = 30f + (index % 2) * 90f,
                right = 80f + index * 90f,
                bottom = 70f + (index % 2) * 90f,
            )
        }

        assertFalse(shouldRunFallbackRegion(region, covered, processedScale = 1f))
        assertTrue(shouldRunFallbackRegion(region, covered.take(2), processedScale = 1f))
    }

    @Test
    fun overlappingUnrelatedWordsAreNotDiscardedByGeometryAlone() {
        val words = listOf(
            ranked("Photo", 90f, OcrPass.PRIMARY, 0, 0f, 0f, 100f, 30f),
            ranked("Settings", 88f, OcrPass.MIXED_POLARITY, 1, 40f, 0f, 140f, 30f),
        )

        assertEquals(2, mergeRankedOcrWords(words).size)
    }

    @Test
    fun exactCrossPassConsensusCanBeatOneHigherConfidenceTypo() {
        val words = listOf(
            ranked("Setlings", 86f, OcrPass.PRIMARY, 0, 0f, 0f, 100f, 30f),
            ranked("Settings", 78f, OcrPass.MIXED_POLARITY, 1, 0f, 0f, 100f, 30f),
            ranked("Settings", 80f, OcrPass.DESKEW, 2, 0f, 0f, 100f, 30f),
        )

        val merged = mergeRankedOcrWords(words)

        assertEquals(1, merged.size)
        assertEquals("Settings", merged.single().word.text)
        assertEquals(2L shl 32, merged.single().lineIdentity)
    }

    private fun ranked(
        text: String,
        confidence: Float,
        pass: OcrPass,
        passIdentity: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) = RankedOcrWord(
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
        pass = pass,
        passIdentity = passIdentity,
        lineIdentity = (passIdentity.toLong() shl 32),
    )
}
