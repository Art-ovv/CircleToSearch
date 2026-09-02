package com.akslabs.circletosearch.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Patterns
import android.util.Log
import com.akslabs.circletosearch.ui.components.SmartEntity
import com.akslabs.circletosearch.ui.components.TextNode
import com.akslabs.circletosearch.ui.components.Word
import com.akslabs.circletosearch.utils.QrScanner
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class ExtractionResult(
    val textNodes: List<TextNode>,
    val smartEntities: List<SmartEntity>
)

data class ModelImportResult(
    val success: Boolean,
    val message: String,
)

internal data class OcrCandidate(
    val word: Word,
    val confidence: Float,
    val textLine: Int,
)

internal enum class OcrPass {
    PRIMARY,
    DESKEW,
    MIXED_POLARITY,
    TARGETED,
}

internal data class RankedOcrWord(
    val word: Word,
    val confidence: Float,
    val pass: OcrPass,
    val passIdentity: Int,
    val lineIdentity: Long,
) {
    val mergeScore: Float
        get() = confidence + when (pass) {
            OcrPass.PRIMARY -> 1.5f
            OcrPass.DESKEW -> 0f
            OcrPass.MIXED_POLARITY -> 1f
            OcrPass.TARGETED -> 2f
        }
}

internal data class OcrPrimaryQuality(
    val wordCount: Int,
    val strongWordCount: Int,
    val meanConfidence: Float,
    val occupiedAreaFraction: Float,
    val verticalSpanFraction: Float,
)

internal fun assessPrimaryOcrQuality(
    words: List<RankedOcrWord>,
    imageWidth: Int,
    imageHeight: Int,
): OcrPrimaryQuality {
    if (words.isEmpty() || imageWidth <= 0 || imageHeight <= 0) {
        return OcrPrimaryQuality(0, 0, 0f, 0f, 0f)
    }
    val primary = words.filter { it.pass == OcrPass.PRIMARY || it.pass == OcrPass.TARGETED }
    if (primary.isEmpty()) return OcrPrimaryQuality(0, 0, 0f, 0f, 0f)

    var confidenceSum = 0f
    var occupiedArea = 0.0
    var minimumTop = Float.POSITIVE_INFINITY
    var maximumBottom = Float.NEGATIVE_INFINITY
    var strongWords = 0
    primary.forEach { ranked ->
        confidenceSum += ranked.confidence
        if (ranked.confidence >= 72f) strongWords++
        val bounds = ranked.word.bounds
        occupiedArea +=
            (bounds.right - bounds.left).coerceAtLeast(0f) *
                (bounds.bottom - bounds.top).coerceAtLeast(0f)
        minimumTop = minOf(minimumTop, bounds.top)
        maximumBottom = maxOf(maximumBottom, bounds.bottom)
    }
    val imageArea = imageWidth.toDouble() * imageHeight.toDouble()
    return OcrPrimaryQuality(
        wordCount = primary.size,
        strongWordCount = strongWords,
        meanConfidence = confidenceSum / primary.size,
        occupiedAreaFraction = (occupiedArea / imageArea).toFloat().coerceIn(0f, 1f),
        verticalSpanFraction = ((maximumBottom - minimumTop) / imageHeight)
            .coerceIn(0f, 1f),
    )
}

/**
 * Global rotation is an expensive second full-screen recognition pass. Skip it
 * only when the primary pass is both strong and distributed across the screen;
 * a short or local result remains eligible for enhancement.
 */
internal fun shouldRunGlobalDeskew(quality: OcrPrimaryQuality): Boolean =
    quality.wordCount < 12 ||
        quality.strongWordCount < 9 ||
        quality.meanConfidence < 78f ||
        quality.occupiedAreaFraction < 0.012f ||
        quality.verticalSpanFraction < 0.30f

internal fun shouldRunFallbackRegion(
    region: OcrFallbackRegion,
    primaryWords: List<RankedOcrWord>,
    processedScale: Float,
): Boolean {
    if (processedScale <= 0f) return true
    val regionLeft = region.left / processedScale
    val regionTop = region.top / processedScale
    val regionRight = region.right / processedScale
    val regionBottom = region.bottom / processedScale
    val confident = primaryWords.filter { ranked ->
        if (ranked.pass != OcrPass.PRIMARY || ranked.confidence < 76f) return@filter false
        val bounds = ranked.word.bounds
        val centerX = (bounds.left + bounds.right) * 0.5f
        val centerY = (bounds.top + bounds.bottom) * 0.5f
        centerX in regionLeft..regionRight &&
            centerY in regionTop..regionBottom
    }
    if (confident.size < 6) return true

    val minimumLeft = confident.minOf { it.word.bounds.left }
    val maximumRight = confident.maxOf { it.word.bounds.right }
    val minimumTop = confident.minOf { it.word.bounds.top }
    val maximumBottom = confident.maxOf { it.word.bounds.bottom }
    val regionWidth = (regionRight - regionLeft).coerceAtLeast(1f)
    val regionHeight = (regionBottom - regionTop).coerceAtLeast(1f)
    val horizontalSpan = (maximumRight - minimumLeft) / regionWidth
    val verticalSpan = (maximumBottom - minimumTop) / regionHeight
    val occupiedArea = confident.sumOf {
        val bounds = it.word.bounds
        ((bounds.right - bounds.left).coerceAtLeast(0f) *
            (bounds.bottom - bounds.top).coerceAtLeast(0f)).toDouble()
    }.toFloat() / (regionWidth * regionHeight).coerceAtLeast(1f)

    // Be deliberately conservative: only skip a fallback when the primary pass
    // already found several strong words distributed through that same region.
    return !(occupiedArea >= 0.018f && (horizontalSpan >= 0.55f || verticalSpan >= 0.45f))
}

internal fun normalizeOcrText(text: String): String = text
    .lowercase(Locale.ROOT)
    .filter(Char::isLetterOrDigit)

private fun ocrTextSimilarity(first: String, second: String): Float {
    val left = normalizeOcrText(first)
    val right = normalizeOcrText(second)
    if (left.isEmpty() || right.isEmpty()) return 0f
    if (left == right) return 1f
    if (minOf(left.length, right.length) >= 4 && (left.contains(right) || right.contains(left))) {
        return minOf(left.length, right.length).toFloat() / maxOf(left.length, right.length)
    }
    val previous = IntArray(right.length + 1) { it }
    val current = IntArray(right.length + 1)
    for (leftIndex in left.indices) {
        current[0] = leftIndex + 1
        for (rightIndex in right.indices) {
            val substitution = if (left[leftIndex] == right[rightIndex]) 0 else 1
            current[rightIndex + 1] = minOf(
                current[rightIndex] + 1,
                previous[rightIndex + 1] + 1,
                previous[rightIndex] + substitution,
            )
        }
        for (index in previous.indices) previous[index] = current[index]
    }
    return 1f - previous[right.length].toFloat() / maxOf(left.length, right.length)
}

private fun overlapOfSmaller(first: RectF, second: RectF): Float {
    val left = maxOf(first.left, second.left)
    val top = maxOf(first.top, second.top)
    val right = minOf(first.right, second.right)
    val bottom = minOf(first.bottom, second.bottom)
    if (right <= left || bottom <= top) return 0f
    val firstArea =
        (first.right - first.left).coerceAtLeast(0f) *
            (first.bottom - first.top).coerceAtLeast(0f)
    val secondArea =
        (second.right - second.left).coerceAtLeast(0f) *
            (second.bottom - second.top).coerceAtLeast(0f)
    val smallerArea = minOf(firstArea, secondArea)
    if (smallerArea <= 0f) return 0f
    return ((right - left) * (bottom - top) / smallerArea).coerceIn(0f, 1f)
}

