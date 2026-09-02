package com.akslabs.circletosearch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ScreenTranslator"

data class TextBlockData(val text: String, val boundingBox: Rect)
data class TranslatedBlockData(val translatedText: String, val boundingBox: Rect)

enum class ScreenTranslationUnchangedReason {
    NO_RECOGNIZED_TEXT,
    NO_TRANSLATABLE_TEXT,
}

/**
 * Result of translating a screen using already recognized text nodes.
 *
 * [Translated.bitmap] is a new mutable bitmap owned by the caller. [Unchanged] deliberately does
 * not contain a bitmap, so an unchanged full-screen frame does not need to be copied.
 */
sealed interface ScreenTranslationOutcome {
    data class Unchanged(
        val reason: ScreenTranslationUnchangedReason,
    ) : ScreenTranslationOutcome

    data class Translated(
        val bitmap: Bitmap,
        val translatedBlockCount: Int,
    ) : ScreenTranslationOutcome
}

/** Immutable, allocation-light OCR handoff prepared when overlay nodes change. */
internal data class ScreenTranslationNode(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

class ScreenTranslator : Closeable {

    private data class TextBlockKey(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val languageIdentifier = LanguageIdentification.getClient()

    private val translators = mutableMapOf<TranslationLanguagePair, Translator>()
    private val translatorLock = Any()
    private val closed = AtomicBoolean(false)
    private val translationPairSemaphore = Semaphore(permits = 2)

    /**
     * Compatibility entry point for capture flows that do not yet own recognized text nodes.
     *
     * It preserves the previous contract of always returning a distinct bitmap. New overlay code
     * should call the overload accepting [textNodes], which avoids both duplicate OCR and an
     * unnecessary bitmap copy when nothing changes.
     */
    suspend fun translateScreen(screenshot: Bitmap, targetLangCode: String? = null): Bitmap {
        return when (
            val outcome = translateScreen(
                screenshot = screenshot,
                textNodes = emptyList<ScreenTranslationNode>(),
                targetLangCode = targetLangCode,
            )
        ) {
            is ScreenTranslationOutcome.Translated -> outcome.bitmap
            is ScreenTranslationOutcome.Unchanged -> copyForCompatibility(screenshot)
        }
    }

    /**
     * Translates an existing OCR result, falling back to ML Kit text recognition only when the
     * caller has no nodes at all. Passing non-empty nodes is therefore an explicit assertion that
     * recognition has completed, even when every node is later rejected as invalid. The caller
     * must retain [screenshot] without mutating or recycling it until this function returns.
     */
    internal suspend fun translateScreen(
        screenshot: Bitmap,
        textNodes: List<ScreenTranslationNode>,
        targetLangCode: String? = null,
    ): ScreenTranslationOutcome {
        var undeliveredBitmap: Bitmap? = null
        return try {
            val outcome = withContext(Dispatchers.Default) {
                checkOpen()
                check(!screenshot.isRecycled) { "Bitmap is already recycled." }

                val textBlocks = if (textNodes.isEmpty()) {
                    recognizeTextWithBounds(screenshot)
                } else {
                    textBlocksFromNodes(
                        nodes = textNodes,
                        bitmapWidth = screenshot.width,
                        bitmapHeight = screenshot.height,
                    )
                }
                if (textBlocks.isEmpty()) {
                    return@withContext ScreenTranslationOutcome.Unchanged(
                        ScreenTranslationUnchangedReason.NO_RECOGNIZED_TEXT,
                    )
                }

                val translatedBlocks = translateBlocks(textBlocks, targetLangCode)
                if (translatedBlocks.isEmpty()) {
                    return@withContext ScreenTranslationOutcome.Unchanged(
                        ScreenTranslationUnchangedReason.NO_TRANSLATABLE_TEXT,
                    )
                }

                ScreenTranslationOutcome.Translated(
                    bitmap = renderTranslations(
                        screenshot = screenshot,
                        translatedBlocks = translatedBlocks,
                    ).also { undeliveredBitmap = it },
                    translatedBlockCount = translatedBlocks.size,
                )
            }
            undeliveredBitmap = null
            outcome
        } finally {
            // withContext has prompt cancellation: recycle a rendered bitmap if cancellation wins
            // while the result is being dispatched back to the caller.
            undeliveredBitmap?.takeUnless { it.isRecycled }?.recycle()
        }
    }

    private suspend fun copyForCompatibility(screenshot: Bitmap): Bitmap {
        var undeliveredBitmap: Bitmap? = null
        return try {
            val result = withContext(Dispatchers.Default) {
                currentCoroutineContext().ensureActive()
                copyMutableBitmap(screenshot).also { undeliveredBitmap = it }
            }
            undeliveredBitmap = null
            result
        } finally {
            undeliveredBitmap?.takeUnless { it.isRecycled }?.recycle()
        }
    }

    private fun textBlocksFromNodes(
        nodes: List<ScreenTranslationNode>,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): List<TextBlockData> {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return emptyList()

        val bitmapBounds = Rect(0, 0, bitmapWidth, bitmapHeight)
        return nodes.mapNotNull { node ->
            val text = node.text.trim()
            if (text.isEmpty()) return@mapNotNull null

            val clampedBounds = Rect(node.left, node.top, node.right, node.bottom)
            if (!clampedBounds.intersect(bitmapBounds) || clampedBounds.isEmpty) {
                return@mapNotNull null
            }
            TextBlockData(text = text, boundingBox = clampedBounds)
        }.distinctBy { block ->
            TextBlockKey(
                text = block.text,
                left = block.boundingBox.left,
                top = block.boundingBox.top,
                right = block.boundingBox.right,
                bottom = block.boundingBox.bottom,
            )
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

    private fun getTranslator(languagePair: TranslationLanguagePair): Translator {
        return synchronized(translatorLock) {
            checkOpen()
            translators.getOrPut(languagePair) {
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(languagePair.sourceLanguage)
                    .setTargetLanguage(languagePair.targetLanguage)
                    .build()
                Translation.getClient(options)
            }
        }
    }

    /**
     * Translates recognized text blocks.
     * Edge cases: unidentified language, missing model, network errors.
     */
    private suspend fun translateBlocks(
        blocks: List<TextBlockData>,
        targetLangCode: String?,
    ): List<TranslatedBlockData> {
        currentCoroutineContext().ensureActive()

        val requestedTarget = targetLangCode ?: Locale.getDefault().language
        val targetLanguage = TranslateLanguage.fromLanguageTag(requestedTarget)
            ?: TranslateLanguage.ENGLISH
        val aggregateLanguage = identifyAggregateLanguage(blocks)
        val detectedLanguages = identifyBlockLanguages(blocks)
        val groups = buildTranslationLanguageGroups(
            blockTexts = blocks.map(TextBlockData::text),
            detectedLanguageTags = detectedLanguages,
            aggregateLanguage = aggregateLanguage,
            targetLanguageTag = targetLanguage,
        )
        val supportedGroups = linkedMapOf<TranslationLanguagePair, MutableList<Int>>()
        groups.forEach { (unvalidatedPair, blockIndexes) ->
            val sourceLanguage = TranslateLanguage.fromLanguageTag(
                unvalidatedPair.sourceLanguage,
            ) ?: return@forEach
            if (sourceLanguage == targetLanguage) return@forEach

            val supportedPair = TranslationLanguagePair(
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
            )
            supportedGroups.getOrPut(supportedPair) { mutableListOf() }
                .addAll(blockIndexes)
        }

        return coroutineScope {
            supportedGroups.map { (supportedPair, blockIndexes) ->
                async {
                    translationPairSemaphore.withPermit {
                        translateLanguageGroup(
                            pair = supportedPair,
                            blockIndexes = blockIndexes,
                            blocks = blocks,
                        )
                    }
                }
            }.awaitAll()
                .flatten()
                .sortedBy(IndexedTranslatedBlock::index)
                .map(IndexedTranslatedBlock::block)
        }
    }

    private suspend fun identifyAggregateLanguage(
        blocks: List<TextBlockData>,
    ): AggregateLanguage? {
        val sample = buildAggregateLanguageSample(blocks.map(TextBlockData::text))
        if (sample.isEmpty()) return null

        return try {
            languageIdentifier.identifyPossibleLanguages(sample).await()
                .asSequence()
                .mapNotNull { language ->
                    normalizeTranslationLanguageTag(language.languageTag)?.let { languageTag ->
                        AggregateLanguage(
                            languageTag = languageTag,
                            confidence = language.confidence,
                        )
                    }
                }
                .maxByOrNull(AggregateLanguage::confidence)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Log.w(TAG, "Aggregate language identification failed")
            null
        }
    }

    private suspend fun identifyBlockLanguages(
        blocks: List<TextBlockData>,
    ): List<String?> {
        var failureCount = 0
        val languages = blocks.map { block ->
            try {
                languageIdentifier.identifyLanguage(
                    buildBlockLanguageSample(block.text),
                ).await()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failureCount += 1
                null
            }
        }
        if (failureCount > 0) {
            Log.w(TAG, "Language identification failed for $failureCount text block(s)")
        }
        return languages
    }

    private data class IndexedTranslatedBlock(
        val index: Int,
        val block: TranslatedBlockData,
    )

    private suspend fun translateLanguageGroup(
        pair: TranslationLanguagePair,
        blockIndexes: List<Int>,
        blocks: List<TextBlockData>,
    ): List<IndexedTranslatedBlock> {
        val coroutineContext = currentCoroutineContext()
        val translator = getTranslator(pair)
        try {
            // Exactly one model check/download is issued for this source/target group.
            translator.downloadModelIfNeeded().await()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Log.w(
                TAG,
                "Translation model unavailable: ${pair.sourceLanguage} -> ${pair.targetLanguage}",
            )
            return emptyList()
        }

        var failureCount = 0
        val translated = buildList {
            for (index in blockIndexes) {
                coroutineContext.ensureActive()
                val block = blocks[index]
                val translatedText = try {
                    translator.translate(block.text).await()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    failureCount += 1
                    continue
                }

                if (translatedText.isNotBlank() && translatedText != block.text) {
                    add(
                        IndexedTranslatedBlock(
                            index = index,
                            block = TranslatedBlockData(
                                translatedText = translatedText,
                                boundingBox = block.boundingBox,
                            ),
                        ),
                    )
                }
            }
        }
        if (failureCount > 0) {
            Log.w(
                TAG,
                "Translation failed for $failureCount block(s) in " +
                    "${pair.sourceLanguage} -> ${pair.targetLanguage}",
            )
        }
        return translated
    }

    private suspend fun renderTranslations(
        screenshot: Bitmap,
        translatedBlocks: List<TranslatedBlockData>,
    ): Bitmap {
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        val resultBitmap = copyMutableBitmap(screenshot)
        try {
            val canvas = Canvas(resultBitmap)
            val backgroundPaint = Paint().apply { style = Paint.Style.FILL }
            val textPaint = TextPaint().apply { isAntiAlias = true }

            for (block in translatedBlocks) {
                coroutineContext.ensureActive()
                val dominantBgColor = getDominantEdgeColor(screenshot, block.boundingBox)
                backgroundPaint.color = dominantBgColor

                val bgRect = Rect(block.boundingBox).apply { inset(-2, -2) }
                canvas.drawRect(bgRect, backgroundPaint)

                textPaint.color = getContrastColor(dominantBgColor)
                drawMultilineTextToFit(canvas, block.translatedText, block.boundingBox, textPaint)
            }
            return resultBitmap
        } catch (error: Throwable) {
            resultBitmap.takeUnless { it.isRecycled }?.recycle()
            throw error
        }
    }

    private fun copyMutableBitmap(screenshot: Bitmap): Bitmap {
        return try {
            screenshot.copy(Bitmap.Config.ARGB_8888, true)
                ?: throw IllegalStateException("Failed to create bitmap copy")
        } catch (error: OutOfMemoryError) {
            throw IllegalStateException("Not enough memory to process screenshot", error)
        }
    }

    /**
     * Color Sampler: iterates around BoundingBox perimeter to find dominant background color.
     * Edge cases: empty bounds, zero area, bounds outside bitmap.
     */
    private fun getDominantEdgeColor(bitmap: Bitmap, bounds: Rect): Int {
        val colorCounts = mutableMapOf<Int, Int>()

        // OPTIMIZATION: Expand the box outward (padding) to move from the font to the clean background.
        val padding = 14
        val left = (bounds.left - padding).coerceIn(0, bitmap.width - 1)
        val right = (bounds.right + padding).coerceIn(0, bitmap.width - 1)
        val top = (bounds.top - padding).coerceIn(0, bitmap.height - 1)
        val bottom = (bounds.bottom + padding).coerceIn(0, bitmap.height - 1)

        // Edge case: empty or zero rect
        if (right <= left || bottom <= top) {
            return Color.WHITE
        }

        // Edge case: very narrow/short boundary — pick single point
        if (right == left && bottom == top) {
            return bitmap.getPixel(left, top)
        }

        // Sample the box perimeter. Read each edge in one getPixels call (4 JNI
        // calls per block) instead of ~200 per-pixel getPixel calls; this runs
        // once per text block, so the saving scales with block count.
        val rowWidth = right - left + 1
        val colHeight = bottom - top + 1
        val rowBuffer = IntArray(rowWidth)
        val colBuffer = IntArray(colHeight)

        val xStep = maxOf(2, (right - left) / 50)
        bitmap.getPixels(rowBuffer, 0, rowWidth, left, top, rowWidth, 1)
        for (x in 0 until rowWidth step xStep) {
            val c = rowBuffer[x]
            colorCounts[c] = (colorCounts[c] ?: 0) + 1
        }
        bitmap.getPixels(rowBuffer, 0, rowWidth, left, bottom, rowWidth, 1)
        for (x in 0 until rowWidth step xStep) {
            val c = rowBuffer[x]
            colorCounts[c] = (colorCounts[c] ?: 0) + 1
        }

        val yStep = maxOf(2, (bottom - top) / 50)
        bitmap.getPixels(colBuffer, 0, 1, left, top, 1, colHeight)
        for (y in 0 until colHeight step yStep) {
            val c = colBuffer[y]
            colorCounts[c] = (colorCounts[c] ?: 0) + 1
        }
        bitmap.getPixels(colBuffer, 0, 1, right, top, 1, colHeight)
        for (y in 0 until colHeight step yStep) {
            val c = colBuffer[y]
            colorCounts[c] = (colorCounts[c] ?: 0) + 1
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
        
        // WCAG Formula: (L1 + 0.05) / (L2 + 0.05) where L1 is the lighter color
        val contrastWithWhite = 1.05 / (luminance + 0.05)
        val contrastWithBlack = (luminance + 0.05) / 0.05
        return if (contrastWithBlack > contrastWithWhite) Color.BLACK else Color.WHITE
    }
    
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        textRecognizer.close()
        languageIdentifier.close()
        synchronized(translatorLock) {
            translators.values.forEach(Translator::close)
            translators.clear()
        }
    }

    private fun checkOpen() {
        check(!closed.get()) { "ScreenTranslator is already closed." }
    }
}
