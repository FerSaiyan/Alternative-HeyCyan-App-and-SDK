#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"

serial="${1:-${CYANBRIDGE_HIL_SERIAL:-}}"
if [[ -z "$serial" ]]; then
  serial="$(find_serial physical || true)"
fi
if [[ -z "$serial" ]]; then
  echo "No physical Android HIL device is connected" >&2
  exit 3
fi

model="$(adb_for "$serial" shell getprop ro.product.model | tr -d '\r')"
sdk="$(adb_for "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
echo "HIL device: $serial ($model, API $sdk)"

adb_for "$serial" wait-for-device >/dev/null

# The dedicated phone is configured to stay logically awake while charging. Do not force
# the display bright: Extinguisher or another OLED-protection layer may black it visually.
power_dump="$(adb_for "$serial" shell dumpsys power 2>/dev/null || true)"
if ! grep -Eq 'mWakefulness=Awake|mInteractive=true' <<<"$power_dump"; then
  echo "Device is not logically awake; sending KEYCODE_WAKEUP" >&2
  adb_for "$serial" shell input keyevent KEYCODE_WAKEUP >/dev/null || true
  sleep 1
fi
adb_for "$serial" shell wm dismiss-keyguard >/dev/null 2>&1 || true

stay_on="$(adb_for "$serial" shell settings get global stay_on_while_plugged_in 2>/dev/null | tr -d '\r' || true)"
if [[ -z "$stay_on" || "$stay_on" == "0" || "$stay_on" == "null" ]]; then
  echo "WARNING: Developer option 'Stay awake while charging' does not appear enabled." >&2
else
  echo "Stay-awake while charging: $stay_on"
fi

for package_name in "$TASKER_PACKAGE" "$AUTOINPUT_PACKAGE"; do
  if ! package_installed "$serial" "$package_name"; then
    echo "Required HIL package is missing: $package_name" >&2
    exit 4
  fi
  version="$(adb_for "$serial" shell dumpsys package "$package_name" 2>/dev/null | sed -n 's/.*versionName=//p' | head -n1 | tr -d '\r')"
  echo "$package_name version=${version:-unknown}"
done

enabled_accessibility="$(adb_for "$serial" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r' || true)"
if ! grep -q "$AUTOINPUT_PACKAGE" <<<"$enabled_accessibility"; then
  echo "AutoInput Accessibility service is not enabled on the HIL phone" >&2
  exit 5
fi
if grep -q "$CYANBRIDGE_PACKAGE" <<<"$enabled_accessibility"; then
  echo "WARNING: a stale CyanBridge Accessibility entry is still present in Android settings." >&2
fi

lock_disabled="$(adb_for "$serial" shell locksettings get-disabled 2>/dev/null | tr -d '\r' || true)"
echo "Lockscreen disabled query: ${lock_disabled:-unsupported}"

battery="$(adb_for "$serial" shell dumpsys battery 2>/dev/null | sed -n 's/^  USB powered: /USB=/p; s/^  AC powered: /AC=/p; s/^  level: /level=/p' | paste -sd' ' - || true)"
echo "Power: ${battery:-unknown}"

echo "Physical HIL preflight passed"
