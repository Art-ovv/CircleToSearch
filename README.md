<div align="center">
  <img src="app/src/main/res/drawable/circletosearch.png" width="160" alt="CircleToSearch icon">
  <h1>CircleToSearch</h1>
  <p>A privacy-conscious Android screen search, OCR, translation, and selection overlay.</p>

  [![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/10)
  [![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
  [![Repository](https://img.shields.io/badge/GitHub-Art--ovv%2FCircleToSearch-181717?logo=github)](https://github.com/Art-ovv/CircleToSearch)
</div>

## About this fork

This repository is an independently maintained fork of
[AKS-Labs/CircleToSearch](https://github.com/AKS-Labs/CircleToSearch). The
comparison baseline for the changes below is the upstream
[v0.5 release](https://github.com/AKS-Labs/CircleToSearch/releases/tag/v0.5).

CircleToSearch captures the current screen after an explicit user gesture and
lets you select text or an image region. Recognition, barcode parsing, and
translation are designed to run on the phone. Network access is used only for
actions that inherently require it, such as a user-requested image or web
search and downloading on-device language models.

## Changes since upstream v0.5

### More reliable system invocation

- Hardened the Android assistant, Accessibility Service, Quick Settings tile,
  screenshot, and overlay hand-off paths against duplicate and stale sessions.
- Added a protected Accessibility-backed fallback for Android builds that keep
  the assistant role but unexpectedly disconnect the Voice Interaction Service.
- Improved process-death, cancellation, timeout, rapid-repeat, and warm-reuse
  handling without silently changing the selected default assistant.
- Reduced conflicts between configurable edge overlays and Android's system
  navigation gestures.

### OCR and text selection

- Expanded on-device OCR for Russian and English text with mixed-polarity,
  adaptive-threshold, skew, small-label, low-contrast, and off-centre handling.
- Improved reading order, line grouping, AssistStructure/OCR merging, and
  filtering of icon-like false positives while preserving short identifiers,
  serial numbers, prices, and codes.
- Added region-aware text recovery so text inside a drawn or highlighted image
  area can be copied without losing image actions.
- Fixed stale selection state and empty-space interactions that could make text
  impossible to select again.

### QR codes, barcodes, translation, and search

- Added multi-pass recognition for QR, Data Matrix, Aztec, PDF417, MaxiCode,
  Codabar, Code 39/93/128, EAN, UPC, ITF, and RSS formats.
- Detected barcode content can be copied immediately; URLs and supported
  entities expose appropriate actions.
- Added on-device language identification and screen translation using ML Kit
  models downloaded by Android when needed.
- Region selection now keeps Share, Save, text Copy, and image-search actions.
- Hardened Google Lens and multi-engine image search fallbacks for Google,
  Bing, Yandex, and TinEye.

### Camera photo search

- Added main-screen Camera button launching the installed system camera via
  `ActivityResultContracts.TakePicture` without requiring app `CAMERA` permissions.
- Captured photos open directly in the local selection overlay with existing image
  selection, on-device OCR text copying, screen and per-text translation, QR/barcode
  scanning, share/save, and explicit external image search.
- Preserves the entire photo without stretching or cropping by decoding off-thread
  with bounded dimensions, normalizing EXIF orientation via platform `ImageDecoder`,
  and aspect-fitting onto a viewport-proportioned canvas with neutral letterboxing.
- Resilient against activity recreation, process death, cancellation, missing camera
  apps, empty output, repeated taps, and stale work, with stable temporary capture paths,
  completion sidecar markers, and best-effort cleanup of orphaned temporary files
  older than 24 hours (with in-flight captures protected for at least 15 minutes).

### UI, performance, and storage

- Reworked scan feedback, selection trails, control transitions, and result
  overlays with Compose animations that avoid full-screen recomposition.
- Coordinated OCR and barcode jobs, bounded stale work, reused OCR engines, and
  tightened bitmap ownership to reduce latency and memory pressure.
- Bounded transient image caches and added lifecycle cleanup without clearing
  user settings or downloaded OCR models.
- Removed donation prompts and unrelated promotional UI from this fork.

## Build and install from source

Requirements:

- JDK 17
- Android SDK 36
- Android platform tools (`adb`) for command-line installation

```bash
git clone https://github.com/Art-ovv/CircleToSearch.git
cd CircleToSearch
./gradlew assembleDebug
```

The build produces two APKs:

- `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` for Pixel 8 and most
  modern Android phones.
- `app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk` for older 32-bit ARM
  devices.

To install the Pixel 8/64-bit build on a connected phone:

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

The `-r` option updates an installation signed with the same key and preserves
its app data. Android will reject an update signed with a different certificate;
in that case the existing package must be uninstalled first, which also removes
its app data and settings.

After installation, launch CircleToSearch, enable its Accessibility Service,
and select it as Android's **Digital assistant app**. You can then invoke it with
the assistant gesture, the configured overlay gesture, or the Quick Settings
tile.

## Development

Useful local checks:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew lintDebug
```

> [!NOTE]
> Camera photo search features are verified via automated unit tests; physical-device testing has not been performed in this environment.

Connected instrumentation tests are intentionally separate because they
install test packages on the selected device:

```bash
./gradlew connectedDebugAndroidTest
```

## Privacy notes

- OCR, QR/barcode parsing, language identification, and translation inference
  stay on-device after their required models are available.
- The app does not add analytics, advertising, or background screenshot upload.
- A selected image region leaves the device only after the user explicitly
  requests an external image-search action.

## Credits and license

The original application and its history belong to
[AKS-Labs/CircleToSearch](https://github.com/AKS-Labs/CircleToSearch). This fork
is a derivative of the upstream GPL-3.0 project; see the
[upstream license](https://github.com/AKS-Labs/CircleToSearch/blob/main/LICENSE)
and the copyright headers retained in the source files.
