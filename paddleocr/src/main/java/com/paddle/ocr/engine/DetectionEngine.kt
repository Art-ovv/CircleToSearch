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

package com.paddle.ocr.engine

import android.graphics.Bitmap
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.OCRBox
import com.paddle.ocr.postprocess.DBPostProcessor
import com.paddle.ocr.preprocess.DetPreprocessResult
import com.paddle.ocr.preprocess.DetPreprocessor
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.opencv.core.Mat

class DetectionEngine(
    private val ortManager: ORTSessionManager,
    private val config: PaddleOCRConfig,
) {
    data class DetectionResult(
        val boxes: List<OCRBox>,
        val preprocessMs: Long,
        val inferenceMs: Long,
        val postprocessMs: Long,
        val timeMs: Long,
        val inputShape: List<Int>,
    )

    suspend fun detect(bitmap: Bitmap): DetectionResult {
        val job = currentCoroutineContext()[Job]
        val cancellationCheck: () -> Unit = { job?.ensureActive() }
        return detect {
            DetPreprocessor.preprocess(
                bitmap,
                config.detLimitSideLen,
                config.detLimitType,
                config.detMaxSideLimit,
                config.detMaxPixelCount,
                config.detImgMode,
                cancellationCheck,
            )
        }
    }

    suspend fun detect(src: Mat): DetectionResult {
        val job = currentCoroutineContext()[Job]
        val cancellationCheck: () -> Unit = { job?.ensureActive() }
        return detect {
            DetPreprocessor.preprocess(
                src,
                config.detLimitSideLen,
                config.detLimitType,
                config.detMaxSideLimit,
                config.detMaxPixelCount,
                config.detImgMode,
                cancellationCheck,
            )
        }
    }

    private suspend fun detect(preprocessor: () -> DetPreprocessResult): DetectionResult {
        val job = currentCoroutineContext()[Job]
        val cancellationCheck: () -> Unit = { job?.ensureActive() }
        cancellationCheck()
        val preStart = android.os.SystemClock.elapsedRealtime()
        val preResult = preprocessor()
        val preprocessMs = android.os.SystemClock.elapsedRealtime() - preStart

        cancellationCheck()
        val infStart = android.os.SystemClock.elapsedRealtime()
        val (outputData, outputShape) = ortManager.runDetection(preResult.tensorData, preResult.shape)
        val inferenceMs = android.os.SystemClock.elapsedRealtime() - infStart

        cancellationCheck()
        val postStart = android.os.SystemClock.elapsedRealtime()
        val boxes = DBPostProcessor.process(
            pred = outputData,
            predShape = outputShape,
            thresh = config.detThresh,
            boxThresh = config.detBoxThresh,
            unclipRatio = config.detUnclipRatio,
            maxCandidates = config.detMaxCandidates,
            useDilation = config.detUseDilation,
            scoreMode = config.detScoreMode,
            boxType = config.detBoxType,
            originalH = preResult.originalH,
            originalW = preResult.originalW,
            cancellationCheck = cancellationCheck,
        )
        val postprocessMs = android.os.SystemClock.elapsedRealtime() - postStart
        cancellationCheck()

        return DetectionResult(
            boxes = boxes,
            preprocessMs = preprocessMs,
            inferenceMs = inferenceMs,
            postprocessMs = postprocessMs,
            timeMs = preprocessMs + inferenceMs + postprocessMs,
            inputShape = listOf(1, 3, preResult.shape[2].toInt(), preResult.shape[3].toInt()),
        )
    }
}
