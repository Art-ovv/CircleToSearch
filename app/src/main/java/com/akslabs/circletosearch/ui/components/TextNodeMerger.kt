package com.akslabs.circletosearch.ui.components

import android.graphics.Rect

private val TEXT_TOKEN_PATTERN = Regex("\\S+")

private val GENERIC_ASSIST_CONTAINER_LABELS = setOf(
    "photo",
    "image",
    "picture",
    "video",
    "media",
    "фото",
    "фотография",
    "изображение",
    "видео",
    "медиа",
)

private fun isGenericAssistContainerLabel(text: String): Boolean =
    text.trim().lowercase() in GENERIC_ASSIST_CONTAINER_LABELS

/**
 * Treats Android assist-structure text as authoritative and retains OCR only where
 * semantic text did not cover the screenshot. This improves accuracy at no OCR cost
 * while preserving recognition for WebViews, canvases, images, and other opaque UI.
 */
internal fun mergeTextNodes(
    assistNodes: List<TextNode>,
    ocrNodes: List<TextNode>,
    bitmapWidth: Int,
    bitmapHeight: Int,
    assistCoordinateWidth: Int = bitmapWidth,
    assistCoordinateHeight: Int = bitmapHeight,
): List<TextNode> {
    if (bitmapWidth <= 0 || bitmapHeight <= 0) return ocrNodes

    val sourceWidth = assistCoordinateWidth.takeIf { it > 0 } ?: return ocrNodes
    val sourceHeight = assistCoordinateHeight.takeIf { it > 0 } ?: return ocrNodes
    val bitmapAspect = bitmapWidth.toDouble() / bitmapHeight.toDouble()
    val assistAspect = sourceWidth.toDouble() / sourceHeight.toDouble()
    if (kotlin.math.abs(bitmapAspect - assistAspect) / bitmapAspect > 0.03) return ocrNodes

    val scaleX = bitmapWidth.toFloat() / sourceWidth.toFloat()
    val scaleY = bitmapHeight.toFloat() / sourceHeight.toFloat()
    val scaledAssistNodes = assistNodes.mapNotNull { node ->
        val left = (node.bounds.left * scaleX).toInt().coerceIn(0, bitmapWidth)
        val top = (node.bounds.top * scaleY).toInt().coerceIn(0, bitmapHeight)
        val right = (node.bounds.right * scaleX).toInt().coerceIn(0, bitmapWidth)
        val bottom = (node.bounds.bottom * scaleY).toInt().coerceIn(0, bitmapHeight)
        if (right <= left || bottom <= top) return@mapNotNull null

        val clippedWords = node.words.mapNotNull { word ->
            val wordLeft = (word.bounds.left * scaleX).coerceIn(0f, bitmapWidth.toFloat())
            val wordTop = (word.bounds.top * scaleY).coerceIn(0f, bitmapHeight.toFloat())
            val wordRight = (word.bounds.right * scaleX).coerceIn(0f, bitmapWidth.toFloat())
            val wordBottom = (word.bounds.bottom * scaleY).coerceIn(0f, bitmapHeight.toFloat())
            if (wordRight <= wordLeft || wordBottom <= wordTop) return@mapNotNull null
            word.copy(
                bounds = android.graphics.RectF().apply {
                    this.left = wordLeft
                    this.top = wordTop
                    this.right = wordRight
                    this.bottom = wordBottom
                },
            )
        }
        if (clippedWords.isEmpty()) return@mapNotNull null

        node.copy(
            bounds = android.graphics.Rect().apply {
                this.left = left
                this.top = top
                this.right = right
                this.bottom = bottom
            },
            words = clippedWords,
        )
    }

    val authoritative = scaledAssistNodes
        .asSequence()
        .filter { node ->
            node.fullText.isNotBlank() &&
                !isGenericAssistContainerLabel(node.fullText) &&
                node.words.isNotEmpty() &&
                node.bounds.right > 0 &&
                node.bounds.bottom > 0 &&
                node.bounds.left < bitmapWidth &&
                node.bounds.top < bitmapHeight &&
                node.bounds.right > node.bounds.left &&
                node.bounds.bottom > node.bounds.top
        }
        .distinctBy { node ->
            listOf(
                node.fullText.trim(),
                node.bounds.left,
                node.bounds.top,
                node.bounds.right,
                node.bounds.bottom,
            )
        }
        .toList()

    if (authoritative.isEmpty()) return ocrNodes

    val projections = mutableMapOf<Int, Pair<TextNode, SemanticProjection>>()
    val coveredTokens = mutableMapOf<String, MutableSet<Int>>()
    ocrNodes.forEachIndexed { index, ocrNode ->
        val ocrWidth = (ocrNode.bounds.right - ocrNode.bounds.left).coerceAtLeast(0)
        val ocrHeight = (ocrNode.bounds.bottom - ocrNode.bounds.top).coerceAtLeast(0)
        val ocrArea = ocrWidth.toLong() * ocrHeight.toLong()
        if (ocrArea == 0L) return@forEachIndexed

        val matchingAssist = authoritative
            .mapNotNull { assistNode ->
            val overlapWidth = (
                minOf(ocrNode.bounds.right, assistNode.bounds.right) -
                    maxOf(ocrNode.bounds.left, assistNode.bounds.left)
                ).coerceAtLeast(0)
            val overlapHeight = (
                minOf(ocrNode.bounds.bottom, assistNode.bounds.bottom) -
                    maxOf(ocrNode.bounds.top, assistNode.bounds.top)
                ).coerceAtLeast(0)
            val overlapArea = overlapWidth.toLong() * overlapHeight.toLong()
            val overlapRatio = overlapArea.toDouble() / ocrArea.toDouble()
            // Geometry alone is not evidence that two nodes represent the same
            // visible text. Accessibility containers such as Telegram's "Photo"
            // can cover an entire message and must not erase OCR inside it.
                if (
                    overlapRatio >= 0.70 &&
                    textLikelyMatches(assistNode.fullText, ocrNode.fullText)
                ) {
                    assistNode to overlapRatio
                } else {
                    null
                }
            }
            .maxWithOrNull(
                compareBy<Pair<TextNode, Double>> { it.second }
                    .thenBy { (assistNode, _) ->
                        -(assistNode.bounds.right - assistNode.bounds.left).toLong() *
                            (assistNode.bounds.bottom - assistNode.bounds.top).toLong()
                    },
            )
            ?.first

        if (matchingAssist != null) {
            val projection = projectSemanticTextOntoVisualGeometry(
                semanticText = matchingAssist.fullText,
                visualNode = ocrNode,
            ) ?: return@forEachIndexed
            projections[index] = matchingAssist to projection
            coveredTokens.getOrPut(matchingAssist.id) { mutableSetOf() }
                .addAll(projection.tokenRange)
        }
    }

    val fullyCovered = authoritative.filter { node ->
        coveredTokens[node.id]?.size == TEXT_TOKEN_PATTERN.findAll(node.fullText).count()
    }.mapTo(mutableSetOf()) { it.id }
    // Assist may expose an entire wrapped paragraph as one Word. Without per-word geometry,
    // splitting its remainder would invent bounds. Keep that block until OCR covers every token,
    // and omit its partial OCR projections so copy/translation never duplicate the same passage.
    val retainedAssist = authoritative.filterNot { it.id in fullyCovered }
    val visualOcr = ocrNodes.mapIndexedNotNull { index, node ->
        val (assist, projection) = projections[index] ?: return@mapIndexedNotNull node
        projection.node.takeIf { assist.id in fullyCovered }
    }
    return (retainedAssist + visualOcr)
        .sortedWith(compareBy<TextNode>({ it.bounds.top }, { it.bounds.left }))
}

