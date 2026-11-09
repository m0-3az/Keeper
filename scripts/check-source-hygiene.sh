#!/usr/bin/env bash
# Source hygiene checks required by the brief:
#   - no stray plaintext file writes outside the encrypted-write boundary
#   - no external-storage or cache writes
#   - no secret logging
#   - no INTERNET/cleartext escape hatch in the manifest
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
FAIL=0

SOURCE_DIRS=(app/src/main contracts/src/main crypto/src/main vault/src/main auth/src/main presence/src/main isolation/src/main duress/src/main)

echo "== Plaintext-write scan =="
WRITE_HITS="$(grep -RInE 'FileOutputStream|Files\.write|FileWriter|PrintWriter|RandomAccessFile' \
    --include='*.kt' --include='*.java' \
    "${SOURCE_DIRS[@]}" 2>/dev/null || true)"
if [[ -n "$WRITE_HITS" ]]; then
    # A file may opt in explicitly with KEEPER_ENCRYPTED_WRITE, and only inside crypto/vault.
    while IFS= read -r hit; do
        file="${hit%%:*}"
        if [[ "$file" == crypto/* || "$file" == vault/* ]] && grep -q 'KEEPER_ENCRYPTED_WRITE' "$file"; then
            echo "ALLOW: $hit"
        else
            echo "FAIL: $hit" >&2
            FAIL=1
        fi
    done <<<"$WRITE_HITS"
fi

echo "== External-storage / cache scan =="
EXTERNAL_HITS="$(grep -RInE 'getExternalFilesDir|getExternalCacheDir|Environment\.getExternalStorage|MediaStore|createTempFile|cacheDir' \
    --include='*.kt' --include='*.java' \
    "${SOURCE_DIRS[@]}" 2>/dev/null || true)"
if [[ -n "$EXTERNAL_HITS" ]]; then
    echo "$EXTERNAL_HITS" >&2
    echo "FAIL: external storage, cache, or temp-file usage in main source." >&2
    FAIL=1
fi

echo "== Secret-logging scan =="
SECRET_LOG_HITS="$(grep -RInE '\bLog\.(v|d|i|w|e)\([^)]*(secret|password|passphrase|pin|pattern|key|vaultKey|embedding|face)' \
    --include='*.kt' --include='*.java' \
    "${SOURCE_DIRS[@]}" 2>/dev/null || true)"
PRINT_HITS="$(grep -RInE '\bprintln\(|System\.out' \
    --include='*.kt' --include='*.java' \
    "${SOURCE_DIRS[@]}" 2>/dev/null || true)"
if [[ -n "$SECRET_LOG_HITS" || -n "$PRINT_HITS" ]]; then
    [[ -n "$SECRET_LOG_HITS" ]] && echo "$SECRET_LOG_HITS" >&2
    [[ -n "$PRINT_HITS" ]] && echo "$PRINT_HITS" >&2
    echo "FAIL: debug logging found in main source." >&2
    FAIL=1
fi

echo "== Manifest escape-hatch scan =="
# INTERNET is enforced authoritatively by aapt on the merged APK. Here we only reject the
# cleartext escape hatch; the removal declaration itself necessarily contains the word INTERNET.
if grep -q 'usesCleartextTraffic="true"' app/src/main/AndroidManifest.xml; then
    echo "FAIL: cleartext traffic enabled in the manifest." >&2
    FAIL=1
fi

if [[ "$FAIL" -ne 0 ]]; then
    echo "FAIL: source hygiene checks failed." >&2
    exit 1
fi

echo "PASS: source hygiene checks passed."
