package com.akslabs.circletosearch.ocr

import java.util.Locale

/**
 * Metadata definition for an OCR recognition language pack.
 *
 * Built-in packs ([isBundled] = true) are bundled in the application's assets and work fully
 * offline without downloading. Downloadable packs ([isBundled] = false) reference pinned official
 * PaddlePaddle ONNX + YAML release artifacts on HuggingFace with exact immutable commit SHAs,
 * sizes, and SHA-256 digests.
 */
data class OcrLanguagePack(
    val id: String,
    val displayName: String,
    val coverageDescription: String,
    val isBundled: Boolean,
    val modelRepo: String? = null,
    val commitSha: String? = null,
    val onnxFilename: String = "inference.onnx",
    val onnxSize: Long = 0L,
    val onnxSha256: String = "",
    val yamlFilename: String = "inference.yml",
    val yamlSize: Long = 0L,
    val yamlSha256: String = "",
) {
    val totalSizeBytes: Long get() = onnxSize + yamlSize

    val formattedSize: String get() {
        if (isBundled) return "Included with app"
        val mb = totalSizeBytes / (1024.0 * 1024.0)
        return "%.1f MB".format(Locale.US, mb)
    }

    fun onnxUrl(): String? {
        if (modelRepo == null || commitSha == null) return null
        return "https://huggingface.co/$modelRepo/resolve/$commitSha/$onnxFilename"
    }

    fun yamlUrl(): String? {
        if (modelRepo == null || commitSha == null) return null
        return "https://huggingface.co/$modelRepo/resolve/$commitSha/$yamlFilename"
    }
}