/**
 * Replaces only words covered by actual refined detections, not by the requested ROI.
 * Missing detections (including a completely empty pass) preserve existing selectable text.
 */
internal fun mergeRegionTextNodes(
    existingNodes: List<TextNode>,
    refinedNodes: List<TextNode>,
    sourceRegion: Rect?,
): List<TextNode> {
    if (
        refinedNodes.isEmpty() ||
        sourceRegion == null ||
        sourceRegion.right <= sourceRegion.left ||
        sourceRegion.bottom <= sourceRegion.top
    ) {
        return existingNodes
    }
    // A TextNode is normally a whole visual line. Subtract at word granularity
    // so refining one serial number or one word in the middle never erases the
    // unselected beginning/end of that line.
    val refinedWords = refinedNodes.flatMap { it.words }.filter { it.isCoveredBy(sourceRegion) }
    fun isReplaced(word: Word): Boolean = word.isCoveredBy(sourceRegion) &&
        refinedWords.any { replacement -> word.isReplacedBy(replacement) }

    val retained = existingNodes.flatMap { node ->
        if (node.words.isEmpty()) return@flatMap listOf(node)
        if (node.words.none { isReplaced(it) }) {
            return@flatMap listOf(node)
        }
        val runs = mutableListOf<List<Word>>()
        var currentRun = mutableListOf<Word>()
        node.words.forEach { word ->
            if (isReplaced(word)) {
                if (currentRun.isNotEmpty()) {
                    runs += currentRun
                    currentRun = mutableListOf()
                }
            } else {
                currentRun += word
            }
        }
        if (currentRun.isNotEmpty()) runs += currentRun
        runs.mapIndexed { runIndex, words -> node.retainedRun(words, runIndex) }
    }
    return (retained + refinedNodes)
        .distinctBy { node ->
            listOf(
                node.fullText,
                node.bounds.left,
                node.bounds.top,
                node.bounds.right,
                node.bounds.bottom,
            )
        }
        .sortedWith(compareBy<TextNode>({ it.bounds.top }, { it.bounds.left }))
}

