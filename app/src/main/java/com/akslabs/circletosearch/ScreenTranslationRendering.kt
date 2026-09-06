package com.akslabs.circletosearch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.akslabs.circletosearch.ui.components.TextNode
import com.akslabs.circletosearch.ui.components.Word
import kotlin.math.floor
import kotlin.math.ceil
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class RenderedScreenTranslation(
    val bitmap: Bitmap,
    val renderedBlockCount: Int,
    val unfittedBlockCount: Int,
    val textNodes: List<TextNode>,
)

internal suspend fun renderScreenTranslations(
    screenshot: Bitmap,
    translatedBlocks: List<TranslatedBlockData>,
    originalBlocks: List<TextBlockData> = emptyList(),
): RenderedScreenTranslation {
    val coroutineContext = currentCoroutineContext()
    coroutineContext.ensureActive()
    val resultBitmap = copyMutableBitmap(screenshot)
    try {
        val canvas = Canvas(resultBitmap)
        val backgroundPaint = Paint().apply { style = Paint.Style.FILL }
        var renderedCount = 0
        val replacements = mutableMapOf<Int, List<TextNode>>()
        val additionalNodes = mutableListOf<TextNode>()

        for (block in translatedBlocks) {
            coroutineContext.ensureActive()
            val layout = createTranslationLayout(block.translatedText, block.boundingBox) ?: continue
            val dominantBgColor = getDominantEdgeColor(screenshot, block.boundingBox)
            backgroundPaint.color = dominantBgColor

            val bgRect = Rect(block.boundingBox).apply { inset(-2, -2) }
            canvas.drawRect(bgRect, backgroundPaint)

            layout.paint.color = getContrastColor(dominantBgColor)
            canvas.save()
            try {
                canvas.translate(block.boundingBox.left.toFloat(),
                    block.boundingBox.centerY() - layout.height / 2f)
                layout.draw(canvas)
            } finally {
                canvas.restore()
            }
            val nodes = translationLayoutNodes(layout, block.boundingBox)
            if (block.sourceIndex in originalBlocks.indices) replacements[block.sourceIndex] = nodes
            else additionalNodes += nodes
            renderedCount++
        }
        val nodes = originalBlocks.flatMapIndexed { index, block ->
            replacements[index] ?: listOf(block.sourceNode ?: TextNode(
                "original-$index", block.text, Rect(block.boundingBox),
                listOf(Word(block.text, 0, 0, block.text.length, RectF(block.boundingBox))),
            ))
        } + additionalNodes
        return RenderedScreenTranslation(resultBitmap, renderedCount, translatedBlocks.size - renderedCount, nodes)
    } catch (error: Throwable) {
        resultBitmap.takeUnless { it.isRecycled }?.recycle()
        throw error
    }
}

/** Uses the same layout and offset as drawing, including wrapping, alignment and bidi. */
internal fun translationLayoutNodes(layout: StaticLayout, box: Rect): List<TextNode> {
    val text = layout.text.toString()
    val offsetY = box.centerY() - layout.height / 2f
    return (0 until layout.lineCount).mapNotNull { line ->
        val start = layout.getLineStart(line)
        val end = layout.getLineEnd(line)
        val lineText = text.substring(start, end).trimEnd()
        val words = Regex("\\S+").findAll(lineText).mapIndexed { index, match ->
            val path = Path()
            layout.getSelectionPath(start + match.range.first, start + match.range.last + 1, path)
            val bounds = RectF()
            path.computeBounds(bounds, true)
            bounds.offset(box.left.toFloat(), offsetY)
            Word(match.value, index, match.range.first, match.range.last + 1, bounds)
        }.toList()
        if (words.isEmpty()) return@mapNotNull null
        val bounds = RectF(words.first().bounds)
        words.drop(1).forEach { bounds.union(it.bounds) }
        TextNode(java.util.UUID.randomUUID().toString(), lineText,
            Rect(floor(bounds.left).toInt(), floor(bounds.top).toInt(),
                ceil(bounds.right).toInt(), ceil(bounds.bottom).toInt()), words)
    }
}

private fun copyMutableBitmap(screenshot: Bitmap): Bitmap {
    return try {
        screenshot.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("Failed to create bitmap copy")
    } catch (error: OutOfMemoryError) {
        throw IllegalStateException("Not enough memory to process screenshot", error)
    }
}

/**
 * Color Sampler: iterates around BoundingBox perimeter to find dominant background color.
 * Edge cases: empty bounds, zero area, bounds outside bitmap.
 */
