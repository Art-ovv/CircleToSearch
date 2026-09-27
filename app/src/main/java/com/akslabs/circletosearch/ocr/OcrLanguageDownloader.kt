package com.akslabs.circletosearch.ocr

import com.paddle.ocr.model.ModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * Functional interface for opening HTTPS input streams with redirect handling.
 * Allows pure in-memory test injection without network calls.
 */
fun interface HttpStreamSupplier {
    @Throws(IOException::class)
    fun open(urlStr: String): InputStream
}

/**
 * Production HTTPS stream supplier enforcing TLS, disabling automatic redirect following,
 * manually resolving relative redirect Locations against URL, verifying HTTPS on each hop,
 * and disconnecting upon exceptions or stream close.
 */
object DefaultHttpStreamSupplier : HttpStreamSupplier {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5

    override fun open(urlStr: String): InputStream {
        return openWithRedirects(urlStr, 0)
    }

    private fun openWithRedirects(urlStr: String, redirectCount: Int): InputStream {
        if (redirectCount > MAX_REDIRECTS) {
            throw IOException("Too many redirects")
        }
        val url = try {
            URL(urlStr)
        } catch (e: Exception) {
            throw IOException("Invalid URL: ${e.message}")
        }
        if (!url.protocol.equals("https", ignoreCase = true)) {
            throw SecurityException("Insecure download protocol rejected: ${url.protocol}")
        }

        var connection: HttpsURLConnection? = null
        try {
            connection = url.openConnection() as? HttpsURLConnection
                ?: throw IOException("Failed to open HTTPS connection")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "CircleToSearch-OCR/1.0")

            val responseCode = connection.responseCode
            if (responseCode in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: run {
                        connection.disconnect()
                        throw IOException("HTTP redirect missing Location header")
                    }
                val resolvedUrl = try {
                    URL(url, location)
                } catch (e: Exception) {
                    connection.disconnect()
                    throw IOException("Malformed redirect Location: $location")
                }
                if (!resolvedUrl.protocol.equals("https", ignoreCase = true)) {
                    connection.disconnect()
                    throw SecurityException("Insecure redirect protocol rejected: ${resolvedUrl.protocol}")
                }
                connection.disconnect()
                return openWithRedirects(resolvedUrl.toString(), redirectCount + 1)
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                throw IOException("Server returned HTTP $responseCode")
            }

            val rawStream = connection.inputStream
            val conn = connection
            return object : FilterInputStream(rawStream) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        conn.disconnect()
                    }
                }
            }
        } catch (e: Exception) {
            connection?.disconnect()
            throw if (e is IOException) e else IOException("Connection failed: ${e.message}", e)
        }
    }
}

/**
 * Handles explicit user-initiated downloads of official PaddleOCR language packs.
 *
 * Streams off main thread, strictly bounds file sizes, updates progress, actively checks for
 * coroutine cancellation, verifies SHA-256 digests and YAML dictionary compatibility, and stages
 * files for atomic installation. Interrupted or failed downloads always clean up temporary files.
 */
object OcrLanguageDownloader {
    private const val BUFFER_SIZE = 32 * 1024

