package com.akslabs.circletosearch.ocr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

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
    private const val DARK_BACKGROUND_LIMIT = 176
    private const val MAX_NON_WHITE_BACKGROUND = 252
    private const val MAX_CLUSTER_SEED_ROWS = 4
    private const val MAX_REGION_AREA_FRACTION = 0.65
    private const val MAX_TOTAL_AREA_FRACTION = 0.75

    fun detect(
        pixels: IntArray,
        width: Int,
        height: Int,
        maxRegions: Int = 2,
        requestedCellSize: Int? = null,
        cancellationCheck: (() -> Unit)? = null,
    ): List<OcrFallbackRegion> {
        if (width <= 0 || height <= 0 || pixels.size < width * height || maxRegions <= 0) {
            return emptyList()
        }

        val cellSize = requestedCellSize
            ?: (minOf(width, height) / 40).coerceIn(24, 48)
        val columns = ceil(width.toDouble() / cellSize).toInt()
        val rows = ceil(height.toDouble() / cellSize).toInt()
        val tileCount = columns * rows
        val backgrounds = IntArray(tileCount)
        val polarity = ByteArray(columns * rows)
        val seeds = BooleanArray(columns * rows)
        val seedScores = IntArray(columns * rows)
        // These buffers are reused for every tile. Allocating a histogram inside
        // the nested tile loop used to create thousands of short-lived arrays.
        val histogram = IntArray(256)
        val brightRows = IntArray(cellSize)
        val brightColumns = IntArray(cellSize)

        for (row in 0 until rows) {
            cancellationCheck?.invoke()
            val top = row * cellSize
            val bottom = minOf(height, top + cellSize)
            for (column in 0 until columns) {
                val left = column * cellSize
                val right = minOf(width, left + cellSize)
                histogram.fill(0)
                var count = 0
                for (y in top until bottom) {
                    var index = y * width + left
                    for (x in left until right) {
                        histogram[luminance(pixels[index++])]++
                        count++
                    }
                }

                var cumulative = 0
                var background = 0
                // Prefer the brighter half when a boundary tile is split exactly
                // 50/50. Treating that tile as dark can bridge otherwise separate
                // dark panels through a light gutter and turn them into one region.
                val medianTarget = count / 2 + 1
                for (value in histogram.indices) {
                    cumulative += histogram[value]
                    if (cumulative >= medianTarget) {
                        background = value
                        break
                    }
                }
                val tileIndex = row * columns + column
                backgrounds[tileIndex] = background
                polarity[tileIndex] = when {
                    background <= DARK_BACKGROUND_LIMIT -> 1
                    background <= MAX_NON_WHITE_BACKGROUND -> 2
                    else -> 0
                }
                if (polarity[tileIndex].toInt() == 0) continue

                val requiredDelta = when {
                    background <= DARK_BACKGROUND_LIMIT -> 24
                    background >= 246 -> 2
                    background >= 232 -> 4
                    background >= 208 -> 7
                    else -> 10
                }
                val brightThreshold = (background + requiredDelta).coerceAtMost(255)
                var brightPixels = 0
                for (value in brightThreshold..255) {
                    brightPixels += histogram[value]
                }
                val minimumBrightPixels = max(2, count / 400)
                val maximumBrightPixels = count * 40 / 100
                if (brightPixels !in minimumBrightPixels..maximumBrightPixels) continue

                val tileWidth = right - left
                val tileHeight = bottom - top
                brightRows.fill(0, 0, tileHeight)
                brightColumns.fill(0, 0, tileWidth)
                var brightRuns = 0
                for (localY in 0 until tileHeight) {
                    var index = (top + localY) * width + left
                    var insideBrightRun = false
                    for (localX in 0 until tileWidth) {
                        val isBright = luminance(pixels[index++]) >= brightThreshold
                        if (isBright) {
                            brightRows[localY]++
                            brightColumns[localX]++
                            if (!insideBrightRun) brightRuns++
                        }
                        insideBrightRun = isBright
                    }
                }
                val occupiedRows = (0 until tileHeight).count { brightRows[it] > 0 }
                val occupiedColumns = (0 until tileWidth).count { brightColumns[it] > 0 }
                // Text needs at least one stroke run spanning more than a single
                // pixel in each direction. This rejects isolated highlights and
                // most photographic glare before they reach expensive OCR.
                if (brightRuns < 2 || occupiedRows < 2 || occupiedColumns < 2) continue

                seeds[tileIndex] = true
                seedScores[tileIndex] = brightPixels + brightRuns * 4 +
                    minOf(occupiedRows, occupiedColumns) * 2
            }
        }

        // Cluster only text-like seed tiles. Flooding all same-polarity background
        // tiles made a single caption on a dark UI expand to a full-screen region.
        val visited = BooleanArray(columns * rows)
        val discovered = mutableListOf<OcrFallbackRegion>()
        val queue = IntArray(columns * rows)
        for (start in visited.indices) {
            if ((start and 31) == 0) cancellationCheck?.invoke()
            if (visited[start] || !seeds[start]) continue
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
                seedTiles++
                score += seedScores[current]

                fun enqueue(neighborRow: Int, neighborColumn: Int) {
                    if (neighborRow !in 0 until rows || neighborColumn !in 0 until columns) return
                    val neighbor = neighborRow * columns + neighborColumn
                    if (visited[neighbor] || !seeds[neighbor]) return
                    if (polarity[neighbor] != componentPolarity) return
                    val allowedBackgroundDrift = if (componentPolarity.toInt() == 1) 28 else 18
                    if (abs(backgrounds[neighbor] - backgrounds[current]) > allowedBackgroundDrift) return
                    if (abs(backgrounds[neighbor] - referenceBackground) > allowedBackgroundDrift * 2) return
                    val expandedRowSpan =
                        maxOf(maxRow, neighborRow) - minOf(minRow, neighborRow) + 1
                    if (expandedRowSpan > MAX_CLUSTER_SEED_ROWS) return
                    visited[neighbor] = true
                    queue[queueEnd++] = neighbor
                }

                for (rowOffset in -1..1) {
                    for (columnOffset in -1..1) {
                        if (rowOffset == 0 && columnOffset == 0) continue
                        enqueue(currentRow + rowOffset, currentColumn + columnOffset)
                    }
                }
            }

            if (seedTiles == 0) continue
            val padding = cellSize
            val left = (minColumn * cellSize - padding).coerceAtLeast(0)
            val top = (minRow * cellSize - padding).coerceAtLeast(0)
            val right = ((maxColumn + 1) * cellSize + padding).coerceAtMost(width)
            val bottom = ((maxRow + 1) * cellSize + padding).coerceAtMost(height)
            if (right - left < cellSize * 2 || bottom - top < cellSize) continue
            val region = OcrFallbackRegion(left, top, right, bottom, score)
            val screenArea = width.toLong() * height.toLong()
            if (region.area > (screenArea * MAX_REGION_AREA_FRACTION).toLong()) continue
            discovered += region
        }

        val maximumFallbackArea =
            (width.toLong() * height.toLong() * MAX_TOTAL_AREA_FRACTION).toLong()
        var selectedArea = 0L
        val selected = mutableListOf<OcrFallbackRegion>()
        discovered
            .sortedWith(compareByDescending<OcrFallbackRegion> { it.score }.thenByDescending { it.area })
            .forEach { region ->
                if (selected.size >= maxRegions) return@forEach
                if (selectedArea + region.area > maximumFallbackArea) return@forEach
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
        cancellationCheck: (() -> Unit)? = null,
    ): CanonicalOcrPixels {
        require(sourcePixels.size >= sourceWidth * sourceHeight)
        require(region.left >= 0 && region.top >= 0)
        require(region.right <= sourceWidth && region.bottom <= sourceHeight)
        require(region.width > 0 && region.height > 0)

        /*
         * Keep one byte of luminance per pixel and update vertical box sums as
         * rows advance. This uses roughly a quarter of the memory of a full
         * integral image while retaining O(width * height) processing.
         */
        val luminances = ByteArray(region.width * region.height)
        for (localY in 0 until region.height) {
            if ((localY and 7) == 0) cancellationCheck?.invoke()
            var sourceIndex = (region.top + localY) * sourceWidth + region.left
            var luminanceIndex = localY * region.width
            for (localX in 0 until region.width) {
                luminances[luminanceIndex++] =
                    luminance(sourcePixels[sourceIndex++]).toByte()
            }
        }

        val localRadius = (minOf(region.width, region.height) / 48)
            .coerceIn(10, 28)
        val detailRadius = (localRadius / 3).coerceIn(4, 8)
        val localColumns = IntArray(region.width)
        val detailColumns = IntArray(region.width)

        fun addLuminanceRow(columns: IntArray, row: Int, multiplier: Int) {
            if (row !in 0 until region.height) return
            var index = row * region.width
            for (x in 0 until region.width) {
                columns[x] += (luminances[index++].toInt() and 0xFF) * multiplier
            }
        }

        for (row in 0..localRadius.coerceAtMost(region.height - 1)) {
            addLuminanceRow(localColumns, row, 1)
        }
        for (row in 0..detailRadius.coerceAtMost(region.height - 1)) {
            addLuminanceRow(detailColumns, row, 1)
        }

        val outputWidth = region.width + border * 2
        val outputHeight = region.height + border * 2
        val output = IntArray(outputWidth * outputHeight) { 0xFFFFFFFF.toInt() }
        for (localY in 0 until region.height) {
            if ((localY and 7) == 0) cancellationCheck?.invoke()
            if (localY > 0) {
                addLuminanceRow(localColumns, localY - localRadius - 1, -1)
                addLuminanceRow(localColumns, localY + localRadius, 1)
                addLuminanceRow(detailColumns, localY - detailRadius - 1, -1)
                addLuminanceRow(detailColumns, localY + detailRadius, 1)
            }

            val localRowCount =
                minOf(region.height - 1, localY + localRadius) -
                    maxOf(0, localY - localRadius) + 1
            val detailRowCount =
                minOf(region.height - 1, localY + detailRadius) -
                    maxOf(0, localY - detailRadius) + 1
            var localSum = 0
            var detailSum = 0
            for (x in 0..localRadius.coerceAtMost(region.width - 1)) {
                localSum += localColumns[x]
            }
            for (x in 0..detailRadius.coerceAtMost(region.width - 1)) {
                detailSum += detailColumns[x]
            }

            var sourceIndex = (region.top + localY) * sourceWidth + region.left
            var outputIndex = (localY + border) * outputWidth + border
            for (localX in 0 until region.width) {
                val localColumnCount =
                    minOf(region.width - 1, localX + localRadius) -
                        maxOf(0, localX - localRadius) + 1
                val detailColumnCount =
                    minOf(region.width - 1, localX + detailRadius) -
                        maxOf(0, localX - detailRadius) + 1
                val localBackground = localSum / (localColumnCount * localRowCount)
                val detailBackground = detailSum / (detailColumnCount * detailRowCount)
                val sourcePixel = sourcePixels[sourceIndex++]
                val foreground = luminance(sourcePixel)

                // A glyph must be brighter at both scales. The small-scale check
                // suppresses broad panel/photo edges that a single large window
                // would otherwise turn into text-like black bands.
                val foregroundDelta = minOf(
                    foreground - localBackground,
                    foreground - detailBackground,
                )
                val red = sourcePixel ushr 16 and 0xFF
                val green = sourcePixel ushr 8 and 0xFF
                val blue = sourcePixel and 0xFF
                val chroma = maxOf(red, green, blue) - minOf(red, green, blue)
                val chromaPenalty = ((chroma - 48).coerceAtLeast(0)) / 3
                val effectiveDelta = foregroundDelta - chromaPenalty
                val minimumDelta = if (localBackground >= 200) 2 else 4
                val availableContrast = (255 - localBackground).coerceIn(20, 120)
                val contrastGain = (420 / availableContrast).coerceIn(4, 18)
                val ink = ((effectiveDelta - minimumDelta) * contrastGain)
                    .coerceIn(0, 255)
                val value = 255 - ink
                output[outputIndex++] = 0xFF000000.toInt() or
                    (value shl 16) or (value shl 8) or value

                val removeLocalColumn = localX - localRadius
                if (removeLocalColumn >= 0) localSum -= localColumns[removeLocalColumn]
                val addLocalColumn = localX + localRadius + 1
                if (addLocalColumn < region.width) localSum += localColumns[addLocalColumn]

                val removeDetailColumn = localX - detailRadius
                if (removeDetailColumn >= 0) {
                    detailSum -= detailColumns[removeDetailColumn]
                }
                val addDetailColumn = localX + detailRadius + 1
                if (addDetailColumn < region.width) {
                    detailSum += detailColumns[addDetailColumn]
                }
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
