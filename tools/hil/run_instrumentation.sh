#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"

serial="${1:-${CYANBRIDGE_HIL_SERIAL:-}}"
mode="${2:-hardware}"
classes="${3:-}"
glasses="${CYANBRIDGE_HIL_GLASSES:-false}"
expect_visual_fact="${CYANBRIDGE_HIL_EXPECT_VISUAL_FACT:-false}"
local_ai="${CYANBRIDGE_HIL_LOCAL_AI:-false}"
email_send="${CYANBRIDGE_HIL_EMAIL_SEND:-false}"

if [[ -z "$serial" ]]; then
  serial="$(find_serial any || true)"
fi
if [[ -z "$serial" ]]; then
  echo "No Android target available for instrumentation" >&2
  exit 3
fi

# API 35+ emulators show a system-owned "Android App Compatibility" overlay for every
# app whose native libs are not 16 KB page-size aligned (the Meta Wearables SDK is not).
# The dialog steals window focus, so Tasker's %WIN reports "android", AutoInput queries
# the wrong window, and Compose tests lose taps. Dismiss it permanently per package
# before running anything.
dismiss_16kb_compat_dialog() {
  local s="$1"
  adb_for "$s" shell true >/dev/null 2>&1 || return 0
  local xml
  xml="$(adb_for "$s" exec-out uiautomator dump /dev/tty 2>/dev/null | tr -d '\r' || true)"
  if ! grep -q 'Android App Compatibility\|16 KB compatible' <<<"$xml"; then
    return 0
  fi
  local xy
  xy="$(python3 -c '
import re, sys
m = re.search(r"<node[^>]+text=\"Don.t Show Again\"[^>]+bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", sys.argv[1])
if m:
    print((int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2)
' "$xml")"
  if [[ -n "$xy" ]]; then
    echo "Dismissing 16 KB compatibility dialog" >&2
    read -r x y <<<"$xy"
    adb_for "$s" shell input tap "$x" "$y" >/dev/null
    sleep 1
  fi
}
dismiss_16kb_compat_dialog "$serial"

mkdir -p "$HIL_BUILD_DIR/results"
out="$HIL_BUILD_DIR/results/instrumentation-${mode}-${serial//[:\/]/_}.txt"

cmd=(
  shell am instrument -w -r
  -e hil_mode "$mode"
  -e hil_glasses "$glasses"
  -e hil_expect_visual_fact "$expect_visual_fact"
  -e hil_local_ai "$local_ai"
  -e hil_email_send "$email_send"
)
if [[ -n "$classes" ]]; then
  cmd+=( -e class "$classes" )
fi
cmd+=( "$CYANBRIDGE_TEST_PACKAGE/$CYANBRIDGE_TEST_RUNNER" )

set +e
adb_for "$serial" "${cmd[@]}" | tee "$out"
status=${PIPESTATUS[0]}
set -e

if (( status != 0 )); then
  echo "Instrumentation command failed with adb status $status" >&2
  exit "$status"
fi
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=Process crashed' "$out"; then
  echo "Instrumentation reported test failures" >&2
  exit 10
fi
if ! grep -Eq 'OK \([0-9]+ tests?\)|OK \([0-9]+ test\)' "$out"; then
  echo "Instrumentation did not report a successful JUnit completion" >&2
  exit 11
fi

echo "Instrumentation passed: $out"
