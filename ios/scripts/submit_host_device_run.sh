#!/bin/bash
# Runs the CyanBridge iOS app's LiteRT self-test (archive from build_host_xctest_zip.sh) on Google Device Run iPhones,
# pulls Documents/litert-selftest.txt and prints the LITERT_SELFTEST lines of each device's system log.
#
# Usage: ios/scripts/submit_host_device_run.sh <CyanBridgeKMPHost-litert-xctest.zip> [<device>,...]
#   devices default: iphone17pro-26-5,iphone16pro-18-3 (gcloud alpha device-run devices list)
# Env: PROJECT (default gcloud core/project), BUCKET (default <project>-devicerun), XCODE_VERSION, TIMEOUT (default 30m),
#      OUT_ROOT (default ~/nvme_tmp/ios-bench/host-sessions)
set -euo pipefail
ZIP="${1:?usage: $0 <xctest.zip> [devices]}"
DEVICES="${2:-iphone17pro-26-5,iphone16pro-18-3}"
PROJECT="${PROJECT:-$(gcloud config get-value core/project 2>/dev/null)}"
BUCKET="${BUCKET:-$PROJECT-devicerun}"
TIMEOUT="${TIMEOUT:-30m}"
OUT_ROOT="${OUT_ROOT:-$HOME/nvme_tmp/ios-bench/host-sessions}"

BUNDLE_ID="$(python3 -I - "$ZIP" <<'EOF'
import plistlib, sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
runs = [n for n in z.namelist() if n.endswith(".xctestrun") and "/" not in n]
if not runs:
    sys.exit("no .xctestrun at the zip root")
def find(node):
    if isinstance(node, dict):
        if "TestHostBundleIdentifier" in node:
            return node["TestHostBundleIdentifier"]
        node = list(node.values())
    if isinstance(node, list):
        for v in node:
            r = find(v)
            if r:
                return r
print(find(plistlib.loads(z.read(runs[0]))) or "")
EOF
)"
[ -n "$BUNDLE_ID" ] || { echo "no host bundle id in $ZIP" >&2; exit 1; }
OUT="$OUT_ROOT/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
python3 -I -c "import zipfile,sys; print(zipfile.ZipFile(sys.argv[1]).read('BUILD_INFO.json').decode())" "$ZIP" \
  > "$OUT/BUILD_INFO.json" 2>/dev/null || true
echo "bundle $BUNDLE_ID on $DEVICES -> $OUT"

ARGS=(--device="$DEVICES" --test="$ZIP" --bucket-name="$BUCKET" --location=global --project="$PROJECT"
      --paths-to-pull="$BUNDLE_ID:/Documents/litert-selftest.txt,$BUNDLE_ID:/Documents/cyanbridge-debug.log"
      --xctest-timeout="$TIMEOUT" --labels="purpose=litert-ios-host-selftest")
[ -n "${XCODE_VERSION:-}" ] && ARGS+=(--xcode-version="$XCODE_VERSION")
set +e
gcloud alpha device-run sessions submit xctest "${ARGS[@]}" 2>&1 | tee "$OUT/submit.log"
STATUS=${PIPESTATUS[0]}
set -e
SESSION="$(grep -oE 'sessions/[A-Za-z0-9_-]+' "$OUT/submit.log" | head -1 | cut -d/ -f2 || true)"
if [ -n "$SESSION" ]; then
  echo "$SESSION" > "$OUT/session_id"
  gcloud storage cp -r "gs://$BUCKET/automation/sessions/$SESSION" "$OUT/" --project="$PROJECT" >/dev/null 2>&1 || true
  for job in "$OUT/$SESSION"/job-*; do
    [ -d "$job" ] || continue
    echo "=== $(basename "$job")"
    f="$(find "$job" -name litert-selftest.txt | head -1)"
    if [ -n "$f" ]; then cat "$f"; else grep -h "LITERT_SELFTEST" $(find "$job" -name system.log) 2>/dev/null | tail -20 || echo "(no report)"; fi
  done
fi
exit "$STATUS"
