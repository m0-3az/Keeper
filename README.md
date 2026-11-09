# Keeper

Keeper is an Android vault (`com.keeper.vault`) built with Kotlin and Jetpack Compose. It stores
vault metadata in Room and file content only as AES-256-GCM ciphertext. It requests no
`INTERNET` permission.

**Status:** v0.1.5 preview. All six phases are merged: AES-256-GCM vault storage,
password + biometric unlock, Presence Shield, isolation warnings, and the opt-in duress/decoy
vault. First run requires accepting the Terms & Acknowledgement; intruder capture is off by
default and requires a separate activation consent. Every intruder capture requires a visible
warning and explicit on-screen confirmation; face-mismatch capture is disabled. The confirmation
token is issued only by the “Take photo” action, and the UI reports “Capture started” rather than
claiming the image already exists; the intruder log records the result. v0.1.4 adds a 12-second
watchdog around camera acquisition, binding, and the still so a blocked camera cannot leave the
foreground service running indefinitely. v0.1.5 adds a narrow `<queries>` declaration so the
root-manager package probe works on Android 11+ without requesting `QUERY_ALL_PACKAGES`. A
read-only Network check screen reports status and opens system settings but cannot toggle radios.
The delivered v0.1.0 release APK declares no `INTERNET` permission. The intruder-capture flow was
exercised on a physical Galaxy A52 on 2026-11-03;
WorkManager, biometric, and long-duration hardware checks remain open. Read `LIMITATIONS.md` and
`PRIVACY.md` before using or reviewing it.

The source is available for review. The app is **not cleared for public APK distribution**;
legal review of the intruder-capture disclosure is pending.

## Toolchain

The verified local toolchain is:

- Temurin JDK 21.0.13
- Gradle 9.7.0 (wrapper)
- Android Gradle Plugin 9.3.1
- Kotlin 2.4.21 (built-in Kotlin, KGP on the buildscript classpath)
- Android SDK platform 37.0, Build Tools 37.0.0
- `minSdk 26`, `compileSdk 37`, `targetSdk 36`

`local.properties` points at the local SDK and is git-ignored. On another machine, set
`ANDROID_HOME` and `JAVA_HOME`, or edit `local.properties`.

## Build

```bash
export JAVA_HOME="$HOME/.local/keeper-toolchain/jdk21"
export ANDROID_HOME="$HOME/.local/keeper-toolchain/android-sdk"
bash ./gradlew :contracts:test :app:assembleDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Gate

```bash
bash scripts/verify-phase0.sh
```

This runs the contracts tests, builds the debug APK, runs lint, checks the merged manifest with
`aapt` for `INTERNET`, scans the runtime classpath for network SDKs, and scans source for
plaintext writes, cache/external-storage writes, and secret logging.

The same command runs from `.githooks/pre-commit`. Enable it once per clone:

```bash
git config core.hooksPath .githooks
```

## Module map

| Module | Phase | Purpose |
|---|---|---|
| `:contracts` | 0 | Nine frozen KDoc'd interfaces |
| `:crypto` | 1 | Key hierarchy, AES-256-GCM, Keystore |
| `:vault` | 2 | Room metadata, encrypted store, vault UI |
| `:auth` | 3 | Secrets, unlock, lock controller, attempts |
| `:presence` | 4 | CameraX, bundled ML Kit, MobileFaceNet, Presence Shield |
| `:isolation` | 5 | Root/debug/accessibility warnings, tamper log |
| `:duress` | 6 | Decoy vault, opt-in intruder capture, purge |
| `:app` | — | Shell, manual DI, navigation |

## Security posture

- No `INTERNET` permission; checked on the merged APK with `aapt` on every commit.
- Bundled ML Kit pulls Google datatransport transitively. Keeper refuses `INTERNET` at manifest
  merge and removes the three telemetry entry points. The code is present but cannot initialize
  or upload. See `LIMITATIONS.md`.
- `FLAG_SECURE` is set on the shell and every vault-facing screen stub.
- No plaintext writes: the only file writes allowed are inside `:crypto`/`:vault` and must carry
  the `KEEPER_ENCRYPTED_WRITE` marker.
- Keys live in the Android Keystore; the vault key is protected by the user's secret. The
  hierarchy is documented in `docs/KEY_HIERARCHY.md`.

## Known limits

Read `LIMITATIONS.md` before using or reviewing the app. Face matching and blink liveness are
deterrents, not guarantees. A rooted device can capture RAM while the vault is open.

## Terms, privacy, and legal

- First run is blocked by a scrollable Terms & Acknowledgement screen. Acceptance is stored in
  `keeper_terms` (`accepted_version`); a version bump re-prompts. Declining exits the app.
- Intruder capture remains off by default. Enabling it requires a separate activation consent in
  `keeper_intruder_consent`. Every capture requires a visible warning and an explicit “Take photo”
  confirmation; cancelling starts no camera. Face-mismatch capture is disabled in this release.
- The Network check screen is read-only: it reports Wi-Fi, mobile data, VPN, and airplane-mode
  state, and opens public system settings screens. Keeper cannot toggle or restore radios and does
  not claim to.
- Keeper is local-only: no `INTERNET` permission, no telemetry, no analytics, and no data
  collection. Face embeddings and settings stay on the device. See `PRIVACY.md`.
- Intruder images are opt-in, encrypted locally, and retained for 7/30/90 days (default 30) or
  forever. They are never uploaded. The user is responsible for lawful use and notice.
- Legal review of intruder capture is **pending**. The source is public for review, but Keeper is
  **not cleared for public APK distribution**; APK release remains blocked until a named reviewer
  signs off. See `LIMITATIONS.md` §8.

## Face model

`presence/src/main/assets/mobilefacenet.tflite` is vendored (4.9 MB) and approved for use. Source,
license, SHA-256, input/output shape, and the attribute requirements are recorded in
`presence/src/main/assets/README.md` and `third_party/NOTICE-MobileFaceNet.md`. The runtime is
LiteRT 2.3.2, the TFLite successor, because TensorFlow Lite 2.17 is not 16 KB page-size aligned.
The model runs only on-device and never receives the vault key.

## License

Keeper is offered under your choice of Apache-2.0, MIT, or GPL-3.0. See `LICENSE` and
`LICENSES/`. Third-party components keep their own licenses; see `THIRD_PARTY_NOTICES.md`.

## Security

Do not open a public issue for a vulnerability. Use GitHub's private vulnerability reporting;
see `SECURITY.md`.
