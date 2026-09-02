package com.akslabs.circletosearch.ocr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Estimates the dominant text-line angle from horizontal luminance edges.
 *
 * The input is sampled to a bounded size, then each candidate angle is scored
 * by how tightly its edges collapse onto horizontal projection rows. This is
 * intentionally limited to the small rotations commonly produced by an
 * off-centre phone camera; larger rotations should be handled by device
 * orientation rather than an expensive OCR search.
 */
internal object OcrSkewEstimator {
    private const val MAX_SAMPLE_DIMENSION = 640
    private const val MAX_ABS_ANGLE = 12
    private const val MIN_EDGE_DELTA = 24
    private const val MIN_EDGE_COUNT = 120
    private const val MIN_USEFUL_ANGLE = 2f
    private const val MIN_SCORE_IMPROVEMENT = 1.12

    fun estimateDegrees(
        pixels: IntArray,
        width: Int,
        height: Int,
        cancellationCheck: (() -> Unit)? = null,
    ): Float? {
        if (width < 48 || height < 48 || pixels.size < width * height) return null

        val sampleStep = ceil(
            max(width, height).toDouble() / MAX_SAMPLE_DIMENSION.toDouble(),
        ).toInt().coerceAtLeast(1)
        val sampleWidth = (width + sampleStep - 1) / sampleStep
        val sampleHeight = (height + sampleStep - 1) / sampleStep
        if (sampleWidth < 32 || sampleHeight < 32) return null

        val maximumEdges = sampleWidth * sampleHeight
        val edgeX = IntArray(maximumEdges)
        val edgeY = IntArray(maximumEdges)
        val edgeWeight = IntArray(maximumEdges)
        var edgeCount = 0
        val horizontalInset = (sampleWidth * 0.02f).roundToInt().coerceAtLeast(1)
        val verticalInset = (sampleHeight * 0.02f).roundToInt().coerceAtLeast(1)

        for (sampleY in verticalInset until sampleHeight - verticalInset) {
            if ((sampleY and 7) == 0) cancellationCheck?.invoke()
            val sourceY = (sampleY * sampleStep).coerceAtMost(height - 1)
            val upperY = (sourceY - sampleStep).coerceAtLeast(0)
            val lowerY = (sourceY + sampleStep).coerceAtMost(height - 1)
            for (sampleX in horizontalInset until sampleWidth - horizontalInset) {
                val sourceX = (sampleX * sampleStep).coerceAtMost(width - 1)
                val upper = luminance(pixels[upperY * width + sourceX])
                val lower = luminance(pixels[lowerY * width + sourceX])
                val delta = abs(lower - upper)
                if (delta < MIN_EDGE_DELTA) continue

                edgeX[edgeCount] = sampleX
                edgeY[edgeCount] = sampleY
                edgeWeight[edgeCount] = delta.coerceAtMost(128)
                edgeCount++
            }
        }
        if (edgeCount < MIN_EDGE_COUNT) return null

        var zeroScore = 0.0
        var bestScore = Double.NEGATIVE_INFINITY
        var bestAngle = 0
        val projectionOffset = ceil(
            sampleWidth * sin(Math.toRadians(MAX_ABS_ANGLE.toDouble())),
        ).toInt() + 3
        val projection = IntArray(sampleHeight + projectionOffset * 2 + 6)
        for (angle in -MAX_ABS_ANGLE..MAX_ABS_ANGLE) {
            cancellationCheck?.invoke()
            val score = projectionScore(
                angle = angle,
                edgeX = edgeX,
                edgeY = edgeY,
                edgeWeight = edgeWeight,
                edgeCount = edgeCount,
                projectionOffset = projectionOffset,
                projection = projection,
            )
            if (angle == 0) zeroScore = score
            if (score > bestScore) {
                bestScore = score
                bestAngle = angle
            }
        }

        if (abs(bestAngle.toFloat()) < MIN_USEFUL_ANGLE) return null
        if (zeroScore <= 0.0 || bestScore < zeroScore * MIN_SCORE_IMPROVEMENT) return null
        return bestAngle.toFloat()
    }

    private fun projectionScore(
        angle: Int,
        edgeX: IntArray,
        edgeY: IntArray,
        edgeWeight: IntArray,
        edgeCount: Int,
        projectionOffset: Int,
        projection: IntArray,
    ): Double {
        val radians = Math.toRadians(angle.toDouble())
        val sine = sin(radians)
        val cosine = cos(radians)
        projection.fill(0)

        for (index in 0 until edgeCount) {
            // For a line y = tan(angle) * x + b, this value is constant.
            val projectedY = (
                -edgeX[index] * sine + edgeY[index] * cosine
                ).roundToInt() + projectionOffset
            if (projectedY in projection.indices) {
                projection[projectedY] += edgeWeight[index]
            }
        }

        var score = 0.0
        projection.forEach { value ->
            score += value.toDouble() * value.toDouble()
        }
        return score
    }

    private fun luminance(pixel: Int): Int {
        val red = pixel ushr 16 and 0xFF
        val green = pixel ushr 8 and 0xFF
        val blue = pixel and 0xFF
        return (77 * red + 150 * green + 29 * blue) ushr 8
    }
}
