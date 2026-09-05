// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.paddle.ocr.postprocess

import android.graphics.PointF
import com.paddle.ocr.model.OCRBox
import com.paddle.ocr.model.OCRError
import com.paddle.ocr.util.MathUtils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

object DBPostProcessor {
    private const val MIN_SIZE_BEFORE_UNCLIP = 3f
    private const val MIN_SIZE_AFTER_UNCLIP = 5f

    fun process(
        pred: FloatArray,
        predShape: LongArray,
        thresh: Float,
        boxThresh: Float,
        unclipRatio: Float,
        maxCandidates: Int,
        useDilation: Boolean,
        scoreMode: String,
        boxType: String,
        originalH: Int,
        originalW: Int,
        cancellationCheck: () -> Unit = {},
    ): List<OCRBox> {
        require(boxType == "quad") { "Only DBPostProcess box_type=quad is supported" }
        require(predShape.size >= 4) { "Detection output must have at least four dimensions" }
        require(predShape[2] in 1..Int.MAX_VALUE.toLong()) { "Invalid detection output height" }
        require(predShape[3] in 1..Int.MAX_VALUE.toLong()) { "Invalid detection output width" }
        require(maxCandidates >= 0) { "Maximum candidate count cannot be negative" }

        val pH = predShape[2].toInt()
        val pW = predShape[3].toInt()
        require(pH.toLong() * pW <= pred.size.toLong()) {
            "Detection output buffer is smaller than its shape"
        }
        if (maxCandidates == 0) return emptyList()
        val scaleX = originalW.toDouble() / pW
        val scaleY = originalH.toDouble() / pH
        val normalizedScoreMode = scoreMode.lowercase()

        val rawProb = Mat(pH, pW, CvType.CV_32FC1)
        val mask = try {
            Mat(pH, pW, CvType.CV_8UC1)
        } catch (failure: Throwable) {
            rawProb.release()
            throw failure
        }
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = try {
            Mat()
        } catch (failure: Throwable) {
            mask.release()
            rawProb.release()
            throw failure
        }
        var contourMask = mask

        return try {
            cancellationCheck()
            rawProb.put(0, 0, pred)
            val threshMat = Mat()
            try {
                Imgproc.threshold(rawProb, threshMat, thresh.toDouble(), 255.0, Imgproc.THRESH_BINARY)
                threshMat.convertTo(mask, CvType.CV_8UC1)
            } finally {
                threshMat.release()
            }

            contourMask = if (useDilation) {
                val kernel = Mat.ones(2, 2, CvType.CV_8UC1)
                try {
                    val dilated = Mat()
                    try {
                        Imgproc.dilate(mask, dilated, kernel)
                        dilated
                    } catch (failure: Throwable) {
                        dilated.release()
                        throw failure
                    }
                } finally {
                    kernel.release()
                }
            } else {
                mask
            }

            if (!hasSafeContourComplexity(contourMask, maxCandidates, cancellationCheck)) {
                throw OCRError.InputTooComplex("detector mask is excessively fragmented")
            }
            Imgproc.findContours(contourMask, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            cancellationCheck()
            val boxes = mutableListOf<OCRBox>()
            val numContours = minOf(contours.size, maxCandidates)
            for (index in 0 until numContours) {
                if (index % 16 == 0) cancellationCheck()
                val contour = contours[index]
                val contour2f = MatOfPoint2f(*contour.toArray())
                val rect = try {
                    Imgproc.minAreaRect(contour2f)
                } finally {
                    contour2f.release()
                }
                val sside = minOf(rect.size.width, rect.size.height)
                if (sside < MIN_SIZE_BEFORE_UNCLIP) continue

                val rectPoints = Array(4) { Point() }
                rect.points(rectPoints)
                val orderedPoints = QuadGeometry.orderMinAreaRectPoints(rectPoints)
                val score = if (normalizedScoreMode == "slow") {
                    computeBoxScore(rawProb, contour.toList())
                } else {
                    computeBoxScore(rawProb, orderedPoints)
                }
                if (score < boxThresh) continue

                val expandedPts = PolygonUnclip.unclip(orderedPoints, unclipRatio)
                val expandedRect = MatOfPoint2f().let { expanded2f ->
                    try {
                        expanded2f.fromList(expandedPts)
                        Imgproc.minAreaRect(expanded2f)
                    } finally {
                        expanded2f.release()
                    }
                }
                val sside2 = minOf(expandedRect.size.width, expandedRect.size.height)
                if (sside2 < MIN_SIZE_AFTER_UNCLIP) continue

                val expandedBox = Array(4) { Point() }
                expandedRect.points(expandedBox)
                val ePts = QuadGeometry.orderMinAreaRectPoints(expandedBox)
                val scaled = listOf(
                    scalePoint(ePts[0], scaleX, scaleY, originalW, originalH),
                    scalePoint(ePts[1], scaleX, scaleY, originalW, originalH),
                    scalePoint(ePts[2], scaleX, scaleY, originalW, originalH),
                    scalePoint(ePts[3], scaleX, scaleY, originalW, originalH),
                )

                val boxW = kotlin.math.hypot(
                    scaled[1].x - scaled[0].x,
                    scaled[1].y - scaled[0].y,
                )
                val boxH = kotlin.math.hypot(
                    scaled[3].x - scaled[0].x,
                    scaled[3].y - scaled[0].y,
                )
                if (boxW <= 3 || boxH <= 3) continue

                boxes.add(OCRBox(points = scaled))
            }
            boxes
        } finally {
            hierarchy.release()
            if (contourMask !== mask) {
                contourMask.release()
            }
            contours.forEach { it.release() }
            mask.release()
            rawProb.release()
        }
    }

    /**
     * `findContours` creates one native Mat per contour before callers can apply maxCandidates.
     * Count foreground runs with a fixed row buffer first so adversarial/noisy masks cannot cause
     * an unbounded native allocation spike. DB text regions are filled blobs, so a useful mask has
     * far fewer runs than a fragmented one.
     */
    private fun hasSafeContourComplexity(
        mask: Mat,
        maxCandidates: Int,
        cancellationCheck: () -> Unit,
    ): Boolean {
        val width = mask.cols()
        val height = mask.rows()
        val maxRuns = (maxCandidates.toLong() * 32L)
            .coerceIn(4_096L, 32_768L)
            .toInt()
        val rowData = ByteArray(width)
        var runCount = 0
        for (row in 0 until height) {
            if (row % 16 == 0) cancellationCheck()
            check(mask.get(row, 0, rowData) >= width) { "Unable to read detector mask" }
            var foreground = false
            for (column in 0 until width) {
                val nextForeground = rowData[column].toInt() != 0
                if (nextForeground && !foreground && ++runCount > maxRuns) return false
                foreground = nextForeground
            }
        }
        return true
    }

    private fun scalePoint(
        point: Point,
        scaleX: Double,
        scaleY: Double,
        originalW: Int,
        originalH: Int,
    ): PointF {
        return PointF(
            MathUtils.roundHalfToEven(point.x * scaleX).coerceIn(0, originalW).toFloat(),
            MathUtils.roundHalfToEven(point.y * scaleY).coerceIn(0, originalH).toFloat(),
        )
    }

    private fun computeBoxScore(probMap: Mat, points: List<Point>): Float {
        if (points.isEmpty()) return 0f

        val h = probMap.rows()
        val w = probMap.cols()
        val xmin = points.minOf { kotlin.math.floor(it.x).toInt() }.coerceIn(0, w - 1)
        val xmax = points.maxOf { kotlin.math.ceil(it.x).toInt() }.coerceIn(0, w - 1)
        val ymin = points.minOf { kotlin.math.floor(it.y).toInt() }.coerceIn(0, h - 1)
        val ymax = points.maxOf { kotlin.math.ceil(it.y).toInt() }.coerceIn(0, h - 1)

        val mask = Mat(ymax - ymin + 1, xmax - xmin + 1, CvType.CV_8UC1, Scalar(0.0))
        val pts = try {
            MatOfPoint()
        } catch (failure: Throwable) {
            mask.release()
            throw failure
        }
        var roi: Mat? = null
        return try {
            pts.fromList(
                points.map {
                    Point((it.x - xmin).toInt().toDouble(), (it.y - ymin).toInt().toDouble())
                },
            )
            Imgproc.fillPoly(mask, mutableListOf(pts), Scalar(1.0))
            roi = probMap.submat(ymin, ymax + 1, xmin, xmax + 1)
            Core.mean(roi, mask).`val`[0].toFloat()
        } finally {
            roi?.release()
            pts.release()
            mask.release()
        }
    }
}
