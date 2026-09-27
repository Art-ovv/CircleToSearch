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
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.OCRResult
import com.paddle.ocr.model.OCRRunResult
import com.paddle.ocr.model.OCRTextSpan
import com.paddle.ocr.model.OCRError
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class ExtractionResult(
    val textNodes: List<TextNode>,
    val smartEntities: List<SmartEntity>,
)

/**
 * Process-wide, fully on-device PaddleOCR pipeline.
 *
 * Native inference is serialized because the cached ONNX sessions are shared. Cancellation is
 * forwarded to the individual ONNX run, while overlay/translation callers retain ownership of
 * their own result lifecycles.
 */
object PaddleOcrEngine {
    private const val TAG = "PaddleOcrEngine"
    private const val DET_MODEL_ASSET = "paddleocr/det/inference.onnx"
    private const val REC_MODEL_ASSET = "paddleocr/rec/inference.onnx"
    private const val REC_CONFIG_ASSET = "paddleocr/rec/inference.yml"

    private val engineMutex = Mutex()
    private var cachedEngine: PaddleOCR? = null
    private var cachedPackId: String? = null
    private var cachedBitmap = WeakReference<Bitmap>(null)
    private var cachedTextNodes: List<TextNode>? = null

    private val paddleConfig = PaddleOCRConfig(
        detMaxSideLimit = 2560,
        detMaxPixelCount = 2_500_000,
        detBoxThresh = 0.5f,
        detMaxCandidates = 512,
        recScoreThresh = 0.35f,
        recBatchSize = 4,
    )

    suspend fun warmUp(context: Context) {
        val appContext = context.applicationContext
        OcrLanguageManager.prepareRuntime(appContext)
        engineMutex.withLock {
            val activePack = OcrLanguageManager.getActivePack(appContext)
            ensureEngine(appContext, activePack)
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
        val engine = cachedEngine
        cachedEngine = null
        cachedPackId = null
        clearTextCache()
        if (engine != null) {
            withContext(NonCancellable + Dispatchers.IO) {
                engine.release()
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
    ): ExtractionResult = coroutineScope {
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

    /** Runs a higher-resolution OCR pass while keeping returned bounds in source-bitmap pixels. */
    suspend fun extractTextInRegion(
        context: Context,
        bitmap: Bitmap,
        sourceRegion: RectF,
    ): List<TextNode> = withContext(Dispatchers.Default) {
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

    private suspend fun recognizeFullScreen(
        context: Context,
        bitmap: Bitmap,
    ): List<TextNode> {
        val appContext = context.applicationContext
        OcrLanguageManager.prepareRuntime(appContext)
        return engineMutex.withLock {
            val activePack = OcrLanguageManager.getActivePack(appContext)
            val activePackId = activePack.id
            if (cachedPackId != null && cachedPackId != activePackId) {
                detachAndReleaseEngine()
            }
            if (cachedBitmap.get() === bitmap && cachedPackId == activePackId && cachedEngine != null) {
                cachedTextNodes?.let { return@withLock it }
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
            cachedBitmap = WeakReference(bitmap)
            cachedTextNodes = nodes
            nodes
        }
    }

    /** Must be called while [engineMutex] is held. */
    private suspend fun ensureEngine(context: Context, activePack: OcrLanguagePack): PaddleOCR {
        if (cachedEngine != null && cachedPackId == activePack.id) {
            return cachedEngine!!
        }
        if (cachedEngine != null) {
            detachAndReleaseEngine()
        }
        check(OpenCVUtils.init(context)) { "Unable to initialize OpenCV for PaddleOCR" }
        val engine = if (activePack.isBundled) {
            PaddleOCR.create(
                context = context,
                config = paddleConfig,
                engineConfig = EngineConfig(numThreads = 4),
                detModelAssetPath = DET_MODEL_ASSET,
                recModelAssetPath = REC_MODEL_ASSET,
                recConfigAssetPath = REC_CONFIG_ASSET,
            )
        } else {
            val baseDir = context.noBackupFilesDir ?: context.filesDir
            val storage = OcrLanguageStorage(java.io.File(baseDir, "ocr_models"))
            val installedFiles = storage.getInstalledModelFiles(activePack.id)
                ?: throw java.io.FileNotFoundException("Installed model files missing for pack ${activePack.displayName}")

            PaddleOCR.create(
                context = context,
                config = paddleConfig,
                engineConfig = EngineConfig(numThreads = 4),
                detModelAssetPath = DET_MODEL_ASSET,
                recModelFile = installedFiles.modelFile,
                recConfigFile = installedFiles.configFile,
            )
        }
        cachedEngine = engine
        cachedPackId = activePack.id
        return engine
    }

    /** Must be called while [engineMutex] is held. */
    private suspend fun recognizeLocked(
        context: Context,
        bitmap: Bitmap,
        activePack: OcrLanguagePack,
    ): OCRRunResult {
        val engine = ensureEngine(context, activePack)
        return try {
            engine.recognize(bitmap)
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

    private fun clearTextCache() {
        cachedBitmap.clear()
        cachedTextNodes = null
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
