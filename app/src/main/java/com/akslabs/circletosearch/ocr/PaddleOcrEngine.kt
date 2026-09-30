package com.akslabs.circletosearch.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.util.Patterns
import com.akslabs.circletosearch.ui.components.SmartEntity
import com.akslabs.circletosearch.ui.components.TextNode
import com.akslabs.circletosearch.ui.components.Word
import com.akslabs.circletosearch.utils.QrScanner
import com.paddle.ocr.model.OCRResult
import com.paddle.ocr.model.OCRRunResult
import com.paddle.ocr.model.OCRTextSpan
import com.paddle.ocr.model.OCRError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class ExtractionResult(
    val textNodes: List<TextNode>,
    val smartEntities: List<SmartEntity>,
)

/**
 * Main-process OCR facade. Native inference runs only in the private :ocr process.
 *
 * Native inference is serialized because the cached ONNX sessions are shared. Cancellation is
 * forwarded to the individual ONNX run, while overlay/translation callers retain ownership of
 * their own result lifecycles.
 */
object PaddleOcrEngine {
    private const val TAG = "PaddleOcrEngine"
    const val DEFAULT_IDLE_TIMEOUT_MS = 15_000L

    interface OcrSessionLease : AutoCloseable {
        override fun close()
    }

    private val engineMutex = Mutex()
    // The worker connection outlives individual UI owners, but only for the bounded idle window.
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val textCache = OcrTextCache<Bitmap, List<TextNode>>()
    private var processClient: OcrProcessClient? = null
    private var cachedPackId: String? = null

    private val lifecycleCoordinator = OcrLifecycleCoordinator(
        scope = engineScope,
        idleTimeoutMs = DEFAULT_IDLE_TIMEOUT_MS,
        releaseMutex = engineMutex,
        releaseImmediatelyOnUiHidden = false,
        releaseAction = { detachAndReleaseEngine() },
        onReleaseFailure = { Log.e(TAG, "Unable to release idle OCR engine", it) },
    )

    fun acquireSession(): OcrSessionLease = lifecycleCoordinator.acquireSession()

    fun requestReleaseIfIdle() {
        lifecycleCoordinator.requestReleaseIfIdle()
    }

    fun onTrimMemory(level: Int) {
        lifecycleCoordinator.onTrimMemory(level)
    }

    fun clearTextCache(bitmap: Bitmap) {
        textCache.clear(bitmap)
    }

    private suspend fun <T> withInFlightOperation(block: suspend () -> T): T =
        lifecycleCoordinator.withInFlightOperation(block)

    suspend fun warmUp(context: Context): Unit = withInFlightOperation {
        val appContext = context.applicationContext
        OcrLanguageManager.prepareRuntime(appContext)
        engineMutex.withLock {
            val activePack = OcrLanguageManager.getActivePack(appContext)
            client(appContext).warmUp(activePack.id)
            cachedPackId = activePack.id
        }
        Log.d(TAG, "PaddleOCR warm-up complete")
    }

    /**
     * Executes a state mutation under [engineMutex] on [Dispatchers.IO], ensuring inference
     * and pack mutations cannot interleave.
     */
    suspend fun <T> executeMutation(block: suspend () -> T): T {
        return engineMutex.withLock {
            withContext(Dispatchers.IO) {
                block()
            }
        }
    }

    /**
     * Detaches and releases the currently cached engine session.
     * Guaranteed to release using NonCancellable + Dispatchers.IO.
     *
     * MUST be called only while [engineMutex] is held (e.g. inside [executeMutation] or private engine lock blocks).
     */
    internal suspend fun detachAndReleaseEngine() {
        check(engineMutex.isLocked) { "detachAndReleaseEngine requires engineMutex to be held" }
        val engine = processClient
        processClient = null
        cachedPackId = null
        textCache.clear()
        if (engine != null) {
            withContext(NonCancellable + Dispatchers.IO) {
                engine.close()
            }
        }
    }

    suspend fun releaseCachedEngine() {
        engineMutex.withLock {
            detachAndReleaseEngine()
        }
    }

