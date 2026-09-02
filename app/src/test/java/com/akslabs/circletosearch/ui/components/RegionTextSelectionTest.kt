package com.akslabs.circletosearch.ui.components

import android.graphics.Rect
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Test

class RegionTextSelectionTest {
    @Test
    fun centerInsideSelectsWordEvenWhenOverlapIsSmall() {
        val nodes = listOf(node(word("centered", 0f, 0f, 100f, 20f)))

        val result = extractRegionText(rect(48, 8, 52, 12), nodes)

        assertEquals("centered", result)
    }

    @Test
    fun thirtyFivePercentOverlapSelectsWordWhoseCenterIsOutside() {
        val nodes = listOf(node(word("threshold", 0f, 0f, 100f, 20f)))

        val result = extractRegionText(rect(0, 0, 35, 20), nodes)

        assertEquals("threshold", result)
    }

    @Test
    fun overlapBelowThresholdDoesNotSelectWord() {
        val nodes = listOf(node(word("outside", 0f, 0f, 100f, 20f)))

        val result = extractRegionText(rect(0, 0, 34, 20), nodes)

        assertEquals("", result)
    }

    @Test
    fun sameLineUsesLeftToRightOrderDespiteTopDrift() {
        val nodes = listOf(
            node(word("second", 110f, 96f, 180f, 120f)),
            node(word("first", 10f, 102f, 80f, 124f)),
            node(word("third", 210f, 99f, 270f, 121f)),
        )

        val result = extractRegionText(rect(0, 80, 300, 140), nodes)

        assertEquals("first second third", result)
    }

    @Test
    fun separateLinesUseTopToBottomOrderRegardlessOfHorizontalPosition() {
        val nodes = listOf(
            node(word("lower", 5f, 150f, 70f, 170f)),
            node(word("upper", 200f, 100f, 275f, 120f)),
        )

        val result = extractRegionText(rect(0, 80, 300, 190), nodes)

        assertEquals("upper\nlower", result)
    }

    @Test
    fun globalSelectionUsesVisualRowBeforeRawTop() {
        val left = word("только ВОТ рейсы", 206f, 1248f, 545f, 1278f)
        val right = word("из махачкалы", 564f, 1242f, 1011f, 1278f)
        val next = word("следующая строка", 203f, 1300f, 386f, 1333f)

        val result = wordsInVisualReadingOrder(
            listOf(node(right), node(next), node(left)),
        )

        assertEquals(listOf(left, right, next), result)
    }

    @Test
    fun verticallyOverlappingWordsWithDifferentHeightsStayOnOneLine() {
        val nodes = listOf(
            node(word("Text", 10f, 100f, 70f, 124f)),
            node(word("2", 80f, 112f, 92f, 128f)),
        )

        val result = extractRegionText(rect(0, 90, 120, 140), nodes)

        assertEquals("Text 2", result)
    }

    @Test
    fun invalidSelectionAndBlankWordsProduceNoText() {
        val nodes = listOf(node(word("   ", 0f, 0f, 100f, 20f)))

        assertEquals("", extractRegionText(rect(0, 0, 0, 20), nodes))
        assertEquals("", extractRegionText(rect(0, 0, 100, 20), nodes))
    }

    @Test
    fun selectionAnchorSurvivesPrimaryToFinalTextCorrection() {
        val primary = word("Setlings", 100f, 200f, 210f, 230f)
        val corrected = word("Settings", 101f, 199f, 212f, 231f)
        val candidates = listOf(
            word("Setlings", 500f, 600f, 610f, 630f),
            corrected,
        )

        val match = findSelectionAnchorMatch(primary.toSelectionAnchor(), candidates)

        assertEquals(1, match)
        assertEquals("Settings", candidates[match].text)
    }

    @Test
    fun selectionAnchorUsesGeometryBeforeACompetingFuzzyWord() {
        val primary = word("comment", 100f, 200f, 220f, 230f)
        val localCorrection = word("comments", 102f, 199f, 224f, 231f)
        val remoteExactText = word("comment", 600f, 800f, 720f, 830f)

        val match = findSelectionAnchorMatch(
            primary.toSelectionAnchor(),
            listOf(remoteExactText, localCorrection),
        )

        assertEquals(1, match)
    }

    @Test
    fun selectionAnchorRejectsUnrelatedTextAtSameLocation() {
        val primary = word("Settings", 100f, 200f, 220f, 230f)
        val unrelated = word("Account", 100f, 200f, 220f, 230f)

        assertEquals(
            -1,
            findSelectionAnchorMatch(primary.toSelectionAnchor(), listOf(unrelated)),
        )
    }

    @Test
    fun hitTestPrefersNarrowOcrWordInsideBroadAssistBounds() {
        val broadAssist = word("A whole semantic paragraph", 0f, 0f, 300f, 120f)
        val localOcr = word("serial", 80f, 40f, 150f, 70f)

        val match = findBestWordHitIndex(
            words = listOf(broadAssist, localOcr),
            x = 100f,
            y = 55f,
            scaleX = 1f,
            scaleY = 1f,
            proximityPx = 30f,
        )

        assertEquals(1, match)
    }

    @Test
    fun hitTestUsesNearestBoundsThenSmallestAreaForNearbyWords() {
        val large = word("large", 100f, 100f, 200f, 150f)
        val small = word("small", 100f, 100f, 130f, 120f)

        val match = findBestWordHitIndex(
            words = listOf(large, small),
            x = 90f,
            y = 90f,
            scaleX = 1f,
            scaleY = 1f,
            proximityPx = 30f,
        )

        assertEquals(1, match)
    }

    @Test
    fun visualLayoutExposesTheSameLinesUsedForFlattenedReadingOrder() {
        val upperLeft = word("first", 10f, 100f, 70f, 124f)
        val upperRight = word("second", 90f, 98f, 170f, 123f)
        val lower = word("third", 10f, 160f, 70f, 184f)

        val layout = visualTextLayout(listOf(node(lower), node(upperRight), node(upperLeft)))

        assertEquals(listOf(listOf(upperLeft, upperRight), listOf(lower)), layout.lines)
        assertEquals(listOf(upperLeft, upperRight, lower), layout.words)
    }

    private fun node(vararg words: Word): TextNode {
        val left = words.minOf { it.bounds.left }.toInt()
        val top = words.minOf { it.bounds.top }.toInt()
        val right = words.maxOf { it.bounds.right }.toInt()
        val bottom = words.maxOf { it.bounds.bottom }.toInt()
        return TextNode(
            id = words.joinToString("|") { it.text },
            fullText = words.joinToString(" ") { it.text },
            bounds = rect(left, top, right, bottom),
            words = words.toList(),
        )
    }

    private fun word(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Word = Word(
        text = text,
        index = 0,
        startIndex = 0,
        endIndex = text.length,
        bounds = rectF(left, top, right, bottom),
    )

    private fun rect(left: Int, top: Int, right: Int, bottom: Int): Rect =
        Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }

    private fun rectF(left: Float, top: Float, right: Float, bottom: Float): RectF =
        RectF().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
}
