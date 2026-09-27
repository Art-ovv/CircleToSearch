package com.akslabs.circletosearch.ocr

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class InstalledPackFiles(
    val modelFile: File,
    val configFile: File,
)

/**
 * File management and path allowlisting for downloaded OCR language packs.
 *
 * Operates purely on a root [File] directory without Android context or logging dependencies.
 * Protects against path traversal by validating canonical boundaries against [parent + File.separator].
 * Guarantees atomic directory-level installation without destructive file-by-file fallbacks.
 */
open class OcrLanguageStorage(val rootDir: File) {
    val recRootDir: File = File(rootDir, REC_DIR_NAME)
    val stagingRootDir: File = File(rootDir, STAGING_DIR_NAME)

    open fun ensureDirectories() {
        if (!recRootDir.exists()) recRootDir.mkdirs()
        if (!stagingRootDir.exists()) stagingRootDir.mkdirs()
    }

    companion object {
        private const val REC_DIR_NAME = "rec"
        private const val STAGING_DIR_NAME = "staging"
    }

    /**
     * Resolves the target directory for an installed pack after validating allowlisted identity.
     * Prevents arbitrary path traversal.
     */
    fun getPackDir(packId: String): File {
        requireAllowlistedPackId(packId)
        val packDir = File(recRootDir, packId)
        checkCanonicalBoundary(packDir, recRootDir)
        return packDir
    }

    /**
     * Resolves an isolated staging directory for an in-progress download of [packId].
     */
    fun getStagingDir(packId: String): File {
        requireAllowlistedPackId(packId)
        val stagingDir = File(stagingRootDir, packId)
        checkCanonicalBoundary(stagingDir, stagingRootDir)
        return stagingDir
    }

    fun isPackInstalled(packId: String): Boolean {
        if (packId == OcrLanguageCatalog.BUNDLED_PACK_ID) return true
        return getInstalledModelFiles(packId) != null
    }

    /**
     * Returns the pair of installed ONNX and YAML files for [packId], or null if not installed,
     * incomplete, or corrupted. Ensures partial pairs are never reported as installed.
     */
    fun getInstalledModelFiles(packId: String): InstalledPackFiles? {
        val pack = OcrLanguageCatalog.getPack(packId) ?: return null
        if (pack.isBundled) return null

        val packDir = getPackDir(packId)
        if (!packDir.exists() || !packDir.isDirectory) return null

        val modelFile = File(packDir, pack.onnxFilename)
        val configFile = File(packDir, pack.yamlFilename)

        checkCanonicalBoundary(modelFile, packDir)
        checkCanonicalBoundary(configFile, packDir)

        if (!modelFile.exists() || !modelFile.isFile || !modelFile.canRead()) {
            return null
        }
        if (!configFile.exists() || !configFile.isFile || !configFile.canRead()) {
            return null
        }

        // Validate expected sizes to catch partial downloads or corrupted writes
        if (modelFile.length() != pack.onnxSize || configFile.length() != pack.yamlSize) {
            return null
        }

        return InstalledPackFiles(modelFile = modelFile, configFile = configFile)
    }

    /**
     * Cleans up any leftover staging directories from interrupted downloads.
     */
    fun cleanStaleStagingDirs() {
        if (!stagingRootDir.exists()) return
        val files = stagingRootDir.listFiles()
            ?: throw IOException("Failed to list files in staging directory: ${stagingRootDir.absolutePath}")
        for (file in files) {
            if (!file.deleteRecursively()) {
                throw IOException("Failed to delete stale staging directory or file: ${file.absolutePath}")
            }
        }
    }

    /**
     * Atomically moves the complete staging directory to the final pack directory.
     * Guarantees all-or-nothing installation without destructive fallbacks.
     */
    @Synchronized
    open fun atomicallyInstall(pack: OcrLanguagePack, stagingDir: File) {
        require(!pack.isBundled) { "Bundled packs cannot be dynamically installed" }
        requireAllowlistedPackId(pack.id)

        val expectedStagingDir = getStagingDir(pack.id)
        if (stagingDir.canonicalFile != expectedStagingDir.canonicalFile) {
            throw SecurityException("Invalid staging directory: ${stagingDir.path}")
        }

        val stagedModel = File(stagingDir, pack.onnxFilename)
        val stagedConfig = File(stagingDir, pack.yamlFilename)
        checkCanonicalBoundary(stagedModel, stagingDir)
        checkCanonicalBoundary(stagedConfig, stagingDir)

        if (!stagedModel.exists() || stagedModel.length() != pack.onnxSize) {
            throw IOException("Staged ONNX model missing or incomplete for ${pack.id}")
        }
        if (!stagedConfig.exists() || stagedConfig.length() != pack.yamlSize) {
            throw IOException("Staged YAML config missing or incomplete for ${pack.id}")
        }

        ensureDirectories()
        val targetDir = getPackDir(pack.id)
        if (targetDir.exists()) {
            // Never overwrite a valid installed pair; reject before mutation
            if (getInstalledModelFiles(pack.id) != null) {
                throw IllegalStateException("Pack ${pack.id} is already installed")
            }
            // Incomplete orphan target can be cleaned safely with explicit failure handling
            if (!targetDir.deleteRecursively()) {
                throw IOException("Failed to clean orphan target directory for ${pack.id}")
            }
        }

        // Stage validated pair under same filesystem then one atomic directory rename
        val renamed = stagingDir.renameTo(targetDir)
        if (!renamed) {
            try {
                Files.move(stagingDir.toPath(), targetDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                // Preserve active staging on failure
                throw IOException("Failed to atomically install pack ${pack.id}: atomic rename failed", e)
            }
        }
    }

    /**
     * Deletes installed pack files from disk. Bundled packs cannot be deleted.
     */
    @Synchronized
    open fun deleteInstalledPack(packId: String): Boolean {
        if (packId == OcrLanguageCatalog.BUNDLED_PACK_ID) return false
        val pack = OcrLanguageCatalog.getPack(packId) ?: return false
        if (pack.isBundled) return false

        val packDir = getPackDir(packId)
        if (!packDir.exists()) return true
        val deleted = packDir.deleteRecursively()
        if (!deleted) {
            throw IOException("Failed to delete pack directory: ${packDir.absolutePath}")
        }
        return true
    }

    private fun requireAllowlistedPackId(packId: String) {
        require(OcrLanguageCatalog.isSupportedPackId(packId)) {
            "Pack ID '$packId' is not allowlisted in OcrLanguageCatalog"
        }
        require(!packId.contains("..") && !packId.contains("/") && !packId.contains("\\")) {
            "Illegal path traversal token in pack ID: $packId"
        }
    }

    internal fun checkCanonicalBoundary(child: File, parent: File) {
        val childCanonical = child.canonicalFile.path
        val parentCanonical = parent.canonicalFile.path
        val parentPrefix = if (parentCanonical.endsWith(File.separator)) {
            parentCanonical
        } else {
            parentCanonical + File.separator
        }
        if (childCanonical != parentCanonical && !childCanonical.startsWith(parentPrefix)) {
            throw SecurityException("Path traversal attempt detected: $childCanonical outside $parentCanonical")
        }
    }
}
