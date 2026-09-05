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

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

object BitmapUtils {

    fun imdecodeBGR(imageBytes: ByteArray): Mat {
        val encoded = MatOfByte(*imageBytes)
        return try {
            Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR)
        } finally {
            encoded.release()
        }
    }

    fun bitmapToBGRMat(bitmap: Bitmap): Mat {
        return bitmapToMat(bitmap, Imgproc.COLOR_RGBA2BGR)
    }

    fun bitmapToRGBMat(bitmap: Bitmap): Mat {
        return bitmapToMat(bitmap, Imgproc.COLOR_RGBA2RGB)
    }

    fun bgrMatToBitmap(mat: Mat): Bitmap {
        val rgba = Mat()
        var bitmap: Bitmap? = null
        return try {
            Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_BGR2RGBA)
            bitmap = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, bitmap)
            bitmap
        } catch (failure: Throwable) {
            bitmap?.recycle()
            throw failure
        } finally {
            rgba.release()
        }
    }

    private fun bitmapToMat(bitmap: Bitmap, colorConversionCode: Int): Mat {
        var ownedCopy: Bitmap? = null
        val inputBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)?.also { ownedCopy = it }
                ?: throw IllegalStateException("Unable to copy bitmap for OCR")
        }
        var rgba: Mat? = null
        var dst: Mat? = null
        return try {
            val rgbaMat = Mat(inputBitmap.height, inputBitmap.width, CvType.CV_8UC4)
            rgba = rgbaMat
            val result = Mat()
            dst = result
            Utils.bitmapToMat(inputBitmap, rgbaMat)
            Imgproc.cvtColor(rgbaMat, result, colorConversionCode)
            dst = null
            result
        } finally {
            dst?.release()
            ownedCopy?.recycle()
            rgba?.release()
        }
    }
}
