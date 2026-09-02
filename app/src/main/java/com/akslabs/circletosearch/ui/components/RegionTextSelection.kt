package com.akslabs.circletosearch.ui.components

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private const val MINIMUM_WORD_OVERLAP = 0.35f
private const val MINIMUM_LINE_OVERLAP = 0.35f
private const val MINIMUM_VERTICAL_TOLERANCE_PX = 4f
private const val MAXIMUM_ANCHOR_TEXT_LENGTH = 64

/** A lightweight immutable snapshot used to keep a selection across OCR refinements. */
internal data class SelectionWordAnchor(
    val normalizedText: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Both line membership and flattened reading order come from the same clustering pass. */
internal data class VisualTextLayout(
    val lines: List<List<Word>>,
    val words: List<Word>,
)

/**
 * Extracts selectable text that belongs to [selection] in visual reading order.
 *
 * A word is selected when its center is inside the region or the region covers at
 * least 35% of its bounds. Words are clustered into visual lines before sorting,
 * so small differences in their top coordinates cannot reorder a line.
 */
internal fun extractRegionText(
    selection: Rect,
    nodes: List<TextNode>,
): String {
    if (selection.right <= selection.left || selection.bottom <= selection.top) {
        return ""
    }

    val candidates = regionWords(nodes) { word -> word.belongsTo(selection) }
    if (candidates.isEmpty()) return ""

    return clusterIntoLines(candidates)
        .joinToString("\n") { line ->
            line.words.joinToString(" ") { it.text }
        }
}

/** Returns line membership and stable visual reading order for selection and highlighting. */
internal fun visualTextLayout(nodes: List<TextNode>): VisualTextLayout {
    val clustered = clusterIntoLines(regionWords(nodes) { true })
    val lines = ArrayList<List<Word>>(clustered.size)
    val words = ArrayList<Word>(clustered.sumOf { it.words.size })

    clustered.forEach { line ->
        val lineWords = ArrayList<Word>(line.words.size)
        line.words.forEach { regionWord ->
            lineWords += regionWord.source
            words += regionWord.source
        }
        lines += lineWords
    }

    return VisualTextLayout(lines = lines, words = words)
}

/** Returns all words in stable visual reading order for drag-based selection. */
internal fun wordsInVisualReadingOrder(nodes: List<TextNode>): List<Word> =
    visualTextLayout(nodes).words

internal fun Word.toSelectionAnchor(): SelectionWordAnchor = SelectionWordAnchor(
    normalizedText = normalizeSelectionText(text),
    left = bounds.left,
    top = bounds.top,
    right = bounds.right,
    bottom = bounds.bottom,
)

/**
 * Finds the refined word that represents [anchor]. Geometry is authoritative while a bounded
 * edit distance tolerates normal primary-to-final OCR corrections such as Setlings -> Settings.
 */
internal fun findSelectionAnchorMatch(
    anchor: SelectionWordAnchor,
    candidates: List<Word>,
): Int {
    val anchorWidth = anchor.right - anchor.left
    val anchorHeight = anchor.bottom - anchor.top
    val anchorArea = anchorWidth * anchorHeight
    if (anchorWidth <= 0f || anchorHeight <= 0f || anchorArea <= 0f) return -1

    var bestIndex = -1
    var bestScore = Float.MAX_VALUE
    candidates.forEachIndexed { index, word ->
        val bounds = word.bounds
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        val area = width * height
        if (width <= 0f || height <= 0f || area <= 0f) return@forEachIndexed

        val intersectionWidth =
            (min(anchor.right, bounds.right) - max(anchor.left, bounds.left)).coerceAtLeast(0f)
        val intersectionHeight =
            (min(anchor.bottom, bounds.bottom) - max(anchor.top, bounds.top)).coerceAtLeast(0f)
        val intersectionArea = intersectionWidth * intersectionHeight
        val unionArea = anchorArea + area - intersectionArea
        val intersectionOverUnion = if (unionArea > 0f) intersectionArea / unionArea else 0f
        val smallerCoverage = intersectionArea / min(anchorArea, area)
        val sizeSimilarity = min(anchorArea, area) / max(anchorArea, area)

        val centerDx = abs((anchor.left + anchor.right) / 2f - (bounds.left + bounds.right) / 2f)
        val centerDy = abs((anchor.top + anchor.bottom) / 2f - (bounds.top + bounds.bottom) / 2f)
        val normalizedDx = centerDx / max(max(anchorWidth, width), 1f)
        val normalizedDy = centerDy / max(max(anchorHeight, height), 1f)

        val strongGeometry =
            intersectionOverUnion >= 0.3f ||
                (smallerCoverage >= 0.72f && sizeSimilarity >= 0.35f)
        val nearbyGeometry = normalizedDx <= 0.75f && normalizedDy <= 0.9f
        if (!strongGeometry && !nearbyGeometry) {
            return@forEachIndexed
        }

        // Edit distance allocates two bounded DP rows. Run it only for a
        // geometrically plausible candidate, never for every word on screen.
        val candidateText = normalizeSelectionText(word.text)
        val textSimilarity = boundedTextSimilarity(anchor.normalizedText, candidateText)
        if (
            (!strongGeometry || textSimilarity < 0.6f) &&
            (!nearbyGeometry || textSimilarity < 0.7f)
        ) return@forEachIndexed

        // Text is only a tie-breaker once a candidate has passed the geometric gate.
        val score =
            (1f - intersectionOverUnion) * 4f +
                normalizedDx +
                normalizedDy * 1.5f +
                (1f - sizeSimilarity) +
                (1f - textSimilarity) * 0.5f
        if (score < bestScore) {
            bestScore = score
            bestIndex = index
        }
    }
    return bestIndex
}

/**
 * Hit-tests screenshot-coordinate words against a point in View coordinates. When broad Assist
 * bounds overlap a local OCR word, the smaller local word wins instead of list insertion order.
 */
internal fun findBestWordHitIndex(
    words: List<Word>,
    x: Float,
    y: Float,
    scaleX: Float,
    scaleY: Float,
    proximityPx: Float,
): Int {
    if (scaleX <= 0f || scaleY <= 0f) return -1

    var containingIndex = -1
    var containingArea = Float.MAX_VALUE
    var containingCenterDistance = Float.MAX_VALUE
    var nearbyIndex = -1
    var nearbyDistance = Float.MAX_VALUE
    var nearbyArea = Float.MAX_VALUE

    words.forEachIndexed { index, word ->
        val bounds = word.bounds
        val left = bounds.left * scaleX
        val top = bounds.top * scaleY
        val right = bounds.right * scaleX
        val bottom = bounds.bottom * scaleY
        val width = right - left
        val height = bottom - top
        if (width <= 0f || height <= 0f) return@forEachIndexed
        val area = width * height

        if (x >= left && x <= right && y >= top && y <= bottom) {
            val dx = x - (left + right) / 2f
            val dy = y - (top + bottom) / 2f
            val centerDistance = dx * dx + dy * dy
            if (
                area < containingArea ||
                (area == containingArea && centerDistance < containingCenterDistance)
            ) {
                containingIndex = index
                containingArea = area
                containingCenterDistance = centerDistance
            }
            return@forEachIndexed
        }

        if (
            x < left - proximityPx || x > right + proximityPx ||
            y < top - proximityPx || y > bottom + proximityPx
        ) {
            return@forEachIndexed
        }
        val dx = when {
            x < left -> left - x
            x > right -> x - right
            else -> 0f
        }
        val dy = when {
            y < top -> top - y
            y > bottom -> y - bottom
            else -> 0f
        }
        val distance = dx * dx + dy * dy
        if (distance < nearbyDistance || (distance == nearbyDistance && area < nearbyArea)) {
            nearbyIndex = index
            nearbyDistance = distance
            nearbyArea = area
        }
    }

    return if (containingIndex >= 0) containingIndex else nearbyIndex
}

private fun normalizeSelectionText(text: String): String {
    val normalized = buildString(min(text.length, MAXIMUM_ANCHOR_TEXT_LENGTH)) {
        for (character in text) {
            if (length >= MAXIMUM_ANCHOR_TEXT_LENGTH) break
            if (character.isLetterOrDigit()) append(character.lowercaseChar())
        }
    }
    return normalized.ifEmpty {
        text.trim().lowercase().take(MAXIMUM_ANCHOR_TEXT_LENGTH)
    }
}

private fun boundedTextSimilarity(first: String, second: String): Float {
    if (first == second) return 1f
    if (first.isEmpty() || second.isEmpty()) return 0f
    val maxLength = max(first.length, second.length)
    val maximumDistance = when {
        maxLength <= 4 -> 1
        maxLength <= 10 -> 2
        maxLength <= 20 -> 4
        else -> min(8, ceil(maxLength * 0.3f).toInt())
    }
    if (abs(first.length - second.length) > maximumDistance) return 0f

    var previous = IntArray(second.length + 1) { it }
    var current = IntArray(second.length + 1)
    first.forEachIndexed { firstIndex, firstCharacter ->
        current[0] = firstIndex + 1
        var rowMinimum = current[0]
        second.forEachIndexed { secondIndex, secondCharacter ->
            val insertion = current[secondIndex] + 1
            val deletion = previous[secondIndex + 1] + 1
            val substitution = previous[secondIndex] + if (firstCharacter == secondCharacter) 0 else 1
            val distance = min(insertion, min(deletion, substitution))
            current[secondIndex + 1] = distance
            rowMinimum = min(rowMinimum, distance)
        }
        if (rowMinimum > maximumDistance) return 0f
        val swap = previous
        previous = current
        current = swap
    }
    val distance = previous[second.length]
    return if (distance <= maximumDistance) 1f - distance.toFloat() / maxLength else 0f
}

private fun regionWords(
    nodes: List<TextNode>,
    include: (Word) -> Boolean,
): List<RegionWord> = buildList {
    var sourceOrder = 0
    nodes.forEach { node ->
        node.words.forEach { word ->
            val text = word.text.trim()
            if (text.isNotEmpty() && include(word)) {
                add(
                    RegionWord(
                        text = text,
                        bounds = WordBounds(
                            left = word.bounds.left,
                            top = word.bounds.top,
                            right = word.bounds.right,
                            bottom = word.bounds.bottom,
                        ),
                        sourceOrder = sourceOrder,
                        source = word,
                    ),
                )
            }
            sourceOrder++
        }
    }
}

private fun clusterIntoLines(candidates: List<RegionWord>): List<RegionLine> {
    val lines = mutableListOf<RegionLine>()
    candidates
        .sortedWith(
            compareBy<RegionWord>({ it.bounds.centerY }, { it.bounds.left }, { it.sourceOrder }),
        )
        .forEach { candidate ->
            val matchingLine = lines
                .asSequence()
                .filter { it.accepts(candidate) }
                .minByOrNull { abs(it.centerY - candidate.bounds.centerY) }

            if (matchingLine == null) {
                lines += RegionLine(candidate)
            } else {
                matchingLine.add(candidate)
            }
        }

    return lines
        .sortedWith(compareBy<RegionLine>({ it.top }, { it.centerY }))
        .onEach { line ->
            line.words
                .sortWith(compareBy<RegionWord>({ it.bounds.left }, { it.sourceOrder }))
        }
}

private data class RegionWord(
    val text: String,
    val bounds: WordBounds,
    val sourceOrder: Int,
    val source: Word,
)

private data class WordBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top

    val centerX: Float
        get() = (left + right) / 2f

    val centerY: Float
        get() = (top + bottom) / 2f
}

