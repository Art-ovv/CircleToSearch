package com.akslabs.circletosearch.ocr

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Installs bundled Tesseract models exactly once for a process.
 *
 * Files are first written next to their destination and then renamed, so another
 * OCR request can never observe a partially copied model.
 */
internal class TessDataPreparer(
    private val filesDir: File,
    private val languages: List<String>,
    private val openAsset: (String) -> InputStream,
) {
    private val lock = Any()
    private val validatedLanguages = mutableSetOf<String>()
    private var prepared = false

    fun prepare(): String = synchronized(lock) {
        if (prepared) return@synchronized filesDir.absolutePath

        val tessDir = File(filesDir, TESSDATA_DIRECTORY)
        check(tessDir.isDirectory || tessDir.mkdirs()) {
            "Unable to create Tesseract data directory: ${tessDir.absolutePath}"
        }

        val marker = File(tessDir, MODEL_VERSION_MARKER)
        val trustedPreviousInstall = marker.isFile && marker.readText() == MODEL_VERSION
        languages.forEach { language ->
            installModelIfNeeded(
                tessDir = tessDir,
                language = language,
                force = !trustedPreviousInstall && language !in validatedLanguages,
            )
            validatedLanguages += language
        }

        publishMarker(marker)
        prepared = true
        filesDir.absolutePath
    }

    private fun installModelIfNeeded(tessDir: File, language: String, force: Boolean) {
        val destination = File(tessDir, "$language.traineddata")
        if (!force && destination.isFile && destination.length() > 0L) return

        val temporary = File(tessDir, ".${destination.name}.tmp")
        if (temporary.exists() && !temporary.delete()) {
            error("Unable to remove stale temporary model: ${temporary.absolutePath}")
        }

        try {
            openAsset("$TESSDATA_DIRECTORY/${destination.name}").use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }

            check(temporary.length() > 0L) {
                "Bundled Tesseract model is empty: ${destination.name}"
            }

            moveIntoPlace(temporary, destination)
        } finally {
            temporary.delete()
        }
    }

    private fun publishMarker(marker: File) {
        if (marker.isFile && marker.readText() == MODEL_VERSION) return

        val temporary = File(marker.parentFile, ".${marker.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(MODEL_VERSION.toByteArray())
                output.fd.sync()
            }
            moveIntoPlace(temporary, marker)
        } finally {
            temporary.delete()
        }
    }

    private fun moveIntoPlace(source: File, destination: File) {
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

    private companion object {
        const val TESSDATA_DIRECTORY = "tessdata"
        const val MODEL_VERSION_MARKER = ".bundled-models.version"
        const val MODEL_VERSION = "1"
    }
}
