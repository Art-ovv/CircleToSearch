package com.akslabs.circletosearch.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrLanguageCatalogTest {

    @Test
    fun bundledPackIsDefinedCorrectly() {
        val bundled = OcrLanguageCatalog.bundledPack
        assertEquals(OcrLanguageCatalog.BUNDLED_PACK_ID, bundled.id)
        assertTrue(bundled.isBundled)
        assertTrue(bundled.displayName.isNotBlank())
        assertTrue(bundled.coverageDescription.isNotBlank())
    }

    @Test
    fun allDownloadablePacksHaveValidMetadataAndChecksums() {
        val downloadable = OcrLanguageCatalog.downloadablePacks
        assertTrue("Catalog should contain downloadable packs", downloadable.isNotEmpty())

        downloadable.forEach { pack ->
            assertFalse("${pack.id} should not be bundled", pack.isBundled)
            assertTrue("${pack.id} id should be non-empty", pack.id.isNotBlank())
            assertTrue("${pack.id} display name should be non-empty", pack.displayName.isNotBlank())
            assertTrue("${pack.id} coverage should be non-empty", pack.coverageDescription.isNotBlank())

            // HuggingFace coordinates
            val repo = pack.modelRepo
            assertNotNull("${pack.id} should have model repo", repo)
            assertTrue("${pack.id} model repo should start with PaddlePaddle/", repo!!.startsWith("PaddlePaddle/"))

            val commit = pack.commitSha
            assertNotNull("${pack.id} should have commit SHA", commit)
            assertEquals("${pack.id} commit SHA should be 40-char git hash", 40, commit!!.length)
            assertTrue("${pack.id} commit SHA must be hex", commit.matches(Regex("^[0-9a-fA-F]{40}$")))

            // ONNX model metadata
            assertEquals("inference.onnx", pack.onnxFilename)
            assertTrue("${pack.id} ONNX size should be > 1MB", pack.onnxSize > 1_000_000L)
            assertEquals("${pack.id} ONNX SHA-256 must be 64 hex characters", 64, pack.onnxSha256.length)
            assertTrue("${pack.id} ONNX SHA-256 must be hex", pack.onnxSha256.matches(Regex("^[0-9a-fA-F]{64}$")))

            // YAML config metadata
            assertEquals("inference.yml", pack.yamlFilename)
            assertTrue("${pack.id} YAML size should be > 100 bytes", pack.yamlSize > 100L)
            assertEquals("${pack.id} YAML SHA-256 must be 64 hex characters", 64, pack.yamlSha256.length)
            assertTrue("${pack.id} YAML SHA-256 must be hex", pack.yamlSha256.matches(Regex("^[0-9a-fA-F]{64}$")))

            // Valid download URLs: must use HTTPS
            val onnxUrl = pack.onnxUrl()
            val yamlUrl = pack.yamlUrl()
            assertNotNull(onnxUrl)
            assertNotNull(yamlUrl)
            assertTrue(onnxUrl!!.startsWith("https://"))
            assertTrue(yamlUrl!!.startsWith("https://"))
            assertTrue(onnxUrl.endsWith("/inference.onnx"))
            assertTrue(yamlUrl.endsWith("/inference.yml"))
        }
    }

    @Test
    fun allowlistAcceptsKnownPacksAndRejectsUnknown() {
        assertTrue(OcrLanguageCatalog.isSupportedPackId("eslav"))
        assertTrue(OcrLanguageCatalog.isSupportedPackId("latin"))
        assertTrue(OcrLanguageCatalog.isSupportedPackId("zh_en"))
        assertTrue(OcrLanguageCatalog.isSupportedPackId("korean"))
        assertTrue(OcrLanguageCatalog.isSupportedPackId("devanagari"))
        assertFalse(OcrLanguageCatalog.isSupportedPackId("arabic"))

        assertFalse(OcrLanguageCatalog.isSupportedPackId("unknown"))
        assertFalse(OcrLanguageCatalog.isSupportedPackId("../etc/passwd"))
        assertFalse(OcrLanguageCatalog.isSupportedPackId(""))
    }

    @Test
    fun getPackResolvesExpectedPacks() {
        val knownIds = listOf("latin", "zh_en", "korean", "devanagari", OcrLanguageCatalog.BUNDLED_PACK_ID)
        for (id in knownIds) {
            val pack = OcrLanguageCatalog.getPack(id)
            assertNotNull("Pack '$id' should be resolvable", pack)
            assertEquals(id, pack!!.id)
            assertTrue(pack.displayName.isNotBlank())
        }

        assertNull(OcrLanguageCatalog.getPack("nonexistent"))
        assertNull(OcrLanguageCatalog.getPack("../passwd"))
        assertNull(OcrLanguageCatalog.getPack(""))
    }
}