    suspend fun extractText(
        context: Context,
        bitmap: Bitmap,
        includeQrCodes: Boolean = true,
    ): ExtractionResult = withInFlightOperation {
        val cacheGeneration = textCache.begin(bitmap)
        coroutineScope {
            require(!bitmap.isRecycled) { "Cannot run OCR on a recycled bitmap" }
            require(bitmap.width > 0 && bitmap.height > 0) { "Cannot run OCR on an empty bitmap" }

            val qrDeferred = if (includeQrCodes) {
                async(Dispatchers.Default) {
                    QrScanner.scanBitmapAll(bitmap).lastOrNull().orEmpty()
                }
            } else {
                null
            }

            val textNodes = recognizeFullScreen(
                context = context.applicationContext,
                bitmap = bitmap,
                cacheGeneration = cacheGeneration,
            )
            val textEntitiesDeferred = async(Dispatchers.Default) {
                extractSmartEntities(textNodes)
            }
            val qrCodes = qrDeferred?.await().orEmpty()
            ExtractionResult(
                textNodes = textNodes,
                smartEntities = textEntitiesDeferred.await() + qrCodes.mapNotNull { qr ->
                    qr.bounds?.let { bounds ->
                        SmartEntity.QrCode(
                            qrResult = qr.result,
                            rawText = qr.rawText,
                            bounds = bounds,
                            format = qr.format,
                        )
                    }
                },
            )
        }
    }

    /** Runs a higher-resolution OCR pass while keeping returned bounds in source-bitmap pixels. */
    suspend fun extractTextInRegion(
        context: Context,
        bitmap: Bitmap,
        sourceRegion: RectF,
    ): List<TextNode> = withInFlightOperation {
        withContext(Dispatchers.Default) {
            require(!bitmap.isRecycled) { "Cannot run OCR on a recycled bitmap" }
            if (!sourceRegion.isFinite()) return@withContext emptyList()

            val appContext = context.applicationContext
            OcrLanguageManager.prepareRuntime(appContext)

            val clipped = Rect(
                floor(sourceRegion.left.toDouble()).toInt().coerceIn(0, bitmap.width),
                floor(sourceRegion.top.toDouble()).toInt().coerceIn(0, bitmap.height),
                ceil(sourceRegion.right.toDouble()).toInt().coerceIn(0, bitmap.width),
                ceil(sourceRegion.bottom.toDouble()).toInt().coerceIn(0, bitmap.height),
            )
            if (clipped.width() < 4 || clipped.height() < 4) return@withContext emptyList()

            val sourceArea = clipped.width().toDouble() * clipped.height().toDouble()
            val scaleByArea = sqrt(2_500_000.0 / sourceArea).toFloat()
            val scaleByDimension = 2400f / maxOf(clipped.width(), clipped.height())
            val scale = minOf(2.25f, scaleByArea, scaleByDimension)
            val targetWidth = (clipped.width() * scale).roundToInt().coerceAtLeast(1)
            val targetHeight = (clipped.height() * scale).roundToInt().coerceAtLeast(1)
            val targetedBitmap = Bitmap.createBitmap(
                targetWidth,
                targetHeight,
                Bitmap.Config.ARGB_8888,
            )

            try {
                Canvas(targetedBitmap).drawBitmap(
                    bitmap,
                    clipped,
                    Rect(0, 0, targetWidth, targetHeight),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                )
                engineMutex.withLock {
                    val activePack = OcrLanguageManager.getActivePack(appContext)
                    val activePackId = activePack.id
                    if (cachedPackId != null && cachedPackId != activePackId) {
                        detachAndReleaseEngine()
                    }
                    val runResult = recognizeLocked(appContext, targetedBitmap, activePack)
                    logTiming("region", runResult.totalTimeMs, runResult.lineCount)
                    mapResultsToTextNodes(
                        results = runResult.results,
                        sourceWidth = bitmap.width,
                        sourceHeight = bitmap.height,
                        scaleX = 1f / scale,
                        scaleY = 1f / scale,
                        offsetX = clipped.left.toFloat(),
                        offsetY = clipped.top.toFloat(),
                    )
                }
            } finally {
                targetedBitmap.recycle()
            }
        }
    }

