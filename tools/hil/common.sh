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

# Prints the PID of the local emulator process whose console listens on the serial's port.
emulator_pid_for_serial() {
  local serial="$1" port
  [[ "$serial" == emulator-* ]] || return 1
  port="${serial#emulator-}"
  ss -ltnpH "sport = :$port" 2>/dev/null | grep -o 'pid=[0-9]*' | head -n1 | cut -d= -f2
}

# True only for a local emulator started with -read-only. Changes made inside such an instance
# are discarded when it exits, so CI may replace the app there without touching the persistent AVD.
is_read_only_emulator() {
  local pid
  pid="$(emulator_pid_for_serial "$1" || true)"
  [[ -n "$pid" && -r "/proc/$pid/cmdline" ]] || return 1
  tr '\0' '\n' < "/proc/$pid/cmdline" | grep -Fxq -- '-read-only'
}

# A cold boot of the API 37 Play image reports sys.boot_completed=1 while its boot broadcasts are
# still starting Google apps. For about a minute after that, MemFree in the 4 GB guest sits near
# 75 MB, and lmkd ("min watermark is breached even after kill") kills even a test target that was
# started 0.2 s earlier: am instrument then reports "Process crashed" before any test runs
# (CI run 43218835150). Before starting a test process on an emulator, wait until the broadcast
# queues are idle and, while the guest is in that post-boot window (uptime under
# CYANBRIDGE_HIL_SETTLE_BOOT_WINDOW_S), until MemFree has stayed above the floor for three samples
# in a row. Later, cached apps keep MemFree lower without any risk, so it is not checked. A device
# that never settles is reported and the run continues, so a real failure is still seen.
settle_emulator() {
  local serial="$1"
  [[ "$serial" == emulator-* ]] || return 0
  local timeout_s="${CYANBRIDGE_HIL_SETTLE_TIMEOUT_S:-180}"
  local min_free_kb="${CYANBRIDGE_HIL_SETTLE_MIN_FREE_KB:-350000}"
  local boot_window_s="${CYANBRIDGE_HIL_SETTLE_BOOT_WINDOW_S:-300}"
  local deadline=$((SECONDS + timeout_s)) good=0 free_kb="" uptime_s="" started=$SECONDS
  while (( SECONDS < deadline )); do
    timeout 60 "$ADB_BIN" -s "$serial" shell am wait-for-broadcast-idle >/dev/null 2>&1 || true
    read -r uptime_s free_kb < <(adb_for "$serial" shell \
      "echo \$(cut -d. -f1 /proc/uptime) \$(awk '/^MemFree:/ {print \$2}' /proc/meminfo)" 2>/dev/null | tr -d '\r') || true
    if [[ "$uptime_s" =~ ^[0-9]+$ ]] && (( uptime_s >= boot_window_s )); then
      return 0
    fi
    if [[ "$free_kb" =~ ^[0-9]+$ ]] && (( free_kb >= min_free_kb )); then
      good=$((good + 1))
      if (( good >= 3 )); then
        (( SECONDS - started > 6 )) && echo "$serial settled after $((SECONDS - started)) s (MemFree ${free_kb} kB)" >&2
        return 0
      fi
    else
      good=0
    fi
    sleep 2
  done
  echo "Warning: $serial did not settle within ${timeout_s} s (MemFree ${free_kb:-unknown} kB); continuing" >&2
  return 0
}

# After "Process crashed", say whether lmkd killed the app or test process (an environment fault
# on a memory-starved emulator, not an app crash), and show the last fatal exception otherwise.
explain_instrumentation_crash() {
  local serial="$1" log
  log="$(adb_for "$serial" logcat -d -t 3000 2>/dev/null | tr -d '\r' || true)"
  if grep -E "lowmemorykiller: Kill '($CYANBRIDGE_PACKAGE|$CYANBRIDGE_TEST_PACKAGE)'" <<<"$log" | tail -n 2 | grep . >&2; then
    echo "lmkd killed the instrumentation target: the emulator ran out of free memory (environment)" >&2
    return 0
  fi
  grep -A 12 'FATAL EXCEPTION' <<<"$log" | tail -n 13 >&2 || true
}
