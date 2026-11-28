#!/bin/bash
# Pre-flight on the Mac: builds the CyanBridge iOS app (CyanBridgeKMPHost) for the iOS Simulator, unsigned, and runs
# only CyanBridgeKMPHostTests/testLiteRtModelsDownloadAndRun. That test downloads the LiteRT models listed in the
# shared Kotlin catalog from Hugging Face and runs each one (CPU + Metal GPU), so it proves the Kotlin download code,
# the Swift LiteRT bridge and the LiteRT 2.4.0 / LiteRT-LM 0.20.0 coexistence compile and work.
#
# Usage: ios/scripts/host_litert_sim_test.sh
# Needs: Xcode, JDK 17, an Android SDK (ANDROID_HOME or android/CyanBridge/local.properties sdk.dir), cmake (for
# llama.xcframework), network. Exit status: non-zero when the build or the test fails.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
OUT="$ROOT/ios/out"
mkdir -p "$OUT"
"$ROOT/ios/scripts/check_host_prereqs.sh"

UDID="$(xcrun simctl list devices available -j | /usr/bin/python3 -c '
import json, sys
devs = [d for rt, ds in json.load(sys.stdin)["devices"].items() if "iOS" in rt for d in ds
        if d.get("isAvailable") and d["name"].startswith("iPhone")]
devs.sort(key=lambda d: d["state"] != "Booted")
print(devs[0]["udid"] if devs else "")')"
[ -n "$UDID" ] || { echo "no iPhone simulator available (Xcode > Settings > Components)" >&2; exit 1; }

LOG="$OUT/host_litert_sim_test.log"
echo "simulator $UDID; log: $LOG (the first build compiles the Kotlin framework and takes several minutes)"
set +e
xcodebuild test -project ios/QCSDKDemo.xcodeproj -scheme CyanBridgeKMPHost -configuration Debug \
  -destination "platform=iOS Simulator,id=$UDID" -derivedDataPath "$OUT/host-sim" \
  -only-testing:CyanBridgeKMPHostTests/CyanBridgeKMPHostTests/testLiteRtModelsDownloadAndRun \
  CODE_SIGNING_ALLOWED=NO ENABLE_USER_SCRIPT_SANDBOXING=NO > "$LOG" 2>&1
STATUS=$?
set -e
grep -E "error:|LITERT_SELFTEST|Test Case|TEST (SUCCEEDED|FAILED)|\*\* " "$LOG" | tail -40 || true
echo "xcodebuild exit $STATUS"
exit "$STATUS"
