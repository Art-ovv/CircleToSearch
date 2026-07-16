package com.akslabs.circletosearch.ocr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

internal data class OcrFallbackRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val score: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = width.toLong() * height.toLong()
}

internal data class CanonicalOcrPixels(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val border: Int,
)

internal object MixedPolarityRegions {
    private const val DARK_BACKGROUND_LIMIT = 160
    private const val LIGHT_BACKGROUND_LIMIT = 247

    fun detect(
        pixels: IntArray,
        width: Int,
        height: Int,
        maxRegions: Int = 2,
        requestedCellSize: Int? = null,
    ): List<OcrFallbackRegion> {
        if (width <= 0 || height <= 0 || pixels.size < width * height || maxRegions <= 0) {
            return emptyList()
        }

        val cellSize = requestedCellSize
            ?: (minOf(width, height) / 40).coerceIn(24, 48)
        val columns = ceil(width.toDouble() / cellSize).toInt()
        val rows = ceil(height.toDouble() / cellSize).toInt()
        val backgrounds = IntArray(columns * rows)
        val polarity = ByteArray(columns * rows)
        val seeds = BooleanArray(columns * rows)
        val seedScores = IntArray(columns * rows)

        for (row in 0 until rows) {
            val top = row * cellSize
            val bottom = minOf(height, top + cellSize)
            for (column in 0 until columns) {
                val left = column * cellSize
                val right = minOf(width, left + cellSize)
                val histogram = IntArray(16)
                var count = 0
                for (y in top until bottom) {
                    var index = y * width + left
                    for (x in left until right) {
                        histogram[luminance(pixels[index++]) ushr 4]++
                        count++
                    }
                }

                var cumulative = 0
                var medianBin = 0
                // Prefer the brighter half when a boundary tile is split exactly
                // 50/50. Treating that tile as dark can bridge otherwise separate
                // dark panels through a light gutter and turn them into one region.
                val medianTarget = count / 2 + 1
                for (bin in histogram.indices) {
                    cumulative += histogram[bin]
                    if (cumulative >= medianTarget) {
                        medianBin = bin
                        break
                    }
                }
                val background = (medianBin * 16 + 8).coerceAtMost(255)
                val tileIndex = row * columns + column
                backgrounds[tileIndex] = background
                polarity[tileIndex] = when {
                    background < DARK_BACKGROUND_LIMIT -> 1
                    background < LIGHT_BACKGROUND_LIMIT -> 2
                    else -> 0
                }
                if (polarity[tileIndex].toInt() == 0) continue

                val requiredDelta = if (background < DARK_BACKGROUND_LIMIT) 28 else 10
                val brightThreshold = (background + requiredDelta).coerceAtMost(255)
                var brightPixels = 0
                for (bin in histogram.indices) {
                    val binLuminance = bin * 16 + 8
                    if (binLuminance >= brightThreshold) brightPixels += histogram[bin]
                }
                val minimumBrightPixels = max(2, count / 300)
                val maximumBrightPixels = count * 45 / 100
                if (brightPixels in minimumBrightPixels..maximumBrightPixels) {
                    seeds[tileIndex] = true
                    seedScores[tileIndex] = brightPixels
                }
            }
        }

        val visited = BooleanArray(columns * rows)
        val discovered = mutableListOf<OcrFallbackRegion>()
        val queue = IntArray(columns * rows)
        for (start in visited.indices) {
            if (visited[start] || polarity[start].toInt() == 0) continue
            val componentPolarity = polarity[start]
            val referenceBackground = backgrounds[start]
            var queueStart = 0
            var queueEnd = 0
            queue[queueEnd++] = start
            visited[start] = true
            var minColumn = columns
            var minRow = rows
            var maxColumn = -1
            var maxRow = -1
            var score = 0
            var seedTiles = 0

            while (queueStart < queueEnd) {
                val current = queue[queueStart++]
                val currentRow = current / columns
                val currentColumn = current % columns
                minColumn = minOf(minColumn, currentColumn)
                minRow = minOf(minRow, currentRow)
                maxColumn = maxOf(maxColumn, currentColumn)
                maxRow = maxOf(maxRow, currentRow)
                if (seeds[current]) {
                    seedTiles++
                    score += seedScores[current]
                }

                fun enqueue(neighborRow: Int, neighborColumn: Int) {
                    if (neighborRow !in 0 until rows || neighborColumn !in 0 until columns) return
                    val neighbor = neighborRow * columns + neighborColumn
                    if (visited[neighbor] || polarity[neighbor] != componentPolarity) return
                    if (
                        componentPolarity.toInt() == 2 &&
                        abs(backgrounds[neighbor] - referenceBackground) > 32
                    ) return
                    visited[neighbor] = true
                    queue[queueEnd++] = neighbor
                }

                enqueue(currentRow - 1, currentColumn)
                enqueue(currentRow + 1, currentColumn)
                enqueue(currentRow, currentColumn - 1)
                enqueue(currentRow, currentColumn + 1)
            }

            if (seedTiles == 0) continue
            val padding = cellSize
            val left = (minColumn * cellSize - padding).coerceAtLeast(0)
            val top = (minRow * cellSize - padding).coerceAtLeast(0)
            val right = ((maxColumn + 1) * cellSize + padding).coerceAtMost(width)
            val bottom = ((maxRow + 1) * cellSize + padding).coerceAtMost(height)
            if (right - left < cellSize * 2 || bottom - top < cellSize) continue
            discovered += OcrFallbackRegion(left, top, right, bottom, score)
        }

        val maximumFallbackArea = width.toLong() * height.toLong()
        var selectedArea = 0L
        val selected = mutableListOf<OcrFallbackRegion>()
        discovered
            .sortedWith(compareByDescending<OcrFallbackRegion> { it.score }.thenByDescending { it.area })
            .forEach { region ->
                if (selected.size >= maxRegions) return@forEach
                if (selected.isNotEmpty() && selectedArea + region.area > maximumFallbackArea) return@forEach
                selected += region
                selectedArea += region.area
            }
        return selected
    }

