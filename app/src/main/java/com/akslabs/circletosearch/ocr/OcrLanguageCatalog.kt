package com.akslabs.circletosearch.ocr

/**
 * Curated catalog of official compatible PaddleOCR recognition packs.
 *
 * All packs are official PaddlePaddle PP-OCRv5 mobile recognition ONNX + matching inference.yml
 * pairs published under the Apache License 2.0. Hashes and commit SHAs are pinned and verified.
 * All models use BGR preprocessing, input height 48, and CTCLabelDecode matching the engine.
 */
object OcrLanguageCatalog {
    const val BUNDLED_PACK_ID = "eslav"

    val bundledPack = OcrLanguagePack(
        id = BUNDLED_PACK_ID,
        displayName = "East Slavic",
        coverageDescription = "Russian, English, Ukrainian, and Belarusian",
        isBundled = true,
        modelRepo = "PaddlePaddle/eslav_PP-OCRv5_mobile_rec_onnx",
        commitSha = "9a32171fc5718746875e1a261818884517975013",
        onnxFilename = "inference.onnx",
        onnxSize = 7943534L,
        onnxSha256 = "b3018ef2b09a0250b6e0c8e871c927098363e5fd4df890cc68e8358eb0aaf1bd",
        yamlFilename = "inference.yml",
        yamlSize = 5122L,
        yamlSha256 = "025039bac23eb4a308efcefa4d58eab3af440767815c6ba6938468bf6353ee5a",
    )

    val downloadablePacks: List<OcrLanguagePack> = listOf(
        OcrLanguagePack(
            id = "latin",
            displayName = "Latin script",
            coverageDescription = "English, Spanish, French, German, Portuguese, Italian, Dutch, Polish, Turkish, Vietnamese, and more",
            isBundled = false,
            modelRepo = "PaddlePaddle/latin_PP-OCRv5_mobile_rec_onnx",
            commitSha = "89d3a50e2c27e2e7cceeab0e944c25c807d5db4f",
            onnxFilename = "inference.onnx",
            onnxSize = 8042023L,
            onnxSha256 = "7888113072263cb471b93f66dd5e2ad70548dc526fa1ace760d0d973dd121498",
            yamlFilename = "inference.yml",
            yamlSize = 6817L,
            yamlSha256 = "0bbe984570f597af3638e50bdf2e8276f3ab26a61966096538b3b0d1849f5c84",
        ),
        OcrLanguagePack(
            id = "zh_en",
            displayName = "Chinese & English",
            coverageDescription = "Simplified Chinese, Traditional Chinese, and English",
            isBundled = false,
            modelRepo = "PaddlePaddle/PP-OCRv5_mobile_rec_onnx",
            commitSha = "ed152b8b495f84de93cda5709d768548a9127622",
            onnxFilename = "inference.onnx",
            onnxSize = 16534782L,
            onnxSha256 = "da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092",
            yamlFilename = "inference.yml",
            yamlSize = 148345L,
            yamlSha256 = "5dfeb2777f6d0db8177d8128a8acfcf6e6276dc4ac73ea3bf0dc06d6a5e85d8e",
        ),
        OcrLanguagePack(
            id = "korean",
            displayName = "Korean",
            coverageDescription = "Korean (Hangul, Hanja) and English",
            isBundled = false,
            modelRepo = "PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx",
            commitSha = "5c6f574b8e2230adf4287b33e736d71b9fabd28e",
            onnxFilename = "inference.onnx",
            onnxSize = 13418787L,
            onnxSha256 = "92f0b7785e64fc9090106a241cf4c1eb97472824558272751b88a2a4476d3a08",
            yamlFilename = "inference.yml",
            yamlSize = 96039L,
            yamlSha256 = "f757fa1c40e99edcf27e9cce879b93eb2a51fa46f5ef39095689b8c37dd75998",
        ),
        OcrLanguagePack(
            id = "devanagari",
            displayName = "Devanagari",
            coverageDescription = "Hindi, Marathi, Sanskrit, Nepali, and English",
            isBundled = false,
            modelRepo = "PaddlePaddle/devanagari_PP-OCRv5_mobile_rec_onnx",
            commitSha = "251aec19e36739540d35e2cc943f6aa7503b98e5",
            onnxFilename = "inference.onnx",
            onnxSize = 7912311L,
            onnxSha256 = "cb789212ce96c69d3e74728ae4309d179281d68cb3945d0616b67cafab41c986",
            yamlFilename = "inference.yml",
            yamlSize = 5027L,
            yamlSha256 = "9bd172dd26440c8ce94d1cde5d5baea6aefdc7cf3c5c8492e0beedef656d4e54",
        ),
    )

    val allPacks: List<OcrLanguagePack> = listOf(bundledPack) + downloadablePacks

    private val packsById: Map<String, OcrLanguagePack> = allPacks.associateBy { it.id }

    fun getPack(id: String): OcrLanguagePack? = packsById[id]

    fun isSupportedPackId(id: String): Boolean = packsById.containsKey(id)

    val supportedPackIds: Set<String> get() = packsById.keys
}
