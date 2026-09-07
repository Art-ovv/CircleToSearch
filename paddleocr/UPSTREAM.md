# PaddleOCR Android SDK source

The Kotlin sources in this module are vendored from the official PaddleOCR repository:

- Repository: https://github.com/PaddlePaddle/PaddleOCR
- Revision: `2661c7c0ef5c613e8f93c6e93b2e052399f0f854`
- Source path: `deploy/ppocr-android/ppocr-sdk`
- License: Apache License 2.0

The local Gradle configuration only changes the Android/JVM targets and dependency coordinates so
the SDK can be built as part of CircleToSearch.

Runtime dependencies are the official Android artifacts from Maven Central:

- `com.microsoft.onnxruntime:onnxruntime-android:1.29.0` — MIT License
- `org.opencv:opencv:4.12.0` — Apache License 2.0