private fun Word.isReplacedBy(replacement: Word): Boolean {
    val area = (bounds.right - bounds.left) * (bounds.bottom - bounds.top)
    if (area <= 0f) return false
    val overlapWidth = (minOf(bounds.right, replacement.bounds.right) -
        maxOf(bounds.left, replacement.bounds.left)).coerceAtLeast(0f)
    val overlapHeight = (minOf(bounds.bottom, replacement.bounds.bottom) -
        maxOf(bounds.top, replacement.bounds.top)).coerceAtLeast(0f)
    val coverage = overlapWidth * overlapHeight / area
    // Strong word-level coverage allows real OCR corrections (including serial numbers).
    // With looser geometry, require textual evidence to avoid deleting a neighbouring word.
    return coverage >= 0.70f ||
        (coverage >= 0.35f && textLikelyMatches(text, replacement.text))
}

private fun Word.isCoveredBy(region: Rect): Boolean {
    val width = (bounds.right - bounds.left).coerceAtLeast(0f)
    val height = (bounds.bottom - bounds.top).coerceAtLeast(0f)
    val area = width * height
    if (area <= 0f) return false
    val intersectionWidth = (
        minOf(bounds.right, region.right.toFloat()) -
            maxOf(bounds.left, region.left.toFloat())
        ).coerceAtLeast(0f)
    val intersectionHeight = (
        minOf(bounds.bottom, region.bottom.toFloat()) -
            maxOf(bounds.top, region.top.toFloat())
        ).coerceAtLeast(0f)
    val centerX = (bounds.left + bounds.right) / 2f
    val centerY = (bounds.top + bounds.bottom) / 2f
    val centerInside =
        centerX >= region.left && centerX < region.right &&
            centerY >= region.top && centerY < region.bottom
    return centerInside || intersectionWidth * intersectionHeight >= area * 0.35f
}

private fun TextNode.retainedRun(run: List<Word>, runIndex: Int): TextNode {
    val bounds = android.graphics.Rect()
    var hasBounds = false
    var cursor = 0
    val retainedWords = run.mapIndexed { index, word ->
        val left = kotlin.math.floor(word.bounds.left.toDouble()).toInt()
        val top = kotlin.math.floor(word.bounds.top.toDouble()).toInt()
        val right = kotlin.math.ceil(word.bounds.right.toDouble()).toInt()
        val bottom = kotlin.math.ceil(word.bounds.bottom.toDouble()).toInt()
        if (!hasBounds) {
            bounds.left = left
            bounds.top = top
            bounds.right = right
            bounds.bottom = bottom
            hasBounds = true
        } else {
            bounds.left = minOf(bounds.left, left)
            bounds.top = minOf(bounds.top, top)
            bounds.right = maxOf(bounds.right, right)
            bounds.bottom = maxOf(bounds.bottom, bottom)
        }
        val start = cursor
        val end = start + word.text.length
        cursor = end + 1
        word.copy(index = index, startIndex = start, endIndex = end)
    }
    val text = retainedWords.joinToString(" ") { it.text }
    return copy(
        id = "$id:outside-roi:$runIndex:${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
        fullText = text,
        bounds = bounds,
        words = retainedWords,
    )
}

private data class SemanticProjection(val node: TextNode, val tokenRange: IntRange)

/**
 * Keeps OCR's granular word boxes while borrowing a matching semantic token
 * sequence from AssistStructure. A broad accessibility block must never turn a
 * selectable OCR line into one screen-sized word.
 */
