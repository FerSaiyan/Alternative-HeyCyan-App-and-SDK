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

# API 37's emulator adbd has been observed going offline during a long, multi-class
# instrumentation process. Keep each class in its own process and recover between classes.
# This is deliberately emulator-only: a disappearing physical HIL phone must fail closed rather
# than silently switching devices halfway through a hardware assertion.
recover_target() {
  local current="$1"
  if [[ "$(adb_for "$current" get-state 2>/dev/null || true)" == "device" ]]; then
    printf '%s\n' "$current"
    return 0
  fi

  echo "ADB target $current is not ready; attempting recovery" >&2
  "$ADB_BIN" reconnect offline >/dev/null 2>&1 || true
  "$ADB_BIN" reconnect >/dev/null 2>&1 || true
  local deadline=$((SECONDS + 20))
  while (( SECONDS < deadline )); do
    if [[ "$(adb_for "$current" get-state 2>/dev/null || true)" == "device" ]]; then
      printf '%s\n' "$current"
      return 0
    fi
    sleep 1
  done

  if [[ "$current" != emulator-* ]]; then
    echo "Physical HIL target $current disconnected; refusing to substitute another device" >&2
    return 1
  fi

  echo "Emulator $current stayed offline; cold-booting the persistent AVD without wiping userdata" >&2
  local restarted
  restarted="$(CYANBRIDGE_HIL_SERIAL= bash "$HIL_REPO_ROOT/tools/hil/start_emulator.sh")" || return 1
  [[ -n "$restarted" ]] || return 1
  adb_for "$restarted" wait-for-device >/dev/null
  local boot_deadline=$((SECONDS + 60))
  while (( SECONDS < boot_deadline )); do
    if [[ "$(adb_for "$restarted" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
      printf '%s\n' "$restarted"
      return 0
    fi
    sleep 1
  done
  echo "Restarted emulator $restarted did not finish booting" >&2
  return 1
}

run_one_class() {
  local class_name="$1"
  local class_slug="${class_name//[^A-Za-z0-9_.-]/_}"
  local attempt=1

  while (( attempt <= 2 )); do
    serial="$(recover_target "$serial")" || return 3
    dismiss_16kb_compat_dialog "$serial"

    mkdir -p "$HIL_BUILD_DIR/results"
    local out="$HIL_BUILD_DIR/results/instrumentation-${mode}-${serial//[:\/]/_}-${class_slug}.txt"
    local cmd=(
      shell am instrument -w -r
      -e hil_mode "$mode"
      -e hil_glasses "$glasses"
      -e hil_expect_visual_fact "$expect_visual_fact"
      -e hil_local_ai "$local_ai"
      -e hil_email_send "$email_send"
      -e class "$class_name"
      "$CYANBRIDGE_TEST_PACKAGE/$CYANBRIDGE_TEST_RUNNER"
    )

    echo "Running HIL class $class_name on $serial (attempt $attempt/2)" >&2
    set +e
    adb_for "$serial" "${cmd[@]}" | tee "$out"
    local status=${PIPESTATUS[0]}
    set -e

    if (( status == 0 )); then
      if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=Process crashed' "$out"; then
        echo "Instrumentation reported test failures in $class_name" >&2
        return 10
      fi
      if ! grep -Eq 'OK \([0-9]+ tests?\)|OK \([0-9]+ test\)' "$out"; then
        echo "Instrumentation did not report a successful JUnit completion for $class_name" >&2
        return 11
      fi
      echo "Instrumentation passed: $class_name -> $out"
      return 0
    fi

    local state
    state="$(adb_for "$serial" get-state 2>/dev/null || true)"
    if [[ "$serial" == emulator-* && "$state" != "device" && $attempt -lt 2 ]]; then
      echo "Instrumentation lost emulator during $class_name (adb status $status); recovering and retrying only this class" >&2
      serial="$(recover_target "$serial")" || return "$status"
      attempt=$((attempt + 1))
      continue
    fi

    echo "Instrumentation command failed for $class_name with adb status $status" >&2
    return "$status"
  done
  return 12
}

# A comma-separated suite is intentionally split into one instrumentation process per class.
# Besides making API-37 ADB recovery possible, this gives CI a precise class-level failure rather
# than losing all remaining tests when one process or device connection dies.
if [[ -n "$classes" ]]; then
  IFS=',' read -r -a class_list <<<"$classes"
else
  class_list=("")
fi

if (( ${#class_list[@]} > 1 )); then
  for class_name in "${class_list[@]}"; do
    class_name="${class_name#${class_name%%[![:space:]]*}}"
    class_name="${class_name%${class_name##*[![:space:]]}}"
    [[ -n "$class_name" ]] || continue
    run_one_class "$class_name"
  done
  echo "Instrumentation suite passed: ${#class_list[@]} isolated classes"
  exit 0
fi

# Preserve the historical no-class behavior for callers that intentionally request the runner's
# default discovery. Single explicit classes use the same isolated runner above.
if [[ -n "$classes" ]]; then
  run_one_class "$classes"
  exit $?
fi

serial="$(recover_target "$serial")" || exit 3
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
  "$CYANBRIDGE_TEST_PACKAGE/$CYANBRIDGE_TEST_RUNNER"
)
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
