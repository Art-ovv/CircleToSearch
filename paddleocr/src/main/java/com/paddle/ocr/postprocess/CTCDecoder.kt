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

import com.paddle.ocr.model.OCRTextSpan
import kotlin.math.ceil

data class CTCDecodedText(
    val text: String,
    val confidence: Float,
    val spans: List<OCRTextSpan>,
)

object CTCDecoder {
    private const val BLANK_IDX = 0

    fun decode(
        output: FloatArray,
        shape: LongArray,
        characterList: List<String>,
        validRatios: FloatArray,
        cancellationCheck: () -> Unit = {},
    ): List<CTCDecodedText> {
        require(shape.size == 3) { "CTC output must have shape [batch, time, classes]" }
        val batchSize = shape[0].toInt()
        val timeSteps = shape[1].toInt()
        val numClasses = shape[2].toInt()
        require(batchSize > 0 && timeSteps > 0 && numClasses > 0) {
            "CTC output dimensions must be positive"
        }
        require(validRatios.size == batchSize) { "Missing valid-width ratio for CTC batch" }
        val requiredOutputSize = batchSize.toLong() * timeSteps * numClasses
        require(requiredOutputSize <= output.size) { "CTC output buffer is smaller than its shape" }

        val results = mutableListOf<CTCDecodedText>()
        for (b in 0 until batchSize) {
            cancellationCheck()
            val baseOffset = b * timeSteps * numClasses
            val ratio = validRatios[b]
                .takeIf { it.isFinite() && it > 0f }
                ?.coerceAtMost(1f)
                ?: 1f
            val effectiveSteps = (timeSteps * ratio).coerceAtLeast(1f)
            val validSteps = ceil(effectiveSteps.toDouble()).toInt().coerceIn(1, timeSteps)

            val indices = IntArray(validSteps)
            val probs = FloatArray(validSteps)
            for (t in 0 until validSteps) {
                if (t % 32 == 0) cancellationCheck()
                val offset = baseOffset + t * numClasses
                var maxIdx = 0
                var maxVal = output[offset]
                for (c in 1 until numClasses) {
                    val v = output[offset + c]
                    if (v > maxVal) {
                        maxVal = v
                        maxIdx = c
                    }
                }
                indices[t] = maxIdx
                probs[t] = maxVal
            }

            val keptProbs = mutableListOf<Float>()
            val spans = mutableListOf<OCRTextSpan>()
            val sb = StringBuilder()
            var timestep = 0
            while (timestep < validSteps) {
                val idx = indices[timestep]
                val runStart = timestep
                do {
                    timestep++
                } while (timestep < validSteps && indices[timestep] == idx)

                if (idx != BLANK_IDX) {
                    val charIdx = idx - 1
                    if (charIdx >= 0 && charIdx < characterList.size) {
                        val textStart = sb.length
                        sb.append(characterList[charIdx])
                        val textEnd = sb.length
                        spans += OCRTextSpan(
                            startIndex = textStart,
                            endIndex = textEnd,
                            startFraction = (runStart / effectiveSteps).coerceIn(0f, 1f),
                            endFraction = (timestep / effectiveSteps).coerceIn(0f, 1f),
                        )
                        keptProbs.add(probs[runStart])
                    }
                }
            }

            val confidence = if (keptProbs.isNotEmpty()) keptProbs.average().toFloat() else 0f
            results += CTCDecodedText(
                text = sb.toString(),
                confidence = confidence,
                spans = spans,
            )
        }
        return results
    }
}