/** Combines independent preprocessing passes without discarding text on overlap alone. */
internal fun mergeRankedOcrWords(candidates: List<RankedOcrWord>): List<RankedOcrWord> {
    if (candidates.size < 2) return candidates
    val clusters = mutableListOf<MutableList<RankedOcrWord>>()
    candidates.forEach { candidate ->
        val cluster = clusters.firstOrNull { members ->
            members.any { member ->
                val overlap = overlapOfSmaller(candidate.word.bounds, member.word.bounds)
                val semanticMatch = ocrTextSimilarity(candidate.word.text, member.word.text) >= 0.72f
                overlap >= 0.50f && semanticMatch
            }
        }
        if (cluster == null) clusters += mutableListOf(candidate) else cluster += candidate
    }

    return clusters.map { members ->
        members.maxWithOrNull(
            compareBy<RankedOcrWord> { candidate ->
                val normalizedCandidate = normalizeOcrText(candidate.word.text)
                val exactAgreementPasses = members
                    .asSequence()
                    .filter { normalizeOcrText(it.word.text) == normalizedCandidate }
                    .map { it.passIdentity }
                    .distinct()
                    .count()
                val fuzzyAgreementPasses = members
                    .asSequence()
                    .filter {
                        normalizeOcrText(it.word.text) != normalizedCandidate &&
                            ocrTextSimilarity(candidate.word.text, it.word.text) >= 0.72f
                    }
                    .map { it.passIdentity }
                    .distinct()
                    .count()
                candidate.mergeScore +
                    (exactAgreementPasses - 1).coerceAtLeast(0) * 10f +
                    fuzzyAgreementPasses * 1.5f
            }.thenBy { it.word.text.count(Char::isLetterOrDigit) },
        ) ?: members.first()
    }
}

internal class LatestOcrRequestGate<T>(
    private val stopTarget: (T) -> Unit,
) {
    internal class Token<T> internal constructor(
        internal val generation: Long,
        internal val target: T,
    )

    private val latestGeneration = AtomicLong(0L)
    private val active = AtomicReference<Token<T>?>(null)

    fun begin(): Long {
        val generation = latestGeneration.incrementAndGet()
        active.get()?.takeIf { it.generation < generation }?.let { stopTarget(it.target) }
        return generation
    }

    fun activate(generation: Long, target: T): Token<T>? {
        if (!isLatest(generation)) return null
        val token = Token(generation, target)
        check(active.compareAndSet(null, token)) { "An OCR native call is already active" }
        if (isLatest(generation)) return token

        if (active.compareAndSet(token, null)) stopTarget(target)
        return null
    }

    fun deactivate(token: Token<T>) {
        active.compareAndSet(token, null)
    }

    fun stopActive() {
        active.get()?.let { stopTarget(it.target) }
    }

    fun isLatest(generation: Long): Boolean = generation == latestGeneration.get()
}

internal fun looksLikeMachineIdentifier(text: String): Boolean {
    val compact = text.trim()
    if (compact.length !in 7..64 || compact.any(Char::isWhitespace)) return false
    if (compact.any { !it.isLetterOrDigit() && it !in ":-_/.#" }) return false

    val letters = compact.count(Char::isLetter)
    val digits = compact.count(Char::isDigit)
    if (letters < 2 || digits < 3) return false

    val hasSeparator = compact.any { it in ":-_/.#" }
    var typeTransitions = 0
    for (index in 1 until compact.length) {
        val first = compact[index - 1]
        val second = compact[index]
        if (
            (first.isLetter() && second.isDigit()) ||
            (first.isDigit() && second.isLetter())
        ) {
            typeTransitions++
        }
    }
    return hasSeparator || typeTransitions >= 2
}

internal fun filterOcrCandidates(
    candidates: List<OcrCandidate>,
    density: Float,
): List<Word> = filterOcrCandidateDetails(candidates, density).map { it.word }

private fun filterOcrCandidateDetails(
    candidates: List<OcrCandidate>,
    density: Float,
): List<OcrCandidate> = candidates.mapNotNull { candidate ->
    val text = candidate.word.text.trim()
    val alphanumericCount = text.count(Char::isLetterOrDigit)
    val machineIdentifier = looksLikeMachineIdentifier(text)
    if (alphanumericCount == 0) return@mapNotNull null

    val bounds = candidate.word.bounds
    val width = bounds.right - bounds.left
    val height = bounds.bottom - bounds.top
    if (width < 2f || height < 2f) return@mapNotNull null

    val hasTextPeer = candidates.any { peer ->
        if (peer === candidate) return@any false
        if (peer.word.text.count(Char::isLetterOrDigit) < 2 || peer.confidence < 55f) return@any false

        val peerBounds = peer.word.bounds
        val peerHeight = (peerBounds.bottom - peerBounds.top).coerceAtLeast(1f)
        val heightRatio = height / peerHeight
        val verticalOverlap = minOf(bounds.bottom, peerBounds.bottom) -
            maxOf(bounds.top, peerBounds.top)
        val requiredOverlap = if (peer.textLine == candidate.textLine) 0.5f else 0.7f
        val horizontalGap = maxOf(
            bounds.left - peerBounds.right,
            peerBounds.left - bounds.right,
            0f,
        )
        heightRatio in 0.65f..1.35f &&
            verticalOverlap >= minOf(height, peerHeight) * requiredOverlap &&
            horizontalGap <= maxOf(32f * density, maxOf(height, peerHeight) * 3f)
    }

    val aspectRatio = width / height
    val looksLikeCompactIcon = alphanumericCount <= 4 &&
        height >= 20f * density &&
        aspectRatio in 0.55f..1.80f

    if (looksLikeCompactIcon && !hasTextPeer) {
        val alphanumeric = text.filter(Char::isLetterOrDigit)
        val repeatedGlyph = alphanumeric.length > 1 && alphanumeric.toSet().size == 1
        val standaloneConfidence = when {
            alphanumericCount == 1 && alphanumeric.single().isDigit() -> Float.POSITIVE_INFINITY
            alphanumericCount == 1 -> 97f
            alphanumericCount == 2 -> 94f
            else -> 98f
        }
        if (repeatedGlyph || candidate.confidence < standaloneConfidence) {
            return@mapNotNull null
        }
    }
    if (alphanumericCount >= 3) {
        return@mapNotNull candidate.takeIf {
            candidate.confidence >= 55f ||
                (hasTextPeer && candidate.confidence >= 45f) ||
                (machineIdentifier && candidate.confidence >= 40f)
        }
    }

    when {
        hasTextPeer && candidate.confidence >= 50f -> candidate
        candidate.confidence >= 88f -> candidate
        else -> null
    }
}

@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
object TesseractEngine {
    private const val TAG = "TesseractEngine"

