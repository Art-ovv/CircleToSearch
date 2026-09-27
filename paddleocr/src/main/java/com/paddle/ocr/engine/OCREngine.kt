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

import android.content.Context
import android.graphics.Bitmap
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.ModelConfig
import com.paddle.ocr.model.OCRError
import com.paddle.ocr.model.OCRResult
import com.paddle.ocr.postprocess.BoxSorter
import com.paddle.ocr.postprocess.QuadTextCrop
import com.paddle.ocr.util.BitmapUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.math.hypot
import kotlin.math.max

class OCREngine(
    context: Context,
    private val config: PaddleOCRConfig,
    engineConfig: EngineConfig,
    detModelAsset: String = "models/det/inference.onnx",
    recModelAsset: String? = "models/rec/inference.onnx",
    recConfigAsset: String? = "models/rec/inference.yml",
    recModelFile: File? = null,
    recConfigFile: File? = null,
) {
    private val ortManager = ORTSessionManager(context, engineConfig)
    private val detectionEngine: DetectionEngine
    private val recognitionEngine: RecognitionEngine
    val coldLoadTimeMs: Long get() = ortManager.coldLoadTimeMs

    init {
        val configured = try {
            ortManager.loadModels(
                detAssetPath = detModelAsset,
                recAssetPath = recModelAsset,
                recModelFile = recModelFile,
            )
            val recModelConfig = when {
                recConfigFile != null -> ModelConfig.parse(recConfigFile)
                recConfigAsset != null -> ModelConfig.parse(context, recConfigAsset)
                else -> throw OCRError.ConfigParseFailed("No recognition config specified")
            }
            recModelConfig
        } catch (t: Throwable) {
            ortManager.release()
            throw t
        }
        detectionEngine = DetectionEngine(ortManager, config)
        recognitionEngine = RecognitionEngine(ortManager, configured.characterList)
    }

    suspend fun run(bitmap: Bitmap): OCREngineResult {
        val srcMat = BitmapUtils.bitmapToBGRMat(bitmap)
        return runWithOwnedMat(srcMat)
    }

    suspend fun run(imageBytes: ByteArray): OCREngineResult {
        val srcMat = BitmapUtils.imdecodeBGR(imageBytes)
        if (srcMat.empty()) {
            srcMat.release()
            throw OCRError.InvalidImage()
        }
        return runWithOwnedMat(srcMat)
    }

    private suspend fun runWithOwnedMat(srcMat: org.opencv.core.Mat): OCREngineResult {
        return try {
            run(srcMat)
        } finally {
            srcMat.release()
        }
    }

    private suspend fun run(srcMat: org.opencv.core.Mat): OCREngineResult {
        val job = currentCoroutineContext()[Job]
        val cancellationCheck: () -> Unit = { job?.ensureActive() }
        cancellationCheck()
        val totalStart = android.os.SystemClock.elapsedRealtime()
        val detResult = detectionEngine.detect(srcMat)
        cancellationCheck()
        val boxes = detResult.boxes

        if (boxes.isEmpty()) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - totalStart
            return OCREngineResult(
                results = emptyList(),
                detectionTimeMs = detResult.timeMs,
                recognitionTimeMs = 0,
                totalTimeMs = elapsed,
                lineCount = 0,
                detPreprocessMs = detResult.preprocessMs,
                detInferenceMs = detResult.inferenceMs,
                detPostprocessMs = detResult.postprocessMs,
                detInputShape = detResult.inputShape,
                coldLoadTimeMs = ortManager.coldLoadTimeMs,
            )
        }

        // 2. Sort boxes
        val sortedBoxes = BoxSorter.sortInReadingOrder(boxes)
        // Recognition batches are padded to the widest crop. Grouping similarly-shaped boxes
        // reduces padding and inference work, while resultsByReadingIndex restores visual order.
        val recognitionOrder = sortedBoxes.indices.sortedBy { index ->
            recognitionAspectRatio(sortedBoxes[index])
        }

        // 3. Crop and recognize text regions
        var totalRecPreMs = 0L
        var totalRecInfMs = 0L
        var totalRecPostMs = 0L
        var totalRecMs = 0L
        val resultsByReadingIndex = arrayOfNulls<OCRResult>(sortedBoxes.size)
        val recInputShapes = mutableListOf<List<Int>>()
        val perLineRecMs = mutableListOf<Long>()
        val batchSize = config.recBatchSize.coerceAtLeast(1)

        var i = 0
        while (i < recognitionOrder.size) {
            cancellationCheck()
            i = withRecognitionCropBatch(
                order = recognitionOrder,
                start = i,
                batchSize = batchSize,
                cancellationCheck = cancellationCheck,
                createCrop = { index -> QuadTextCrop.crop(srcMat, sortedBoxes[index]) },
                isUsable = { crop -> crop.image.rows() > 0 && crop.image.cols() > 0 },
                release = { crop -> crop.image.release() },
            ) { batchCrops, batchBoxIndices ->
                if (batchCrops.isNotEmpty()) {
                    val batchResult = recognitionEngine.recognize(batchCrops.map { it.image })
                    totalRecPreMs += batchResult.preprocessMs
                    totalRecInfMs += batchResult.inferenceMs
                    totalRecPostMs += batchResult.postprocessMs
                    totalRecMs += batchResult.timeMs
                    recInputShapes.add(batchResult.inputShape)
                    if (batchSize == 1) {
                        perLineRecMs.add(batchResult.timeMs)
                    }

                    for (j in batchResult.texts.indices) {
                        val boxIdx = batchBoxIndices[j]
                        val decoded = batchResult.texts[j]
                        if (decoded.confidence >= config.recScoreThresh) {
                            resultsByReadingIndex[boxIdx] = OCRResult(
                                box = sortedBoxes[boxIdx],
                                text = decoded.text,
                                confidence = decoded.confidence,
                                textSpans = decoded.spans,
                                recognitionBox = batchCrops[j].recognitionBox,
                            )
                        }
                    }
                }
            }
        }

        cancellationCheck()
        val allResults = resultsByReadingIndex.filterNotNull()
        val totalElapsed = android.os.SystemClock.elapsedRealtime() - totalStart
        val pipelineOverhead = (totalElapsed - detResult.timeMs - totalRecMs).coerceAtLeast(0)

        return OCREngineResult(
            results = allResults,
            detectionTimeMs = detResult.timeMs,
            recognitionTimeMs = totalRecMs,
            totalTimeMs = totalElapsed,
            lineCount = allResults.size,
            detPreprocessMs = detResult.preprocessMs,
            detInferenceMs = detResult.inferenceMs,
            detPostprocessMs = detResult.postprocessMs,
            recPreprocessMs = totalRecPreMs,
            recInferenceMs = totalRecInfMs,
            recPostprocessMs = totalRecPostMs,
            pipelineOverheadMs = pipelineOverhead,
            coldLoadTimeMs = ortManager.coldLoadTimeMs,
            detInputShape = detResult.inputShape,
            recInputShapes = recInputShapes,
            perLineRecMs = perLineRecMs,
        )
    }

    fun release() {
        ortManager.release()
    }

    private fun recognitionAspectRatio(box: com.paddle.ocr.model.OCRBox): Double {
        val points = box.points
        if (points.size < 4) return Double.MAX_VALUE
        val width = max(
            hypot(
                (points[1].x - points[0].x).toDouble(),
                (points[1].y - points[0].y).toDouble(),
            ),
            hypot(
                (points[2].x - points[3].x).toDouble(),
                (points[2].y - points[3].y).toDouble(),
            ),
        ).coerceAtLeast(1.0)
        val height = max(
            hypot(
                (points[3].x - points[0].x).toDouble(),
                (points[3].y - points[0].y).toDouble(),
            ),
            hypot(
                (points[2].x - points[1].x).toDouble(),
                (points[2].y - points[1].y).toDouble(),
            ),
        ).coerceAtLeast(1.0)
        return if (height / width >= 1.5) height / width else width / height
    }
}
