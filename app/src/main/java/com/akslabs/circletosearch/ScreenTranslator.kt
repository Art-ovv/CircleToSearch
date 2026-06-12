package com.akslabs.circletosearch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

private const val TAG = "ScreenTranslator"

data class TextBlockData(val text: String, val boundingBox: Rect)
data class TranslatedBlockData(val translatedText: String, val boundingBox: Rect)

class ScreenTranslator : Closeable {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    private val languageIdentifier = LanguageIdentification.getClient()

    private val translators = mutableMapOf<String, Translator>()

    suspend fun translateScreen(screenshot: Bitmap, targetLangCode: String? = null): Bitmap {
        return withContext(Dispatchers.Default) {
            if (screenshot.isRecycled) {
                throw IllegalStateException("Bitmap is already recycled.")
            }

            val resultBitmap = try {
                screenshot.copy(Bitmap.Config.ARGB_8888, true)
            } catch (e: OutOfMemoryError) {
                throw IllegalStateException("Not enough memory to process screenshot", e)
            }

            if (resultBitmap == null) {
                throw IllegalStateException("Failed to create bitmap copy")
            }

            val canvas = Canvas(resultBitmap)

            val backgroundPaint = Paint().apply { style = Paint.Style.FILL }
            val textPaint = TextPaint().apply {
                isAntiAlias = true
            }

            val textBlocks = recognizeTextWithBounds(screenshot)

            val translatedBlocks = translateBlocks(textBlocks, targetLangCode)

            for (block in translatedBlocks) {
                val dominantBgColor = getDominantEdgeColor(screenshot, block.boundingBox)
                backgroundPaint.color = dominantBgColor
                
                val bgRect = Rect(block.boundingBox).apply { inset(-2, -2) }
                canvas.drawRect(bgRect, backgroundPaint)

                textPaint.color = getContrastColor(dominantBgColor)
                drawMultilineTextToFit(canvas, block.translatedText, block.boundingBox, textPaint)
            }
            resultBitmap
        }
    }

    private suspend fun recognizeTextWithBounds(bitmap: Bitmap): List<TextBlockData> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val visionText = textRecognizer.process(image).await()
        
