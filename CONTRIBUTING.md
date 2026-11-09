# Contributing

## Build

Keeper is verified with:

- JDK 21 (Temurin 21.0.13)
- Android SDK platform 37.0 and build-tools 37.0.0
- Gradle wrapper 9.7.0

```bash
export JAVA_HOME="$HOME/.local/keeper-toolchain/jdk21"
export ANDROID_HOME="$HOME/.local/keeper-toolchain/android-sdk"
bash ./gradlew :contracts:test :app:assembleDebug
bash scripts/verify-phase0.sh
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Rules

- Keeper declares no `INTERNET` permission. The merged manifest is checked by
  `scripts/check-manifest-no-internet.sh` on every gate.
- Vault file content is AES-256-GCM ciphertext. Plaintext writes are forbidden;
  allowed encrypted writes carry the `KEEPER_ENCRYPTED_WRITE` marker.
- The contract interfaces are frozen. A change needs a version bump, a written
  rationale, and review.
- Add tests for behavior changes. Unit tests and the instrumented suite must
  pass.
- Never commit keystores, `local.properties`, vault data, decrypted content, or
  intruder images.

## Pull requests

Describe the behavior or threat-model change, the tests you ran, and any update
needed in `LIMITATIONS.md`. Security fixes should follow `SECURITY.md` rather
than starting with a public pull request.