private fun projectSemanticTextOntoVisualGeometry(
    semanticText: String,
    visualNode: TextNode,
): SemanticProjection? {
    if (visualNode.words.isEmpty()) return null
    val semanticTokens = TEXT_TOKEN_PATTERN.findAll(semanticText).map { it.value }.toList()
    if (semanticTokens.isEmpty()) return null

    val visualTokenCount = TEXT_TOKEN_PATTERN.findAll(visualNode.fullText).count().coerceAtLeast(1)
    val desiredTokenCount = if (visualNode.words.size == 1) {
        visualTokenCount
    } else {
        visualNode.words.size
    }
    val minimumWindow = if (visualNode.words.size == 1) {
        (desiredTokenCount - 2).coerceAtLeast(1)
    } else {
        desiredTokenCount
    }
    val maximumWindow = if (visualNode.words.size == 1) {
        (desiredTokenCount + 2).coerceAtMost(semanticTokens.size)
    } else {
        desiredTokenCount.coerceAtMost(semanticTokens.size)
    }
    if (minimumWindow > maximumWindow) return null

    val normalizedVisual = normalizeForTextMatch(visualNode.fullText)
    var bestTokens: List<String>? = null
    var bestStart = 0
    var bestScore = Double.POSITIVE_INFINITY
    for (windowSize in minimumWindow..maximumWindow) {
        for (start in 0..semanticTokens.size - windowSize) {
            val candidate = semanticTokens.subList(start, start + windowSize)
            val normalizedCandidate = normalizeForTextMatch(candidate.joinToString(" "))
            val denominator = maxOf(normalizedVisual.length, normalizedCandidate.length, 1)
            val score = editDistance(normalizedVisual, normalizedCandidate).toDouble() / denominator
            if (score < bestScore) {
                bestScore = score
                bestTokens = candidate
                bestStart = start
            }
        }
    }
    val projectedTokens = bestTokens?.takeIf { bestScore <= 0.38 } ?: return null

    val tokenRange = bestStart until bestStart + projectedTokens.size
    if (visualNode.words.size == 1) {
        val projectedText = projectedTokens.joinToString(" ")
        return SemanticProjection(
            visualNode.copy(
                fullText = projectedText,
                words = listOf(
                    visualNode.words.single().copy(
                        text = projectedText,
                        index = 0,
                        startIndex = 0,
                        endIndex = projectedText.length,
                    ),
                ),
            ),
            tokenRange,
        )
    }
    if (projectedTokens.size != visualNode.words.size) return null

    val projectedText = projectedTokens.joinToString(" ")
    var cursor = 0
    val projectedWords = visualNode.words.mapIndexed { index, word ->
        val token = projectedTokens[index]
        val start = cursor
        val end = start + token.length
        cursor = end + 1
        word.copy(
            text = token,
            index = index,
            startIndex = start,
            endIndex = end,
        )
    }
    return SemanticProjection(
        visualNode.copy(fullText = projectedText, words = projectedWords),
        tokenRange,
    )
}

internal fun textLikelyMatches(first: String, second: String): Boolean {
    val normalizedFirst = normalizeForTextMatch(first)
    val normalizedSecond = normalizeForTextMatch(second)
    if (normalizedFirst.length >= 4 && normalizedSecond.length >= 4 && (
            normalizedFirst.contains(normalizedSecond) ||
                normalizedSecond.contains(normalizedFirst)
            )
    ) return true

    val firstTokens = normalizedFirst.split(' ').filter { it.length >= 2 }.toSet()
    val secondTokens = normalizedSecond.split(' ').filter { it.length >= 2 }.toSet()
    val smallerSize = minOf(firstTokens.size, secondTokens.size)
    if (
        smallerSize > 0 &&
        firstTokens.intersect(secondTokens).size.toDouble() / smallerSize.toDouble() >= 0.60
    ) return true

    if (normalizedFirst.length < 4 || normalizedSecond.length < 4) return false
    val longestLength = maxOf(normalizedFirst.length, normalizedSecond.length)
    if (kotlin.math.abs(normalizedFirst.length - normalizedSecond.length) > longestLength * 0.35) {
        return false
    }
    return editDistance(normalizedFirst, normalizedSecond).toDouble() / longestLength <= 0.28
}

private fun normalizeForTextMatch(value: String): String = buildString(value.length) {
    var lastWasSpace = true
    value.lowercase().forEach { character ->
        if (character.isLetterOrDigit()) {
            append(character)
            lastWasSpace = false
        } else if (!lastWasSpace) {
            append(' ')
            lastWasSpace = true
        }
    }
}.trim()

private fun editDistance(first: String, second: String): Int {
    if (first == second) return 0
    if (first.isEmpty()) return second.length
    if (second.isEmpty()) return first.length

    val shorter: String
    val longer: String
    if (first.length <= second.length) {
        shorter = first
        longer = second
    } else {
        shorter = second
        longer = first
    }

    var previous = IntArray(shorter.length + 1) { it }
    var current = IntArray(shorter.length + 1)
    longer.forEachIndexed { longerIndex, longerCharacter ->
        current[0] = longerIndex + 1
        shorter.forEachIndexed { shorterIndex, shorterCharacter ->
            val insertion = current[shorterIndex] + 1
            val deletion = previous[shorterIndex + 1] + 1
            val substitution = previous[shorterIndex] +
                if (longerCharacter == shorterCharacter) 0 else 1
            current[shorterIndex + 1] = minOf(insertion, deletion, substitution)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[shorter.length]
}
