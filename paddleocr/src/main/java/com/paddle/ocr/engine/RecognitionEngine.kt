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

import com.paddle.ocr.postprocess.CTCDecoder
import com.paddle.ocr.preprocess.RecPreprocessor
import com.paddle.ocr.postprocess.CTCDecodedText
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.opencv.core.Mat

class RecognitionEngine(
    private val ortManager: ORTSessionManager,
    private val characterList: List<String>,
) {
    data class RecognitionResult(
        val texts: List<CTCDecodedText>,
        val preprocessMs: Long,
        val inferenceMs: Long,
        val postprocessMs: Long,
        val timeMs: Long,
        val inputShape: List<Int>,
    )

    suspend fun recognize(crops: List<Mat>): RecognitionResult {
        val job = currentCoroutineContext()[Job]
        val cancellationCheck: () -> Unit = { job?.ensureActive() }
        // Preprocess
        cancellationCheck()
        val preStart = android.os.SystemClock.elapsedRealtime()
        val preResult = RecPreprocessor.preprocessBatch(crops, cancellationCheck)
        val preprocessMs = android.os.SystemClock.elapsedRealtime() - preStart

        // Inference
        cancellationCheck()
        val infStart = android.os.SystemClock.elapsedRealtime()
        val (outputData, outputShape) = ortManager.runRecognition(preResult.tensorData, preResult.shape)
        val inferenceMs = android.os.SystemClock.elapsedRealtime() - infStart

        // Postprocess (CTC decode)
        cancellationCheck()
        val postStart = android.os.SystemClock.elapsedRealtime()
        val decoded = CTCDecoder.decode(
            output = outputData,
            shape = outputShape,
            characterList = characterList,
            validRatios = preResult.validRatios,
            cancellationCheck = cancellationCheck,
        )
        check(decoded.size == crops.size) {
            "Recognition output batch does not match the number of input crops"
        }
        val postprocessMs = android.os.SystemClock.elapsedRealtime() - postStart
        cancellationCheck()

        val inputShape = preResult.shape.map { it.toInt() }
        val timeMs = preprocessMs + inferenceMs + postprocessMs
        return RecognitionResult(
            texts = decoded,
            preprocessMs = preprocessMs,
            inferenceMs = inferenceMs,
            postprocessMs = postprocessMs,
            timeMs = timeMs,
            inputShape = inputShape,
        )
    }
}