    // Cache Tesseract instance to avoid loading 30MB+ dictionaries from disk on every scan
    private var cachedTessApi: TessBaseAPI? = null
    private var cachedLang: String? = null
    private var cachedWordsBitmap = WeakReference<Bitmap>(null)
    private var cachedWordsLanguage: String? = null
    private var cachedWords: List<RankedOcrWord>? = null
    private val ocrMutex = Mutex()
    private val recognitionDispatcher = Dispatchers.Default.limitedParallelism(2)
    private val preparerLock = Any()
    private var dataPreparer: TessDataPreparer? = null
    private val engineResetRequested = AtomicBoolean(false)
    private val requestGate = LatestOcrRequestGate<ActiveRecognition> { it.requestStop() }

    private class ActiveRecognition(
        private val api: TessBaseAPI,
        private val resetRequested: AtomicBoolean,
    ) {
        private var acceptingStop = true
        private var stopIssued = false

        fun requestStop() = synchronized(this) {
            if (!acceptingStop || stopIssued) return@synchronized
            stopIssued = true
            resetRequested.set(true)
            try {
                api.stop()
            } catch (error: RuntimeException) {
                Log.w(
                    "TesseractEngine",
                    "Unable to stop superseded Tesseract recognition",
                    error,
                )
            }
        }

        fun finish() = synchronized(this) {
            acceptingStop = false
        }

        fun wasStopRequested(): Boolean = synchronized(this) { stopIssued }
    }

    // Default languages: eng + rus for Cyrillic support
    private val defaultLanguages = listOf("eng", "rus")

    /**
     * Pre-initializes Tesseract engine in background so the first real OCR call
     * doesn't pay the ~2-4 second cold-start cost of loading 30MB+ model files.
     * Safe to call multiple times; no-ops if already warmed up.
     */
    suspend fun warmUp(context: Context) {
        val appContext = context.applicationContext
        val dataPath = withContext(Dispatchers.IO) { prepareTessData(appContext) }
        val lang = getOcrLanguage(appContext)

        withContext(recognitionDispatcher) {
            ocrMutex.withLock {
                if (ensureTessApi(dataPath, lang) != null) {
                    Log.d(TAG, "Warm-up complete: Tesseract ready for lang=$lang")
                } else {
                    Log.e(TAG, "Warm-up failed: could not init Tesseract for lang=$lang")
                }
            }
        }
    }

    suspend fun releaseCachedEngine() = withContext(recognitionDispatcher) {
        requestGate.stopActive()
        ocrMutex.withLock {
            discardCachedEngine()
            engineResetRequested.set(false)
        }
    }

    /** Must be called while [ocrMutex] is held. */
    private fun clearWordCache() {
        cachedWordsBitmap.clear()
        cachedWordsLanguage = null
        cachedWords = null
    }

    /** Must be called while [ocrMutex] is held and never during a native call. */
    private fun discardCachedEngine(expected: TessBaseAPI? = null) {
        val current = cachedTessApi
        if (expected != null && current !== expected) return
        try {
            current?.recycle()
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to recycle Tesseract engine", error)
        }
        cachedTessApi = null
        cachedLang = null
        clearWordCache()
    }

    private fun beginLatestExtraction(): Long {
        return requestGate.begin()
    }

    private fun ensureLatestExtraction(
        generation: Long,
        jobContext: kotlin.coroutines.CoroutineContext,
    ) {
        jobContext.ensureActive()
        if (!requestGate.isLatest(generation)) {
            throw CancellationException("OCR extraction was superseded by a newer request")
        }
    }

    /** Must be called while [ocrMutex] is held. */
    private fun ensureTessApi(dataPath: String, lang: String): TessBaseAPI? {
        cachedTessApi?.takeIf { cachedLang == lang }?.let { return it }

        cachedTessApi?.recycle()
        cachedTessApi = null
        cachedLang = null

        val candidate = TessBaseAPI()
        return try {
            if (candidate.init(dataPath, lang)) {
                cachedTessApi = candidate
                cachedLang = lang
                candidate
            } else {
                candidate.recycle()
                null
            }
        } catch (error: Throwable) {
            candidate.recycle()
            throw error
        }
    }

    internal data class RecognitionTransform(
        val recognitionScaleX: Float,
        val recognitionScaleY: Float,
        val border: Float,
        val processedOffsetX: Float,
        val processedOffsetY: Float,
        val processedScale: Float,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val rotationDegrees: Float = 0f,
        val rotationCenterX: Float = 0f,
        val rotationCenterY: Float = 0f,
        val rotationCanvasOffsetX: Float = 0f,
        val rotationCanvasOffsetY: Float = 0f,
    ) {
        private val hasRotation = abs(rotationDegrees) >= 0.01f
        private val inverseCosine = if (hasRotation) {
            cos(Math.toRadians(-rotationDegrees.toDouble())).toFloat()
        } else {
            1f
        }
        private val inverseSine = if (hasRotation) {
            sin(Math.toRadians(-rotationDegrees.toDouble())).toFloat()
        } else {
            0f
        }

        fun map(rect: Rect): RectF {
            if (!hasRotation) {
                return RectF().apply {
                    left = ((rect.left / recognitionScaleX - border + processedOffsetX) /
                        processedScale).coerceIn(0f, sourceWidth.toFloat())
                    top = ((rect.top / recognitionScaleY - border + processedOffsetY) /
                        processedScale).coerceIn(0f, sourceHeight.toFloat())
                    right = ((rect.right / recognitionScaleX - border + processedOffsetX) /
                        processedScale).coerceIn(0f, sourceWidth.toFloat())
                    bottom = ((rect.bottom / recognitionScaleY - border + processedOffsetY) /
                        processedScale).coerceIn(0f, sourceHeight.toFloat())
                }
            }

            var minimumX = Float.POSITIVE_INFINITY
            var minimumY = Float.POSITIVE_INFINITY
            var maximumX = Float.NEGATIVE_INFINITY
            var maximumY = Float.NEGATIVE_INFINITY

            for (corner in 0 until 4) {
                val recognitionX = if (corner == 0 || corner == 3) {
                    rect.left.toFloat()
                } else {
                    rect.right.toFloat()
                }
                val recognitionY = if (corner < 2) {
                    rect.top.toFloat()
                } else {
                    rect.bottom.toFloat()
                }
                val processedX = recognitionX / recognitionScaleX -
                    border + processedOffsetX
                val processedY = recognitionY / recognitionScaleY -
                    border + processedOffsetY
                val localX =
                    processedX - rotationCanvasOffsetX - rotationCenterX
                val localY =
                    processedY - rotationCanvasOffsetY - rotationCenterY
                val mappedX =
                    localX * inverseCosine - localY * inverseSine + rotationCenterX
                val mappedY =
                    localX * inverseSine + localY * inverseCosine + rotationCenterY
                val sourceX = (mappedX / processedScale)
                    .coerceIn(0f, sourceWidth.toFloat())
                val sourceY = (mappedY / processedScale)
                    .coerceIn(0f, sourceHeight.toFloat())
                minimumX = minOf(minimumX, sourceX)
                minimumY = minOf(minimumY, sourceY)
                maximumX = maxOf(maximumX, sourceX)
                maximumY = maxOf(maximumY, sourceY)
            }
            return RectF().apply {
                left = minimumX
                top = minimumY
                right = maximumX
                bottom = maximumY
            }
        }
    }

