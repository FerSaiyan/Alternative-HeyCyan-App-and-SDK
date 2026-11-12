#!/usr/bin/env bash
# Starts (or reuses) the CI's own read-only instance of the configured AVD on a dedicated port.
#
# - CYANBRIDGE_HIL_EMULATOR_AVD is required (the repository variable is Pixel_9a).
# - CYANBRIDGE_HIL_EMULATOR_PORT picks the console port (default 5584); the serial is emulator-$port.
# - Only emulator-$port is ever reused, and only when it runs the same AVD in -read-only mode.
#   A user's emulator (any other port, or a writable instance) is never reused or stopped.
# - -read-only discards every change on exit, so the persistent AVD's account, Tasker setup and
#   app data stay untouched while CI replaces the app inside the instance.
set -euo pipefail
source "$(dirname "$0")/common.sh"

avd="${CYANBRIDGE_HIL_EMULATOR_AVD:-}"
if [[ -z "$avd" ]]; then
  echo "Set CYANBRIDGE_HIL_EMULATOR_AVD (for example Pixel_9a); there is no fallback AVD." >&2
  exit 2
fi
port="${CYANBRIDGE_HIL_EMULATOR_PORT:-5584}"
[[ "$port" =~ ^[0-9]+$ ]] && (( port >= 5554 && port <= 5682 && port % 2 == 0 )) || {
  echo "Emulator port must be an even number from 5554 through 5682" >&2
  exit 2
}
serial="emulator-$port"
memory_mb="${CYANBRIDGE_HIL_EMULATOR_MEMORY_MB:-3072}"

prepare() {
  adb_for "$serial" shell settings put global window_animation_scale 0 >/dev/null || true
  adb_for "$serial" shell settings put global transition_animation_scale 0 >/dev/null || true
  adb_for "$serial" shell settings put global animator_duration_scale 0 >/dev/null || true
  adb_for "$serial" shell input keyevent KEYCODE_WAKEUP >/dev/null || true
  adb_for "$serial" shell wm dismiss-keyguard >/dev/null 2>&1 || true
  # Play Store auto-updates can fill a read-only instance's /data during long runs. Opt-in only:
  # Tasker's licence check needs the Play Store, so the Tasker HIL workflows leave it enabled.
  if [[ "${CYANBRIDGE_HIL_DISABLE_PLAY_STORE:-false}" == "true" ]]; then
    adb_for "$serial" shell pm disable-user --user 0 com.android.vending >/dev/null 2>&1 || true
  fi
}

if [[ "$(adb_for "$serial" get-state 2>/dev/null || true)" == "device" ]]; then
  running_avd="$(adb_for "$serial" emu avd name 2>/dev/null | head -n1 | tr -d '\r' || true)"
  if [[ "$running_avd" != "$avd" ]]; then
    echo "$serial is occupied by AVD '${running_avd:-unknown}', expected '$avd'; pick another CYANBRIDGE_HIL_EMULATOR_PORT" >&2
    exit 3
  fi
  if ! is_read_only_emulator "$serial"; then
    echo "$serial runs '$avd' without -read-only (a user's emulator); refusing to reuse it" >&2
    exit 3
  fi
  prepare
  printf '%s\n' "$serial"
  exit 0
fi
if ss -ltnH "sport = :$port" 2>/dev/null | grep -q .; then
  echo "Port $port is in use by something that is not a ready emulator; pick another CYANBRIDGE_HIL_EMULATOR_PORT" >&2
  exit 3
fi

sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
emulator_bin="${EMULATOR_BIN:-$sdk_root/emulator/emulator}"
if [[ ! -x "$emulator_bin" ]]; then
  emulator_bin="$(command -v emulator || true)"
fi
if [[ -z "${emulator_bin:-}" || ! -x "$emulator_bin" ]]; then
  echo "No Android emulator binary found; emulator smoke test will be skipped" >&2
  exit 2
fi
if ! "$emulator_bin" -list-avds 2>/dev/null | grep -Fxq "$avd"; then
  echo "AVD '$avd' does not exist; this script never creates AVDs" >&2
  exit 2
fi

mkdir -p "$HIL_BUILD_DIR"
log="$HIL_BUILD_DIR/emulator-$port.log"
echo "Starting a read-only instance of '$avd' on $serial (cold boot, ${memory_mb} MB)" >&2
nohup "$emulator_bin" \
  -avd "$avd" \
  -port "$port" \
  -read-only \
  -no-snapshot \
  -memory "$memory_mb" \
  -no-window \
  -no-audio \
  -no-boot-anim \
  -gpu swiftshader_indirect \
  >"$log" 2>&1 &

# A cold API 37 boot on a busy host has taken almost 9 minutes.
deadline=$((SECONDS + 600))
booted=""
while (( SECONDS < deadline )); do
  if [[ "$(adb_for "$serial" get-state 2>/dev/null || true)" == "device" ]]; then
    booted="$(adb_for "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    [[ "$booted" == "1" ]] && break
  fi
  sleep 2
done
if [[ "$booted" != "1" ]]; then
  echo "Emulator $serial did not finish booting; see $log" >&2
  exit 3
fi

prepare
printf '%s\n' "$serial"