    fun canonicalize(
        sourcePixels: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        region: OcrFallbackRegion,
        border: Int = 8,
    ): CanonicalOcrPixels {
        require(sourcePixels.size >= sourceWidth * sourceHeight)
        require(region.left >= 0 && region.top >= 0)
        require(region.right <= sourceWidth && region.bottom <= sourceHeight)
        require(region.width > 0 && region.height > 0)

        val histogram = IntArray(32)
        for (y in region.top until region.bottom) {
            var index = y * sourceWidth + region.left
            for (x in region.left until region.right) {
                histogram[luminance(sourcePixels[index++]) ushr 3]++
            }
        }
        val backgroundBin = histogram.indices.maxByOrNull { histogram[it] } ?: 0
        val background = (backgroundBin * 8 + 4).coerceAtMost(255)

        var brightTailCount = 0
        for (bin in histogram.indices) {
            val value = bin * 8 + 4
            if (value >= background + 8) brightTailCount += histogram[bin]
        }
        val foreground = if (brightTailCount == 0) {
            (background + 40).coerceAtMost(255)
        } else {
            val target = (brightTailCount * 85) / 100
            var cumulative = 0
            var percentile = (background + 40).coerceAtMost(255)
            for (bin in histogram.indices) {
                val value = bin * 8 + 4
                if (value < background + 8) continue
                cumulative += histogram[bin]
                if (cumulative >= target) {
                    percentile = value
                    break
                }
            }
            percentile
        }
        val foregroundDelta = (foreground - background).coerceAtLeast(8)
        val gain = (160f / foregroundDelta.toFloat()).coerceIn(1.25f, 5f)

        val outputWidth = region.width + border * 2
        val outputHeight = region.height + border * 2
        val output = IntArray(outputWidth * outputHeight) { 0xFFFFFFFF.toInt() }
        for (localY in 0 until region.height) {
            var sourceIndex = (region.top + localY) * sourceWidth + region.left
            var outputIndex = (localY + border) * outputWidth + border
            for (localX in 0 until region.width) {
                val value = (235f + gain * (background - luminance(sourcePixels[sourceIndex++]))).roundToInt()
                    .coerceIn(0, 255)
                output[outputIndex++] = 0xFF000000.toInt() or
                    (value shl 16) or (value shl 8) or value
            }
        }
        return CanonicalOcrPixels(output, outputWidth, outputHeight, border)
    }

    private fun luminance(pixel: Int): Int {
        val red = pixel ushr 16 and 0xFF
        val green = pixel ushr 8 and 0xFF
        val blue = pixel and 0xFF
        return (77 * red + 150 * green + 29 * blue) ushr 8
    }
}