    private fun recognizeWords(
        api: TessBaseAPI,
        image: Bitmap,
        density: Float,
        jobContext: kotlin.coroutines.CoroutineContext,
        transform: RecognitionTransform,
        canonicalPolarity: Boolean,
        generation: Long,
        pageSegMode: Int = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,
    ): List<OcrCandidate> {
        ensureLatestExtraction(generation, jobContext)
        api.pageSegMode = pageSegMode
        if (!api.setVariable("thresholding_method", "2")) {
            Log.w(TAG, "Sauvola thresholding is unavailable; using Tesseract default")
        }
        api.setVariable("invert_threshold", if (canonicalPolarity) "0.0" else "0.7")

        val active = ActiveRecognition(api, engineResetRequested)
        val activeToken = requestGate.activate(generation, active)
            ?: throw CancellationException("OCR extraction was superseded before recognition")
        val cancellationHandle = jobContext[Job]?.invokeOnCompletion(
            onCancelling = true,
            invokeImmediately = true,
        ) { cause ->
            if (cause != null) active.requestStop()
        }

        return try {
            if (active.wasStopRequested()) {
                throw CancellationException("Tesseract recognition was stopped before it started")
            }
            api.setImage(image)
            api.getUTF8Text()
            if (active.wasStopRequested()) {
                throw CancellationException("Tesseract recognition was stopped")
            }
            ensureLatestExtraction(generation, jobContext)

            val iterator = api.resultIterator ?: return emptyList()
            try {
                val candidates = mutableListOf<OcrCandidate>()
                var iteration = 0
                var textLine = -1
                iterator.begin()
                do {
                    if (iteration++ % 32 == 0) {
                        if (active.wasStopRequested()) {
                            throw CancellationException("Tesseract recognition was stopped")
                        }
                        ensureLatestExtraction(generation, jobContext)
                    }
                    if (
                        textLine == -1 ||
                        iterator.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                    ) {
                        textLine++
                    }
                    val wordText = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    if (wordText.isNullOrBlank()) continue

                    val confidence = iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    val minimumConfidence = if (looksLikeMachineIdentifier(wordText)) 40f else 45f
                    if (confidence < minimumConfidence) continue

                    val wordRectParams = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                        ?: iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    val wordRect = when (wordRectParams) {
                        is Rect -> wordRectParams
                        is IntArray -> Rect(
                            wordRectParams[0],
                            wordRectParams[1],
                            wordRectParams[2],
                            wordRectParams[3],
                        )
                        else -> continue
                    }
                    val mappedBounds = transform.map(wordRect)
                    if (mappedBounds.right - mappedBounds.left < 2f) continue
                    if (mappedBounds.bottom - mappedBounds.top < 2f) continue

                    candidates += OcrCandidate(
                        word = Word(
                            text = wordText,
                            index = 0,
                            startIndex = 0,
                            endIndex = wordText.length,
                            bounds = mappedBounds,
                        ),
                        confidence = confidence,
                        textLine = textLine,
                    )
                } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                if (active.wasStopRequested()) {
                    throw CancellationException("Tesseract recognition was stopped")
                }
                filterOcrCandidateDetails(candidates, density)
            } finally {
                iterator.delete()
            }
        } catch (error: RuntimeException) {
            engineResetRequested.set(true)
            if (active.wasStopRequested() && error !is CancellationException) {
                throw CancellationException("Stopped Tesseract recognition failed").also {
                    it.initCause(error)
                }
            }
            throw error
        } finally {
            cancellationHandle?.dispose()
            active.finish()
            requestGate.deactivate(activeToken)
            try {
                api.clear()
            } catch (error: RuntimeException) {
                engineResetRequested.set(true)
                Log.w(TAG, "Unable to clear Tesseract after recognition", error)
            }
        }
    }

    private val latinToCyrillic = mapOf(
        'A' to 'А', 'a' to 'а',
        'B' to 'В',
        'C' to 'С', 'c' to 'с',
        'E' to 'Е', 'e' to 'е',
        'H' to 'Н',
        'K' to 'К', 'k' to 'к',
        'M' to 'М', 'm' to 'м',
        'O' to 'О', 'o' to 'о',
        'P' to 'Р', 'p' to 'р',
        'T' to 'Т', 't' to 'т',
        'X' to 'Х', 'x' to 'х',
        'Y' to 'У', 'y' to 'у'
    )
    private val cyrillicToLatin = latinToCyrillic.entries.associate { (k, v) -> v to k }

    private fun cleanWordText(text: String, lineDominantCyrillic: Boolean): String =
        cleanRecognizedWordText(text, lineDominantCyrillic)

    internal fun cleanRecognizedWordText(
        text: String,
        lineDominantCyrillic: Boolean,
    ): String {
        var t = text.trim()
        if (t.isEmpty()) return ""

        // URLs, emails, identifiers and code are intentionally script-sensitive.
        // Replacing Latin lookalikes with Cyrillic there silently corrupts data.
        val compactToken = t.none(Char::isWhitespace)
        val compactAlphaNumericIdentifier = compactToken && t.length >= 7 &&
            t.count(Char::isLetter) >= 2 && t.count(Char::isDigit) >= 3
        val protectedToken = looksLikeMachineIdentifier(t) ||
            compactAlphaNumericIdentifier ||
            t.contains("://") ||
            (compactToken && '@' in t) ||
            (compactToken && (t.startsWith("www.", ignoreCase = true) || '/' in t)) ||
            (compactToken && '.' in t && t.substringAfterLast('.').length in 2..12) ||
            t.any { it in "<>={}[]\\|" } ||
            t.contains("::") ||
            (t.any(Char::isDigit) && t.any { it in ":/_-.#" })
        if (protectedToken) return t

        val cCyr = t.count { it in 'А'..'я' || it == 'Ё' || it == 'ё' }
        val cLat = t.count { it in 'A'..'Z' || it in 'a'..'z' }

        if (cCyr == 0 && cLat == 0) return t

        if (cCyr > 0 && cLat > 0) {
            val toCyrillic = cCyr >= cLat
            t = t.map { char ->
                if (toCyrillic && latinToCyrillic.containsKey(char)) latinToCyrillic[char]!!
                else if (!toCyrillic && cyrillicToLatin.containsKey(char)) cyrillicToLatin[char]!!
                else char
            }.joinToString("")
        } else if (lineDominantCyrillic && cLat > 0 && cCyr == 0) {
            val onlyLookalikes = t.all { !it.isLetter() || latinToCyrillic.containsKey(it) }
            if (onlyLookalikes) t = t.map { char -> latinToCyrillic[char] ?: char }.joinToString("")
        } else if (!lineDominantCyrillic && cCyr > 0 && cLat == 0) {
            val onlyLookalikes = t.all { !it.isLetter() || cyrillicToLatin.containsKey(it) }
            if (onlyLookalikes) t = t.map { char -> cyrillicToLatin[char] ?: char }.joinToString("")
        }
        
        return t
    }

    fun prepareTessData(context: Context): String {
        val appContext = context.applicationContext
        val preparer = synchronized(preparerLock) {
            dataPreparer ?: TessDataPreparer(
                filesDir = appContext.filesDir,
                languages = defaultLanguages,
                openAsset = appContext.assets::open,
            ).also { dataPreparer = it }
        }
        return preparer.prepare()
    }

    fun getAvailableModels(context: Context): List<String> {
        val dir = File(context.filesDir, "tessdata")
        if (!dir.exists()) {
            prepareTessData(context)
        }

        val files = dir.listFiles() ?: return listOf("eng")
        val available = files.filter { it.name.endsWith(".traineddata") }
            .map { it.name.removeSuffix(".traineddata") }
            .sorted()

        // Fallback to eng if no models found
        return available.ifEmpty { listOf("eng") }
    }

