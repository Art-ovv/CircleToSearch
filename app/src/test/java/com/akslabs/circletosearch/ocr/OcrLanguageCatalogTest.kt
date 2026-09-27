package com.akslabs.circletosearch.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrLanguageCatalogTest {

    @Test
    fun bundledPackIsDefinedCorrectly() {
        val bundled = OcrLanguageCatalog.bundledPack
        assertEquals(OcrLanguageCatalog.BUNDLED_PACK_ID, bundled.id)
        assertTrue(bundled.isBundled)
        assertEquals("East Slavic", bundled.displayName)
        assertTrue(bundled.coverageDescription.contains("Russian"))
        assertTrue(bundled.coverageDescription.contains("English"))
    }

    @Test
    fun allDownloadablePacksHaveValidMetadataAndChecksums() {
        val downloadable = OcrLanguageCatalog.downloadablePacks
        assertEquals("Catalog should contain exactly 4 downloadable packs", 4, downloadable.size)

        downloadable.forEach { pack ->
            assertFalse("${pack.id} should not be bundled", pack.isBundled)
            assertTrue("${pack.id} id should be non-empty", pack.id.isNotEmpty())
            assertTrue("${pack.id} display name should be non-empty", pack.displayName.isNotEmpty())
            assertTrue("${pack.id} coverage should be non-empty", pack.coverageDescription.isNotEmpty())

            // HuggingFace coordinates
            assertNotNull("${pack.id} should have model repo", pack.modelRepo)
            assertTrue("${pack.id} model repo should start with PaddlePaddle/", pack.modelRepo!!.startsWith("PaddlePaddle/"))
            assertNotNull("${pack.id} should have commit SHA", pack.commitSha)
            assertEquals("${pack.id} commit SHA should be 40-char git hash", 40, pack.commitSha!!.length)

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

            // Valid download URLs
            val onnxUrl = pack.onnxUrl()
            val yamlUrl = pack.yamlUrl()
            assertNotNull(onnxUrl)
            assertNotNull(yamlUrl)
            assertTrue(onnxUrl!!.startsWith("https://huggingface.co/"))
            assertTrue(yamlUrl!!.startsWith("https://huggingface.co/"))
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
        val latin = OcrLanguageCatalog.getPack("latin")
        assertNotNull(latin)
        assertEquals("Latin script", latin!!.displayName)

        val zhEn = OcrLanguageCatalog.getPack("zh_en")
        assertNotNull(zhEn)
        assertEquals("Chinese & English", zhEn!!.displayName)

        val korean = OcrLanguageCatalog.getPack("korean")
        assertNotNull(korean)
        assertEquals("Korean", korean!!.displayName)
    }
}
