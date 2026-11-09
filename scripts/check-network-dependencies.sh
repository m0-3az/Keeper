#!/usr/bin/env bash
# Fails if a known network client/analytics SDK appears on the app runtime classpath.
# This complements the merged-manifest INTERNET check: a library can be present without yet
# adding the permission, and would be a latent risk.
#
# Bundled ML Kit transitively brings Google datatransport + Firebase encoders. That telemetry
# pipeline is decapitated at manifest merge (INTERNET refused, three entry points removed). This
# script allows only those specific artifacts, and only while the manifest strip is present.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN="${KEEPER_TOOLCHAIN:-$HOME/.local/keeper-toolchain}"
if [[ -z "${JAVA_HOME:-}" && -x "$TOOLCHAIN/jdk21/bin/java" ]]; then
    export JAVA_HOME="$TOOLCHAIN/jdk21"
    export PATH="$JAVA_HOME/bin:$PATH"
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$TOOLCHAIN/android-sdk" ]]; then
    export ANDROID_HOME="$TOOLCHAIN/android-sdk"
    export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi
cd "$ROOT"
mkdir -p "$ROOT/build/reports"
OUT="$ROOT/build/reports/dependencies-debug.txt"
MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"

bash "$ROOT/gradlew" -q :app:dependencies --configuration debugRuntimeClasspath >"$OUT"

DENY='com\.squareup\.okhttp3|com\.squareup\.retrofit2|com\.android\.volley|com\.google\.firebase|com\.google\.android\.gms:play-services-(auth|ads|analytics|measurement|location|maps)|org\.chromium\.net|cronet|io\.grpc|io\.ktor|org\.apache\.http|io\.sentry|com\.amplitude|com\.mixpanel|com\.facebook\.|com\.google\.android\.datatransport'
MLKIT_ALLOW='com\.google\.android\.datatransport|com\.google\.firebase'

MLKIT_STRIPPED=false
if grep -q 'android.permission.INTERNET' "$MANIFEST" \
    && grep -q 'tools:node="remove"' "$MANIFEST" \
    && grep -q 'TransportBackendDiscovery' "$MANIFEST" \
    && grep -q 'JobInfoSchedulerService' "$MANIFEST" \
    && grep -q 'AlarmManagerSchedulerBroadcastReceiver' "$MANIFEST"; then
    MLKIT_STRIPPED=true
fi

MATCHES="$(grep -Ei "$DENY" "$OUT" || true)"
if [[ -n "$MATCHES" ]]; then
    UNALLOWED="$(printf '%s\n' "$MATCHES" | grep -Eiv "$MLKIT_ALLOW" || true)"
    if [[ -n "$UNALLOWED" ]]; then
        echo "FAIL: denylisted network SDK found on debugRuntimeClasspath:" >&2
        printf '%s\n' "$UNALLOWED" | head -40 >&2
        exit 1
    fi
    if [[ "$MLKIT_STRIPPED" != "true" ]]; then
        echo "FAIL: ML Kit telemetry libraries are present and the manifest strip is missing." >&2
        printf '%s\n' "$MATCHES" | head -20 >&2
        exit 1
    fi
    echo "WARN: ML Kit's transitive datatransport/Firebase encoders are present, but the"
    echo "      INTERNET permission and all three telemetry entry points are removed at merge."
fi

echo "PASS: no usable denylisted network SDK on debugRuntimeClasspath."
echo "Report: $OUT"
