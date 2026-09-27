package com.akslabs.circletosearch.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class OcrLanguageStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var storage: OcrLanguageStorage

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("ocr_storage_test")
        storage = OcrLanguageStorage(storageDir)
    }

    private fun createSparseFile(file: File, size: Long) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(size) }
    }

    @Test
    fun rejectsPathTraversalAttemptsInPackId() {
        val traversalIds = listOf(
            "../outside",
            "../../etc/passwd",
            "latin/../korean",
            "latin/subdir",
            "\\windows\\style",
        )

        traversalIds.forEach { invalidId ->
            try {
                storage.getPackDir(invalidId)
                fail("Expected IllegalArgumentException for pack ID: $invalidId")
            } catch (e: IllegalArgumentException) {
                // Expected
            }
        }
    }

    @Test
    fun rejectsUnsupportedPackIds() {
        try {
            storage.getPackDir("unsupported_pack")
            fail("Expected IllegalArgumentException for unsupported pack")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("not allowlisted"))
        }
    }

    @Test
    fun atomicInstallationSucceedsWhenStagedPairIsComplete() {
        val pack = OcrLanguageCatalog.getPack("latin")!!
        val stagingDir = storage.getStagingDir(pack.id)

        // Create mock files matching exact pack size without 8MB in-memory array
        val stagedOnnx = File(stagingDir, pack.onnxFilename)
        val stagedYaml = File(stagingDir, pack.yamlFilename)

        createSparseFile(stagedOnnx, pack.onnxSize)
        createSparseFile(stagedYaml, pack.yamlSize)

        storage.atomicallyInstall(pack, stagingDir)

        // Staging directory should be moved/installed
        assertFalse("Staging directory should not exist after install", stagingDir.exists())

        // Target directory should contain installed files
        val installed = storage.getInstalledModelFiles(pack.id)
        assertNotNull("Installed files should not be null", installed)
        assertEquals(pack.onnxSize, installed!!.modelFile.length())
        assertEquals(pack.yamlSize, installed.configFile.length())
        assertTrue(storage.isPackInstalled(pack.id))
    }

    @Test
    fun atomicInstallationNeverOverwritesValidInstalledPair() {
        val pack = OcrLanguageCatalog.getPack("latin")!!
        val targetDir = storage.getPackDir(pack.id)
        targetDir.mkdirs()

        // Valid existing target files
        val existingOnnx = File(targetDir, pack.onnxFilename)
        val existingYaml = File(targetDir, pack.yamlFilename)
        createSparseFile(existingOnnx, pack.onnxSize)
        createSparseFile(existingYaml, pack.yamlSize)
        assertTrue(storage.isPackInstalled(pack.id))

        // New staged files
        val stagingDir = storage.getStagingDir(pack.id)
        val stagedOnnx = File(stagingDir, pack.onnxFilename)
        val stagedYaml = File(stagingDir, pack.yamlFilename)
        createSparseFile(stagedOnnx, pack.onnxSize)
        createSparseFile(stagedYaml, pack.yamlSize)

        try {
            storage.atomicallyInstall(pack, stagingDir)
            fail("Expected IllegalStateException when trying to overwrite already installed pack")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("already installed"))
        }

        // Active staging must not be deleted on rejection
        assertTrue("Active staging should be preserved on rejection", stagingDir.exists())
        // Target remains valid
        assertTrue(storage.isPackInstalled(pack.id))
    }

    @Test
    fun atomicInstallationCleansIncompleteOrphanTarget() {
        val pack = OcrLanguageCatalog.getPack("latin")!!
        val targetDir = storage.getPackDir(pack.id)
        targetDir.mkdirs()

        // Orphan target file with wrong size
        val orphanOnnx = File(targetDir, pack.onnxFilename)
        createSparseFile(orphanOnnx, 42L)
        assertFalse("Orphan target is not a valid pack", storage.isPackInstalled(pack.id))

        // Complete staged files
        val stagingDir = storage.getStagingDir(pack.id)
        val stagedOnnx = File(stagingDir, pack.onnxFilename)
        val stagedYaml = File(stagingDir, pack.yamlFilename)
        createSparseFile(stagedOnnx, pack.onnxSize)
        createSparseFile(stagedYaml, pack.yamlSize)

        storage.atomicallyInstall(pack, stagingDir)

        assertTrue(storage.isPackInstalled(pack.id))
        assertEquals(pack.onnxSize, File(targetDir, pack.onnxFilename).length())
    }

    @Test
    fun atomicInstallationFailsAndPreservesStagingIfSizeMismatches() {
        val pack = OcrLanguageCatalog.getPack("latin")!!
        val stagingDir = storage.getStagingDir(pack.id)

        // Write undersized ONNX
        val stagedOnnx = File(stagingDir, pack.onnxFilename)
        val stagedYaml = File(stagingDir, pack.yamlFilename)

        createSparseFile(stagedOnnx, 100L)
        createSparseFile(stagedYaml, pack.yamlSize)

        try {
            storage.atomicallyInstall(pack, stagingDir)
            fail("Expected IOException for mismatched file size")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("missing or incomplete"))
        }

        assertNull("Pack should not be reported installed", storage.getInstalledModelFiles(pack.id))
        assertTrue("Staging dir preserved", stagingDir.exists())
    }

    @Test
    fun partialInstallationIsNeverReportedAsInstalled() {
        val pack = OcrLanguageCatalog.getPack("latin")!!
        val packDir = storage.getPackDir(pack.id)
        packDir.mkdirs()

        // Case 1: Only ONNX file present
        val onnxFile = File(packDir, pack.onnxFilename)
        createSparseFile(onnxFile, pack.onnxSize)

        assertNull(storage.getInstalledModelFiles(pack.id))
        assertFalse(storage.isPackInstalled(pack.id))

        // Case 2: Both files present, but YAML size is corrupted/incomplete
        val yamlFile = File(packDir, pack.yamlFilename)
        createSparseFile(yamlFile, 10L)

        assertNull(storage.getInstalledModelFiles(pack.id))
        assertFalse(storage.isPackInstalled(pack.id))

        // Case 3: Both files present with correct sizes
        createSparseFile(yamlFile, pack.yamlSize)

        assertNotNull(storage.getInstalledModelFiles(pack.id))
        assertTrue(storage.isPackInstalled(pack.id))
    }

    @Test
    fun deleteInstalledPackRemovesDirectory() {
        val pack = OcrLanguageCatalog.getPack("latin")!!
        val packDir = storage.getPackDir(pack.id)
        packDir.mkdirs()
        createSparseFile(File(packDir, pack.onnxFilename), pack.onnxSize)
        createSparseFile(File(packDir, pack.yamlFilename), pack.yamlSize)

        assertTrue(storage.isPackInstalled(pack.id))

        val deleted = storage.deleteInstalledPack(pack.id)
        assertTrue(deleted)
        assertFalse(packDir.exists())
        assertFalse(storage.isPackInstalled(pack.id))
    }

    @Test
    fun bundledPackCannotBeDeleted() {
        val deleted = storage.deleteInstalledPack(OcrLanguageCatalog.BUNDLED_PACK_ID)
        assertFalse("Bundled pack must not be deleted", deleted)
    }

    @Test
    fun cleanStaleStagingDirsRemovesIncompleteStagingFolders() {
        val staleDir1 = File(storage.stagingRootDir, "latin_stale").apply { mkdirs() }
        File(staleDir1, "temp.part").writeText("incomplete")

        val staleDir2 = File(storage.stagingRootDir, "zh_en_stale").apply { mkdirs() }
        File(staleDir2, "temp.part").writeText("incomplete")

        assertTrue(staleDir1.exists())
        assertTrue(staleDir2.exists())

        storage.cleanStaleStagingDirs()

        assertFalse(staleDir1.exists())
        assertFalse(staleDir2.exists())
    }

    @Test
    fun canonicalBoundaryCheckOnInstalledChildFiles() {
        val packDir = storage.getPackDir("latin")
        val outsideFile = File(packDir, "../outside.onnx")
        try {
            storage.checkCanonicalBoundary(outsideFile, packDir)
            fail("Expected SecurityException for child outside canonical boundary")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("Path traversal attempt detected"))
        }

        val insideFile = File(packDir, "inference.onnx")
        storage.checkCanonicalBoundary(insideFile, packDir)
    }

    @Test
    fun cleanStaleStagingDirsExplicitFailureHonestHandling() {
        val staleDir = File(storage.stagingRootDir, "locked_stale").apply { mkdirs() }
        val lockedFile = File(staleDir, "locked.bin").apply { writeText("cannot delete") }
        staleDir.setWritable(false, false)
        try {
            storage.cleanStaleStagingDirs()
            fail("Expected IOException when deletion fails")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Failed to delete stale staging"))
        } finally {
            staleDir.setWritable(true, false)
        }
    }
}