    suspend fun downloadPack(
        storage: OcrLanguageStorage,
        pack: OcrLanguagePack,
        streamSupplier: HttpStreamSupplier = DefaultHttpStreamSupplier,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        require(!pack.isBundled) { "Cannot download a bundled model" }
        val onnxUrl = pack.onnxUrl() ?: throw IllegalArgumentException("Missing ONNX URL for ${pack.id}")
        val yamlUrl = pack.yamlUrl() ?: throw IllegalArgumentException("Missing YAML URL for ${pack.id}")

        val totalExpectedBytes = pack.totalSizeBytes
        val stagingDir = storage.getStagingDir(pack.id)

        // Reset staging directory
        stagingDir.deleteRecursively()
        if (!stagingDir.mkdirs()) {
            throw IOException("Failed to create staging directory for ${pack.displayName}")
        }

        var downloadedBytesSoFar = 0L
        var installedSuccessfully = false

        try {
            try {
                // 1. Download YAML config first (smaller, fast to fail early)
                val stagedConfig = File(stagingDir, pack.yamlFilename)
                val stagedConfigPart = File(stagingDir, "${pack.yamlFilename}.part")
                downloadFile(
                    streamSupplier = streamSupplier,
                    urlStr = yamlUrl,
                    destination = stagedConfigPart,
                    expectedSize = pack.yamlSize,
                    expectedSha256 = pack.yamlSha256,
                    displayName = pack.displayName,
                    filename = pack.yamlFilename,
                    progressOffset = downloadedBytesSoFar,
                    totalBytes = totalExpectedBytes,
                    onProgress = onProgress,
                )
                if (!stagedConfigPart.renameTo(stagedConfig)) {
                    throw IOException("Failed to rename temporary YAML file for ${pack.displayName}")
                }
                downloadedBytesSoFar += pack.yamlSize

                // Validate YAML config dictionary compatibility
                try {
                    ModelConfig.parse(stagedConfig)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw IOException("Downloaded YAML failed dictionary compatibility verification for ${pack.displayName}: ${e.message}", e)
                }

                // 2. Download ONNX model
                val stagedModel = File(stagingDir, pack.onnxFilename)
                val stagedModelPart = File(stagingDir, "${pack.onnxFilename}.part")
                downloadFile(
                    streamSupplier = streamSupplier,
                    urlStr = onnxUrl,
                    destination = stagedModelPart,
                    expectedSize = pack.onnxSize,
                    expectedSha256 = pack.onnxSha256,
                    displayName = pack.displayName,
                    filename = pack.onnxFilename,
                    progressOffset = downloadedBytesSoFar,
                    totalBytes = totalExpectedBytes,
                    onProgress = onProgress,
                )
                if (!stagedModelPart.renameTo(stagedModel)) {
                    throw IOException("Failed to rename temporary ONNX file for ${pack.displayName}")
                }
                downloadedBytesSoFar += pack.onnxSize

                // Ensure active before final progress and installation
                currentCoroutineContext().ensureActive()
                onProgress(downloadedBytesSoFar, totalExpectedBytes)

                // 3. Atomically install pair to final destination
                storage.atomicallyInstall(pack, stagingDir)
                installedSuccessfully = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw if (e is IOException) e else IOException("Download failed for ${pack.displayName}: ${e.message}", e)
            }
        } finally {
            if (!installedSuccessfully) {
                stagingDir.deleteRecursively()
            }
        }
    }

    suspend fun downloadFile(
        streamSupplier: HttpStreamSupplier,
        urlStr: String,
        destination: File,
        expectedSize: Long,
        expectedSha256: String,
        displayName: String,
        filename: String,
        progressOffset: Long,
        totalBytes: Long,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytesWritten = 0L
        var completedSuccessfully = false

        try {
            try {
                streamSupplier.open(urlStr).use { inputStream ->
                    FileOutputStream(destination).use { outputStream ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int

                        while (true) {
                            bytesRead = inputStream.read(buffer)
                            if (bytesRead == -1) {
                                // Check ensureActive on EOF (empty/final-chunk cancel case)
                                currentCoroutineContext().ensureActive()
                                break
                            }

                            bytesWritten += bytesRead
                            // Strict expected byte count check (not +1024)
                            if (bytesWritten > expectedSize) {
                                throw IOException("Received data exceeded expected size of $expectedSize bytes for $displayName ($filename)")
                            }

                            outputStream.write(buffer, 0, bytesRead)
                            digest.update(buffer, 0, bytesRead)

                            // Check ensureActive BEFORE publication
                            currentCoroutineContext().ensureActive()
                            onProgress(progressOffset + bytesWritten, totalBytes)
                        }
                        outputStream.flush()
                    }
                }

                if (bytesWritten != expectedSize) {
                    throw IOException("File size mismatch for $displayName ($filename): expected $expectedSize, got $bytesWritten")
                }

                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualHash.equals(expectedSha256, ignoreCase = true)) {
                    throw IOException("SHA-256 digest mismatch for $displayName ($filename)")
                }
                completedSuccessfully = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw if (e is IOException) e else IOException("Download failed for $displayName ($filename): ${e.message}", e)
            }
        } finally {
            if (!completedSuccessfully) {
                destination.delete()
            }
        }
    }
}
