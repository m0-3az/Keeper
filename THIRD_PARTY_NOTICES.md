# Third-party notices

Keeper includes the following third-party components. Their licenses apply to
those components independently of Keeper's own license.

## MobileFaceNet model

- File: `presence/src/main/assets/mobilefacenet.tflite`
- Source: https://github.com/dangnh0611/MobileFaceNet_TF
- Upstream: https://github.com/sirius-ai/MobileFaceNet_TF (Apache-2.0)
- License: Apache License 2.0; full text in `third_party/MobileFaceNet-LICENSE`
- Attribution: `third_party/NOTICE-MobileFaceNet.md`

The model runs only on-device for owner-presence matching. It never receives
the vault key and cannot decrypt anything.

## AndroidX, Jetpack Compose, Room, CameraX, WorkManager, Lifecycle

Copyright The Android Open Source Project. Licensed under the Apache License,
Version 2.0.

## Kotlin and Kotlin Coroutines

Copyright JetBrains s.r.o. Licensed under the Apache License, Version 2.0.

## Google ML Kit face detection

Bundled through `com.google.mlkit:face-detection`. The transitive Google
datatransport/Firebase encoder code is present in the APK but has no entry
point and no `INTERNET` permission; see `LIMITATIONS.md` section 10a. Review the
upstream artifact notices for the ML Kit terms that apply.

## LiteRT

The on-device inference runtime used for the bundled model. See the upstream
LiteRT notices for the applicable license.

Dependency versions are recorded in `gradle/libs.versions.toml`.
