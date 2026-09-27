# PaddleOCR models

All models are published by PaddlePaddle under the Apache License 2.0. This OCR runs on device. Language downloads do not upload screenshots or text.

## Text detection (bundled)

- Model: `PaddlePaddle/PP-OCRv6_tiny_det_onnx`
- Revision: `2ba1506c0380b8f0b03dd142459aac66d4421f6c`
- Source: https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_det_onnx
- File: `det/inference.onnx`
- SHA-256: `193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8`

The text detector is shared across all recognition language packs.

## Text recognition

### Bundled (default fallback)

- Pack: `eslav` (East Slavic)
- Coverage: Russian, English, Ukrainian, and Belarusian
- Model: `PaddlePaddle/eslav_PP-OCRv5_mobile_rec_onnx`
- Revision: `9a32171fc5718746875e1a261818884517975013`
- Source: https://huggingface.co/PaddlePaddle/eslav_PP-OCRv5_mobile_rec_onnx
- Files:
  - `rec/inference.onnx` — SHA-256 `b3018ef2b09a0250b6e0c8e871c927098363e5fd4df890cc68e8358eb0aaf1bd` (7,943,534 bytes)
  - `rec/inference.yml` — SHA-256 `025039bac23eb4a308efcefa4d58eab3af440767815c6ba6938468bf6353ee5a` (5,122 bytes)

### Downloadable language packs

All downloadable packs are fetched on-demand from official pinned HuggingFace releases over HTTPS. Only the selected pack is loaded into memory.

#### Latin script (`latin`)
- Coverage: English, Spanish, French, German, Portuguese, Italian, Dutch, Polish, Turkish, Vietnamese, and more
- Model: `PaddlePaddle/latin_PP-OCRv5_mobile_rec_onnx`
- Revision: `89d3a50e2c27e2e7cceeab0e944c25c807d5db4f`
- Source: https://huggingface.co/PaddlePaddle/latin_PP-OCRv5_mobile_rec_onnx
- Files:
  - `inference.onnx` — SHA-256 `7888113072263cb471b93f66dd5e2ad70548dc526fa1ace760d0d973dd121498` (8,042,023 bytes)
  - `inference.yml` — SHA-256 `0bbe984570f597af3638e50bdf2e8276f3ab26a61966096538b3b0d1849f5c84` (6,817 bytes)

#### Chinese & English (`zh_en`)
- Coverage: Simplified Chinese, Traditional Chinese, and English
- Model: `PaddlePaddle/PP-OCRv5_mobile_rec_onnx`
- Revision: `ed152b8b495f84de93cda5709d768548a9127622`
- Source: https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx
- Files:
  - `inference.onnx` — SHA-256 `da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092` (16,534,782 bytes)
  - `inference.yml` — SHA-256 `5dfeb2777f6d0db8177d8128a8acfcf6e6276dc4ac73ea3bf0dc06d6a5e85d8e` (148,345 bytes)

#### Korean (`korean`)
- Coverage: Korean (Hangul, Hanja) and English
- Model: `PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx`
- Revision: `5c6f574b8e2230adf4287b33e736d71b9fabd28e`
- Source: https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx
- Files:
  - `inference.onnx` — SHA-256 `92f0b7785e64fc9090106a241cf4c1eb97472824558272751b88a2a4476d3a08` (13,418,787 bytes)
  - `inference.yml` — SHA-256 `f757fa1c40e99edcf27e9cce879b93eb2a51fa46f5ef39095689b8c37dd75998` (96,039 bytes)

#### Devanagari (`devanagari`)
- Coverage: Hindi, Marathi, Sanskrit, Nepali, and English
- Model: `PaddlePaddle/devanagari_PP-OCRv5_mobile_rec_onnx`
- Revision: `251aec19e36739540d35e2cc943f6aa7503b98e5`
- Source: https://huggingface.co/PaddlePaddle/devanagari_PP-OCRv5_mobile_rec_onnx
- Files:
  - `inference.onnx` — SHA-256 `cb789212ce96c69d3e74728ae4309d179281d68cb3945d0616b67cafab41c986` (7,912,311 bytes)
  - `inference.yml` — SHA-256 `9bd172dd26440c8ce94d1cde5d5baea6aefdc7cf3c5c8492e0beedef656d4e54` (5,027 bytes)
