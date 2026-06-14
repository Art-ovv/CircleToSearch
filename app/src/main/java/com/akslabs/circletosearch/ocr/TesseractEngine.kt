package com.akslabs.circletosearch.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.akslabs.circletosearch.ui.components.TextNode
import com.akslabs.circletosearch.ui.components.Word
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object TesseractEngine {
    private const val TAG = "TesseractEngine"
    private var isPrepared = false

    // Cache Tesseract instance to avoid loading 30MB+ dictionaries from disk on every scan
    private var cachedTessApi: TessBaseAPI? = null
    private var cachedLang: String? = null
    private val ocrMutex = Mutex()

    // Default languages: eng + rus for Cyrillic support
    private val defaultLanguages = listOf("eng", "rus")

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

    private fun calculateAverageLuminance(bitmap: Bitmap): Float {
        val stepX = maxOf(1, bitmap.width / 50)
        val stepY = maxOf(1, bitmap.height / 50)
        var sumLuminance = 0f
        var count = 0
        for (x in 0 until bitmap.width step stepX) {
            for (y in 0 until bitmap.height step stepY) {
                val pixel = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)
                sumLuminance += (0.299f * r + 0.587f * g + 0.114f * b)
                count++
            }
        }
        return if (count > 0) sumLuminance / count else 255f
    }

    fun prepareTessData(context: Context): String {
        val filesDir = context.filesDir.absolutePath
        // OPTIMIZATION: Immediate exit if files are already verified in this session
        if (isPrepared) return filesDir
        val tessDir = File(filesDir, "tessdata")
        if (!tessDir.exists()) {
            tessDir.mkdirs()
        }

        // Copy default models
        for (lang in defaultLanguages) {
            val langFile = File(tessDir, "$lang.traineddata")
            if (!langFile.exists()) {
                Log.d(TAG, "Copying $lang.traineddata from assets...")
                try {
                    context.assets.open("tessdata/$lang.traineddata").use { input ->
                        FileOutputStream(langFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy $lang.traineddata: ${e.message}")
                }
            }
        }
        isPrepared = true
        return filesDir
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

    fun importModel(context: Context, uri: android.net.Uri, callback: (Boolean, String) -> Unit) {
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            var fileName = "unknown.traineddata"
            if (cursor != null && cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    fileName = cursor.getString(nameIndex)
                }
                cursor.close()
            }

            if (!fileName.endsWith(".traineddata")) {
                callback(false, "File must be a .traineddata Tesseract model.")
                return
            }

            val tessDir = File(context.filesDir, "tessdata")
            if (!tessDir.exists()) tessDir.mkdirs()

            val destFile = File(tessDir, fileName)
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "Imported model to ${destFile.absolutePath}")
            callback(true, "Successfully imported ${fileName.removeSuffix(".traineddata").uppercase()} model!")

        } catch (e: Exception) {
            Log.e(TAG, "Error importing model: ${e.message}")
            callback(false, "Failed to import model")
        }
    }

    /**
     * Extracts text from a bitmap using a cached Tesseract instance for massive speed improvements.
     */
    suspend fun extractText(context: Context, bitmap: Bitmap): List<TextNode> = withContext(Dispatchers.IO) {
        val dataPath = prepareTessData(context)
        // Use automatic language detection
        val lang = getOcrLanguage(context)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val density = context.resources.displayMetrics.density

        val words = ocrMutex.withLock {
            if (cachedTessApi == null || cachedLang != lang) {
                cachedTessApi?.recycle()
                cachedTessApi = TessBaseAPI()
                val success = cachedTessApi?.init(dataPath, lang) ?: false
                if (!success) {
                    cachedTessApi?.recycle()
                    cachedTessApi = null
                    return@withLock emptyList<Word>()
                }
                cachedLang = lang
            }

            // SPEED AND QUALITY OPTIMIZATION:
            // Scaling + Conversion to B&W with high contrast.
            val maxDim = Math.max(bitmap.width, bitmap.height).toFloat()
            val targetMax = 2048f
            // UI text can be small. Scale by 1.5x up to a hard cap of ~2048px.
            val scaleFactor = Math.min(1.5f, targetMax / maxDim).coerceAtLeast(1f)
            
            val scaledWidth = (bitmap.width * scaleFactor).toInt()
            val scaledHeight = (bitmap.height * scaleFactor).toInt()
            
            val processedBitmap = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(processedBitmap)
            val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG) // Bilinear filtering
            
            // Smart Binarization & Inversion
            val avgLuminance = calculateAverageLuminance(bitmap)
            val isDarkMode = avgLuminance < 128f

            val colorMatrix = android.graphics.ColorMatrix()
            colorMatrix.setSaturation(0f) // Grayscale
            
            if (isDarkMode) {
                // Invert the image (makes text black and backgrounds white)
                colorMatrix.postConcat(android.graphics.ColorMatrix(floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }

            // Harsh contrast multiplier to force binarization and crush background noise
            val contrast = 5.0f
            val translate = (-128f * contrast) + 128f
            colorMatrix.postConcat(android.graphics.ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )))
            
            paint.colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
            
            val matrix = android.graphics.Matrix()
            matrix.postScale(scaleFactor, scaleFactor)
            canvas.drawBitmap(bitmap, matrix, paint)

            val api = cachedTessApi!!
            
            // Set Page Segmentation Mode to Sparse Text (11) to handle non-linear chat bubbles
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
            
            api.setImage(processedBitmap)
            api.getUTF8Text() // Trigger recognition

            val iterator = api.resultIterator ?: return@withLock emptyList<Word>()
            val extractedWords = mutableListOf<Word>()

            iterator.begin()
            do {
                val wordText = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                if (wordText.isNullOrBlank()) continue

                // CONFIDENCE FILTER: Reject low-quality recognitions (suspected noise/icons)
                val confidence = iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                if (confidence < 60) continue

                val wordRectParams = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    ?: iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_WORD)

                val wRect = if (wordRectParams is Rect) {
                    wordRectParams
                } else if (wordRectParams is IntArray) {
                    Rect(wordRectParams[0], wordRectParams[1], wordRectParams[2], wordRectParams[3])
                } else {
                    continue
                }

                // GARBAGE/ICON FILTER: Identify UI elements misidentified as text (e.g. settings dots, bars)
                if (wordText.length <= 3) {
                    val trimmed = wordText.trim()
                    val isHallucination = trimmed.all { it in "|Il!i(){cCo0-_.•°~,·" }
                    if (isHallucination) continue
                }

                if (wRect.isEmpty || wRect.width() < 2) continue

                extractedWords.add(
                    Word(
                        text = wordText,
                        index = 0,
                        startIndex = 0,
                        endIndex = wordText.length,
                        // Return coordinates back to the original screen scale
                        bounds = RectF(
                            wRect.left / scaleFactor,
                            wRect.top / scaleFactor,
                            wRect.right / scaleFactor,
                            wRect.bottom / scaleFactor
                        )
                    )
                )
            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))

            iterator.delete()
            api.clear() // Clear image buffer from native memory to prevent leaks, but keep models loaded
            processedBitmap.recycle()
            extractedWords
        }

        // Wrapper to maintain compatibility with groupWordsIntoNodes
        val allWordsWithSource = words.map { 0 to it }
        
        // Final Merge & Line Grouping
        groupWordsIntoNodes(allWordsWithSource, screenWidth, density)
    }

    private fun groupWordsIntoNodes(allWordsWithSource: List<Pair<Int, Word>>, screenWidth: Int, density: Float): List<TextNode> {
        if (allWordsWithSource.isEmpty()) return emptyList()

        // 1. Spatial Deduplication
        // We prefer results from quadrants (indices 1-4) over the full pass (index 0) 
        // because zoomed-in crops generally yield higher accuracy for small text.
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
                    // If overlap area is more than 50% of the SMALLEST word, it's a duplicate.
                    overlapArea > minOf(wArea, existingArea) * 0.5f
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
