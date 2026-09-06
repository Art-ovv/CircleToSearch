package com.akslabs.circletosearch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.akslabs.circletosearch.ui.components.TextNode
import android.util.Log
import android.os.SystemClock
import com.akslabs.circletosearch.ocr.PaddleOcrEngine
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
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
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ScreenTranslator"
private const val SCREEN_TRANSLATION_OCR_TIMEOUT_MS = 25_000L
private const val MAX_PARALLEL_TRANSLATION_REQUESTS = 4

data class TextBlockData(val text: String, val boundingBox: Rect, val sourceNode: TextNode? = null)
data class TranslatedBlockData(val translatedText: String, val boundingBox: Rect, val sourceIndex: Int = -1)

enum class ScreenTranslationUnchangedReason {
    NO_RECOGNIZED_TEXT,
    NO_TRANSLATABLE_TEXT,
    ALREADY_TARGET_LANGUAGE,
    UNKNOWN_OR_UNSUPPORTED_LANGUAGE,
    LANGUAGE_IDENTIFICATION_FAILED,
    MODEL_UNAVAILABLE,
    TRANSLATION_FAILED,
    TEXT_DOES_NOT_FIT,
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
        val textNodes: List<TextNode> = emptyList(),
        val untranslatedReasons: Set<ScreenTranslationUnchangedReason> = emptySet(),
    ) : ScreenTranslationOutcome
}

/** Immutable, allocation-light OCR handoff prepared when overlay nodes change. */
internal data class ScreenTranslationNode(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val sourceNode: TextNode? = null,
)

class ScreenTranslator(context: Context) : Closeable {

