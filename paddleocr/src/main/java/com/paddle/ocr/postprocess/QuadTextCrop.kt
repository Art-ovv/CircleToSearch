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

import com.paddle.ocr.model.OCRBox
import android.graphics.PointF
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max

object QuadTextCrop {
    data class Crop(val image: Mat, val recognitionBox: OCRBox)

    fun crop(src: Mat, box: OCRBox): Crop {
        // Align with PaddleX CropByPolys.get_minarea_rect_crop: recompute minAreaRect
        // from the detected quad before perspective transform.
        val rectPoints = box.points.map { Point(it.x.toDouble(), it.y.toDouble()) }
        val rectInput = MatOfPoint2f()
        val boundingBox = try {
            rectInput.fromList(rectPoints)
            Imgproc.minAreaRect(rectInput)
        } finally {
            rectInput.release()
        }

        val boxPoints = Array(4) { Point() }
        boundingBox.points(boxPoints)
        val ordered = QuadGeometry.orderMinAreaRectPoints(boxPoints)

        val widthTop = hypot(ordered[0].x - ordered[1].x, ordered[0].y - ordered[1].y)
        val widthBottom = hypot(ordered[2].x - ordered[3].x, ordered[2].y - ordered[3].y)
        val heightLeft = hypot(ordered[0].x - ordered[3].x, ordered[0].y - ordered[3].y)
        val heightRight = hypot(ordered[1].x - ordered[2].x, ordered[1].y - ordered[2].y)

        val dstW = max(widthTop, widthBottom).toInt().coerceAtLeast(1)
        val dstH = max(heightLeft, heightRight).toInt().coerceAtLeast(1)
        val orientation = CropOrientation(dstW, dstH)
        val recognitionBox = OCRBox(orientation.recognitionCorners(ordered).map {
            PointF(it.x.toFloat(), it.y.toFloat())
        })

        val srcPts = MatOfPoint2f()
        val dstPts = try {
            MatOfPoint2f()
        } catch (failure: Throwable) {
            srcPts.release()
            throw failure
        }
        val transform = try {
            srcPts.fromList(ordered)
            dstPts.fromList(
                listOf(
                    Point(0.0, 0.0),
                    Point(dstW.toDouble(), 0.0),
                    Point(dstW.toDouble(), dstH.toDouble()),
                    Point(0.0, dstH.toDouble()),
                ),
            )
            Imgproc.getPerspectiveTransform(srcPts, dstPts)
        } finally {
            srcPts.release()
            dstPts.release()
        }

        val dst = try {
            Mat(dstH, dstW, CvType.CV_8UC3)
        } catch (failure: Throwable) {
            transform.release()
            throw failure
        }
        try {
            try {
                Imgproc.warpPerspective(
                    src,
                    dst,
                    transform,
                    Size(dstW.toDouble(), dstH.toDouble()),
                    Imgproc.INTER_CUBIC,
                    Core.BORDER_REPLICATE,
                )
            } catch (failure: Throwable) {
                dst.release()
                throw failure
            }

            if (orientation.rotatedCounterClockwise) {
                val rotated = Mat()
                return try {
                    Core.rotate(dst, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
                    Crop(rotated, recognitionBox)
                } catch (failure: Throwable) {
                    rotated.release()
                    throw failure
                } finally {
                    dst.release()
                }
            }
            return Crop(dst, recognitionBox)
        } finally {
            transform.release()
        }
    }
}
