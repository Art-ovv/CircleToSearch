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

package com.paddle.ocr.preprocess

import android.graphics.Bitmap
import com.paddle.ocr.util.BitmapUtils
import com.paddle.ocr.util.ImageUtils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

data class DetPreprocessResult(
    val tensorData: FloatArray,
    val shape: LongArray,
    val originalH: Int,
    val originalW: Int,
)

object DetPreprocessor {
    private val mean = doubleArrayOf(0.485, 0.456, 0.406)
    private val std = doubleArrayOf(0.229, 0.224, 0.225)
    private const val scale = 1.0 / 255.0

    fun preprocess(
        bitmap: Bitmap,
        limitSideLen: Int,
        limitType: String,
        maxSideLimit: Int,
        maxPixelCount: Int,
        imgMode: String,
        cancellationCheck: () -> Unit = {},
    ): DetPreprocessResult {
        val src = BitmapUtils.bitmapToBGRMat(bitmap)
        return try {
            preprocess(
                src,
                limitSideLen,
                limitType,
                maxSideLimit,
                maxPixelCount,
                imgMode,
                cancellationCheck,
            )
        } finally {
            src.release()
        }
    }

    fun preprocess(
        src: Mat,
        limitSideLen: Int,
        limitType: String,
        maxSideLimit: Int,
        maxPixelCount: Int,
        imgMode: String,
        cancellationCheck: () -> Unit = {},
    ): DetPreprocessResult {
        val originalH = src.rows()
        val originalW = src.cols()
        var convertedInput: Mat? = null
        var resized: Mat? = null
        var floatMat: Mat? = null
        try {
            cancellationCheck()
            val input = if (imgMode.uppercase() == "RGB") {
                Mat().also {
                    convertedInput = it
                    Imgproc.cvtColor(src, it, Imgproc.COLOR_BGR2RGB)
                }
            } else {
                src
            }
            resized = ImageUtils.resizeToMultipleOf32(
                input,
                limitSideLen,
                limitType,
                maxSideLimit,
                maxPixelCount,
            )
            cancellationCheck()

            val h = resized.rows()
            val w = resized.cols()
            floatMat = Mat(h, w, CvType.CV_32FC3)
            resized.convertTo(floatMat, CvType.CV_32F)
            Core.multiply(
                floatMat,
                Scalar(scale / std[0], scale / std[1], scale / std[2]),
                floatMat,
            )
            Core.subtract(
                floatMat,
                Scalar(mean[0] / std[0], mean[1] / std[1], mean[2] / std[2]),
                floatMat,
            )

            val channelSize = h * w
            val tensorData = FloatArray(3 * channelSize)
            val rowBuffer = FloatArray(w * 3)
            for (row in 0 until h) {
                if (row % 16 == 0) cancellationCheck()
                floatMat.get(row, 0, rowBuffer)
                val rowOffset = row * w
                for (column in 0 until w) {
                    val pixelOffset = column * 3
                    val tensorOffset = rowOffset + column
                    tensorData[tensorOffset] = rowBuffer[pixelOffset]
                    tensorData[channelSize + tensorOffset] = rowBuffer[pixelOffset + 1]
                    tensorData[2 * channelSize + tensorOffset] = rowBuffer[pixelOffset + 2]
                }
            }
            cancellationCheck()

            return DetPreprocessResult(
                tensorData = tensorData,
                shape = longArrayOf(1, 3, h.toLong(), w.toLong()),
                originalH = originalH,
                originalW = originalW,
            )
        } finally {
            floatMat?.release()
            resized?.release()
            convertedInput?.release()
        }
    }
}
