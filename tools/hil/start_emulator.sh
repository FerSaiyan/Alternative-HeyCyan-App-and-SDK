#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"

existing="$(find_serial emulator || true)"
if [[ -n "$existing" ]]; then
  printf '%s\n' "$existing"
  exit 0
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

avd="${CYANBRIDGE_HIL_EMULATOR_AVD:-}"
if [[ -z "$avd" ]]; then
  avd="$($emulator_bin -list-avds 2>/dev/null | head -n1 || true)"
fi
if [[ -z "$avd" ]]; then
  echo "No existing AVD found. Create one in Android Studio or set CYANBRIDGE_HIL_EMULATOR_AVD." >&2
  exit 2
fi

mkdir -p "$HIL_BUILD_DIR"
log="$HIL_BUILD_DIR/emulator.log"
echo "Starting persistent AVD '$avd' headlessly with a cold boot" >&2
# -no-snapshot disables Quick Boot RAM snapshot load/save but does NOT wipe the AVD userdata
# partition. Paid Tasker/AutoInput installs, Google account state, and permissions therefore persist.
nohup "$emulator_bin" \
  -avd "$avd" \
  -no-window \
  -no-audio \
  -no-boot-anim \
  -no-snapshot \
  -gpu swiftshader_indirect \
  >"$log" 2>&1 &

serial=""
deadline=$((SECONDS + 180))
while (( SECONDS < deadline )); do
  serial="$(find_serial emulator || true)"
  if [[ -n "$serial" ]]; then
    booted="$(adb_for "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    if [[ "$booted" == "1" ]]; then
      break
    fi
  fi
  sleep 2
done

if [[ -z "$serial" ]]; then
  echo "Emulator did not appear; see $log" >&2
  exit 3
fi
booted="$(adb_for "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
if [[ "$booted" != "1" ]]; then
  echo "Emulator did not finish booting; see $log" >&2
  exit 3
fi

adb_for "$serial" shell settings put global window_animation_scale 0 >/dev/null || true
adb_for "$serial" shell settings put global transition_animation_scale 0 >/dev/null || true
adb_for "$serial" shell settings put global animator_duration_scale 0 >/dev/null || true
adb_for "$serial" shell input keyevent KEYCODE_WAKEUP >/dev/null || true
adb_for "$serial" shell wm dismiss-keyguard >/dev/null 2>&1 || true

printf '%s\n' "$serial"
