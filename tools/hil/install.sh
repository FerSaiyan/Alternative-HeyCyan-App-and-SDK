#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"

serial="${1:-${CYANBRIDGE_HIL_SERIAL:-}}"
if [[ -z "$serial" ]]; then
  serial="$(find_serial any || true)"
fi
if [[ -z "$serial" ]]; then
  echo "No Android device/emulator available for install" >&2
  exit 3
fi

app_apk="${CYANBRIDGE_HIL_APP_APK:-$HIL_REPO_ROOT/android/CyanBridge/app/build/outputs/apk/debug/app-debug.apk}"
test_apk="${CYANBRIDGE_HIL_TEST_APK:-$HIL_REPO_ROOT/android/CyanBridge/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"

[[ -f "$app_apk" ]] || { echo "Missing debug APK: $app_apk" >&2; exit 4; }
[[ -f "$test_apk" ]] || { echo "Missing androidTest APK: $test_apk" >&2; exit 4; }

echo "Installing CyanBridge debug APK on $serial without clearing app data"
adb_for "$serial" install -r -d "$app_apk"
adb_for "$serial" install -r -d -t "$test_apk"

wait_for_package "$serial" "$CYANBRIDGE_PACKAGE" 15 || {
  echo "CyanBridge package was not visible after install" >&2
  exit 5
}
wait_for_package "$serial" "$CYANBRIDGE_TEST_PACKAGE" 15 || {
  echo "CyanBridge androidTest package was not visible after install" >&2
  exit 5
}

echo "Installed branch build and instrumentation APK on $serial"
