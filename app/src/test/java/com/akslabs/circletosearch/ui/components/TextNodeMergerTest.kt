package com.akslabs.circletosearch.ui.components

import android.graphics.Rect
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Test

class TextNodeMergerTest {
    @Test
    fun assistTextReplacesOverlappingOcrButKeepsUncoveredOcr() {
        val assist = node("assist", "Settings", 0, 0, 120, 30)
        val coveredOcr = node("covered", "Setlings", 5, 2, 115, 28)
        val uncoveredOcr = node("uncovered", "Canvas text", 0, 80, 140, 110)

        val result = mergeTextNodes(
            assistNodes = listOf(assist),
            ocrNodes = listOf(coveredOcr, uncoveredOcr),
            bitmapWidth = 200,
            bitmapHeight = 200,
        )

        assertEquals(listOf("covered", "uncovered"), result.map { it.id })
        assertEquals("Settings", result.first().fullText)
        assertBoundsEqual(coveredOcr.words.first().bounds, result.first().words.first().bounds)
    }

    @Test
    fun incompatibleCoordinateSpaceFallsBackToOcr() {
        val assist = node("assist", "Semantic", 0, 0, 100, 30)
        val ocr = node("ocr", "Visible", 0, 0, 100, 30)

        val result = mergeTextNodes(
            assistNodes = listOf(assist),
            ocrNodes = listOf(ocr),
            bitmapWidth = 100,
            bitmapHeight = 200,
            assistCoordinateWidth = 200,
            assistCoordinateHeight = 100,
        )

        assertEquals(listOf("ocr"), result.map { it.id })
    }

    @Test
    fun blankAssistNodeCannotSuppressOcr() {
        val blankAssist = node("assist", "   ", 0, 0, 100, 30)
        val ocr = node("ocr", "Text", 0, 0, 100, 30)

        val result = mergeTextNodes(
            assistNodes = listOf(blankAssist),
            ocrNodes = listOf(ocr),
            bitmapWidth = 100,
            bitmapHeight = 100,
        )

        assertEquals(listOf("ocr"), result.map { it.id })
    }

    @Test
    fun partialAssistCoverageKeepsBroadOcrLine() {
        val assist = node("assist", "Prefix", 0, 0, 60, 30)
        val ocr = node("ocr", "Prefix and remaining text", 0, 0, 100, 30)

        val result = mergeTextNodes(
            assistNodes = listOf(assist),
            ocrNodes = listOf(ocr),
            bitmapWidth = 100,
            bitmapHeight = 100,
        )

        assertEquals(listOf("assist", "ocr"), result.map { it.id })
    }

    @Test
    fun clipsAssistGeometryToBitmap() {
        val assist = node("assist", "Clipped", -20, -10, 120, 40)

        val result = mergeTextNodes(
            assistNodes = listOf(assist),
            ocrNodes = emptyList(),
            bitmapWidth = 100,
            bitmapHeight = 100,
        )

        assertEquals(0, result.single().bounds.left)
        assertEquals(0, result.single().bounds.top)
        assertEquals(100, result.single().bounds.right)
        assertEquals(40, result.single().bounds.bottom)
    }

    @Test
    fun broadUnrelatedAssistContainerDoesNotEraseNestedImageText() {
        val assist = node("assist", "У нас токсичные отношения", 0, 0, 500, 500)
        val nestedOcr = node("ocr", "Да как ты проверь информацию", 100, 220, 420, 255)

        val result = mergeTextNodes(
            assistNodes = listOf(assist),
            ocrNodes = listOf(nestedOcr),
            bitmapWidth = 500,
            bitmapHeight = 500,
        )

        assertEquals(listOf("assist", "ocr"), result.map { it.id })
    }

    @Test
    fun genericAssistImageContainerIsNotExposedAsSelectableText() {
        val assist = node("assist", "Photo", 10, 10, 190, 60)
        val ocr = node("ocr", "только вот рейсы из махачкалы", 10, 10, 190, 60)

        val result = mergeTextNodes(
            assistNodes = listOf(assist),
            ocrNodes = listOf(ocr),
            bitmapWidth = 200,
            bitmapHeight = 100,
        )

        assertEquals(listOf("ocr"), result.map { it.id })
    }

    @Test
    fun russianGenericAssistImageContainerIsNotExposedAsSelectableText() {
        val assist = node("assist", "Фотография", 10, 10, 190, 60)
        val ocr = node("ocr", "только вот рейсы из махачкалы", 10, 10, 190, 60)

        val result = mergeTextNodes(
            assistNodes = listOf(assist),
            ocrNodes = listOf(ocr),
            bitmapWidth = 200,
            bitmapHeight = 100,
        )

        assertEquals(listOf("ocr"), result.map { it.id })
    }