private fun getDominantEdgeColor(bitmap: Bitmap, bounds: Rect): Int {
    val colorCounts = mutableMapOf<Int, Int>()

    // OPTIMIZATION: Expand the box outward (padding) to move from the font to the clean background.
    val padding = 14
    val left = (bounds.left - padding).coerceIn(0, bitmap.width - 1)
    val right = (bounds.right + padding).coerceIn(0, bitmap.width - 1)
    val top = (bounds.top - padding).coerceIn(0, bitmap.height - 1)
    val bottom = (bounds.bottom + padding).coerceIn(0, bitmap.height - 1)

    // Edge case: empty or zero rect
    if (right <= left || bottom <= top) {
        return Color.WHITE
    }

    // Edge case: very narrow/short boundary — pick single point
    if (right == left && bottom == top) {
        return bitmap.getPixel(left, top)
    }

    // Sample the box perimeter. Read each edge in one getPixels call (4 JNI
    // calls per block) instead of ~200 per-pixel getPixel calls; this runs
    // once per text block, so the saving scales with block count.
    val rowWidth = right - left + 1
    val colHeight = bottom - top + 1
    val rowBuffer = IntArray(rowWidth)
    val colBuffer = IntArray(colHeight)

    val xStep = maxOf(2, (right - left) / 50)
    bitmap.getPixels(rowBuffer, 0, rowWidth, left, top, rowWidth, 1)
    for (x in 0 until rowWidth step xStep) {
        val c = rowBuffer[x]
        colorCounts[c] = (colorCounts[c] ?: 0) + 1
    }
    bitmap.getPixels(rowBuffer, 0, rowWidth, left, bottom, rowWidth, 1)
    for (x in 0 until rowWidth step xStep) {
        val c = rowBuffer[x]
        colorCounts[c] = (colorCounts[c] ?: 0) + 1
    }

    val yStep = maxOf(2, (bottom - top) / 50)
    bitmap.getPixels(colBuffer, 0, 1, left, top, 1, colHeight)
    for (y in 0 until colHeight step yStep) {
        val c = colBuffer[y]
        colorCounts[c] = (colorCounts[c] ?: 0) + 1
    }
    bitmap.getPixels(colBuffer, 0, 1, right, top, 1, colHeight)
    for (y in 0 until colHeight step yStep) {
        val c = colBuffer[y]
        colorCounts[c] = (colorCounts[c] ?: 0) + 1
    }

    return colorCounts.maxByOrNull { it.value }?.key ?: Color.WHITE
}

internal fun createTranslationLayout(text: String, rect: Rect): StaticLayout? {
    if (text.isBlank() || rect.width() <= 0 || rect.height() <= 0) return null
    fun layoutAt(size: Int): StaticLayout {
        // Each layout owns its paint; later sizing probes cannot mutate the chosen layout.
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size.toFloat() }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, rect.width())
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()
    }
    var minimum = 10
    var maximum = 120
    var best: StaticLayout? = null
    while (minimum <= maximum) {
        val size = (minimum + maximum) / 2
        val layout = layoutAt(size)
        val fits = layout.height <= rect.height() &&
            (0 until layout.lineCount).all { layout.getLineWidth(it) <= rect.width() }
        if (fits) {
            best = layout
            minimum = size + 1
        } else {
            maximum = size - 1
        }
    }
    return best
}

/**
 * WCAG 2.1 relative luminance + contrast ratio.
 * Returns BLACK or WHITE for guaranteed readable text.
 */
private fun getContrastColor(backgroundColor: Int): Int {
    // WCAG relative luminance
    fun channelLuminance(c: Int): Double {
        val sRGB = c / 255.0
        return if (sRGB <= 0.03928) sRGB / 12.92 else Math.pow((sRGB + 0.055) / 1.055, 2.4)
    }
    val r = Color.red(backgroundColor)
    val g = Color.green(backgroundColor)
    val b = Color.blue(backgroundColor)
    val luminance = 0.2126 * channelLuminance(r) +
                   0.7152 * channelLuminance(g) +
                   0.0722 * channelLuminance(b)

    // WCAG Formula: (L1 + 0.05) / (L2 + 0.05) where L1 is the lighter color
    val contrastWithWhite = 1.05 / (luminance + 0.05)
    val contrastWithBlack = (luminance + 0.05) / 0.05
    return if (contrastWithBlack > contrastWithWhite) Color.BLACK else Color.WHITE
}
