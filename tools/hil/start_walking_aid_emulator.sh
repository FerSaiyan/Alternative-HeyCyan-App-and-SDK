#!/usr/bin/env bash
# Starts (or reuses) a read-only instance of an existing AVD for the Walking Aid real-model test,
# for local runs outside the self-hosted workflow (which runs that test on its main CI instance).
# It never creates an AVD and never stops another emulator: run it when the machine has the memory.
set -euo pipefail
source "$(dirname "$0")/common.sh"

export CYANBRIDGE_HIL_EMULATOR_AVD="${CYANBRIDGE_WALKING_AID_AVD:-Pixel_9a}"
export CYANBRIDGE_HIL_EMULATOR_PORT="${CYANBRIDGE_WALKING_AID_EMULATOR_PORT:-5580}"
serial="$(bash "$(dirname "$0")/start_emulator.sh")"

# The three pinned models are staged next to the app's data; run_walking_aid_model_ci.sh checks the
# exact free space it needs, this only rejects AVDs whose /data partition is plainly too small.
total_kb="$(adb_for "$serial" shell df -Pk /data | tr -d '\r' | awk 'END {print $2}')"
[[ "$total_kb" =~ ^[0-9]+$ ]] || { echo "Could not read the /data size of $serial" >&2; exit 3; }
if (( total_kb < 7 * 1024 * 1024 )); then
  echo "AVD '$CYANBRIDGE_HIL_EMULATOR_AVD' has a /data partition under 7 GiB; pick an AVD with more storage" >&2
  exit 6
fi
printf '%s\n' "$serial"
