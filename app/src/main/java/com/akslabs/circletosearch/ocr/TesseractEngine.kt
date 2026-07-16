package com.akslabs.circletosearch.ocr

import android.content.Context
import android.graphics.Bitmap
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
import java.util.UUID
import kotlin.math.roundToInt
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

internal fun filterOcrCandidates(
    candidates: List<OcrCandidate>,
    density: Float,
): List<Word> = candidates.mapNotNull { candidate ->
    val text = candidate.word.text.trim()
    val alphanumericCount = text.count(Char::isLetterOrDigit)
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
        return@mapNotNull candidate.word.takeIf {
            candidate.confidence >= 55f || (hasTextPeer && candidate.confidence >= 45f)
        }
    }

    when {
        hasTextPeer && candidate.confidence >= 50f -> candidate.word
        candidate.confidence >= 88f -> candidate.word
        else -> null
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
object TesseractEngine {
    private const val TAG = "TesseractEngine"

    // Cache Tesseract instance to avoid loading 30MB+ dictionaries from disk on every scan
    private var cachedTessApi: TessBaseAPI? = null
    private var cachedLang: String? = null
    private var cachedWordsBitmap = WeakReference<Bitmap>(null)
    private var cachedWordsLanguage: String? = null
    private var cachedWords: List<Pair<Int, Word>>? = null
    private val ocrMutex = Mutex()
    private val recognitionDispatcher = Dispatchers.Default.limitedParallelism(2)
    private val preparerLock = Any()
    private var dataPreparer: TessDataPreparer? = null

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
        ocrMutex.withLock {
            cachedTessApi?.recycle()
            cachedTessApi = null
            cachedLang = null
            clearWordCache()
        }
    }

    /** Must be called while [ocrMutex] is held. */
    private fun clearWordCache() {
        cachedWordsBitmap.clear()
        cachedWordsLanguage = null
        cachedWords = null
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

    private data class RecognitionTransform(
        val recognitionScaleX: Float,
        val recognitionScaleY: Float,
        val border: Float,
        val processedOffsetX: Float,
        val processedOffsetY: Float,
        val processedScale: Float,
        val sourceWidth: Int,
        val sourceHeight: Int,
    ) {
        fun map(rect: Rect): RectF = RectF(
            ((rect.left / recognitionScaleX - border + processedOffsetX) / processedScale)
                .coerceIn(0f, sourceWidth.toFloat()),
            ((rect.top / recognitionScaleY - border + processedOffsetY) / processedScale)
                .coerceIn(0f, sourceHeight.toFloat()),
            ((rect.right / recognitionScaleX - border + processedOffsetX) / processedScale)
                .coerceIn(0f, sourceWidth.toFloat()),
            ((rect.bottom / recognitionScaleY - border + processedOffsetY) / processedScale)
                .coerceIn(0f, sourceHeight.toFloat()),
        )
    }

    private fun recognizeWords(
        api: TessBaseAPI,
        image: Bitmap,
        density: Float,
        jobContext: kotlin.coroutines.CoroutineContext,
        transform: RecognitionTransform,
        canonicalPolarity: Boolean,
    ): List<Word> {
        jobContext.ensureActive()
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
        if (!api.setVariable("thresholding_method", "2")) {
            Log.w(TAG, "Sauvola thresholding is unavailable; using Tesseract default")
        }
        api.setVariable("invert_threshold", if (canonicalPolarity) "0.0" else "0.7")

        return try {
            api.setImage(image)
            api.getUTF8Text()
            jobContext.ensureActive()

            val iterator = api.resultIterator ?: return emptyList()
            try {
                val candidates = mutableListOf<OcrCandidate>()
                var iteration = 0
                var textLine = -1
                iterator.begin()
                do {
                    if (iteration++ % 32 == 0) jobContext.ensureActive()
                    if (
                        textLine == -1 ||
                        iterator.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                    ) {
                        textLine++
                    }
                    val wordText = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    if (wordText.isNullOrBlank()) continue

                    val confidence = iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    if (confidence < 45f) continue

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
                filterOcrCandidates(candidates, density)
            } finally {
                iterator.delete()
            }
        } finally {
            api.clear()
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

    private fun cleanWordText(text: String, lineDominantCyrillic: Boolean): String {
        // Remove frequent OCR artifacts (including noise like "<")
        var t = text.replace("<", "").replace(">", "").trim()
        if (t.isEmpty()) return ""

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

            withContext(recognitionDispatcher) {
                ocrMutex.withLock {
                    cachedTessApi?.recycle()
                    cachedTessApi = null
                    cachedLang = null
                    clearWordCache()
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
     * Extracts text from a bitmap using a cached Tesseract instance for massive speed improvements.
     * Also detects URLs, emails, phone numbers, and QR codes natively in the same pass.
     */
    suspend fun extractText(
        context: Context,
        bitmap: Bitmap,
        includeQrCodes: Boolean = true,
    ): ExtractionResult = withContext(recognitionDispatcher) {
        require(!bitmap.isRecycled) { "Cannot run OCR on a recycled bitmap" }

        val appContext = context.applicationContext
        val dataPath = withContext(Dispatchers.IO) { prepareTessData(appContext) }
        // Use automatic language detection
        val lang = getOcrLanguage(appContext)
        val screenWidth = bitmap.width
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
            jobContext.ensureActive()
            if (cachedWordsBitmap.get() === bitmap && cachedWordsLanguage == lang) {
                return@withLock cachedWords.orEmpty()
            }
            val api = ensureTessApi(dataPath, lang)
                ?: return@withLock emptyList<Pair<Int, Word>>()

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
            val extractedWords = mutableListOf<Pair<Int, Word>>()
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
                ).forEach { word -> extractedWords += 0 to word }

                jobContext.ensureActive()
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
                val fallbackRegions = MixedPolarityRegions.detect(
                    pixels = processedPixels,
                    width = scaledWidth,
                    height = scaledHeight,
                    maxRegions = 2,
                )

                fallbackRegions.forEachIndexed { index, region ->
                    jobContext.ensureActive()
                    var baseBitmap: Bitmap? = null
                    var fallbackBitmap: Bitmap? = null
                    try {
                        val canonical = MixedPolarityRegions.canonicalize(
                            sourcePixels = processedPixels,
                            sourceWidth = scaledWidth,
                            sourceHeight = scaledHeight,
                            region = region,
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
                            .coerceAtLeast(0.85f)
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
                        ).forEach { word -> extractedWords += (index + 1) to word }
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
                        "fallbackRegions=${fallbackRegions.size}",
                )
            } finally {
                try {
                    api.clear()
                } finally {
                    if (processedBitmap !== bitmap && !processedBitmap.isRecycled) {
                        processedBitmap.recycle()
                    }
                }
            }
            cachedWordsBitmap = WeakReference(bitmap)
            cachedWordsLanguage = lang
            cachedWords = extractedWords.toList()
            cachedWords.orEmpty()
        }
        
        // Final Merge & Line Grouping
        val textNodes = groupWordsIntoNodes(allWordsWithSource, screenWidth, density)

        // Await parallel QR results
        val qrCodes = qrDeferred?.await().orEmpty()

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
                smartEntities.add(SmartEntity.QrCode(qr.result, qr.rawText, b))
            }
        }

        // Entities and TextNodes gathered

        ExtractionResult(textNodes, smartEntities)
    }

    private fun groupWordsIntoNodes(allWordsWithSource: List<Pair<Int, Word>>, screenWidth: Int, density: Float): List<TextNode> {
        if (allWordsWithSource.isEmpty()) return emptyList()

        // 1. Spatial Deduplication
        // Prefer canonical mixed-polarity region results (indices > 0) over the
        // standard full-screen pass when their word boxes overlap.
        val uniqueWords = mutableListOf<Word>()
        val sortedByPreference = allWordsWithSource.sortedWith(compareByDescending<Pair<Int, Word>> { it.first }.thenBy { it.second.bounds.width() * it.second.bounds.height() })
        
        for (pair in sortedByPreference) {
            val w = pair.second
            val isDuplicate = uniqueWords.any { existing ->
                val overlap = RectF(w.bounds)
                if (overlap.intersect(existing.bounds)) {
                    val overlapArea = overlap.width() * overlap.height()
                    val wArea = w.bounds.width() * w.bounds.height()
                    val existingArea = existing.bounds.width() * existing.bounds.height()
                    // If the existing word is a massive graphic block, don't let it swallow small words!
                    if (maxOf(wArea, existingArea) > 10 * minOf(wArea, existingArea)) {
                        false
                    } else {
                        overlapArea > minOf(wArea, existingArea) * 0.5f
                    }
                } else false
            }
            if (!isDuplicate) uniqueWords.add(w)
        }

        // 2. Line Clustering (Vertical Overlap & Horizontal Proximity)
        val sortedWords = uniqueWords.sortedBy { it.bounds.top }
        val lines = mutableListOf<MutableList<Word>>()
        
        for (word in sortedWords) {
            var addedToLine = false
            
            for (line in lines) {
                val allowedGap = 60f * density
                
                val isCloseAndOverlapping = line.any { w ->
                    val topMax = maxOf(word.bounds.top, w.bounds.top)
                    val bottomMin = minOf(word.bounds.bottom, w.bounds.bottom)
                    val overlapHeight = maxOf(0f, bottomMin - topMax)
                    val minHeight = minOf(word.bounds.height(), w.bounds.height())
                    val verticalOverlap = overlapHeight >= (minHeight * 0.5f)
                    
                    if (verticalOverlap) {
                        val g1 = word.bounds.left - w.bounds.right
                        val g2 = w.bounds.left - word.bounds.right
                        maxOf(g1, g2) < allowedGap
                    } else {
                        false
                    }
                }
                
                if (isCloseAndOverlapping) {
                    line.add(word)
                    addedToLine = true
                    break
                }
            }
            
            if (!addedToLine) {
                lines.add(mutableListOf(word))
            }
        }

        // 3. Horizontal Sorting & Node Construction
        val result = mutableListOf<TextNode>()
        lines.forEach { lineWords ->
            val finalLineWords = lineWords.sortedBy { it.bounds.left }
            
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
                        id = UUID.randomUUID().toString(),
                        fullText = fullText,
                        bounds = lineBounds,
                        words = cleanedWords
                    )
                )
            }
        }
        
        return result
    }
}
