#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"

serial="${1:-${CYANBRIDGE_HIL_SERIAL:-}}"
if [[ -z "$serial" ]]; then
  serial="$(find_serial any || true)"
fi
if [[ -z "$serial" ]]; then
  echo "No Android target available for diagnostics" >&2
  exit 0
fi

safe_dir="$HIL_BUILD_DIR/diagnostics-safe"
private_dir="$HIL_BUILD_DIR/diagnostics-private"
mkdir -p "$safe_dir" "$private_dir"

{
  echo "serial=$serial"
  echo "model=$(adb_for "$serial" shell getprop ro.product.model | tr -d '\r')"
  echo "sdk=$(adb_for "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "build=$(adb_for "$serial" shell getprop ro.build.fingerprint | tr -d '\r')"
  echo "stay_on_while_plugged_in=$(adb_for "$serial" shell settings get global stay_on_while_plugged_in 2>/dev/null | tr -d '\r' || true)"
  echo "enabled_accessibility_services=$(adb_for "$serial" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r' || true)"
  for package_name in "$CYANBRIDGE_PACKAGE" "$TASKER_PACKAGE" "$AUTOINPUT_PACKAGE"; do
    echo "--- $package_name ---"
    adb_for "$serial" shell dumpsys package "$package_name" 2>/dev/null \
      | grep -E 'versionName=|versionCode=|firstInstallTime=|lastUpdateTime=' \
      | head -n20 || true
  done
} >"$safe_dir/device-summary.txt"

adb_for "$serial" shell dumpsys power >"$safe_dir/dumpsys-power.txt" 2>&1 || true
adb_for "$serial" shell dumpsys battery >"$safe_dir/dumpsys-battery.txt" 2>&1 || true
adb_for "$serial" shell dumpsys bluetooth_manager >"$safe_dir/dumpsys-bluetooth-manager.txt" 2>&1 || true

# Filter logcat to the integrations under test instead of uploading the phone's full logs.
adb_for "$serial" logcat -d -v threadtime 2>/dev/null \
  | grep -E 'CyanBridge|TaskerLocalAgent|TaskerAgent|AutoDiary|VisualDiary|ImageAutomation|ExternalAssistant|MetaRayban|HeyCyan' \
  | tail -n 12000 >"$safe_dir/logcat-filtered.txt" || true

if [[ "${CYANBRIDGE_HIL_UPLOAD_VISUAL_DIAGNOSTICS:-false}" == "true" ]]; then
  echo "Visual diagnostics explicitly enabled; these may contain account/UI content." >&2
  adb_for "$serial" exec-out screencap -p >"$private_dir/screenshot.png" 2>/dev/null || true
  adb_for "$serial" shell uiautomator dump /sdcard/cyanbridge-hil-ui.xml >/dev/null 2>&1 || true
  adb_for "$serial" pull /sdcard/cyanbridge-hil-ui.xml "$private_dir/uiautomator.xml" >/dev/null 2>&1 || true
  adb_for "$serial" shell rm -f /sdcard/cyanbridge-hil-ui.xml >/dev/null 2>&1 || true
fi

echo "Safe diagnostics: $safe_dir"
if [[ -n "$(find "$private_dir" -type f -print -quit 2>/dev/null)" ]]; then
  echo "Private visual diagnostics: $private_dir"
fi