    private suspend fun recognizeFullScreen(
        context: Context,
        bitmap: Bitmap,
        cacheGeneration: Long,
    ): List<TextNode> {
        val appContext = context.applicationContext
        OcrLanguageManager.prepareRuntime(appContext)
        return engineMutex.withLock {
            val activePack = OcrLanguageManager.getActivePack(appContext)
            val activePackId = activePack.id
            if (cachedPackId != null && cachedPackId != activePackId) {
                detachAndReleaseEngine()
            }
            if (cachedPackId == activePackId && processClient != null) {
                textCache.get(bitmap, cacheGeneration)?.let { return@withLock it }
            }

            val runResult = recognizeLocked(appContext, bitmap, activePack)
            logTiming("full", runResult.totalTimeMs, runResult.lineCount)
            val nodes = withContext(Dispatchers.Default) {
                mapResultsToTextNodes(
                    results = runResult.results,
                    sourceWidth = bitmap.width,
                    sourceHeight = bitmap.height,
                )
            }
            textCache.putIfCurrent(bitmap, cacheGeneration, nodes)
            nodes
        }
    }

    private fun client(context: Context): OcrProcessClient =
        processClient ?: OcrProcessClient(context).also { processClient = it }

    /** Must be called while [engineMutex] is held. */
    private suspend fun recognizeLocked(
        context: Context,
        bitmap: Bitmap,
        activePack: OcrLanguagePack,
    ): OCRRunResult {
        return try {
            client(context).recognize(activePack.id, bitmap).also { cachedPackId = activePack.id }
        } catch (error: CancellationException) {
            throw error
        } catch (error: OCRError.InputTooComplex) {
            // A fragmented detector mask is an input-level rejection, not a poisoned session.
            throw error
        } catch (error: Exception) {
            // An ORT/OpenCV failure may leave a native session unusable. Drop it so a later user
            // request gets one clean reload instead of inheriting the same broken session.
            try {
                detachAndReleaseEngine()
            } catch (releaseError: Exception) {
                error.addSuppressed(releaseError)
            }
            throw error
        }
    }

    private fun RectF.isFinite(): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()

    private fun logTiming(scope: String, elapsedMs: Long, lineCount: Int) {
        Log.d(TAG, "PaddleOCR $scope pass: ${elapsedMs}ms, lines=$lineCount")
    }

    internal fun mapResultsToTextNodes(
        results: List<OCRResult>,
        sourceWidth: Int,
        sourceHeight: Int,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ): List<TextNode> {
        if (sourceWidth <= 0 || sourceHeight <= 0) return emptyList()

        return results.mapNotNull { result ->
            val rawText = result.text
            val trimStart = rawText.indexOfFirst { !it.isWhitespace() }
            if (trimStart < 0) return@mapNotNull null
            val trimEndExclusive = rawText.indexOfLast { !it.isWhitespace() } + 1
            val fullText = rawText.substring(trimStart, trimEndExclusive)

            val points = result.box.points.map { point ->
                PointF(
                    (point.x * scaleX + offsetX).coerceIn(0f, sourceWidth.toFloat()),
                    (point.y * scaleY + offsetY).coerceIn(0f, sourceHeight.toFloat()),
                )
            }
            if (points.size < 4) return@mapNotNull null
            val lineBoundsF = points.bounds()
            if (lineBoundsF.width() < 2f || lineBoundsF.height() < 2f) return@mapNotNull null
            // Preserve the crop's actual min-area rectangle and orientation. Apply clipping
            // after projection so off-screen corners do not distort individual word positions.
            val recognitionPoints = result.recognitionBox.points.map { point ->
                PointF(point.x * scaleX + offsetX, point.y * scaleY + offsetY)
            }

            val matches = Regex("\\S+").findAll(fullText).toList()
            if (matches.isEmpty()) return@mapNotNull null
            val words = matches.mapIndexed { index, match ->
                val rawMatchStart = trimStart + match.range.first
                val rawMatchEnd = trimStart + match.range.last + 1
                val (startFraction, endFraction) = resolveWordFractions(
                    fullText = fullText,
                    matchStart = match.range.first,
                    matchEnd = match.range.last + 1,
                    rawMatchStart = rawMatchStart,
                    rawMatchEnd = rawMatchEnd,
                    spans = result.textSpans,
                )
                Word(
                    text = match.value,
                    index = index,
                    startIndex = match.range.first,
                    endIndex = match.range.last + 1,
                    bounds = wordBounds(recognitionPoints, startFraction, endFraction).apply {
                        left = left.coerceIn(0f, sourceWidth.toFloat())
                        right = right.coerceIn(0f, sourceWidth.toFloat())
                        top = top.coerceIn(0f, sourceHeight.toFloat())
                        bottom = bottom.coerceIn(0f, sourceHeight.toFloat())
                    },
                )
            }

            val lineBounds = Rect(
                floor(lineBoundsF.left.toDouble()).toInt().coerceIn(0, sourceWidth),
                floor(lineBoundsF.top.toDouble()).toInt().coerceIn(0, sourceHeight),
                ceil(lineBoundsF.right.toDouble()).toInt().coerceIn(0, sourceWidth),
                ceil(lineBoundsF.bottom.toDouble()).toInt().coerceIn(0, sourceHeight),
            )
            if (lineBounds.isEmpty) return@mapNotNull null
            TextNode(
                id = stableTextNodeId(fullText, lineBounds),
                fullText = fullText,
                bounds = lineBounds,
                words = words,
            )
        }
    }

