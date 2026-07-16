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

        assertEquals(listOf("assist", "uncovered"), result.map { it.id })
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

        assertEquals(listOf("assist"), result.map { it.id })
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
}
