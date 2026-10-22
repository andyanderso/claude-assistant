# Vendored: openWakeWord Android (Kotlin)

- Upstream: https://github.com/Re-MENTIA/openwakeword-android-kt
- Commit: 397333e14acd5e3d83bb2373ebe505a41c0d37f1 (2025-07-30), library version 0.1.4
- License: Apache License 2.0 (see `LICENSE` and `NOTICE` in this folder)

Copied as source so the app builds without depending on a published Maven artifact.
Changes from upstream: only `build.gradle.kts` (publishing, signing and docs plugins removed,
compileSdk raised to 35, toolchain pin replaced with jvmTarget 17). The Kotlin source is unmodified.

The two shared feature models in `app/src/main/assets/` (`melspectrogram.onnx`,
`embedding_model.onnx`) come from the same upstream repo and the openWakeWord project
(Apache-2.0). Copyright: David Scripka (openWakeWord); embedding model originally Google LLC.
