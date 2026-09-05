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

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.model.OCRError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.FloatBuffer
import java.util.concurrent.locks.ReentrantLock

class ORTSessionManager(
    private val context: Context,
    private val config: EngineConfig,
) {
    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var detInputName: String = "x"
    private var recInputName: String = "x"
    var coldLoadTimeMs: Long = 0
        private set

    fun loadModels(detAssetPath: String, recAssetPath: String) {
        val loadStart = android.os.SystemClock.elapsedRealtime()
        env = try {
            OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }
        } catch (error: Exception) {
            throw OCRError.ModelLoadFailed("OCR environment", error)
        }
        val opts = OrtSession.SessionOptions()
        try {
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            opts.setIntraOpNumThreads(config.numThreads)
            val ortEnv = env ?: throw OCRError.ModelLoadFailed("OCR", Exception("Environment not initialized"))
            try {
                detSession = createSessionFromAsset(ortEnv, opts, detAssetPath)
            } catch (error: Exception) {
                throw OCRError.ModelLoadFailed("detection", error)
            }
            try {
                recSession = createSessionFromAsset(ortEnv, opts, recAssetPath)
            } catch (error: Exception) {
                detSession?.close()
                detSession = null
                throw OCRError.ModelLoadFailed("recognition", error)
            }

            detInputName = try {
                detSession!!.inputNames.iterator().next()
            } catch (error: Exception) {
                throw OCRError.ModelLoadFailed("detection", error)
            }
            recInputName = try {
                recSession!!.inputNames.iterator().next()
            } catch (error: Exception) {
                throw OCRError.ModelLoadFailed("recognition", error)
            }
            coldLoadTimeMs = android.os.SystemClock.elapsedRealtime() - loadStart
        } finally {
            opts.close()
        }
    }

    suspend fun runDetection(input: FloatArray, shape: LongArray): Pair<FloatArray, LongArray> {
        val session = detSession
            ?: throw OCRError.ModelLoadFailed("detection", Exception("Session not initialized"))
        val ortEnv = env
            ?: throw OCRError.ModelLoadFailed("detection", Exception("Environment not initialized"))
        return runSession(ortEnv, session, detInputName, input, shape, "detection")
    }

    suspend fun runRecognition(input: FloatArray, shape: LongArray): Pair<FloatArray, LongArray> {
        val session = recSession
            ?: throw OCRError.ModelLoadFailed("recognition", Exception("Session not initialized"))
        val ortEnv = env
            ?: throw OCRError.ModelLoadFailed("recognition", Exception("Environment not initialized"))
        return runSession(ortEnv, session, recInputName, input, shape, "recognition")
    }

    fun release() {
        try {
            detSession?.close()
        } finally {
            detSession = null
            try {
                recSession?.close()
            } finally {
                recSession = null
                env = null
            }
        }
    }

    private fun readModelAsset(assetPath: String): ByteArray {
        return try {
            context.assets.open(assetPath).use { it.readBytes() }
        } catch (error: Exception) {
            throw OCRError.ModelNotFound(assetPath, error)
        }
    }

    private fun createSessionFromAsset(
        ortEnv: OrtEnvironment,
        options: OrtSession.SessionOptions,
        assetPath: String,
    ): OrtSession {
        val modelBytes = readModelAsset(assetPath)
        return ortEnv.createSession(modelBytes, options)
    }

    private suspend fun runSession(
        ortEnv: OrtEnvironment,
        session: OrtSession,
        inputName: String,
        input: FloatArray,
        shape: LongArray,
        modelName: String,
    ): Pair<FloatArray, LongArray> {
        currentCoroutineContext().ensureActive()
        val tensor = try {
            OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(input), shape)
        } catch (error: Exception) {
            throw OCRError.InferenceFailed(modelName, error)
        }
        val runOptions = try {
            OrtSession.RunOptions()
        } catch (failure: Throwable) {
            tensor.close()
            if (failure is Error) throw failure
            throw OCRError.InferenceFailed(modelName, failure)
        }
        val optionsLock = ReentrantLock()
        var optionsOpen = true

        return try {
            try {
                runPromptCancellable(
                    terminate = {
                        // Native inference can outlive a coroutine timeout. Terminate only this
                        // run; the OrtSession remains reusable by the next OCR request.
                        if (optionsLock.tryLock()) {
                            try {
                                if (optionsOpen) {
                                    runOptions.setTerminate(true)
                                }
                            } finally {
                                optionsLock.unlock()
                            }
                        }
                    },
                    run = { isActive ->
                        session.run(
                            mapOf(inputName to tensor),
                            runOptions,
                        ).use { result ->
                            if (!isActive()) return@use null
                            val outputName = session.outputNames.iterator().next()
                            val outputTensor = result.get(outputName)
                                .orElseThrow { IllegalStateException("No output tensor found") }
                                as? OnnxTensor
                                ?: throw IllegalStateException("Output is not an ONNX tensor")
                            Pair(
                                copyFloatBuffer(outputTensor.floatBuffer) {
                                    isActive()
                                },
                                outputTensor.info.shape.clone(),
                            )
                        }
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw OCRError.InferenceFailed(modelName, error)
            }
        } finally {
            tensor.close()
            optionsLock.lock()
            try {
                if (optionsOpen) {
                    optionsOpen = false
                    runOptions.close()
                }
            } finally {
                optionsLock.unlock()
            }
        }
    }

    private fun copyFloatBuffer(
        buffer: FloatBuffer,
        isActive: () -> Boolean,
    ): FloatArray {
        val duplicate = buffer.duplicate()
        duplicate.rewind()
        val output = FloatArray(duplicate.remaining())
        var offset = 0
        while (offset < output.size) {
            if (!isActive()) throw CancellationException("ONNX output copy was cancelled")
            val count = minOf(64 * 1024, output.size - offset)
            duplicate.get(output, offset, count)
            offset += count
        }
        return output
    }
}
