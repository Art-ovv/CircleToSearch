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
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object TesseractEngine {
    private const val TAG = "TesseractEngine"
    private var isPrepared = false

    // Default languages: eng + rus for Cyrillic support
    private val defaultLanguages = listOf("eng", "rus")

    private val latinToCyrillic = mapOf(
        'A' to 'А', 'a' to 'а',
        'B' to 'В', 'b' to 'в',
        'C' to 'С', 'c' to 'с',
        'E' to 'Е', 'e' to 'е',
        'H' to 'Н', 'h' to 'н',
        'K' to 'К', 'k' to 'к',
        'M' to 'М', 'm' to 'м',
        'O' to 'О', 'o' to 'о',
        'P' to 'Р', 'p' to 'р',
        'T' to 'Т', 't' to 'т',
        'X' to 'Х', 'x' to 'х',
        'Y' to 'У', 'y' to 'у',
        'N' to 'П', 'n' to 'п', // Frequently confused (CHYNA -> СНУПА)
        'W' to 'Ш', 'w' to 'ш'
    )
    private val cyrillicToLatin = latinToCyrillic.entries.associate { (k, v) -> v to k }

    private fun cleanWordText(text: String, lineDominantCyrillic: Boolean): String {
        // Remove frequent OCR artifacts
        var t = text.replace("<", "").replace(">", "").replace("|", "").trim()
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
        val filesDir = context.filesDir.absolutePath
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
        val systemLang = java.util.Locale.getDefault().language
        return when (systemLang) {
            "ru", "uk", "be", "kk" -> "rus+eng" // Combine Cyrillic and Latin for mixed text support
            else -> "eng"
        }
    }

    /**
     * Returns language for OCR - either system default or user saved.
     */
    fun getOcrLanguage(context: Context): String {
        val prefs = context.getSharedPreferences("OcrSettings", Context.MODE_PRIVATE)
        var savedLang = prefs.getString("selected_lang", "")

        // Auto-upgrade legacy single language choice to dual language
        if (savedLang == "rus") {
            savedLang = "rus+eng"
            prefs.edit().putString("selected_lang", savedLang).apply()
        }

        // Use system language if no user selection
        if (savedLang.isNullOrEmpty()) {
            val systemLang = getSystemLanguage()
            // Save system language as default
            prefs.edit().putString("selected_lang", systemLang).apply()
            return systemLang
        }

        return savedLang
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
     * Tiled extraction: Runs multiple OCR passes in parallel and merges results.
     * This mirrors the QR scanner's multi-resolution logic to maximize accuracy.
     */
    suspend fun extractText(context: Context, bitmap: Bitmap): List<TextNode> = coroutineScope {
        val dataPath = withContext(Dispatchers.IO) { prepareTessData(context) }
        // Use automatic language detection
        val lang = getOcrLanguage(context)

        val w = bitmap.width
        val h = bitmap.height

        // Define Tiles (Full + 2x2 Grid with 20% overlap)
        val tiles = mutableListOf<Rect>()
        // 1. Full
        tiles.add(Rect(0, 0, w, h))
        
        // 2. 2x2 Grid (Each tile is ~60% size to provide nice overlap)
        val tw = (w * 0.6f).toInt()
        val th = (h * 0.6f).toInt()
        tiles.add(Rect(0, 0, tw, th)) // Top Left
        tiles.add(Rect(w - tw, 0, w, th)) // Top Right
        tiles.add(Rect(0, h - th, tw, h)) // Bottom Left
        tiles.add(Rect(w - tw, h - th, w, h)) // Bottom Right

        val allPasses = tiles.mapIndexed { index, rect ->
            async(Dispatchers.Default) {
                index to internalExtractWords(dataPath, lang, bitmap, rect)
            }
        }

        val allWordsWithSource = allPasses.awaitAll().flatMap { (index, words) ->
            words.map { index to it }
        }
        
        // Final Merge & Line Grouping
        groupWordsIntoNodes(allWordsWithSource)
    }

    private fun internalExtractWords(dataPath: String, lang: String, fullBitmap: Bitmap, crop: Rect): List<Word> {
        val words = mutableListOf<Word>()
        val tess = TessBaseAPI()
        try {
            if (!tess.init(dataPath, lang)) return emptyList()
            
            // If the crop is a sub-region, create a subset bitmap
            val tileBitmap = if (crop.left == 0 && crop.top == 0 && crop.width() == fullBitmap.width && crop.height() == fullBitmap.height) {
                fullBitmap
            } else {
                Bitmap.createBitmap(fullBitmap, crop.left, crop.top, crop.width(), crop.height())
            }

            tess.setImage(tileBitmap)
            tess.getUTF8Text() // Trigger recognition

            val iterator = tess.resultIterator ?: run {
                tess.recycle()
                return emptyList()
            }

            iterator.begin()
            do {
                val wordText = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                if (wordText.isNullOrBlank()) continue

                val wordRectParams = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    ?: iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_WORD)

                val wRect = if (wordRectParams is Rect) {
                    wordRectParams
                } else if (wordRectParams is IntArray) {
                    Rect(wordRectParams[0], wordRectParams[1], wordRectParams[2], wordRectParams[3])
                } else {
                    continue
                }

                if (wRect.isEmpty || wRect.width() < 2) continue

                // Adjust coordinates to global screen space
                val globalBounds = RectF(
                    (wRect.left + crop.left).toFloat(),
                    (wRect.top + crop.top).toFloat(),
                    (wRect.right + crop.left).toFloat(),
                    (wRect.bottom + crop.top).toFloat()
                )

                words.add(
                    Word(
                        text = wordText,
                        index = 0, // Assigned later
                        startIndex = 0,
                        endIndex = wordText.length,
                        bounds = globalBounds
                    )
                )

            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))

            iterator.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Tile process error: ${e.message}")
        } finally {
            tess.recycle()
        }
        return words
    }

    private fun groupWordsIntoNodes(allWordsWithSource: List<Pair<Int, Word>>): List<TextNode> {
        if (allWordsWithSource.isEmpty()) return emptyList()

        // 1. Spatial Deduplication
        // We prefer results from quadrants (indices 1-4) over the full pass (index 0) 
        // because zoomed-in crops generally yield higher accuracy for small text.
        val uniqueWords = mutableListOf<Word>()
        val sortedByPreference = allWordsWithSource.sortedWith(compareByDescending<Pair<Int, Word>> { it.first }.thenByDescending { it.second.bounds.width() * it.second.bounds.height() })
        
        for (pair in sortedByPreference) {
            val w = pair.second
            val isDuplicate = uniqueWords.any { existing ->
                val overlap = RectF(w.bounds)
                if (overlap.intersect(existing.bounds)) {
                    val overlapArea = overlap.width() * overlap.height()
                    val wArea = w.bounds.width() * w.bounds.height()
                    val textMatch = w.text.equals(existing.text, ignoreCase = true)
                    // If text matches and there is significant overlap, it's a duplicate
                    overlapArea > wArea * 0.7 && textMatch
                } else false
            }
            if (!isDuplicate) uniqueWords.add(w)
        }

        // 2. Line Clustering (Vertical Overlap)
        val sortedWords = uniqueWords.sortedBy { it.bounds.top }
        val lines = mutableListOf<MutableList<Word>>()
        
        if (sortedWords.isNotEmpty()) {
            var currentLine = mutableListOf<Word>()
            currentLine.add(sortedWords[0])
            lines.add(currentLine)
            
            for (i in 1 until sortedWords.size) {
                val prev = currentLine.last()
                val curr = sortedWords[i]
                
                val avgHeight = (curr.bounds.height() + prev.bounds.height()) / 2f
                val verticalOverlap = Math.abs(curr.bounds.centerY() - prev.bounds.centerY()) < (avgHeight * 0.6f)
                
                // Prevent multi-column bleeding: Max horizontal gap of ~3.5 character widths
                val horizontalGap = curr.bounds.left - prev.bounds.right
                val isCloseHorizontally = horizontalGap < (avgHeight * 3.5f)
                
                if (verticalOverlap && isCloseHorizontally) {
                    currentLine.add(curr)
                } else {
                    currentLine = mutableListOf(curr)
                    lines.add(currentLine)
                }
            }
        }

        // 3. Horizontal Sorting & Node Construction
        val result = mutableListOf<TextNode>()
        lines.forEach { lineWords ->
            val finalLineWords = lineWords.sortedBy { it.bounds.left }
            
            val rawLineText = finalLineWords.joinToString(" ") { it.text }
            val cCyr = rawLineText.count { it in 'А'..'я' || it == 'Ё' || it == 'ё' }
            val cLat = rawLineText.count { it in 'A'..'Z' || it in 'a'..'z' }
            val lineDominantCyrillic = cCyr >= cLat
            
            // Проверяем, не написана ли вся строка капсом (чтобы не понижать регистр легальным аббревиатурам)
            val lettersOnly = rawLineText.filter { it.isLetter() }
            val lineIsAllCaps = lettersOnly.isNotEmpty() && lettersOnly.all { it.isUpperCase() }

            val cleanedWords = mutableListOf<Word>()
            val lineBounds = Rect()
                
            finalLineWords.forEach { w ->
                var cleanedText = cleanWordText(w.text, lineDominantCyrillic)
                
                // Фикс бага OCR с КАПСОМ: если слово длиннее 2 букв и всё из заглавных, но
                // всё остальное предложение нормальное — Tesseract ошибся. Понижаем регистр.
                if (!lineIsAllCaps && cleanedText.length > 2 && cleanedText.all { !it.isLetter() || it.isUpperCase() }) {
                    cleanedText = cleanedText.lowercase()
                }

                if (cleanedText.isNotBlank()) {
                    val r = Rect()
                    w.bounds.roundOut(r)
                    if (lineBounds.isEmpty()) lineBounds.set(r) else lineBounds.union(r)
                    
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
