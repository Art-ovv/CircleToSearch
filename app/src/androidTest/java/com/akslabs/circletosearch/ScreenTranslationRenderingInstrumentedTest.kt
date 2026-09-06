package com.akslabs.circletosearch

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenTranslationRenderingInstrumentedTest {
    @Test
    fun renderedTextReplacesOnlySuccessfulSourceBlocksWithoutOcr() = runBlocking {
        val source = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        val original = com.akslabs.circletosearch.ui.components.TextNode(
            "kept", "Original", Rect(10, 200, 150, 202), emptyList())
        try {
            val result = renderScreenTranslations(source, listOf(
                TranslatedBlockData("Hello world", Rect(10, 10, 350, 100), 0),
                TranslatedBlockData("Cannot fit here", original.bounds, 1),
            ), listOf(
                TextBlockData("Привет мир", Rect(10, 10, 350, 100)),
                TextBlockData(original.fullText, original.bounds, original),
            ))
            try {
                assertEquals(listOf("Hello", "world"),
                    result.textNodes.filter { it.id != "kept" }.flatMap { it.words }.map { it.text })
                assertSame(original, result.textNodes.last())
                assertFalse(result.textNodes.any { it.fullText.contains("Привет") })
                result.textNodes.first().words.forEach {
                    assertTrue(it.bounds.width() > 0)
                    assertTrue(it.bounds.left >= 10 && it.bounds.right <= 350)
                    assertTrue(it.bounds.top >= 10 && it.bounds.bottom <= 100)
                }
            } finally {
                result.bitmap.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun layoutWordBoundsFollowWrappedAndRtlText() {
        val box = Rect(20, 30, 200, 200)
        for (text in listOf("First line\nSecond line", "שלום עולם\nעוד טקסט")) {
            val layout = requireNotNull(createTranslationLayout(text, box))
            val nodes = translationLayoutNodes(layout, box)
            assertTrue(nodes.size >= 2)
            assertEquals(text.split(Regex("\\s+")), nodes.flatMap { it.words }.map { it.text })
            nodes.forEach { node ->
                node.words.forEach { word ->
                    assertEquals(word.text, node.fullText.substring(word.startIndex, word.endIndex))
                    assertTrue(word.bounds.left >= box.left - 1)
                    assertTrue(word.bounds.right <= box.right + 1)
                    assertTrue(word.bounds.width() > 0)
                }
            }
        }
    }

    @Test
    fun unfitTranslationPreservesEveryOriginalPixel() = runBlocking {
        val source = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLUE)
        source.setPixel(5, 5, Color.RED)
        try {
            val result = renderScreenTranslations(source,
                listOf(TranslatedBlockData("A translation that cannot fit", Rect(4, 4, 15, 6))))
            try {
                assertEquals(0, result.renderedBlockCount)
                assertEquals(1, result.unfittedBlockCount)
                assertTrue(source.sameAs(result.bitmap))
                assertFalse(source.isRecycled)
            } finally {
                result.bitmap.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun partialRenderKeepsUnfitBlockAndCountsOnlyVisibleTranslations() = runBlocking {
        val source = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        source.setPixel(15, 151, Color.RED)
        try {
            val result = renderScreenTranslations(source, listOf(
                TranslatedBlockData("Hello", Rect(10, 10, 250, 90)),
                TranslatedBlockData("Too long to fit", Rect(10, 150, 30, 152)),
            ))
            try {
                assertEquals(1, result.renderedBlockCount)
                assertEquals(1, result.unfittedBlockCount)
                assertEquals(Color.RED, result.bitmap.getPixel(15, 151))
                assertFalse(source.sameAs(result.bitmap))
            } finally {
                result.bitmap.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun chosenLayoutKeepsItsMeasuredFontSize() {
        val bounds = Rect(0, 0, 150, 35)
        val layout = requireNotNull(createTranslationLayout("Longer translated label", bounds))
        assertTrue(layout.height <= bounds.height())
        assertTrue(layout.paint.textSize >= 10f)
        for (line in 0 until layout.lineCount) {
            assertTrue(layout.paint.measureText(layout.text, layout.getLineStart(line),
                layout.getLineEnd(line)).let { it <= bounds.width() })
        }
        assertNull(createTranslationLayout("text", Rect(0, 0, 0, 30)))
        assertNull(createTranslationLayout("text", Rect(0, 0, 10, 1)))
    }
}
