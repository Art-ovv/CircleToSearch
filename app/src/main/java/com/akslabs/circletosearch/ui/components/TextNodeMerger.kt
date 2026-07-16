package com.akslabs.circletosearch.ui.components

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

    val uncoveredOcr = ocrNodes.filterNot { ocrNode ->
        val ocrWidth = (ocrNode.bounds.right - ocrNode.bounds.left).coerceAtLeast(0)
        val ocrHeight = (ocrNode.bounds.bottom - ocrNode.bounds.top).coerceAtLeast(0)
        val ocrArea = ocrWidth.toLong() * ocrHeight.toLong()
        if (ocrArea == 0L) return@filterNot false

        authoritative.any { assistNode ->
            val overlapWidth = (
                minOf(ocrNode.bounds.right, assistNode.bounds.right) -
                    maxOf(ocrNode.bounds.left, assistNode.bounds.left)
                ).coerceAtLeast(0)
            val overlapHeight = (
                minOf(ocrNode.bounds.bottom, assistNode.bounds.bottom) -
                    maxOf(ocrNode.bounds.top, assistNode.bounds.top)
                ).coerceAtLeast(0)
            val overlapArea = overlapWidth.toLong() * overlapHeight.toLong()
            val assistWidth = (assistNode.bounds.right - assistNode.bounds.left).coerceAtLeast(0)
            val assistHeight = (assistNode.bounds.bottom - assistNode.bounds.top).coerceAtLeast(0)
            val assistArea = assistWidth.toLong() * assistHeight.toLong()
            val overlapRatio = overlapArea.toDouble() / ocrArea.toDouble()
            val assistToOcrAreaRatio = assistArea.toDouble() / ocrArea.toDouble()
            overlapRatio >= 0.70 && (
                assistToOcrAreaRatio <= 8.0 ||
                    textLikelyMatches(assistNode.fullText, ocrNode.fullText)
                )
        }
    }

    return (authoritative + uncoveredOcr)
        .sortedWith(compareBy<TextNode>({ it.bounds.top }, { it.bounds.left }))
}

internal fun textLikelyMatches(first: String, second: String): Boolean {
    fun normalize(value: String): String = buildString(value.length) {
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

    val normalizedFirst = normalize(first)
    val normalizedSecond = normalize(second)
    if (normalizedFirst.length >= 4 && normalizedSecond.length >= 4 && (
            normalizedFirst.contains(normalizedSecond) ||
                normalizedSecond.contains(normalizedFirst)
            )
    ) return true

    val firstTokens = normalizedFirst.split(' ').filter { it.length >= 2 }.toSet()
    val secondTokens = normalizedSecond.split(' ').filter { it.length >= 2 }.toSet()
    val smallerSize = minOf(firstTokens.size, secondTokens.size)
    if (smallerSize == 0) return false
    return firstTokens.intersect(secondTokens).size.toDouble() / smallerSize.toDouble() >= 0.60
}
