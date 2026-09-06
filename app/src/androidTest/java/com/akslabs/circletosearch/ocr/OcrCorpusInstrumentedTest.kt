package com.akslabs.circletosearch.ocr

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.text.Normalizer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrCorpusInstrumentedTest {
    @Test
    fun editDistanceCountsMissingExtraAndReplacedTokens() {
        assertEquals(1, distance(listOf("a", "b"), listOf("a")))
        assertEquals(1, distance(listOf("a"), listOf("a", "b")))
        assertEquals(1, distance(listOf("a"), listOf("b")))
        assertEquals(0, distance(emptyList<String>(), emptyList()))
    }

    @Test
    fun measureBundledModelAccuracy() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val rows = JSONArray()
        val report = JSONObject()
            .put("corpusVersion", OcrCorpus.VERSION)
            .put("device", Build.MODEL)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            .put("samples", rows)
        val output = File(context.getExternalFilesDir(null) ?: context.filesDir, "ocr-corpus-v1.json")
        val models = JSONObject()
        for (asset in listOf("paddleocr/det/inference.onnx", "paddleocr/rec/inference.onnx",
            "paddleocr/rec/inference.yml")) {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            context.assets.open(asset).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            models.put(asset, digest.digest().joinToString("") { "%02x".format(it) })
        }
        report.put("modelSha256", models)
        var characterErrors = 0
        var characterCount = 0
        var wordErrors = 0
        var wordCount = 0
        var failures = 0
        try {
            for (sample in OcrCorpus.samples) {
                val bitmap = sample.render()
                val row = JSONObject().put("id", sample.id).put("expected", sample.expected)
                rows.put(row)
                try {
                    val startedAt = SystemClock.elapsedRealtime()
                    val result = withTimeout(30_000) {
                        PaddleOcrEngine.extractText(context, bitmap, includeQrCodes = false)
                    }
                    row.put("elapsedMs", SystemClock.elapsedRealtime() - startedAt)
                    val actual = normalize(result.textNodes.joinToString(" ") { it.fullText })
                    val expected = normalize(sample.expected)
                    val expectedChars = expected.codePoints().toArray().toList()
                    val expectedWords = expected.split(' ')
                    val charErrors = distance(expectedChars, actual.codePoints().toArray().toList())
                    val tokenErrors = distance(expectedWords,
                        if (actual.isEmpty()) emptyList() else actual.split(' '))
                    characterErrors += charErrors
                    characterCount += expectedChars.size
                    wordErrors += tokenErrors
                    wordCount += expectedWords.size
                    row.put("actual", actual)
                        .put("cer", charErrors.toDouble() / expectedChars.size)
                        .put("wer", tokenErrors.toDouble() / expectedWords.size)
                        .put("exact", actual == expected)
                } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                    failures++
                    row.put("error", "timeout")
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failures++
                    row.put("error", error.javaClass.simpleName)
                } finally {
                    bitmap.recycle()
                }
            }
            report.put("failedSamples", failures)
            if (characterCount > 0) {
                report.put("cer", characterErrors.toDouble() / characterCount)
                report.put("wer", wordErrors.toDouble() / wordCount)
            }
        } finally {
            // Only synthetic fixture contents are exported. Partial reports survive failed runs.
            output.writeText(report.toString(2))
        }
        assertEquals("OCR execution failed; see ${output.name}", 0, failures)
        // No invented quality baseline: opt into a threshold after measuring a reference device.
        args.getString("ocrMaxCer")?.let { threshold ->
            val maximum = threshold.toDouble()
            require(maximum.isFinite() && maximum >= 0)
            assertTrue("CER exceeded $maximum; see ${output.name}",
                characterErrors.toDouble() / characterCount <= maximum)
        }
    }

    private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
        .trim().replace(Regex("\\s+"), " ")

    private fun <T> distance(expected: List<T>, actual: List<T>): Int {
        var previous = IntArray(actual.size + 1) { it }
        for (i in expected.indices) {
            val current = IntArray(actual.size + 1)
            current[0] = i + 1
            for (j in actual.indices) {
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1,
                    previous[j] + if (expected[i] == actual[j]) 0 else 1)
            }
            previous = current
        }
        return previous.last()
    }
}
