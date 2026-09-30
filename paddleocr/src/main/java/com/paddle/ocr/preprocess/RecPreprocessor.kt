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

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.ceil

data class RecPreprocessResult(
    val tensorData: FloatBuffer,
    val shape: LongArray,
    val validRatios: FloatArray,
)

object RecPreprocessor {
    private const val FIXED_HEIGHT = 48
    private const val MAX_IMG_W = 3200

    fun preprocessBatch(
        crops: List<Mat>,
        cancellationCheck: () -> Unit = {},
    ): RecPreprocessResult {
        require(crops.isNotEmpty()) { "At least one text crop is required" }

        // The bundled Paddle recognition model declares img_mode: BGR. Keep the channel order
        // produced by QuadTextCrop and normalize directly into the final tensor.
        val floatMats = mutableListOf<Mat>()
        try {
            for (crop in crops) {
                cancellationCheck()
                val height = crop.rows()
                val width = crop.cols()
                require(height > 0 && width > 0) { "Text crop dimensions must be positive" }
                val aspectRatio = width.toDouble() / height
                val newWidth = ceil(FIXED_HEIGHT * aspectRatio).toInt()
                    .coerceIn(1, MAX_IMG_W)
                val resized = Mat()
                try {
                    Imgproc.resize(
                        crop,
                        resized,
                        Size(newWidth.toDouble(), FIXED_HEIGHT.toDouble()),
                        0.0,
                        0.0,
                        Imgproc.INTER_LINEAR,
                    )
                    val floatMat = Mat(FIXED_HEIGHT, newWidth, CvType.CV_32FC3)
                    try {
                        resized.convertTo(floatMat, CvType.CV_32F, 1.0 / 127.5, -1.0)
                        floatMats.add(floatMat)
                    } catch (failure: Throwable) {
                        floatMat.release()
                        throw failure
                    }
                } finally {
                    resized.release()
                }
            }

            val maxWidth = floatMats.maxOf { it.cols() }
            val batchSize = floatMats.size
            val validWidths = IntArray(batchSize) { floatMats[it].cols() }
            val validRatios = FloatArray(batchSize) { validWidths[it].toFloat() / maxWidth }
            val tensorData = packNchwTensor(
                FIXED_HEIGHT, maxWidth, validWidths, cancellationCheck,
            ) { batch, row, rowBuffer ->
                floatMats[batch].get(row, 0, rowBuffer)
            }

            return RecPreprocessResult(
                tensorData = tensorData,
                shape = longArrayOf(
                    batchSize.toLong(),
                    3,
                    FIXED_HEIGHT.toLong(),
                    maxWidth.toLong(),
                ),
                validRatios = validRatios,
            )
        } finally {
            floatMats.forEach { it.release() }
        }
    }
}