    private data class TextBlockKey(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private val appContext = context.applicationContext
    private val languageIdentifier = LanguageIdentification.getClient()

    private val translators = mutableMapOf<TranslationLanguagePair, Translator>()
    private val translatorLock = Any()
    private val closed = AtomicBoolean(false)
    private val translationPairSemaphore = Semaphore(permits = 2)
    private val translationRequestSemaphore = Semaphore(
        permits = MAX_PARALLEL_TRANSLATION_REQUESTS,
    )

    /**
     * Translates an existing OCR result, falling back to PaddleOCR only when [textNodes] is null.
     * An empty non-null list is the valid completed result "no recognized text". The caller must
     * retain [screenshot] without mutating or recycling it until this function returns.
     */
    internal suspend fun translateScreen(
        screenshot: Bitmap,
        textNodes: List<ScreenTranslationNode>?,
        targetLangCode: String? = null,
    ): ScreenTranslationOutcome {
        var undeliveredBitmap: Bitmap? = null
        return try {
            val outcome = withContext(Dispatchers.Default) {
                checkOpen()
                check(!screenshot.isRecycled) { "Bitmap is already recycled." }
                val totalStartedAt = SystemClock.elapsedRealtime()

                val recognitionStartedAt = SystemClock.elapsedRealtime()
                val textBlocks = if (textNodes == null) {
                    recognizeTextWithBounds(screenshot)
                } else {
                    textBlocksFromNodes(
                        nodes = textNodes,
                        bitmapWidth = screenshot.width,
                        bitmapHeight = screenshot.height,
                    )
                }
                val recognitionDurationMs = SystemClock.elapsedRealtime() - recognitionStartedAt
                if (textBlocks.isEmpty()) {
                    logTranslationTiming(
                        sharedOcr = textNodes != null,
                        blockCount = 0,
                        recognitionDurationMs = recognitionDurationMs,
                        translationDurationMs = 0L,
                        renderDurationMs = 0L,
                        totalStartedAt = totalStartedAt,
                    )
                    return@withContext ScreenTranslationOutcome.Unchanged(
                        ScreenTranslationUnchangedReason.NO_RECOGNIZED_TEXT,
                    )
                }

                val translationStartedAt = SystemClock.elapsedRealtime()
                val translation = translateBlocks(textBlocks, targetLangCode)
                val translatedBlocks = translation.blocks
                val translationDurationMs = SystemClock.elapsedRealtime() - translationStartedAt
                if (translatedBlocks.isEmpty()) {
                    logTranslationTiming(
                        sharedOcr = textNodes != null,
                        blockCount = textBlocks.size,
                        recognitionDurationMs = recognitionDurationMs,
                        translationDurationMs = translationDurationMs,
                        renderDurationMs = 0L,
                        totalStartedAt = totalStartedAt,
                    )
                    return@withContext ScreenTranslationOutcome.Unchanged(
                        translation.unchangedReason,
                    )
                }

                val renderStartedAt = SystemClock.elapsedRealtime()
                val rendered = renderScreenTranslations(
                    screenshot = screenshot,
                    translatedBlocks = translatedBlocks,
                    originalBlocks = textBlocks,
                ).also { undeliveredBitmap = it.bitmap }
                if (rendered.renderedBlockCount == 0) {
                    rendered.bitmap.recycle()
                    undeliveredBitmap = null
                    return@withContext ScreenTranslationOutcome.Unchanged(
                        translationUnchangedReason(false,
                            translation.issues + ScreenTranslationUnchangedReason.TEXT_DOES_NOT_FIT),
                    )
                }
                val renderDurationMs = SystemClock.elapsedRealtime() - renderStartedAt
                logTranslationTiming(
                    sharedOcr = textNodes != null,
                    blockCount = textBlocks.size,
                    recognitionDurationMs = recognitionDurationMs,
                    translationDurationMs = translationDurationMs,
                    renderDurationMs = renderDurationMs,
                    totalStartedAt = totalStartedAt,
                )
                ScreenTranslationOutcome.Translated(
                    bitmap = rendered.bitmap,
                    translatedBlockCount = rendered.renderedBlockCount,
                    textNodes = rendered.textNodes,
                    untranslatedReasons = translation.issues + if (rendered.unfittedBlockCount > 0) {
                        setOf(ScreenTranslationUnchangedReason.TEXT_DOES_NOT_FIT)
                    } else {
                        emptySet()
                    },
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

    private fun logTranslationTiming(
        sharedOcr: Boolean,
        blockCount: Int,
        recognitionDurationMs: Long,
        translationDurationMs: Long,
        renderDurationMs: Long,
        totalStartedAt: Long,
    ) {
        Log.d(
            TAG,
            "Screen translation: sharedOcr=$sharedOcr, blocks=$blockCount, " +
                "recognition=${recognitionDurationMs}ms, " +
                "translation=${translationDurationMs}ms, render=${renderDurationMs}ms, " +
                "total=${SystemClock.elapsedRealtime() - totalStartedAt}ms",
        )
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
            TextBlockData(text = text, boundingBox = clampedBounds, sourceNode = node.sourceNode)
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
        return withTimeout(SCREEN_TRANSLATION_OCR_TIMEOUT_MS) {
            PaddleOcrEngine.extractText(
                context = appContext,
                bitmap = bitmap,
                includeQrCodes = false,
            ).textNodes
        }.map { node ->
            TextBlockData(node.fullText, Rect(node.bounds), node)
        }
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
    ): TranslationBlocksResult {
        currentCoroutineContext().ensureActive()

        val requestedTarget = targetLangCode ?: Locale.getDefault().language
        val targetLanguage = TranslateLanguage.fromLanguageTag(requestedTarget)
            ?: TranslateLanguage.ENGLISH
        val aggregateLanguage = identifyAggregateLanguage(blocks)
        val identification = identifyBlockLanguages(blocks)
        val detectedLanguages = identification.languages
        val resolvedLanguages = blocks.mapIndexed { index, block ->
            resolveBlockSourceLanguage(block.text, detectedLanguages[index], aggregateLanguage)
                ?.let(TranslateLanguage::fromLanguageTag)
        }
        val issues = mutableSetOf<ScreenTranslationUnchangedReason>()
        if (identification.failed) issues += ScreenTranslationUnchangedReason.LANGUAGE_IDENTIFICATION_FAILED
        issues += if (resolvedLanguages.any { it == null }) {
            setOf(ScreenTranslationUnchangedReason.UNKNOWN_OR_UNSUPPORTED_LANGUAGE)
        } else {
            emptySet()
        }
        val groups = buildTranslationLanguageGroups(
            blockTexts = blocks.map(TextBlockData::text),
            detectedLanguageTags = resolvedLanguages,
            aggregateLanguage = null,
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

        val groupResults = coroutineScope {
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
        }
        val allIssues = issues + groupResults.flatMap { it.issues }
        return TranslationBlocksResult(
            blocks = groupResults.flatMap { it.blocks }
                .sortedBy(IndexedTranslatedBlock::index).map(IndexedTranslatedBlock::block),
            issues = allIssues,
            unchangedReason = translationUnchangedReason(
                allAlreadyTarget = resolvedLanguages.all { it == targetLanguage },
                issues = allIssues,
            ),
        )
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

    private data class IdentifiedBlockLanguages(val languages: List<String?>, val failed: Boolean)

    private suspend fun identifyBlockLanguages(
        blocks: List<TextBlockData>,
    ): IdentifiedBlockLanguages {
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
        return IdentifiedBlockLanguages(languages, failureCount > 0)
    }

    private data class IndexedTranslatedBlock(
        val index: Int,
        val block: TranslatedBlockData,
    )

    private data class TranslationBlocksResult(
        val blocks: List<TranslatedBlockData>,
        val issues: Set<ScreenTranslationUnchangedReason>,
        val unchangedReason: ScreenTranslationUnchangedReason,
    )

    private data class LanguageGroupResult(
        val blocks: List<IndexedTranslatedBlock>,
        val issues: Set<ScreenTranslationUnchangedReason>,
    )

    private suspend fun translateLanguageGroup(
        pair: TranslationLanguagePair,
        blockIndexes: List<Int>,
        blocks: List<TextBlockData>,
    ): LanguageGroupResult {
        val translator = getTranslator(pair)
        val startedAt = SystemClock.elapsedRealtime()
        val result = translateTextGroup(
            texts = blockIndexes.map { blocks[it].text },
            ensureModel = { translator.downloadModelIfNeeded().await() },
            translate = { translator.translate(it).await() },
            requestSemaphore = translationRequestSemaphore,
        )
        Log.d(TAG, "Translation group: blocks=${blockIndexes.size}, " +
            "elapsed=${SystemClock.elapsedRealtime() - startedAt}ms, issues=${result.issues.size}")
        return LanguageGroupResult(
            blocks = result.translations.map { translated ->
                val index = blockIndexes[translated.index]
                IndexedTranslatedBlock(index, TranslatedBlockData(translated.text, blocks[index].boundingBox, index))
            },
            issues = result.issues,
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
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
