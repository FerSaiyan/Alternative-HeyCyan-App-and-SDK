#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"

sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
emulator_bin="${EMULATOR_BIN:-$sdk_root/emulator/emulator}"
avdmanager_bin="${AVDMANAGER_BIN:-$sdk_root/cmdline-tools/latest/bin/avdmanager}"
avd="${CYANBRIDGE_WALKING_AID_AVD:-CyanBridge_Walking_Aid_CI}"
port="${CYANBRIDGE_WALKING_AID_EMULATOR_PORT:-5580}"
serial="emulator-$port"
system_image="${CYANBRIDGE_WALKING_AID_SYSTEM_IMAGE:-system-images;android-36;google_apis_playstore;x86_64}"

[[ -x "$emulator_bin" ]] || { echo "Android emulator not found: $emulator_bin" >&2; exit 2; }
[[ -x "$avdmanager_bin" ]] || { echo "avdmanager not found: $avdmanager_bin" >&2; exit 2; }
[[ "$port" =~ ^[0-9]+$ ]] && (( port >= 5554 && port <= 5682 && port % 2 == 0 )) || {
  echo "Emulator port must be an even number from 5554 through 5682" >&2
  exit 2
}

stop_other_emulators() {
  [[ "${CYANBRIDGE_WALKING_AID_STOP_OTHER_EMULATORS:-false}" == "true" ]] || return
  while IFS= read -r other; do
    [[ -z "$other" || "$other" == "$serial" || "$other" != emulator-* ]] && continue
    echo "Stopping $other before the memory-intensive model test" >&2
    adb_for "$other" emu kill >/dev/null 2>&1 || true
  done < <(list_serials)
  sleep 3
}

if [[ "$(adb_for "$serial" get-state 2>/dev/null || true)" == "device" ]]; then
  running_avd="$(adb_for "$serial" emu avd name 2>/dev/null | head -n1 | tr -d '\r' || true)"
  [[ "$running_avd" == "$avd" ]] || {
    echo "$serial is already occupied by AVD '$running_avd', expected '$avd'" >&2
    exit 3
  }
  stop_other_emulators
  printf '%s\n' "$serial"
  exit 0
fi

created=false
if ! "$emulator_bin" -list-avds 2>/dev/null | grep -Fxq "$avd"; then
  image_path="$sdk_root/${system_image//;/\/}"
  [[ -d "$image_path" ]] || {
    echo "Required Android system image is not installed: $system_image" >&2
    echo "Install it with sdkmanager '$system_image' before running model CI." >&2
    exit 2
  }
  echo "Creating dedicated 8 GiB Walking Aid model-test AVD '$avd'" >&2
  printf 'no\n' | "$avdmanager_bin" create avd \
    --force \
    --name "$avd" \
    --package "$system_image" \
    --device "pixel_6"
  created=true
fi

avd_home="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
config="$avd_home/$avd.avd/config.ini"
[[ -f "$config" ]] || { echo "Missing AVD config: $config" >&2; exit 2; }

# These settings are applied only when this script creates its dedicated AVD. Never rewrite a
# pre-existing AVD that may have been provisioned manually for another purpose.
if [[ "$created" == "true" ]]; then
python3 - "$config" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
lines = path.read_text().splitlines()
updates = {
    "disk.dataPartition.size": "8G",
    "hw.ramSize": "4096",
    "hw.sdCard": "yes",
}
seen = set()
output = []
for line in lines:
    key = line.split("=", 1)[0]
    if key in updates:
        output.append(f"{key}={updates[key]}")
        seen.add(key)
    else:
        output.append(line)
for key, value in updates.items():
    if key not in seen:
        output.append(f"{key}={value}")
path.write_text("\n".join(output) + "\n")
PY
fi

stop_other_emulators

mkdir -p "$HIL_BUILD_DIR"
log="$HIL_BUILD_DIR/walking-aid-emulator.log"
echo "Starting dedicated Walking Aid AVD '$avd' on $serial" >&2
nohup "$emulator_bin" \
  -avd "$avd" \
  -port "$port" \
  -no-window \
  -no-audio \
  -no-boot-anim \
  -no-snapshot \
  -gpu swiftshader_indirect \
  >"$log" 2>&1 &

deadline=$((SECONDS + 240))
while (( SECONDS < deadline )); do
  if [[ "$(adb_for "$serial" get-state 2>/dev/null || true)" == "device" ]]; then
    booted="$(adb_for "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    [[ "$booted" == "1" ]] && break
  fi
  sleep 2
done

booted="$(adb_for "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
[[ "$booted" == "1" ]] || { echo "Walking Aid emulator failed to boot; see $log" >&2; exit 3; }

total_kb="$(adb_for "$serial" shell df -Pk /data | tr -d '\r' | awk 'END {print $2}')"
[[ "$total_kb" =~ ^[0-9]+$ ]] || { echo "Could not read Walking Aid AVD partition size" >&2; exit 3; }
if (( total_kb < 7 * 1024 * 1024 )); then
  echo "Walking Aid AVD /data is smaller than 7 GiB despite its 8 GiB configuration." >&2
  echo "Delete only the dedicated '$avd' AVD and let this script recreate it; never wipe the automation AVD." >&2
  exit 6
fi

adb_for "$serial" shell settings put global window_animation_scale 0 >/dev/null || true
adb_for "$serial" shell settings put global transition_animation_scale 0 >/dev/null || true
adb_for "$serial" shell settings put global animator_duration_scale 0 >/dev/null || true
printf '%s\n' "$serial"
