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

# Installs with -r (keeping data). A build signed with a different key than the installed app fails
# with INSTALL_FAILED_UPDATE_INCOMPATIBLE: local builds carry the release key when
# keystore.properties exists, CI builds the debug key. With
# CYANBRIDGE_HIL_UNINSTALL_ON_SIGNATURE_MISMATCH=true, and only on a local emulator started with
# -read-only (whose data is discarded on exit), the old package is uninstalled and installed again.
install_package() {
  local package_name="$1"
  shift
  local output status
  set +e
  output="$(adb_for "$serial" install "$@" 2>&1)"
  status=$?
  set -e
  printf '%s\n' "$output"
  (( status == 0 )) && ! grep -q 'Failure \[' <<<"$output" && return 0
  if ! grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' <<<"$output"; then
    return 1
  fi
  if [[ "${CYANBRIDGE_HIL_UNINSTALL_ON_SIGNATURE_MISMATCH:-false}" != "true" ]]; then
    echo "$package_name on $serial is signed with a different key. Use a read-only emulator instance" >&2
    echo "and set CYANBRIDGE_HIL_UNINSTALL_ON_SIGNATURE_MISMATCH=true, or uninstall it by hand." >&2
    return 1
  fi
  if ! is_read_only_emulator "$serial"; then
    echo "Signature mismatch for $package_name on $serial, which is not a local -read-only emulator;" >&2
    echo "refusing to uninstall (it would delete persistent app data)." >&2
    return 1
  fi
  echo "Signature mismatch on read-only $serial: uninstalling $package_name and installing again" >&2
  adb_for "$serial" uninstall "$package_name" >/dev/null 2>&1 || true
  adb_for "$serial" install "$@"
}

echo "Installing CyanBridge debug APK on $serial without clearing app data"
install_package "$CYANBRIDGE_PACKAGE" -r -d "$app_apk"
install_package "$CYANBRIDGE_TEST_PACKAGE" -r -d -t "$test_apk"

wait_for_package "$serial" "$CYANBRIDGE_PACKAGE" 15 || {
  echo "CyanBridge package was not visible after install" >&2
  exit 5
}
wait_for_package "$serial" "$CYANBRIDGE_TEST_PACKAGE" 15 || {
  echo "CyanBridge androidTest package was not visible after install" >&2
  exit 5
}

echo "Installed branch build and instrumentation APK on $serial"
