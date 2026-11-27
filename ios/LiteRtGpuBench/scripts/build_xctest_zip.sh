#!/bin/bash
# Builds the signed device archive that Google Device Run takes for an iOS XCTest job
# (https://docs.cloud.google.com/developer-device-platform/device-run/ios): the Release-iphoneos
# products and the .xctestrun manifest at the root of a zip, plus BUILD_INFO.json. Device Run
# re-signs the app with its own lab profile, but the build must be signed once here with a valid
# Apple development identity.
#
# Usage: DEVELOPMENT_TEAM=<10-char Apple team id> [BUNDLE_ID=<app id>] [DEVELOPER_DIR=<Xcode.app>] \
#          ./scripts/build_xctest_zip.sh [<out.zip>]
#   BUNDLE_ID defaults to dev.litertgpubench.<team id in lowercase> (unique to your team).
#   DEVELOPER_DIR picks the Xcode when several are installed; Device Run runs Xcode 26.2 (default)
#   or 16.4, so build with 26.2 if you can.
# Output: out/LiteRtGpuBench-xctest.zip (default) and its .sha256.
# Exit status: 1 bad setup or build failure, 2 the archive is missing a required part.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
cd "$HERE"
: "${DEVELOPMENT_TEAM:?set DEVELOPMENT_TEAM to your Apple team id (Xcode > Settings > Accounts, or developer.apple.com > Membership)}"
TEAM_LC="$(echo "$DEVELOPMENT_TEAM" | tr '[:upper:]' '[:lower:]')"
BUNDLE_ID="${BUNDLE_ID:-dev.litertgpubench.$TEAM_LC}"
OUT="${1:-$HERE/out/LiteRtGpuBench-xctest.zip}"
case "$OUT" in *.zip) ;; *) OUT="$OUT.zip";; esac
command -v xcodegen >/dev/null || { echo "xcodegen missing: brew install xcodegen" >&2; exit 1; }

XCODE_VERSION="$(xcodebuild -version | head -1 | awk '{print $2}')"
case "$XCODE_VERSION" in
  26.2*|16.4*) ;;
  *) echo "warning: Xcode $XCODE_VERSION; Device Run runs Xcode 26.2 or 16.4. Continuing; say which Xcode you used." >&2;;
esac

xcodegen generate --quiet
BUILD="$HERE/out/build"
rm -rf "$BUILD"
LOG="$HERE/out/build_xctest.log"
mkdir -p "$HERE/out"
echo "building for device (team $DEVELOPMENT_TEAM, bundle $BUNDLE_ID); log: $LOG"
if ! xcodebuild build-for-testing -project LiteRtGpuBench.xcodeproj -scheme LiteRtGpuBench \
    -destination "generic/platform=iOS" -derivedDataPath "$BUILD" -allowProvisioningUpdates \
    DEVELOPMENT_TEAM="$DEVELOPMENT_TEAM" BENCH_BUNDLE_ID="$BUNDLE_ID" > "$LOG" 2>&1; then
  grep -E "error:|warning: .*[Ss]ign|Provisioning|No profiles|requires a development team" "$LOG" | head -30 >&2 || true
  echo "xcodebuild build-for-testing failed; see $LOG" >&2
  exit 1
fi

PRODUCTS="$BUILD/Build/Products"
APP="$PRODUCTS/Release-iphoneos/LiteRtGpuBench.app"
XCTESTRUN="$(ls "$PRODUCTS"/*.xctestrun 2>/dev/null | head -1 || true)"
missing=0
for need in "$APP" "$APP/PlugIns/LiteRtGpuBenchTests.xctest" \
            "$APP/Frameworks/LiteRtMetalAccelerator.framework" "$XCTESTRUN"; do
  if [ -z "$need" ] || [ ! -e "$need" ]; then echo "missing: ${need:-<.xctestrun>}" >&2; missing=1; fi
done
# CLiteRT may be embedded as a framework (dynamic) or linked into the app (static); either is fine.
ls "$APP/Frameworks" >&2
[ "$missing" = 0 ] || exit 2
codesign --verify --deep --strict --verbose=1 "$APP" || { echo "codesign verification failed" >&2; exit 2; }

GIT_SHA="$(git -C "$HERE" rev-parse HEAD 2>/dev/null || echo unknown)"
GIT_DIRTY="$(git -C "$HERE" status --porcelain -- . 2>/dev/null | grep -v '^?? out/' | head -1 || true)"
LITERT_PIN="$(/usr/bin/python3 - "$HERE" <<'EOF'
import json, sys, glob
for p in glob.glob(sys.argv[1] + "/LiteRtGpuBench.xcodeproj/**/Package.resolved", recursive=True):
    for pin in json.load(open(p)).get("pins", []):
        if "litert" in pin.get("identity", ""):
            print(pin["identity"], pin["state"].get("version"), pin["state"].get("revision"))
EOF
)"
cat > "$PRODUCTS/BUILD_INFO.json" <<EOF
{
  "bundle_id": "$BUNDLE_ID",
  "test_bundle_id": "$BUNDLE_ID.tests",
  "development_team": "$DEVELOPMENT_TEAM",
  "git_sha": "$GIT_SHA",
  "git_dirty": $( [ -n "$GIT_DIRTY" ] && echo true || echo false ),
  "xcode": "$(xcodebuild -version | tr '\n' ' ' | sed 's/ *$//')",
  "macos": "$(sw_vers -productVersion)",
  "litert_swift": "$LITERT_PIN",
  "built_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF

mkdir -p "$(dirname "$OUT")"
OUT="$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")"
rm -f "$OUT"
(cd "$PRODUCTS" && zip -qry "$OUT" Release-iphoneos "$(basename "$XCTESTRUN")" BUILD_INFO.json -x "*.dSYM/*")
(cd "$(dirname "$OUT")" && shasum -a 256 "$(basename "$OUT")" > "$(basename "$OUT").sha256")
echo
echo "archive : $OUT ($(du -h "$OUT" | cut -f1))"
echo "sha256  : $(cut -d' ' -f1 "$OUT.sha256")"
echo "bundle  : $BUNDLE_ID"
cat "$PRODUCTS/BUILD_INFO.json"
