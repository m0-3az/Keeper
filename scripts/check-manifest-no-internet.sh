#!/usr/bin/env bash
# Fails if the merged manifest / built APK requests INTERNET.
#
# The spec requires an automated check on the merged manifest, not reviewer judgment. Checking
# the built APK with aapt exercises the actual merger output and the packaged manifest.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"

if [[ ! -f "$APK" ]]; then
    echo "FAIL: APK not found: $APK" >&2
    echo "Build it first: bash ./gradlew :app:assembleDebug" >&2
    exit 2
fi

AAPT="${AAPT:-}"
if [[ -z "$AAPT" ]]; then
    for candidate in \
        "${ANDROID_HOME:-}/build-tools/37.0.0/aapt" \
        "${ANDROID_HOME:-}/build-tools/36.0.0/aapt" \
        "${ANDROID_SDK_ROOT:-}/build-tools/37.0.0/aapt" \
        "${ANDROID_SDK_ROOT:-}/build-tools/36.0.0/aapt" \
        "$HOME/.local/keeper-toolchain/android-sdk/build-tools/37.0.0/aapt" \
        "$HOME/.local/keeper-toolchain/android-sdk/build-tools/36.0.0/aapt"; do
        if [[ -n "$candidate" && -x "$candidate" ]]; then
            AAPT="$candidate"
            break
        fi
    done
fi
if [[ -z "$AAPT" ]] || [[ ! -x "$AAPT" ]]; then
    echo "FAIL: aapt not found. Set AAPT or ANDROID_HOME." >&2
    exit 2
fi

echo "== Merged-manifest permissions (aapt) =="
PERMISSIONS="$("$AAPT" dump permissions "$APK")"
printf '%s\n' "$PERMISSIONS"

if grep -q 'android.permission.INTERNET' <<<"$PERMISSIONS"; then
    echo "FAIL: android.permission.INTERNET is present in the merged manifest." >&2
    exit 1
fi

# Defense in depth: inspect the binary manifest tree too.
if "$AAPT" dump xmltree "$APK" AndroidManifest.xml | grep -q 'android.permission.INTERNET'; then
    echo "FAIL: INTERNET permission found in AndroidManifest.xml." >&2
    exit 1
fi

echo "PASS: no INTERNET permission in the merged manifest."
