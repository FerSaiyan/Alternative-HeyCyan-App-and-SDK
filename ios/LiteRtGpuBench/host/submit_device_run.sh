#!/bin/bash
# Runs the bench archive on Google Device Run iPhones: pushes every file of a pack (prepare_pack.py)
# into the app's Documents folder, runs the XCTest, pulls Documents/bench_out back and downloads the
# session folder.
#
# Usage: host/submit_device_run.sh <LiteRtGpuBench-xctest.zip> <pack dir> [<device>,...]
#   devices default: iphone17pro-26-5,iphone16pro-18-3 (list: gcloud alpha device-run devices list)
# Env: PROJECT (default: gcloud's core/project), BUCKET (default <project>-devicerun),
#      XCODE_VERSION (default: lab default 26.2), TIMEOUT (default 30m), OUT_ROOT (default ~/nvme_tmp/ios-bench/sessions)
# Exit status: non-zero when gcloud fails.
set -euo pipefail
ZIP="${1:?usage: $0 <xctest.zip> <pack dir> [devices]}"
PACK="${2:?usage: $0 <xctest.zip> <pack dir> [devices]}"
DEVICES="${3:-iphone17pro-26-5,iphone16pro-18-3}"
PROJECT="${PROJECT:-$(gcloud config get-value core/project 2>/dev/null)}"
BUCKET="${BUCKET:-$PROJECT-devicerun}"
TIMEOUT="${TIMEOUT:-30m}"
OUT_ROOT="${OUT_ROOT:-$HOME/nvme_tmp/ios-bench/sessions}"

# The app's bundle id, from the archive's .xctestrun (TestHostBundleIdentifier).
BUNDLE_ID="$(python3 -I - "$ZIP" <<'EOF'
import plistlib, sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
runs = [n for n in z.namelist() if n.endswith(".xctestrun") and "/" not in n.strip("/")]
if not runs:
    sys.exit("no .xctestrun at the zip root")
plist = plistlib.loads(z.read(runs[0]))
def find(node):
    if isinstance(node, dict):
        if "TestHostBundleIdentifier" in node:
            return node["TestHostBundleIdentifier"]
        for v in node.values():
            r = find(v)
            if r:
                return r
    if isinstance(node, list):
        for v in node:
            r = find(v)
            if r:
                return r
print(find(plist) or "")
EOF
)"
[ -n "$BUNDLE_ID" ] || { echo "could not read the host bundle id from $ZIP" >&2; exit 1; }
echo "bundle: $BUNDLE_ID  devices: $DEVICES  project: $PROJECT"

PUSH=""
for f in "$PACK"/*; do
  name="$(basename "$f")"
  case "$name" in PACK_MANIFEST.json|bench_out) continue;; esac
  PUSH="$PUSH,$f=$BUNDLE_ID:/Documents/$name"
done
PUSH="${PUSH#,}"

STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$OUT_ROOT/$STAMP"
mkdir -p "$OUT"
cp "$PACK/bench_plan.json" "$OUT/"
[ -f "$PACK/PACK_MANIFEST.json" ] && cp "$PACK/PACK_MANIFEST.json" "$OUT/"
python3 -I -c "import zipfile,sys; print(zipfile.ZipFile(sys.argv[1]).read('BUILD_INFO.json').decode())" "$ZIP" \
  > "$OUT/BUILD_INFO.json" 2>/dev/null || true

ARGS=(--device="$DEVICES" --test="$ZIP" --bucket-name="$BUCKET" --location=global --project="$PROJECT"
      --other-files-to-push="$PUSH" --paths-to-pull="$BUNDLE_ID:/Documents/bench_out"
      --xctest-timeout="$TIMEOUT" --labels="purpose=litert-ios-gpu-bench")
[ -n "${XCODE_VERSION:-}" ] && ARGS+=(--xcode-version="$XCODE_VERSION")

echo "submitting (results -> $OUT)"
set +e
gcloud alpha device-run sessions submit xctest "${ARGS[@]}" 2>&1 | tee "$OUT/submit.log"
STATUS=${PIPESTATUS[0]}
set -e

SESSION="$(grep -oE 'sessions/[A-Za-z0-9_-]+' "$OUT/submit.log" | head -1 | cut -d/ -f2 || true)"
if [ -n "$SESSION" ]; then
  echo "$SESSION" > "$OUT/session_id"
  gcloud storage cp -r "gs://$BUCKET/automation/sessions/$SESSION" "$OUT/" --project="$PROJECT" >/dev/null 2>&1 \
    || echo "download failed; retry: gcloud storage cp -r gs://$BUCKET/automation/sessions/$SESSION $OUT/" >&2
  echo "session $SESSION downloaded to $OUT"
  python3 -I "$(dirname "$0")/summarize.py" "$OUT" || true
else
  echo "no session id in the gcloud output; see $OUT/submit.log" >&2
fi
exit "$STATUS"
