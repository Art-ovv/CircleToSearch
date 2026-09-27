package com.akslabs.circletosearch.ocr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class OcrLanguageDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testBaseDir: File
    private lateinit var storage: OcrLanguageStorage

    // Synthetic small test pack
    private val testPack = OcrLanguagePack(
        id = "latin",
        displayName = "Latin script",
        coverageDescription = "English, Spanish, etc.",
        isBundled = false,
        modelRepo = "PaddlePaddle/latin_PP-OCRv5_mobile_rec_onnx",
        commitSha = "89d3a50e2c27e2e7cceeab0e944c25c807d5db4f",
        onnxFilename = "inference.onnx",
        onnxSize = 256L,
        onnxSha256 = sha256Of(ByteArray(256) { 0x41 }),
        yamlFilename = "inference.yml",
        yamlSize = validYamlContent.toByteArray(Charsets.UTF_8).size.toLong(),
        yamlSha256 = sha256Of(validYamlContent.toByteArray(Charsets.UTF_8)),
    )

    @Before
    fun setUp() {
        testBaseDir = tempFolder.newFolder("downloader_test")
        storage = OcrLanguageStorage(testBaseDir)
    }

    @Test
    fun truncatedStreamFailsWithSizeMismatchAndCleansDestination() = runBlocking {
        val destFile = File(testBaseDir, "truncated.bin")
        val streamSupplier = HttpStreamSupplier {
            ByteArrayInputStream(ByteArray(100)) // Expected 256
        }

        try {
            OcrLanguageDownloader.downloadFile(
                streamSupplier = streamSupplier,
                urlStr = "https://huggingface.co/test/truncated.bin",
                destination = destFile,
                expectedSize = 256L,
                expectedSha256 = testPack.onnxSha256,
                displayName = "Test Pack",
                filename = "truncated.bin",
                progressOffset = 0L,
                totalBytes = 256L,
                onProgress = { _, _ -> },
            )
            fail("Expected IOException on truncated stream")
        } catch (e: IOException) {
            assertTrue("Error must mention file size mismatch", e.message!!.contains("File size mismatch"))
            assertFalse("Error message must not leak raw URL", e.message!!.contains("https://"))
        }

        assertFalse("Destination file must be deleted on failure", destFile.exists())
    }

    @Test
    fun oversizeStreamFailsStrictSizeCheckImmediately() = runBlocking {
        val destFile = File(testBaseDir, "oversize.bin")
        val streamSupplier = HttpStreamSupplier {
            // Exceeds 256 bytes; strict size check must fail before finishing
            ByteArrayInputStream(ByteArray(300) { 0x41 })
        }

        try {
            OcrLanguageDownloader.downloadFile(
                streamSupplier = streamSupplier,
                urlStr = "https://huggingface.co/test/oversize.bin",
                destination = destFile,
                expectedSize = 256L,
                expectedSha256 = testPack.onnxSha256,
                displayName = "Test Pack",
                filename = "oversize.bin",
                progressOffset = 0L,
                totalBytes = 256L,
                onProgress = { _, _ -> },
            )
            fail("Expected IOException on oversize stream")
        } catch (e: IOException) {
            assertTrue("Error must mention exceeded expected size", e.message!!.contains("exceeded expected size"))
        }

        assertFalse("Destination file must be deleted on failure", destFile.exists())
    }

    @Test
    fun wrongHashFailsDigestMismatchAndCleansDestination() = runBlocking {
        val destFile = File(testBaseDir, "wrong_hash.bin")
        val streamSupplier = HttpStreamSupplier {
            // Correct length 256, but different byte content (0x42 instead of 0x41)
            ByteArrayInputStream(ByteArray(256) { 0x42 })
        }

        try {
            OcrLanguageDownloader.downloadFile(
                streamSupplier = streamSupplier,
                urlStr = "https://huggingface.co/test/wrong_hash.bin",
                destination = destFile,
                expectedSize = 256L,
                expectedSha256 = testPack.onnxSha256,
                displayName = "Test Pack",
                filename = "wrong_hash.bin",
                progressOffset = 0L,
                totalBytes = 256L,
                onProgress = { _, _ -> },
            )
            fail("Expected IOException on SHA-256 digest mismatch")
        } catch (e: IOException) {
            assertTrue("Error must mention SHA-256 digest mismatch", e.message!!.contains("SHA-256 digest mismatch"))
        }

        assertFalse("Destination file must be deleted on failure", destFile.exists())
    }

    @Test
    fun cancellationNearEofPropagatesCancellationExceptionAndCleansFile() = runBlocking {
        val destFile = File(testBaseDir, "cancel_eof.bin")

        // Custom stream that cancels the coroutine context when EOF is approached
        val streamSupplier = HttpStreamSupplier {
            object : InputStream() {
                private var bytesRead = 0
                override fun read(): Int {
                    if (bytesRead >= 256) {
                        return -1 // EOF
                    }
                    bytesRead++
                    return 0x41
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (bytesRead >= 256) return -1
                    val toRead = minOf(len, 256 - bytesRead)
                    for (i in 0 until toRead) {
                        b[off + i] = 0x41
                    }
                    bytesRead += toRead
                    return toRead
                }
            }
        }

        try {
            // Cancel current coroutine right before read returns EOF
            OcrLanguageDownloader.downloadFile(
                streamSupplier = streamSupplier,
                urlStr = "https://huggingface.co/test/cancel_eof.bin",
                destination = destFile,
                expectedSize = 256L,
                expectedSha256 = testPack.onnxSha256,
                displayName = "Test Pack",
                filename = "cancel_eof.bin",
                progressOffset = 0L,
                totalBytes = 256L,
                onProgress = { downloaded, _ ->
                    if (downloaded >= 256L) {
                        // Cancel coroutine before completion
                        runBlocking {
                            currentCoroutineContext().cancel(CancellationException("Cancelled at EOF"))
                        }
                    }
                },
            )
            fail("Expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("Cancelled at EOF", e.message)
        }

        assertFalse("Destination file must be deleted on cancellation", destFile.exists())
    }

    @Test
    fun successfulDownloadPackInstallsAtomically(): Unit = runBlocking {
        val yamlBytes = validYamlContent.toByteArray(Charsets.UTF_8)
        val onnxBytes = ByteArray(256) { 0x41 }

        val streamSupplier = HttpStreamSupplier { urlStr ->
            if (urlStr.endsWith(".yml")) {
                ByteArrayInputStream(yamlBytes)
            } else {
                ByteArrayInputStream(onnxBytes)
            }
        }

        var progressCalls = 0
        OcrLanguageDownloader.downloadPack(
            storage = storage,
            pack = testPack,
            streamSupplier = streamSupplier,
            onProgress = { _, _ -> progressCalls++ },
        )

        assertTrue("Progress should have been reported", progressCalls > 0)
        // Staging directory must be cleaned
        assertFalse(storage.getStagingDir(testPack.id).exists())
        // Target directory must have installed files
        val targetDir = storage.getPackDir(testPack.id)
        val installedModel = File(targetDir, testPack.onnxFilename)
        val installedConfig = File(targetDir, testPack.yamlFilename)
        assertTrue("Installed model must exist", installedModel.exists())
        assertTrue("Installed config must exist", installedConfig.exists())
        assertEquals(testPack.onnxSize, installedModel.length())
        assertEquals(testPack.yamlSize, installedConfig.length())
    }

    @Test
    fun failedInstallationCleansStagingDirectory() = runBlocking {
        val yamlBytes = validYamlContent.toByteArray(Charsets.UTF_8)
        val onnxBytes = ByteArray(256) { 0x41 }

        val streamSupplier = HttpStreamSupplier { urlStr ->
            if (urlStr.endsWith(".yml")) {
                ByteArrayInputStream(yamlBytes)
            } else {
                ByteArrayInputStream(onnxBytes)
            }
        }

        // Storage that fails atomic install
        val failingStorage = object : OcrLanguageStorage(testBaseDir) {
            override fun atomicallyInstall(pack: OcrLanguagePack, stagingDir: File) {
                throw IOException("Simulated disk error during atomic install")
            }
        }

        try {
            OcrLanguageDownloader.downloadPack(
                storage = failingStorage,
                pack = testPack,
                streamSupplier = streamSupplier,
            )
            fail("Expected IOException on install failure")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Simulated disk error"))
        }

        // Staging directory must be cleaned
        assertFalse(failingStorage.getStagingDir(testPack.id).exists())
    }

    @Test
    fun downloaderStagingCleanupFinallyCoversErrorWithoutCatching(): Unit = runBlocking {
        val streamSupplier = HttpStreamSupplier {
            throw AssertionError("Simulated JVM Error or AssertionError")
        }

        try {
            OcrLanguageDownloader.downloadPack(
                storage = storage,
                pack = testPack,
                streamSupplier = streamSupplier,
            )
            fail("Expected AssertionError to propagate")
        } catch (e: AssertionError) {
            assertEquals("Simulated JVM Error or AssertionError", e.message)
        }

        // Staging directory must be cleaned by finally block
        assertFalse("Staging directory must be deleted on Error", storage.getStagingDir(testPack.id).exists())
    }

    companion object {
        private const val validYamlContent = """
Global:
  model_type: rec
  algorithm: CRNN
PostProcess:
  name: CTCLabelDecode
  character_dict:
    - a
    - b
    - c
    - " "
"""

        private fun sha256Of(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }
}
