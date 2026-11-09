#!/usr/bin/env bash
# Phase 1 gate: full commit checks + crypto tests + 80% coverage + instrumented-test compile.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN="${KEEPER_TOOLCHAIN:-$HOME/.local/keeper-toolchain}"

export JAVA_HOME="${JAVA_HOME:-$TOOLCHAIN/jdk21}"
export ANDROID_HOME="${ANDROID_HOME:-$TOOLCHAIN/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$TOOLCHAIN/gradle-9.7.0/bin:$ANDROID_HOME/platform-tools:$PATH"

cd "$ROOT"
mkdir -p build/reports

echo "== Phase 0/commit checks =="
bash scripts/verify-phase0.sh

echo "== Phase 1: crypto unit tests, coverage gate, instrumented-test compile =="
bash ./gradlew \
    :crypto:testDebugUnitTest \
    :crypto:jacocoDebugUnitTestCoverage \
    :crypto:compileDebugAndroidTestKotlin \
    2>&1 | tee build/reports/phase1-gradle.log

python3 - <<'PY'
import glob
import sys
import xml.etree.ElementTree as ET

report = "crypto/build/reports/jacoco/jacocoDebugUnitTestReport/jacocoDebugUnitTestReport.xml"
root = ET.parse(report).getroot()
counter = root.find("counter[@type='INSTRUCTION']")
covered = int(counter.get("covered"))
missed = int(counter.get("missed"))
ratio = covered / (covered + missed)
print(f"JaCoCo instruction coverage: {covered}/{covered + missed} = {ratio:.2%}")
if ratio < 0.80:
    print("FAIL: crypto instruction coverage is below 80%")
    sys.exit(1)

tests = failures = errors = skipped = 0
for path in glob.glob("crypto/build/test-results/testDebugUnitTest/TEST-*.xml"):
    suite = ET.parse(path).getroot()
    tests += int(suite.get("tests"))
    failures += int(suite.get("failures"))
    errors += int(suite.get("errors"))
    skipped += int(suite.get("skipped"))
print(f"Crypto unit tests: tests={tests} failures={failures} errors={errors} skipped={skipped}")
if failures or errors:
    print("FAIL: crypto unit tests failed")
    sys.exit(1)
print("PASS: crypto coverage >= 80% and all unit tests passed")
PY

echo
echo "PHASE 1 GATE: PASS"
echo "Instrumented Keystore tests compiled; run on a real device for Phase 4."
echo "Evidence: build/reports/phase1-gradle.log"