    @Test
    fun broadAssistContainerCanSuppressMatchingOcrText() {
        val assist = node(
            "assist",
            "Ответ: Да как ты проверь информацию, тут ошибка",
            0,
            0,
            500,
            500,
        )
        val nestedOcr = node("ocr", "Да как ты проверь информацию", 100, 220, 420, 255)

        val result = mergeTextNodes(
            assistNodes = listOf(assist),
            ocrNodes = listOf(nestedOcr),
            bitmapWidth = 500,
            bitmapHeight = 500,
        )

        assertEquals(listOf("ocr"), result.map { it.id })
        assertEquals("Да как ты проверь информацию,", result.single().fullText)
        assertBoundsEqual(nestedOcr.words.single().bounds, result.single().words.single().bounds)
    }

    @Test
    fun semanticCorrectionRetainsGranularOcrWordBounds() {
        val assist = node("assist", "Copy serial number", 0, 0, 240, 40)
        val ocr = nodeWithWords(
            id = "ocr",
            words = listOf("Copy", "serlal", "number"),
            left = 5,
            top = 4,
            wordWidth = 70,
            height = 30,
        )

        val result = mergeTextNodes(
            assistNodes = listOf(assist),
            ocrNodes = listOf(ocr),
            bitmapWidth = 260,
            bitmapHeight = 80,
        ).single()

        assertEquals("ocr", result.id)
        assertEquals("Copy serial number", result.fullText)
        assertEquals(listOf("Copy", "serial", "number"), result.words.map { it.text })
        assertEquals(ocr.words.map { it.bounds }, result.words.map { it.bounds })
    }

    @Test
    fun targetedRegionReplacesOnlyCoveredNodes() {
        val outside = node("outside", "Keep me", 0, 0, 90, 30)
        val coarse = node("coarse", "SN FOWI", 100, 100, 240, 140)
        val refined = node("refined", "SN:FCW1915B1AE", 105, 102, 245, 142)

        val result = mergeRegionTextNodes(
            existingNodes = listOf(outside, coarse),
            refinedNodes = listOf(refined),
            sourceRegion = rect(95, 90, 250, 150),
        )

        assertEquals(listOf("outside", "refined"), result.map { it.id })
    }

    @Test
    fun emptyTargetedRegionNeverErasesExistingText() {
        val existing = node("existing", "Already visible", 100, 100, 240, 140)

        val result = mergeRegionTextNodes(
            existingNodes = listOf(existing),
            refinedNodes = emptyList(),
            sourceRegion = rect(95, 90, 250, 150),
        )

        assertEquals(listOf("existing"), result.map { it.id })
    }

    @Test
    fun targetedRegionPreservesWordsOutsideMiddleOfLongLine() {
        val line = nodeWithWords(
            id = "line",
            words = listOf("left", "replace", "right"),
            left = 0,
            top = 100,
            wordWidth = 100,
            height = 40,
        )
        val refined = node("refined", "correct", 105, 100, 195, 140)

        val result = mergeRegionTextNodes(
            existingNodes = listOf(line),
            refinedNodes = listOf(refined),
            sourceRegion = rect(100, 90, 200, 150),
        )

        assertEquals(listOf("left", "correct", "right"), result.flatMap { it.words }.map { it.text })
    }

    private fun node(
        id: String,
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): TextNode {
        val rect = Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
        val wordBounds = RectF().apply {
            this.left = left.toFloat()
            this.top = top.toFloat()
            this.right = right.toFloat()
            this.bottom = bottom.toFloat()
        }
        return TextNode(
            id = id,
            fullText = text,
            bounds = rect,
            words = listOf(
                Word(
                    text = text,
                    index = 0,
                    startIndex = 0,
                    endIndex = text.length,
                    bounds = wordBounds,
                )
            ),
        )
    }

    private fun nodeWithWords(
        id: String,
        words: List<String>,
        left: Int,
        top: Int,
        wordWidth: Int,
        height: Int,
    ): TextNode {
        val fullText = words.joinToString(" ")
        var cursor = 0
        val wordModels = words.mapIndexed { index, text ->
            val start = cursor
            val end = start + text.length
            cursor = end + 1
            Word(
                text = text,
                index = index,
                startIndex = start,
                endIndex = end,
                bounds = RectF().apply {
                    this.left = (left + index * wordWidth).toFloat()
                    this.top = top.toFloat()
                    this.right = (left + (index + 1) * wordWidth).toFloat()
                    this.bottom = (top + height).toFloat()
                },
            )
        }
        return TextNode(
            id = id,
            fullText = fullText,
            bounds = rect(left, top, left + words.size * wordWidth, top + height),
            words = wordModels,
        )
    }

    private fun rect(left: Int, top: Int, right: Int, bottom: Int): Rect =
        Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }

    private fun assertBoundsEqual(expected: RectF, actual: RectF) {
        assertEquals(expected.left, actual.left, 0.001f)
        assertEquals(expected.top, actual.top, 0.001f)
        assertEquals(expected.right, actual.right, 0.001f)
        assertEquals(expected.bottom, actual.bottom, 0.001f)
    }
}
