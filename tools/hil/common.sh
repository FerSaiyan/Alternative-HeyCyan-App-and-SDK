#!/usr/bin/env bash
set -euo pipefail

HIL_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HIL_BUILD_DIR="${HIL_BUILD_DIR:-$HIL_REPO_ROOT/build/hil}"
mkdir -p "$HIL_BUILD_DIR"

ADB_BIN="${ADB_BIN:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}/platform-tools/adb}"
if [[ ! -x "$ADB_BIN" ]]; then
  ADB_BIN="$(command -v adb || true)"
fi
if [[ -z "${ADB_BIN:-}" || ! -x "$ADB_BIN" ]]; then
  echo "adb was not found; set ANDROID_HOME/ANDROID_SDK_ROOT or ADB_BIN" >&2
  exit 2
fi

CYANBRIDGE_PACKAGE="com.fersaiyan.cyanbridge"
CYANBRIDGE_TEST_PACKAGE="com.fersaiyan.cyanbridge.test"
CYANBRIDGE_TEST_RUNNER="androidx.test.runner.AndroidJUnitRunner"
TASKER_PACKAGE="net.dinglisch.android.taskerm"
AUTOINPUT_PACKAGE="com.joaomgcd.autoinput"

adb_for() {
  local serial="$1"
  shift
  "$ADB_BIN" -s "$serial" "$@"
}

list_serials() {
  "$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" { print $1 }'
}

find_serial() {
  local kind="${1:-any}"
  local requested="${CYANBRIDGE_HIL_SERIAL:-}"

  if [[ -n "$requested" ]]; then
    if adb_for "$requested" get-state >/dev/null 2>&1; then
      case "$kind" in
        physical)
          [[ "$requested" == emulator-* ]] && return 1
          ;;
        emulator)
          [[ "$requested" != emulator-* ]] && return 1
          ;;
      esac
      printf '%s\n' "$requested"
      return 0
    fi
    return 1
  fi

  local serial
  while IFS= read -r serial; do
    [[ -z "$serial" ]] && continue
    case "$kind" in
      physical)
        [[ "$serial" == emulator-* ]] && continue
        ;;
      emulator)
        [[ "$serial" != emulator-* ]] && continue
        ;;
    esac
    printf '%s\n' "$serial"
    return 0
  done < <(list_serials)
  return 1
}

package_installed() {
  local serial="$1"
  local package_name="$2"
  adb_for "$serial" shell pm path "$package_name" 2>/dev/null | grep -q '^package:'
}

wait_for_package() {
  local serial="$1"
  local package_name="$2"
  local timeout_s="${3:-20}"
  local deadline=$((SECONDS + timeout_s))
  while (( SECONDS < deadline )); do
    if package_installed "$serial" "$package_name"; then
      return 0
    fi
    sleep 1
  done
  return 1
}