    internal fun resolveWordFractions(
        fullText: String,
        matchStart: Int,
        matchEnd: Int,
        rawMatchStart: Int,
        rawMatchEnd: Int,
        spans: List<OCRTextSpan>,
    ): Pair<Float, Float> {
        val alignedSpans = spans.filter { span ->
            span.startIndex < rawMatchEnd &&
                span.endIndex > rawMatchStart &&
                span.startFraction.isFinite() &&
                span.endFraction.isFinite() &&
                span.endFraction > span.startFraction
        }
        if (alignedSpans.isNotEmpty()) {
            val start = alignedSpans.minOf { it.startFraction }.coerceIn(0f, 1f)
            val end = alignedSpans.maxOf { it.endFraction }.coerceIn(start, 1f)
            if (end > start) return start to end
        }

        val codePointCount = fullText.codePointCount(0, fullText.length).coerceAtLeast(1)
        val startCodePoints = fullText.codePointCount(0, matchStart)
        val endCodePoints = fullText.codePointCount(0, matchEnd)
        return startCodePoints.toFloat() / codePointCount to
            endCodePoints.toFloat() / codePointCount
    }

    private fun wordBounds(points: List<PointF>, start: Float, end: Float): RectF {
        val wordPoints = listOf(
            interpolate(points[0], points[1], start),
            interpolate(points[0], points[1], end),
            interpolate(points[3], points[2], end),
            interpolate(points[3], points[2], start),
        )
        return wordPoints.bounds()
    }

    private fun interpolate(start: PointF, end: PointF, fraction: Float): PointF = PointF(
        start.x + (end.x - start.x) * fraction,
        start.y + (end.y - start.y) * fraction,
    )

    private fun List<PointF>.bounds(): RectF = RectF(
        minOf { it.x },
        minOf { it.y },
        maxOf { it.x },
        maxOf { it.y },
    )

    private fun stableTextNodeId(text: String, bounds: Rect): String = buildString(text.length + 48) {
        append(text)
        append('@')
        append(bounds.left)
        append(',')
        append(bounds.top)
        append(',')
        append(bounds.right)
        append(',')
        append(bounds.bottom)
    }

    internal fun extractSmartEntities(textNodes: List<TextNode>): List<SmartEntity> {
        val entities = mutableListOf<SmartEntity>()
        textNodes.forEach { node ->
            val bounds = RectF(node.bounds)
            Patterns.WEB_URL.matcher(node.fullText).forEachMatch { value ->
                if (value.length > 4) entities += SmartEntity.Url(value, RectF(bounds))
            }
            Patterns.EMAIL_ADDRESS.matcher(node.fullText).forEachMatch { value ->
                entities += SmartEntity.Email(value, RectF(bounds))
            }
            Patterns.PHONE.matcher(node.fullText).forEachMatch { value ->
                if (value.length >= 7) entities += SmartEntity.Phone(value, RectF(bounds))
            }
        }
        return entities.distinctBy { Triple(it.typeName, it.text, it.bounds) }
    }

    private inline fun java.util.regex.Matcher.forEachMatch(block: (String) -> Unit) {
        while (find()) {
            group()?.let(block)
        }
    }
}