    /**
     * Automatically detects language based on system settings.
     * Returns preferred language for OCR.
     */
    fun getSystemLanguage(): String {
        // Priority is given to Russian ("rus+eng") to prevent 
        // Cyrillic words from being converted into lookalike English ones (e.g., "тому" -> "tommy").
        return "rus+eng"
    }

    /**
     * Returns language for OCR - either system default or user saved.
     */
    fun getOcrLanguage(context: Context): String {
        val prefs = context.getSharedPreferences("OcrSettings", Context.MODE_PRIVATE)
        return prefs.getString("selected_lang", "rus+eng") ?: "rus+eng"
    }

    /**
     * Checks if model is available for specified language.
     */
    fun isModelAvailable(context: Context, lang: String): Boolean {
        val tessDir = File(context.filesDir, "tessdata")
        // Support combined languages like "rus+eng"
        return lang.split("+").all { File(tessDir, "$it.traineddata").exists() }
    }

    /**
     * Checks for Russian model and offers download if missing.
     */
    fun checkAndOfferRussianModel(context: Context): Boolean {
        if (isModelAvailable(context, "rus")) return true

        val prefs = context.getSharedPreferences("OcrSettings", Context.MODE_PRIVATE)
        val offerShown = prefs.getBoolean("rus_model_offer_shown", false)

        if (!offerShown) {
            prefs.edit().putBoolean("rus_model_offer_shown", true).apply()
            return false
        }
        return false
    }

    suspend fun importModel(context: Context, uri: android.net.Uri): ModelImportResult {
        val appContext = context.applicationContext
        return try {
            val fileName = withContext(Dispatchers.IO) {
                val displayName = appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex == -1) null else cursor.getString(nameIndex)
                } ?: "unknown.traineddata"
                File(displayName).name
            }

            if (!fileName.endsWith(".traineddata", ignoreCase = true)) {
                return ModelImportResult(false, "File must be a .traineddata Tesseract model.")
            }

            withContext(Dispatchers.IO) {
                val tessDir = File(appContext.filesDir, "tessdata")
                check(tessDir.isDirectory || tessDir.mkdirs()) {
                    "Unable to create ${tessDir.absolutePath}"
                }
                val destination = File(tessDir, fileName)
                val temporary = File(tessDir, ".${destination.name}.import.tmp")
                try {
                    val input = checkNotNull(appContext.contentResolver.openInputStream(uri)) {
                        "Unable to open selected model"
                    }
                    input.use {
                        FileOutputStream(temporary).use { output ->
                            it.copyTo(output)
                            output.fd.sync()
                        }
                    }
                    check(temporary.length() > 0L) { "Selected model is empty" }
                    moveImportedModel(temporary, destination)
                } finally {
                    temporary.delete()
                }
            }

            requestGate.stopActive()
            withContext(recognitionDispatcher) {
                ocrMutex.withLock {
                    discardCachedEngine()
                    engineResetRequested.set(false)
                }
            }

