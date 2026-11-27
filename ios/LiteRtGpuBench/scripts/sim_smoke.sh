#!/bin/bash
# Pre-flight on the Mac: compiles the app + XCTest for the iOS Simulator (no signing) and runs the
# bench on 4 public models with synthetic inputs. Proves the code compiles, LiteRT loads and the CPU
# path gives rows, before the signed device archive is built. GPU rows on the simulator are a bonus:
# if they error, that is not a blocker (the real GPU test is on Google's iPhones).
#
# Usage: ./scripts/sim_smoke.sh
# Needs: Xcode, xcodegen (brew install xcodegen), network (Swift package + models).
# Exit status: 1 when the build/test fails or no CPU row ran cleanly.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
cd "$HERE"
command -v xcodegen >/dev/null || { echo "xcodegen missing: brew install xcodegen" >&2; exit 1; }

PACK="$HERE/out/smoke_pack"
"$HERE/scripts/fetch_smoke_pack.sh" "$PACK"
xcodegen generate --quiet

# First available iPhone simulator (prefer one that is already booted).
UDID="$(xcrun simctl list devices available -j | /usr/bin/python3 -c '
import json, sys
devs = [d for rt, ds in json.load(sys.stdin)["devices"].items() if "iOS" in rt for d in ds
        if d.get("isAvailable") and d["name"].startswith("iPhone")]
devs.sort(key=lambda d: d["state"] != "Booted")
print(devs[0]["udid"] if devs else "")')"
[ -n "$UDID" ] || { echo "no iPhone simulator available (Xcode > Settings > Components)" >&2; exit 1; }
echo "simulator: $UDID"

LOG="$HERE/out/sim_smoke_xcodebuild.log"
# TEST_RUNNER_<VAR> reaches the test host as <VAR>: the runner reads the pack straight from the Mac.
set +e
TEST_RUNNER_BENCH_DIR="$PACK" xcodebuild test -project LiteRtGpuBench.xcodeproj -scheme LiteRtGpuBench \
  -destination "platform=iOS Simulator,id=$UDID" -derivedDataPath "$HERE/out/build-sim" \
  CODE_SIGNING_ALLOWED=NO > "$LOG" 2>&1
STATUS=$?
set -e
grep -E "error:|Test Case|TEST (SUCCEEDED|FAILED)|\*\* " "$LOG" | tail -40 || true
echo "xcodebuild exit $STATUS (full log: $LOG)"

RESULTS="$PACK/bench_out/results.jsonl"
if [ ! -f "$RESULTS" ] && grep -q "BUILD SUCCEEDED\|Test Suite\|Testing started" "$LOG"; then
  # Fallback if this Xcode did not pass TEST_RUNNER_BENCH_DIR to the host app: copy the pack into the
  # simulator app's Documents (as Device Run does on the phones) and run the built tests again.
  echo "BENCH_DIR did not reach the app; copying the pack into the simulator container"
  CONTAINER="$(xcrun simctl get_app_container "$UDID" dev.litertgpubench.app data)"
  mkdir -p "$CONTAINER/Documents"
  rm -rf "$CONTAINER/Documents/bench_out"
  cp "$PACK"/*.tflite "$PACK"/bench_plan.json "$CONTAINER/Documents/"
  set +e
  xcodebuild test-without-building -project LiteRtGpuBench.xcodeproj -scheme LiteRtGpuBench \
    -destination "platform=iOS Simulator,id=$UDID" -derivedDataPath "$HERE/out/build-sim" >> "$LOG" 2>&1
  STATUS=$?
  set -e
  echo "xcodebuild (retry) exit $STATUS"
  RESULTS="$CONTAINER/Documents/bench_out/results.jsonl"
fi
[ -f "$RESULTS" ] || { echo "no results.jsonl: the bench did not run (see $LOG)" >&2; exit 1; }
/usr/bin/python3 - "$RESULTS" <<'EOF'
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
runs = [r for r in rows if r.get("event") == "run"]
for r in rows:
    if r.get("event") == "gpu_probe":
        print("gpu_probe:", json.dumps(r.get("attempts")), "chosen:", r.get("chosen"))
for r in runs:
    status = ("ERROR " + str(r["error"])[:120]) if "error" in r else f"median {r.get('median_ms', 0):.1f} ms"
    print(f"{r.get('model', '?'):42s} {r.get('config', '?'):18s} {status}")
cpu_ok = [r for r in runs if r.get("config") == "cpu" and "error" not in r]
print(f"cpu rows ok: {len(cpu_ok)} / models: {len({r.get('model') for r in runs})}")
sys.exit(0 if cpu_ok else 1)
EOF
echo "simulator smoke test passed"
