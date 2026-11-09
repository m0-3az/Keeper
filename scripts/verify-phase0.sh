#!/usr/bin/env bash
# Phase 0 / every-commit gate. Produces raw proof-of-work output under build/reports/.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN="${KEEPER_TOOLCHAIN:-$HOME/.local/keeper-toolchain}"

export JAVA_HOME="${JAVA_HOME:-$TOOLCHAIN/jdk21}"
export ANDROID_HOME="${ANDROID_HOME:-$TOOLCHAIN/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$TOOLCHAIN/gradle-9.7.0/bin:$ANDROID_HOME/platform-tools:$PATH"

cd "$ROOT"
mkdir -p build/reports

echo "== Toolchain =="
"$JAVA_HOME/bin/java" -version
bash ./gradlew --version | sed -n '1,8p'

echo "== Gradle: contracts tests, debug APK, lint =="
bash ./gradlew :contracts:test :app:assembleDebug lint --stacktrace 2>&1 \
    | tee build/reports/phase0-gradle.log

echo "== Merged-manifest INTERNET scan =="
bash scripts/check-manifest-no-internet.sh "$ROOT/app/build/outputs/apk/debug/app-debug.apk" \
    | tee build/reports/phase0-manifest.txt

echo "== Network SDK scan =="
bash scripts/check-network-dependencies.sh \
    | tee build/reports/phase0-network.txt

echo "== Source hygiene scan =="
bash scripts/check-source-hygiene.sh \
    | tee build/reports/phase0-source.txt

echo
echo "PHASE 0 GATE: PASS"
echo "Evidence: build/reports/phase0-*.log|txt"