            Log.d(TAG, "Imported model: $fileName")
            ModelImportResult(
                true,
                "Successfully imported ${fileName.removeSuffix(".traineddata").uppercase()} model!",
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Error importing model", error)
            ModelImportResult(false, "Failed to import model")
        }
    }

    private fun moveImportedModel(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    /**
     * Performs a bounded, high-resolution OCR pass for a user-selected source
     * region. Returned word bounds remain in the original bitmap coordinates.
     * Calling this supersedes an older full-screen extraction, which prevents a
     * stale native pass from delaying an interactive refinement indefinitely.
     */
    suspend fun extractTextInRegion(
        context: Context,
        bitmap: Bitmap,
        sourceRegion: RectF,
    ): List<TextNode> = withContext(recognitionDispatcher) {
        require(!bitmap.isRecycled) { "Cannot run OCR on a recycled bitmap" }
        if (
            !sourceRegion.left.isFinite() || !sourceRegion.top.isFinite() ||
            !sourceRegion.right.isFinite() || !sourceRegion.bottom.isFinite()
        ) {
            return@withContext emptyList()
        }
        val clipped = Rect(
            sourceRegion.left.toInt().coerceIn(0, bitmap.width),
            sourceRegion.top.toInt().coerceIn(0, bitmap.height),
            ceil(sourceRegion.right.toDouble()).toInt().coerceIn(0, bitmap.width),
            ceil(sourceRegion.bottom.toDouble()).toInt().coerceIn(0, bitmap.height),
        )
        if (clipped.width() < 4 || clipped.height() < 4) return@withContext emptyList()

        val generation = beginLatestExtraction()
        val appContext = context.applicationContext
        val dataPath = withContext(Dispatchers.IO) { prepareTessData(appContext) }
        val lang = getOcrLanguage(appContext)
        val density = appContext.resources.displayMetrics.density
        val jobContext = currentCoroutineContext()

        ocrMutex.withLock {
            ensureLatestExtraction(generation, jobContext)
            val api = ensureTessApi(dataPath, lang) ?: return@withLock emptyList()
            val sourceArea = clipped.width().toDouble() * clipped.height().toDouble()
            val scaleByArea = sqrt(2_500_000.0 / sourceArea).toFloat()
            val scaleByDimension = 2_400f / maxOf(clipped.width(), clipped.height())
            val scale = minOf(2.25f, scaleByArea, scaleByDimension)
            val targetWidth = (clipped.width() * scale).roundToInt().coerceAtLeast(1)
            val targetHeight = (clipped.height() * scale).roundToInt().coerceAtLeast(1)
            val targetedBitmap = Bitmap.createBitmap(
                targetWidth,
                targetHeight,
                Bitmap.Config.ARGB_8888,
            )
            val words = mutableListOf<RankedOcrWord>()
            try {
                val canvas = Canvas(targetedBitmap)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(
                    bitmap,
                    clipped,
                    Rect(0, 0, targetWidth, targetHeight),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                )
                val transform = RecognitionTransform(
                    recognitionScaleX = 1f,
                    recognitionScaleY = 1f,
                    border = 0f,
                    processedOffsetX = clipped.left * scale,
                    processedOffsetY = clipped.top * scale,
                    processedScale = scale,
                    sourceWidth = bitmap.width,
                    sourceHeight = bitmap.height,
                )
                val aspectRatio = clipped.width().toFloat() / clipped.height().coerceAtLeast(1)
                val pageSegMode = if (aspectRatio >= 4f) {
                    TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
                } else {
                    TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
                }
                recognizeWords(
                    api = api,
                    image = targetedBitmap,
                    density = density,
                    jobContext = jobContext,
                    transform = transform,
                    canonicalPolarity = false,
                    generation = generation,
                    pageSegMode = pageSegMode,
                ).forEach { candidate ->
                    words += RankedOcrWord(
                        word = candidate.word,
                        confidence = candidate.confidence,
                        pass = OcrPass.TARGETED,
                        passIdentity = 100,
                        lineIdentity = (100L shl 32) or
                            (candidate.textLine.toLong() and 0xFFFFFFFFL),
                    )
                }

                val meanConfidence = if (words.isEmpty()) {
                    0f
                } else {
                    words.sumOf { it.confidence.toDouble() }.toFloat() / words.size
                }
                val needsContrastFallback = words.size < 2 || meanConfidence < 76f
                if (needsContrastFallback) {
                    ensureLatestExtraction(generation, jobContext)
                    val pixels = IntArray(targetWidth * targetHeight)
                    targetedBitmap.getPixels(
                        pixels,
                        0,
                        targetWidth,
                        0,
                        0,
                        targetWidth,
                        targetHeight,
                    )
                    val fallbackRegion = MixedPolarityRegions.detect(
                        pixels = pixels,
                        width = targetWidth,
                        height = targetHeight,
                        maxRegions = 1,
                        cancellationCheck = {
                            ensureLatestExtraction(generation, jobContext)
                        },
                    ).firstOrNull()
                    if (fallbackRegion != null) {
                        val canonical = MixedPolarityRegions.canonicalize(
                            sourcePixels = pixels,
                            sourceWidth = targetWidth,
                            sourceHeight = targetHeight,
                            region = fallbackRegion,
                            cancellationCheck = {
                                ensureLatestExtraction(generation, jobContext)
                            },
                        )
                        val fallbackBitmap = Bitmap.createBitmap(
                            canonical.pixels,
                            canonical.width,
                            canonical.height,
                            Bitmap.Config.ARGB_8888,
                        )
                        try {
                            val fallbackTransform = RecognitionTransform(
                                recognitionScaleX = 1f,
                                recognitionScaleY = 1f,
                                border = canonical.border.toFloat(),
                                processedOffsetX = clipped.left * scale + fallbackRegion.left,
                                processedOffsetY = clipped.top * scale + fallbackRegion.top,
                                processedScale = scale,
                                sourceWidth = bitmap.width,
                                sourceHeight = bitmap.height,
                            )
                            recognizeWords(
                                api = api,
                                image = fallbackBitmap,
                                density = density,
                                jobContext = jobContext,
                                transform = fallbackTransform,
                                canonicalPolarity = true,
                                generation = generation,
                                pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK,
                            ).forEach { candidate ->
                                words += RankedOcrWord(
                                    word = candidate.word,
                                    confidence = candidate.confidence,
                                    pass = OcrPass.MIXED_POLARITY,
                                    passIdentity = 101,
                                    lineIdentity = (101L shl 32) or
                                        (candidate.textLine.toLong() and 0xFFFFFFFFL),
                                )
                            }
                        } finally {
                            fallbackBitmap.recycle()
                        }
                    }
                }
                ensureLatestExtraction(generation, jobContext)
                groupWordsIntoNodes(words, density)
            } finally {
                targetedBitmap.recycle()
                if (engineResetRequested.getAndSet(false)) {
                    discardCachedEngine(api)
                }
            }
        }
    }

    /**
     * Extracts text from a bitmap using a cached Tesseract instance for massive speed improvements.
     * Also detects URLs, emails, phone numbers, and QR codes natively in the same pass.
     */
    suspend fun extractText(
        context: Context,
        bitmap: Bitmap,
        includeQrCodes: Boolean = true,
        onPrimaryTextNodes: (suspend (List<TextNode>) -> Unit)? = null,
    ): ExtractionResult = withContext(recognitionDispatcher) {
        require(!bitmap.isRecycled) { "Cannot run OCR on a recycled bitmap" }
        val generation = beginLatestExtraction()

        val appContext = context.applicationContext
        val dataPath = withContext(Dispatchers.IO) { prepareTessData(appContext) }
        // Use automatic language detection
        val lang = getOcrLanguage(appContext)
        val density = appContext.resources.displayMetrics.density
        val jobContext = currentCoroutineContext()

        // Run QR scanning in parallel with OCR instead of sequentially.
        // This saves 500ms-2s since both are CPU-heavy and independent.
        val qrDeferred: kotlinx.coroutines.Deferred<List<com.akslabs.circletosearch.utils.QrResultWithBounds>>? = if (includeQrCodes) {
            async { QrScanner.scanBitmapAll(bitmap).lastOrNull() ?: emptyList() }
        } else {
            null
        }

        val allWordsWithSource = ocrMutex.withLock {
            ensureLatestExtraction(generation, jobContext)
            if (cachedWordsBitmap.get() === bitmap && cachedWordsLanguage == lang) {
                return@withLock cachedWords.orEmpty()
            }
            val api = ensureTessApi(dataPath, lang)
                ?: return@withLock emptyList<RankedOcrWord>()

            // Preserve RGB for the primary pass. A bounded canonical fallback below handles
            // light text inside dark/low-contrast regions without inverting the whole screen.
            val maxDim = Math.max(bitmap.width, bitmap.height).toFloat()
            val targetMax = 2560f
            val scaleFactor = Math.min(1f, targetMax / maxDim)
            
            val scaledWidth = (bitmap.width * scaleFactor).toInt().coerceAtLeast(1)
            val scaledHeight = (bitmap.height * scaleFactor).toInt().coerceAtLeast(1)
            val canUseSourceDirectly = scaledWidth == bitmap.width &&
                scaledHeight == bitmap.height &&
                bitmap.config == Bitmap.Config.ARGB_8888
            val processedBitmap = if (canUseSourceDirectly) {
                bitmap
            } else {
                Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888).also { target ->
                    val canvas = android.graphics.Canvas(target)
                    val source = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
                    val destination = android.graphics.Rect(0, 0, scaledWidth, scaledHeight)
                    val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                    canvas.drawBitmap(bitmap, source, destination, paint)
                }
            }
            val extractedWords = mutableListOf<RankedOcrWord>()
            val startedAt = android.os.SystemClock.elapsedRealtime()
            try {
                val primaryTransform = RecognitionTransform(
                    recognitionScaleX = 1f,
                    recognitionScaleY = 1f,
                    border = 0f,
                    processedOffsetX = 0f,
                    processedOffsetY = 0f,
                    processedScale = scaleFactor,
                    sourceWidth = bitmap.width,
                    sourceHeight = bitmap.height,
                )
                recognizeWords(
                    api = api,
                    image = processedBitmap,
                    density = density,
                    jobContext = jobContext,
                    transform = primaryTransform,
                    canonicalPolarity = false,
                    generation = generation,
                ).forEach { candidate ->
                    extractedWords += RankedOcrWord(
                        word = candidate.word,
                        confidence = candidate.confidence,
                        pass = OcrPass.PRIMARY,
                        passIdentity = 0,
                        lineIdentity = candidate.textLine.toLong(),
                    )
                }

                if (onPrimaryTextNodes != null) {
                    ensureLatestExtraction(generation, jobContext)
                    val primaryNodes = if (extractedWords.isEmpty()) {
                        emptyList()
                    } else {
                        groupWordsIntoNodes(
                            allWordsWithSource = extractedWords,
                            density = density,
                        )
                    }
                    Log.d(
                        TAG,
                        "Primary OCR ready in " +
                            "${android.os.SystemClock.elapsedRealtime() - startedAt} ms; " +
                            "nodes=${primaryNodes.size}",
                    )
                    onPrimaryTextNodes(primaryNodes)
                }

                ensureLatestExtraction(generation, jobContext)
                val processedPixels = IntArray(scaledWidth * scaledHeight)
                processedBitmap.getPixels(
                    processedPixels,
                    0,
                    scaledWidth,
                    0,
                    0,
                    scaledWidth,
                    scaledHeight,
                )
                val primaryQuality = assessPrimaryOcrQuality(
                    words = extractedWords,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                )
                val detectedSkewDegrees = if (shouldRunGlobalDeskew(primaryQuality)) {
                    OcrSkewEstimator.estimateDegrees(
                        pixels = processedPixels,
                        width = scaledWidth,
                        height = scaledHeight,
                        cancellationCheck = {
                            ensureLatestExtraction(generation, jobContext)
                        },
                    )
                } else {
                    null
                }
                if (detectedSkewDegrees != null) {
                    ensureLatestExtraction(generation, jobContext)
                    var deskewSourceBitmap: Bitmap? = null
                    var deskewedBitmap: Bitmap? = null
                    try {
                        val correctionDegrees = -detectedSkewDegrees
                        val absoluteRadians =
                            Math.toRadians(abs(correctionDegrees).toDouble())
                        val absoluteCosine = cos(absoluteRadians)
                        val absoluteSine = sin(absoluteRadians)
                        val expandedWidthAtFullScale =
                            scaledWidth * absoluteCosine + scaledHeight * absoluteSine
                        val expandedHeightAtFullScale =
                            scaledWidth * absoluteSine + scaledHeight * absoluteCosine
                        val deskewInputScale = minOf(
                            1f,
                            sqrt(
                                3_000_000.0 /
                                    (expandedWidthAtFullScale * expandedHeightAtFullScale)
                            ).toFloat(),
                        )
                        val deskewInput = if (deskewInputScale < 0.995f) {
                            Bitmap.createScaledBitmap(
                                processedBitmap,
                                (scaledWidth * deskewInputScale).roundToInt().coerceAtLeast(1),
                                (scaledHeight * deskewInputScale).roundToInt().coerceAtLeast(1),
                                true,
                            ).also { deskewSourceBitmap = it }
                        } else {
                            processedBitmap
                        }
                        val appliedDeskewScale = if (deskewInput === processedBitmap) {
                            1f
                        } else {
                            minOf(
                                deskewInput.width.toFloat() / scaledWidth,
                                deskewInput.height.toFloat() / scaledHeight,
                            )
                        }
                        val rotatedWidth = ceil(
                            deskewInput.width * absoluteCosine +
                                deskewInput.height * absoluteSine
                        ).toInt().coerceAtLeast(1)
                        val rotatedHeight = ceil(
                            deskewInput.width * absoluteSine +
                                deskewInput.height * absoluteCosine
                        ).toInt().coerceAtLeast(1)
                        val rotationOffsetX = (rotatedWidth - deskewInput.width) / 2f
                        val rotationOffsetY = (rotatedHeight - deskewInput.height) / 2f
                        val targetBitmap = Bitmap.createBitmap(
                            rotatedWidth,
                            rotatedHeight,
                            Bitmap.Config.ARGB_8888,
                        )
                        deskewedBitmap = targetBitmap
                        val centerX = deskewInput.width / 2f
                        val centerY = deskewInput.height / 2f
                        val rotation = Matrix().apply {
                            setRotate(correctionDegrees, centerX, centerY)
                            postTranslate(rotationOffsetX, rotationOffsetY)
                        }
                        val deskewCanvas = Canvas(targetBitmap)
                        deskewCanvas.drawColor(Color.WHITE)
                        val deskewPaint = Paint(
                            Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG,
                        )
                        deskewCanvas.drawBitmap(deskewInput, rotation, deskewPaint)

                        val deskewTransform = RecognitionTransform(
                            recognitionScaleX = 1f,
                            recognitionScaleY = 1f,
                            border = 0f,
                            processedOffsetX = 0f,
                            processedOffsetY = 0f,
                            processedScale = scaleFactor * appliedDeskewScale,
                            sourceWidth = bitmap.width,
                            sourceHeight = bitmap.height,
                            rotationDegrees = correctionDegrees,
                            rotationCenterX = centerX,
                            rotationCenterY = centerY,
                            rotationCanvasOffsetX = rotationOffsetX,
                            rotationCanvasOffsetY = rotationOffsetY,
                        )
                        recognizeWords(
                            api = api,
                            image = targetBitmap,
                            density = density,
                            jobContext = jobContext,
                            transform = deskewTransform,
                            canonicalPolarity = false,
                            generation = generation,
                        ).forEach { candidate ->
                            extractedWords += RankedOcrWord(
                                word = candidate.word,
                                confidence = candidate.confidence,
                                pass = OcrPass.DESKEW,
                                passIdentity = 1,
                                lineIdentity = (1L shl 32) or
                                    (candidate.textLine.toLong() and 0xFFFFFFFFL),
                            )
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(
                            TAG,
                            "Deskew OCR pass failed for angle=$detectedSkewDegrees",
                            error,
                        )
                    } finally {
                        deskewedBitmap?.takeUnless { it.isRecycled }?.recycle()
                        deskewSourceBitmap?.takeUnless { it.isRecycled }?.recycle()
                    }
                }

                val fallbackRegions = MixedPolarityRegions.detect(
                    pixels = processedPixels,
                    width = scaledWidth,
                    height = scaledHeight,
                    maxRegions = 2,
                    cancellationCheck = {
                        ensureLatestExtraction(generation, jobContext)
                    },
                ).filter { region ->
                    shouldRunFallbackRegion(region, extractedWords, scaleFactor)
                }

                fallbackRegions.forEachIndexed { fallbackIndex, region ->
                    ensureLatestExtraction(generation, jobContext)
                    var baseBitmap: Bitmap? = null
                    var fallbackBitmap: Bitmap? = null
                    try {
                        val canonical = MixedPolarityRegions.canonicalize(
                            sourcePixels = processedPixels,
                            sourceWidth = scaledWidth,
                            sourceHeight = scaledHeight,
                            region = region,
                            cancellationCheck = {
                                ensureLatestExtraction(generation, jobContext)
                            },
                        )
                        baseBitmap = Bitmap.createBitmap(
                            canonical.pixels,
                            canonical.width,
                            canonical.height,
                            Bitmap.Config.ARGB_8888,
                        )
                        val baseArea = canonical.width.toDouble() * canonical.height.toDouble()
                        val scaleByArea = sqrt(2_500_000.0 / baseArea).toFloat()
                        val scaleByDimension = 2_200f / maxOf(canonical.width, canonical.height)
                        val requestedScale = minOf(1.75f, scaleByArea, scaleByDimension)
                        val targetWidth = (canonical.width * requestedScale).roundToInt()
                            .coerceAtLeast(1)
                        val targetHeight = (canonical.height * requestedScale).roundToInt()
                            .coerceAtLeast(1)
                        fallbackBitmap = if (
                            targetWidth == canonical.width && targetHeight == canonical.height
                        ) {
                            checkNotNull(baseBitmap).also { baseBitmap = null }
                        } else {
                            Bitmap.createScaledBitmap(
                                checkNotNull(baseBitmap),
                                targetWidth,
                                targetHeight,
                                true,
                            ).also {
                                baseBitmap?.recycle()
                                baseBitmap = null
                            }
                        }

                        val fallbackTransform = RecognitionTransform(
                            recognitionScaleX = fallbackBitmap.width.toFloat() / canonical.width,
                            recognitionScaleY = fallbackBitmap.height.toFloat() / canonical.height,
                            border = canonical.border.toFloat(),
                            processedOffsetX = region.left.toFloat(),
                            processedOffsetY = region.top.toFloat(),
                            processedScale = scaleFactor,
                            sourceWidth = bitmap.width,
                            sourceHeight = bitmap.height,
                        )
                        recognizeWords(
                            api = api,
                            image = fallbackBitmap,
                            density = density,
                            jobContext = jobContext,
                            transform = fallbackTransform,
                            canonicalPolarity = true,
                            generation = generation,
                        ).forEach { candidate ->
                            val passIdentity = fallbackIndex + 2
                            extractedWords += RankedOcrWord(
                                word = candidate.word,
                                confidence = candidate.confidence,
                                pass = OcrPass.MIXED_POLARITY,
                                passIdentity = passIdentity,
                                lineIdentity = (passIdentity.toLong() shl 32) or
                                    (candidate.textLine.toLong() and 0xFFFFFFFFL),
                            )
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(TAG, "Mixed-polarity OCR fallback failed for $region", error)
                    } finally {
                        baseBitmap?.takeUnless { it.isRecycled }?.recycle()
                        fallbackBitmap?.takeUnless { it.isRecycled }?.recycle()
                    }
                }

                Log.d(
                    TAG,
                    "OCR completed in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms; " +
                        "skewDegrees=$detectedSkewDegrees; fallbackRegions=${fallbackRegions.size}",
                )
            } finally {
                try {
                    api.clear()
                } finally {
                    if (processedBitmap !== bitmap && !processedBitmap.isRecycled) {
                        processedBitmap.recycle()
                    }
                    if (engineResetRequested.getAndSet(false)) {
                        discardCachedEngine(api)
                    }
                }
            }
            ensureLatestExtraction(generation, jobContext)
            cachedWordsBitmap = WeakReference(bitmap)
            cachedWordsLanguage = lang
            cachedWords = extractedWords.toList()
            cachedWords.orEmpty()
        }
        
        // Final Merge & Line Grouping
        val textNodes = groupWordsIntoNodes(allWordsWithSource, density)

        // Await parallel QR results
        val qrCodes = qrDeferred?.await().orEmpty()
        ensureLatestExtraction(generation, jobContext)

        // Native Smart Links extraction via regex
        val smartEntities = mutableListOf<SmartEntity>()
        textNodes.forEach { node ->
            val text = node.fullText
            
            // Extract URLs
            val urlMatcher = Patterns.WEB_URL.matcher(text)
            while (urlMatcher.find()) {
                val matchText = urlMatcher.group() ?: continue
                if (matchText.length > 4) {
                    smartEntities.add(SmartEntity.Url(matchText, android.graphics.RectF(node.bounds)))
                }
            }

            // Extract Emails
            val emailMatcher = Patterns.EMAIL_ADDRESS.matcher(text)
            while (emailMatcher.find()) {
                val matchText = emailMatcher.group() ?: continue
                smartEntities.add(SmartEntity.Email(matchText, android.graphics.RectF(node.bounds)))
            }

            // Extract Phone Numbers
            val phoneMatcher = Patterns.PHONE.matcher(text)
            while (phoneMatcher.find()) {
                val matchText = phoneMatcher.group() ?: continue
                if (matchText.length >= 7) {
                    smartEntities.add(SmartEntity.Phone(matchText, android.graphics.RectF(node.bounds)))
                }
            }
        }

        qrCodes.forEach { qr ->
            qr.bounds?.let { b ->
                smartEntities.add(
                    SmartEntity.QrCode(
                        qrResult = qr.result,
                        rawText = qr.rawText,
                        bounds = b,
                        format = qr.format,
                    )
                )
            }
        }

        // Entities and TextNodes gathered

        ensureLatestExtraction(generation, jobContext)
        ExtractionResult(textNodes, smartEntities)
    }

    private fun groupWordsIntoNodes(
        allWordsWithSource: List<RankedOcrWord>,
        density: Float,
    ): List<TextNode> {
        if (allWordsWithSource.isEmpty()) return emptyList()

        val uniqueWords = mergeRankedOcrWords(allWordsWithSource)

        // Preserve Tesseract's original line identity. Geometry remains a
        // fallback for words contributed by different preprocessing passes.
        val sortedWords = uniqueWords.sortedWith(
            compareBy<RankedOcrWord> { it.word.bounds.top }.thenBy { it.word.bounds.left },
        )
        val lines = mutableListOf<MutableList<RankedOcrWord>>()
        
        for (rankedWord in sortedWords) {
            val word = rankedWord.word
            var addedToLine = false
            
            for (line in lines) {
                val isCloseAndOverlapping = line.any { rankedPeer ->
                    val w = rankedPeer.word
                    if (
                        overlapOfSmaller(word.bounds, w.bounds) >= 0.50f &&
                        ocrTextSimilarity(word.text, w.text) < 0.72f
                    ) {
                        return@any false
                    }
                    val topMax = maxOf(word.bounds.top, w.bounds.top)
                    val bottomMin = minOf(word.bounds.bottom, w.bounds.bottom)
                    val overlapHeight = maxOf(0f, bottomMin - topMax)
                    val minHeight = minOf(word.bounds.height(), w.bounds.height())
                    val verticalOverlap = overlapHeight >= (minHeight * 0.5f)
                    
                    if (verticalOverlap) {
                        val g1 = word.bounds.left - w.bounds.right
                        val g2 = w.bounds.left - word.bounds.right
                        val sameNativeLine = rankedWord.lineIdentity == rankedPeer.lineIdentity
                        val relativeGap = maxOf(word.bounds.height(), w.bounds.height()) *
                            if (sameNativeLine) 4f else 2.25f
                        val allowedGap = maxOf(24f * density, relativeGap)
                            .coerceAtMost(60f * density)
                        maxOf(g1, g2) < allowedGap
                    } else {
                        false
                    }
                }
                
                if (isCloseAndOverlapping) {
                    line.add(rankedWord)
                    addedToLine = true
                    break
                }
            }
            
            if (!addedToLine) {
                lines.add(mutableListOf(rankedWord))
            }
        }

        // 3. Horizontal Sorting & Node Construction
        val result = mutableListOf<TextNode>()
        lines.forEach { rankedLineWords ->
            val finalLineWords = rankedLineWords.map { it.word }.sortedBy { it.bounds.left }
            
            val lineText = finalLineWords.joinToString(" ") { it.text }
            val cCyr = lineText.count { it in 'А'..'я' || it == 'Ё' || it == 'ё' }
            val cLat = lineText.count { it in 'A'..'Z' || it in 'a'..'z' }
            val lineDominantCyrillic = cCyr >= cLat
            
            val cleanedWords = mutableListOf<Word>()
            val lineBounds = Rect()
            
            finalLineWords.forEachIndexed { idx, w ->
                val cleanedText = cleanWordText(w.text, lineDominantCyrillic)
                if (cleanedText.isNotBlank()) {
                    val r = Rect()
                    w.bounds.roundOut(r)
                    if (lineBounds.isEmpty) lineBounds.set(r) else lineBounds.union(r)
                    
                    cleanedWords.add(w.copy(index = cleanedWords.size, text = cleanedText))
                }
            }

            if (cleanedWords.isNotEmpty()) {
                val fullText = cleanedWords.joinToString(" ") { it.text }
                result.add(
                    TextNode(
                        id = stableTextNodeId(fullText, lineBounds),
                        fullText = fullText,
                        bounds = lineBounds,
                        words = cleanedWords
                    )
                )
            }
        }
        
        return result
    }

    private fun stableTextNodeId(text: String, bounds: Rect): String =
        buildString(text.length + 48) {
            append(text.trim())
            append('@')
            append(bounds.left)
            append(',')
            append(bounds.top)
            append(',')
            append(bounds.right)
            append(',')
            append(bounds.bottom)
        }
}
