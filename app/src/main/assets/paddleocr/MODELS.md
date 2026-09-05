# Bundled PaddleOCR models

Both models are published by PaddlePaddle under the Apache License 2.0 and are bundled so OCR works
fully offline. Runtime screenshots and recognized text are not sent to a server.

## Text detection

- Model: `PaddlePaddle/PP-OCRv6_tiny_det_onnx`
- Revision: `2ba1506c0380b8f0b03dd142459aac66d4421f6c`
- Source: https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_det_onnx
- File: `det/inference.onnx`
- SHA-256: `193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8`

## Text recognition

- Model: `PaddlePaddle/eslav_PP-OCRv5_mobile_rec_onnx`
- Revision: `9a32171fc5718746875e1a261818884517975013`
- Source: https://huggingface.co/PaddlePaddle/eslav_PP-OCRv5_mobile_rec_onnx
- Files:
  - `rec/inference.onnx` — SHA-256 `b3018ef2b09a0250b6e0c8e871c927098363e5fd4df890cc68e8358eb0aaf1bd`
  - `rec/inference.yml` — SHA-256 `025039bac23eb4a308efcefa4d58eab3af440767815c6ba6938468bf6353ee5a`

The East Slavic recognizer covers Russian, English, Ukrainian, and Belarusian text.
