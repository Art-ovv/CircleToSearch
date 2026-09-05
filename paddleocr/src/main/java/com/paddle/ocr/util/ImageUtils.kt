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

package com.paddle.ocr.util

import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt

internal data class ResizeDimensions(
    val width: Int,
    val height: Int,
)

object ImageUtils {

    fun resizeToMultipleOf32(
        src: Mat,
        limitSideLen: Int,
        limitType: String,
        maxSideLimit: Int,
        maxPixelCount: Int,
    ): Mat {
        val h = src.rows()
        val w = src.cols()
        val dimensions = calculateResizeDimensions(
            sourceHeight = h,
            sourceWidth = w,
            limitSideLen = limitSideLen,
            limitType = limitType,
            maxSideLimit = maxSideLimit,
            maxPixelCount = maxPixelCount,
        )
        val dst = Mat()
        return try {
            Imgproc.resize(
                src,
                dst,
                Size(dimensions.width.toDouble(), dimensions.height.toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_LINEAR,
            )
            dst
        } catch (failure: Throwable) {
            dst.release()
            throw failure
        }
    }

    internal fun calculateResizeDimensions(
        sourceHeight: Int,
        sourceWidth: Int,
        limitSideLen: Int,
        limitType: String,
        maxSideLimit: Int,
        maxPixelCount: Int,
    ): ResizeDimensions {
        require(sourceHeight > 0 && sourceWidth > 0) { "Source dimensions must be positive" }
        require(limitSideLen > 0) { "Detection side limit must be positive" }
        require(maxSideLimit >= 32) { "Detection maximum side must be at least 32" }
        require(maxPixelCount >= 32 * 32) { "Detection pixel budget must be at least 1024" }

        val initialRatio = when (limitType.lowercase()) {
            "max" -> if (maxOf(sourceHeight, sourceWidth) > limitSideLen) {
                limitSideLen.toDouble() / maxOf(sourceHeight, sourceWidth)
            } else {
                1.0
            }
            "min" -> if (minOf(sourceHeight, sourceWidth) < limitSideLen) {
                limitSideLen.toDouble() / minOf(sourceHeight, sourceWidth)
            } else {
                1.0
            }
            "resize_long" -> limitSideLen.toDouble() / maxOf(sourceHeight, sourceWidth)
            else -> throw IllegalArgumentException("Unsupported det limit type: $limitType")
        }

        var newHeight = (sourceHeight * initialRatio).toInt().coerceAtLeast(1)
        var newWidth = (sourceWidth * initialRatio).toInt().coerceAtLeast(1)
        val sideRatio = if (maxOf(newHeight, newWidth) > maxSideLimit) {
            maxSideLimit.toDouble() / maxOf(newHeight, newWidth)
        } else {
            1.0
        }
        val currentPixels = newHeight.toDouble() * newWidth
        val pixelRatio = if (currentPixels > maxPixelCount) {
            sqrt(maxPixelCount / currentPixels)
        } else {
            1.0
        }
        val capRatio = minOf(sideRatio, pixelRatio)
        newHeight = (newHeight * capRatio).toInt().coerceAtLeast(1)
        newWidth = (newWidth * capRatio).toInt().coerceAtLeast(1)

        var roundedHeight = maxOf(MathUtils.roundHalfToEven(newHeight / 32.0) * 32, 32)
        var roundedWidth = maxOf(MathUtils.roundHalfToEven(newWidth / 32.0) * 32, 32)
        while (
            maxOf(roundedHeight, roundedWidth) > maxSideLimit ||
            roundedHeight.toLong() * roundedWidth > maxPixelCount.toLong()
        ) {
            if (roundedHeight >= roundedWidth && roundedHeight > 32) {
                roundedHeight -= 32
            } else if (roundedWidth > 32) {
                roundedWidth -= 32
            } else {
                break
            }
        }
        return ResizeDimensions(width = roundedWidth, height = roundedHeight)
    }
}
