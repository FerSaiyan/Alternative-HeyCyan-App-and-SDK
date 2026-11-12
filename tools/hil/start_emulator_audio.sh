#!/usr/bin/env bash
# Audio-enabled variant of start_emulator.sh — keeps host audio so AudioRecord/SpeechRecognizer can run.
# Usage: CYANBRIDGE_HIL_EMULATOR_AVD=Pixel_9a bash tools/hil/start_emulator_audio.sh
# Starts a -read-only instance of the AVD on CYANBRIDGE_HIL_AUDIO_EMULATOR_PORT (default 5586) and
# reuses only that serial. It never creates an AVD and never reuses a user's emulator.
set -euo pipefail
source "$(dirname "$0")/common.sh"

: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}"
EMULATOR_BIN="${EMULATOR_BIN:-$ANDROID_HOME/emulator/emulator}"
if [ ! -x "$EMULATOR_BIN" ]; then
  EMULATOR_BIN="$(command -v emulator || true)"
fi
if [ -z "$EMULATOR_BIN" ] || [ ! -x "$EMULATOR_BIN" ]; then
  echo "emulator binary not found" >&2
  exit 2
fi
AVD="${CYANBRIDGE_HIL_EMULATOR_AVD:-}"
if [ -z "$AVD" ] || ! "$EMULATOR_BIN" -list-avds 2>/dev/null | grep -Fxq "$AVD"; then
  echo "Set CYANBRIDGE_HIL_EMULATOR_AVD to an existing AVD (for example Pixel_9a)" >&2
  exit 2
fi
PORT="${CYANBRIDGE_HIL_AUDIO_EMULATOR_PORT:-5586}"
SERIAL="emulator-$PORT"
if [ "$(adb_for "$SERIAL" get-state 2>/dev/null || true)" = "device" ]; then
  RUNNING="$(adb_for "$SERIAL" emu avd name 2>/dev/null | head -n 1 | tr -d '\r' || true)"
  if [ "$RUNNING" = "$AVD" ] && is_read_only_emulator "$SERIAL"; then
    echo "$SERIAL"
    exit 0
  fi
  echo "$SERIAL is used by another emulator ('${RUNNING:-unknown}'); set CYANBRIDGE_HIL_AUDIO_EMULATOR_PORT" >&2
  exit 3
fi
LOG="$HIL_BUILD_DIR/emulator-audio-$PORT.log"
: > "$LOG"
# Note: no -no-audio. We keep audio host so the emulator exposes a virtual mic.
"$EMULATOR_BIN" -avd "$AVD" -port "$PORT" -read-only -no-snapshot \
  -memory "${CYANBRIDGE_HIL_EMULATOR_MEMORY_MB:-3072}" \
  -no-window -no-boot-anim -gpu swiftshader_indirect >>"$LOG" 2>&1 &
for _ in $(seq 1 180); do
  if [ "$(adb_for "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    adb_for "$SERIAL" shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
    adb_for "$SERIAL" shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
    adb_for "$SERIAL" shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
    echo "$SERIAL"
    exit 0
  fi
  sleep 2
done
echo "Emulator $AVD (audio) did not boot; see $LOG" >&2
exit 1
