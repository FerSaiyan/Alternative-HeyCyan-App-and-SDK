#!/bin/bash
# Builds the signed CyanBridge iOS app + its XCTest bundle as the archive Google Device Run takes
# (https://docs.cloud.google.com/developer-device-platform/device-run/ios): Debug-iphoneos products and the
# .xctestrun at the zip root, limited to CyanBridgeKMPHostTests/testLiteRtModelsDownloadAndRun (the LiteRT model
# download + run self-test). Also checks that the LiteRT 2.4.0 framework binds to CLiteRT, never to LiteRT-LM's copy.
#
# Usage: DEVELOPMENT_TEAM=MZ7DJZ88T2 [DEVELOPER_DIR=<Xcode.app>] ios/scripts/build_host_xctest_zip.sh [<out.zip>]
#   The app's bundle id (com.fersaiyan.cyanbridge) and Hotspot / Wi-Fi entitlements need the CyanBridge team MZ7DJZ88T2.
# Output: ios/out/CyanBridgeKMPHost-litert-xctest.zip (+ .sha256).
# Exit status: 1 setup/build failure, 2 the archive is missing a part, 3 a LiteRT symbol binds to the wrong framework.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
: "${DEVELOPMENT_TEAM:?set DEVELOPMENT_TEAM (CyanBridge team: MZ7DJZ88T2)}"
OUT_DIR="$ROOT/ios/out"
OUT="${1:-$OUT_DIR/CyanBridgeKMPHost-litert-xctest.zip}"
mkdir -p "$OUT_DIR"
"$ROOT/ios/scripts/check_host_prereqs.sh"

BUILD="$OUT_DIR/host-device"
LOG="$OUT_DIR/build_host_xctest.log"
rm -rf "$BUILD"
echo "building for device (team $DEVELOPMENT_TEAM); log: $LOG"
if ! xcodebuild build-for-testing -project ios/QCSDKDemo.xcodeproj -scheme CyanBridgeKMPHost -configuration Debug \
    -destination "generic/platform=iOS" -derivedDataPath "$BUILD" -allowProvisioningUpdates \
    DEVELOPMENT_TEAM="$DEVELOPMENT_TEAM" ENABLE_USER_SCRIPT_SANDBOXING=NO > "$LOG" 2>&1; then
  grep -E "error:|Provisioning|No profiles|requires a development team" "$LOG" | head -30 >&2 || true
  echo "xcodebuild build-for-testing failed; see $LOG" >&2
  exit 1
fi

PRODUCTS="$BUILD/Build/Products"
APP="$PRODUCTS/Debug-iphoneos/CyanBridgeKMPHost.app"
XCTESTRUN="$(ls "$PRODUCTS"/*.xctestrun 2>/dev/null | head -1 || true)"
missing=0
for need in "$APP" "$APP/PlugIns/CyanBridgeKMPHostTests.xctest" "$APP/Frameworks/CyanBridgeShared.framework" \
            "$APP/Frameworks/CyanLiteRtModels.framework" "$APP/Frameworks/CLiteRT.framework" \
            "$APP/Frameworks/LiteRtMetalAccelerator.framework" "$APP/Frameworks/CLiteRTLM.framework" "$XCTESTRUN"; do
  if [ -z "$need" ] || [ ! -e "$need" ]; then echo "missing: ${need:-<.xctestrun>}" >&2; missing=1; fi
done
ls "$APP/Frameworks" >&2
[ "$missing" = 0 ] || exit 2
codesign --verify --deep --strict --verbose=1 "$APP" || { echo "codesign verification failed" >&2; exit 2; }

# Every LiteRt* symbol the LiteRT 2.4.0 bridge imports must come from CLiteRT (not CLiteRTLM's private copy).
BIN="$APP/Frameworks/CyanLiteRtModels.framework/CyanLiteRtModels"
echo "CyanLiteRtModels links:"; otool -L "$BIN" | sed 1d
WRONG="$(nm -m "$BIN" | grep -E 'undefined.*_LiteRt' | grep -v '(from CLiteRT)' || true)"
if [ -n "$WRONG" ]; then
  echo "LiteRT symbols not bound to CLiteRT:" >&2; echo "$WRONG" | head -20 >&2
  exit 3
fi
echo "LiteRT 2.4.0 symbols bound to CLiteRT: $(nm -m "$BIN" | grep -c 'undefined.*_LiteRt.*(from CLiteRT)')"

# Run only the LiteRT self-test on Device Run (the other test drives XCUIApplication, which a unit-test bundle cannot).
/usr/bin/python3 - "$XCTESTRUN" <<'EOF'
import plistlib, sys
path = sys.argv[1]
plist = plistlib.load(open(path, "rb"))
only = ["CyanBridgeKMPHostTests/testLiteRtModelsDownloadAndRun"]
targets = []
if "TestConfigurations" in plist:  # FormatVersion 2
    for cfg in plist["TestConfigurations"]:
        targets += cfg.get("TestTargets", [])
else:  # FormatVersion 1
    targets = [v for k, v in plist.items() if not k.startswith("__") and isinstance(v, dict)]
for t in targets:
    if t.get("BlueprintName") == "CyanBridgeKMPHostTests":
        t["OnlyTestIdentifiers"] = only
plistlib.dump(plist, open(path, "wb"))
print("xctestrun limited to", only)
EOF

GIT_SHA="$(git rev-parse HEAD)"
cat > "$PRODUCTS/BUILD_INFO.json" <<EOF
{
  "app": "CyanBridgeKMPHost",
  "bundle_id": "com.fersaiyan.cyanbridge",
  "development_team": "$DEVELOPMENT_TEAM",
  "git_sha": "$GIT_SHA",
  "git_dirty": $( [ -n "$(git status --porcelain -- ios android/CyanBridge/shared | grep -v '^?? ios/out/')" ] && echo true || echo false ),
  "xcode": "$(xcodebuild -version | tr '\n' ' ' | sed 's/ *$//')",
  "macos": "$(sw_vers -productVersion)",
  "test": "CyanBridgeKMPHostTests/testLiteRtModelsDownloadAndRun",
  "built_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF
OUT="$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")"
rm -f "$OUT"
(cd "$PRODUCTS" && zip -qry "$OUT" Debug-iphoneos "$(basename "$XCTESTRUN")" BUILD_INFO.json -x "*.dSYM/*")
(cd "$(dirname "$OUT")" && shasum -a 256 "$(basename "$OUT")" > "$(basename "$OUT").sha256")
echo
echo "archive : $OUT ($(du -h "$OUT" | cut -f1))"
echo "sha256  : $(cut -d' ' -f1 "$OUT.sha256")"
cat "$PRODUCTS/BUILD_INFO.json"