private class RegionLine(first: RegionWord) {
    val words = mutableListOf(first)

    private var centerSum = first.bounds.centerY
    private var heightSum = first.bounds.height

    var top: Float = first.bounds.top
        private set
    private var bottom: Float = first.bounds.bottom

    val centerY: Float
        get() = centerSum / words.size

    private val averageHeight: Float
        get() = heightSum / words.size

    fun accepts(candidate: RegionWord): Boolean {
        val candidateHeight = candidate.bounds.height
        if (candidateHeight <= 0f || averageHeight <= 0f) return false

        val overlap = min(bottom, candidate.bounds.bottom) - max(top, candidate.bounds.top)
        val overlapRatio = overlap.coerceAtLeast(0f) / min(averageHeight, candidateHeight)
        if (overlapRatio >= MINIMUM_LINE_OVERLAP) return true

        val verticalTolerance = max(
            MINIMUM_VERTICAL_TOLERANCE_PX,
            min(averageHeight, candidateHeight) * 0.45f,
        )
        return abs(centerY - candidate.bounds.centerY) <= verticalTolerance
    }

    fun add(candidate: RegionWord) {
        words += candidate
        centerSum += candidate.bounds.centerY
        heightSum += candidate.bounds.height
        top = min(top, candidate.bounds.top)
        bottom = max(bottom, candidate.bounds.bottom)
    }
}

private fun Word.belongsTo(selection: Rect): Boolean {
    val wordWidth = bounds.right - bounds.left
    val wordHeight = bounds.bottom - bounds.top
    if (wordWidth <= 0f || wordHeight <= 0f) return false

    val centerX = (bounds.left + bounds.right) / 2f
    val centerY = (bounds.top + bounds.bottom) / 2f
    if (
        centerX >= selection.left && centerX <= selection.right &&
        centerY >= selection.top && centerY <= selection.bottom
    ) {
        return true
    }

    val overlapWidth = (
        min(bounds.right, selection.right.toFloat()) -
            max(bounds.left, selection.left.toFloat())
        ).coerceAtLeast(0f)
    val overlapHeight = (
        min(bounds.bottom, selection.bottom.toFloat()) -
            max(bounds.top, selection.top.toFloat())
        ).coerceAtLeast(0f)
    val overlapRatio = overlapWidth * overlapHeight / (wordWidth * wordHeight)
    return overlapRatio >= MINIMUM_WORD_OVERLAP
}