        val resultList = mutableListOf<TextBlockData>()
        for (block in visionText.textBlocks) {
            val boundingBox = block.boundingBox ?: continue
            val originalText = block.text
            
            if (originalText.isNotBlank()) {
                resultList.add(TextBlockData(originalText, boundingBox))
            }
        }
        return resultList
    }

    private fun getTranslator(sourceLang: String, targetLang: String): Translator {
        val key = "$sourceLang-$targetLang"
        return translators.getOrPut(key) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(targetLang)
                .build()
            Translation.getClient(options)
        }
    }

    /**
     * Translates recognized text blocks.
     * Edge cases: unidentified language, missing model, network errors.
     */
    private suspend fun translateBlocks(blocks: List<TextBlockData>, targetLangCode: String?): List<TranslatedBlockData> {
        val translatedList = mutableListOf<TranslatedBlockData>()
        val langToUse = targetLangCode ?: java.util.Locale.getDefault().language
        val targetLanguage = TranslateLanguage.fromLanguageTag(langToUse) ?: TranslateLanguage.ENGLISH

        for (block in blocks) {
            try {
                val langCode = languageIdentifier.identifyLanguage(block.text).await()

                // Edge case: language not identified ("und") — skip block
                if (langCode == "und" || langCode.isNullOrEmpty()) {
                    continue
                }

                val sourceLang = TranslateLanguage.fromLanguageTag(langCode)

                // Edge case: language not supported by ML Kit — skip
                if (sourceLang == null || sourceLang == targetLanguage) {
                    continue
                }

                val translator = getTranslator(sourceLang, targetLanguage)

                // Edge case: GrapheneOS / offline mode — model not downloaded
                try {
                    translator.downloadModelIfNeeded().await()
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Translation model unavailable: $sourceLang -> $targetLanguage. Skipping block.")
                    continue
                }

                val translatedText = translator.translate(block.text).await()

                // Edge case: empty translation or translation matches original
                if (translatedText.isNotBlank() && translatedText != block.text) {
                    translatedList.add(TranslatedBlockData(translatedText, block.boundingBox))
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Error translating block: '${block.text.take(50)}...'", e)
            }
        }
        return translatedList
    }

    /**
     * Color Sampler: iterates around BoundingBox perimeter to find dominant background color.
     * Edge cases: empty bounds, zero area, bounds outside bitmap.
     */
    private fun getDominantEdgeColor(bitmap: Bitmap, bounds: Rect): Int {
        val colorCounts = mutableMapOf<Int, Int>()

        val left = bounds.left.coerceIn(0, bitmap.width - 1)
        val right = bounds.right.coerceIn(0, bitmap.width - 1)
        val top = bounds.top.coerceIn(0, bitmap.height - 1)
        val bottom = bounds.bottom.coerceIn(0, bitmap.height - 1)

        // Edge case: empty or zero rect
        if (right <= left || bottom <= top) {
            return Color.WHITE
        }

        // Edge case: very narrow/short boundary — pick single point
        if (right == left && bottom == top) {
            return bitmap.getPixel(left, top)
        }

        val xStep = maxOf(2, (right - left) / 50)
        for (x in left..right step xStep) {
            val colorTop = bitmap.getPixel(x, top)
            val colorBottom = bitmap.getPixel(x, bottom)
            colorCounts[colorTop] = (colorCounts[colorTop] ?: 0) + 1
            colorCounts[colorBottom] = (colorCounts[colorBottom] ?: 0) + 1
        }

        val yStep = maxOf(2, (bottom - top) / 50)
        for (y in top..bottom step yStep) {
            val colorLeft = bitmap.getPixel(left, y)
            val colorRight = bitmap.getPixel(right, y)
            colorCounts[colorLeft] = (colorCounts[colorLeft] ?: 0) + 1
            colorCounts[colorRight] = (colorCounts[colorRight] ?: 0) + 1
        }

        return colorCounts.maxByOrNull { it.value }?.key ?: Color.WHITE
    }

    private fun drawMultilineTextToFit(canvas: Canvas, text: String, rect: Rect, paint: TextPaint) {
        var minSize = 10f
        var maxSize = 120f
        var bestSize = minSize
        var bestLayout: StaticLayout? = null

        val textWidth = rect.width().coerceAtLeast(1)

        while (minSize <= maxSize) {
            val midSize = (minSize + maxSize) / 2
            paint.textSize = midSize
            
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .build()

            if (layout.height <= rect.height()) {
                bestSize = midSize
                bestLayout = layout
                minSize = midSize + 1f
            } else {
                maxSize = midSize - 1f
            }
        }

        if (bestLayout != null) {
            canvas.save()
            val textY = rect.centerY() - (bestLayout.height / 2f)
            canvas.translate(rect.left.toFloat(), textY)
            bestLayout.draw(canvas)
            canvas.restore()
        }
    }

    /**
     * WCAG 2.1 relative luminance + contrast ratio.
     * Returns BLACK or WHITE for guaranteed readable text.
     */
    private fun getContrastColor(backgroundColor: Int): Int {
        // WCAG relative luminance
        fun channelLuminance(c: Int): Double {
            val sRGB = c / 255.0
            return if (sRGB <= 0.03928) sRGB / 12.92 else Math.pow((sRGB + 0.055) / 1.055, 2.4)
        }
        val r = Color.red(backgroundColor)
        val g = Color.green(backgroundColor)
        val b = Color.blue(backgroundColor)
        val luminance = 0.2126 * channelLuminance(r) +
                       0.7152 * channelLuminance(g) +
                       0.0722 * channelLuminance(b)
        // Contrast ratio against WHITE (1.0) vs BLACK (0.0)
        val ratioWhite = (luminance + 0.05) / 0.05
        val ratioBlack = 0.05 / (luminance + 0.05)
        return if (ratioWhite > ratioBlack) Color.BLACK else Color.WHITE
    }
    
    override fun close() {
        textRecognizer.close()
        languageIdentifier.close()
        translators.values.forEach { it.close() }
        translators.clear()
    }
}