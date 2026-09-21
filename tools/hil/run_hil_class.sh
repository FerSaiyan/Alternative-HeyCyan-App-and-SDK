#!/usr/bin/env bash
# Hardened single-class HIL runner: preflight, warm Tasker, single
# instrumentation attempt, tag-filtered verdict.
#
# Lessons baked in:
# - Tasker's monitor goes dormant after an emulator reboot; launch the Tasker
#   UI once (dismissing review/Tip dialogs) before model HIL.
# - Never run adb install while a calibration/HIL run is in flight
#   (installPackageLI kills the app mid-run).
# - The service loop and the test must not observe concurrently at 1 Hz;
#   HIL tests poll status cheaply and observe rarely (see the embedding
#   YouTube test's OBSERVE_POLL_MS).
# - YouTube codec spam rotates small rings in minutes; use a 32M buffer and
#   grep the verdict by test name, not by tail position.
set -euo pipefail
source "$(dirname "$0")/common.sh"

serial="${1:-${CYANBRIDGE_HIL_SERIAL:-}}"
class="${2:?usage: run_hil_class.sh <serial> <TestClass> [extra am instrument args...]}"
shift 2

[[ -n "$serial" ]] || { echo "No device serial supplied" >&2; exit 3; }
adb_for "$serial" get-state >/dev/null || { echo "Device $serial not ready" >&2; exit 3; }
[[ "$(adb_for "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] || {
  echo "Device $serial has not finished booting" >&2; exit 3
}

echo "== preflight: Tasker stack =="
package_installed "$serial" "$TASKER_PACKAGE" || { echo "Tasker missing on $serial" >&2; exit 4; }
package_installed "$serial" "$AUTOINPUT_PACKAGE" || { echo "AutoInput missing on $serial" >&2; exit 4; }
a11y="$(adb_for "$serial" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')"
grep -q "net.dinglisch.android.taskerm" <<<"$a11y" || { echo "Tasker accessibility not enabled" >&2; exit 4; }
grep -q "com.joaomgcd.autoinput" <<<"$a11y" || { echo "AutoInput accessibility not enabled" >&2; exit 4; }
package_installed "$serial" "$CYANBRIDGE_PACKAGE" || { echo "CyanBridge debug APK missing" >&2; exit 4; }
package_installed "$serial" "$CYANBRIDGE_TEST_PACKAGE" || { echo "androidTest APK missing" >&2; exit 4; }

echo "== warming Tasker monitor =="
adb_for "$serial" shell am start -n net.dinglisch.android.taskerm/.Tasker >/dev/null 2>&1 || true
sleep 8
adb_for "$serial" shell uiautomator dump /sdcard/hil-warm.xml >/dev/null 2>&1 || true
warm_xml="$(adb_for "$serial" exec-out cat /sdcard/hil-warm.xml 2>/dev/null | tr -d '\r' || true)"
if grep -q "Not now" <<<"$warm_xml"; then
  xy="$(python3 -c '
import re, sys
m = re.search(r"<node[^>]+text=\"Not now\"[^>]+bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", sys.argv[1])
if m: print((int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2)
' "$warm_xml" || true)"
  [[ -n "$xy" ]] && adb_for "$serial" shell input tap $xy >/dev/null || true
  sleep 3
fi
adb_for "$serial" shell rm -f /sdcard/hil-warm.xml >/dev/null 2>&1 || true

echo "== running $class =="
adb_for "$serial" logcat -G 32M >/dev/null 2>&1 || true
adb_for "$serial" logcat -c
short="${class##*.}"

mkdir -p "$HIL_BUILD_DIR/results"
out="$HIL_BUILD_DIR/results/hil-class-${serial//[:\/]/_}-${short}.txt"
set +e
adb_for "$serial" shell am instrument -w -r "$@" \
  -e class "$class" \
  "$CYANBRIDGE_TEST_PACKAGE/$CYANBRIDGE_TEST_RUNNER" | tee "$out"
adb_status=${PIPESTATUS[0]}
set -e
echo "am instrument client exit: $adb_status (transport drops are expected on API-37; verdict comes from logcat)"

deadline=$((SECONDS + 1500))
verdict=""
while (( SECONDS < deadline )); do
  verdict="$(adb_for "$serial" logcat -d -b all 2>/dev/null | grep -E "finished: $short|failed: $short|OK \(1 test\)|FAILURES!!!" | tail -n 3 || true)"
  if [[ -n "$verdict" ]]; then break; fi
  echo "waiting for $short verdict ($((SECONDS))s elapsed)..." >&2
  sleep 10
done
echo "$verdict" | tee -a "$out"

if grep -Eq "OK \(1 test\)" <<<"$verdict"; then
  echo "HIL PASSED: $short -> $out"
  exit 0
fi
echo "HIL verdict for $short (log=$out):" >&2
echo "$verdict" >&2
exit 10
