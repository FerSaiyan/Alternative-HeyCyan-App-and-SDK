#!/usr/bin/env bash
# Audio-enabled variant of start_emulator.sh — keeps host audio so AudioRecord/SpeechRecognizer can run.
# Usage: bash tools/hil/start_emulator_audio.sh
# Respects the same CYANBRIDGE_HIL_EMULATOR_AVD as the standard script but omits -no-audio / -noaudio.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
HIL_BUILD_DIR="$REPO_ROOT/build/hil"
mkdir -p "$HIL_BUILD_DIR"
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR_BIN="${EMULATOR_BIN:-$ANDROID_HOME/emulator/emulator}"
if [ ! -x "$EMULATOR_BIN" ]; then
  EMULATOR_BIN="$(command -v emulator || true)"
fi
if [ -z "$EMULATOR_BIN" ] || [ ! -x "$EMULATOR_BIN" ]; then
  echo "emulator binary not found" >&2
  exit 2
fi
AVD="${CYANBRIDGE_HIL_EMULATOR_AVD:-}"
if [ -z "$AVD" ]; then
  AVD="$("$EMULATOR_BIN" -list-avds 2>/dev/null | head -n 1 | tr -d '\r')"
fi
if [ -z "$AVD" ]; then
  echo "No AVD found; create one with: avdmanager create avd -n cyanbridge_audio -k 'system-images;android-34;google_apis;x86_64'" >&2
  exit 0
fi
SERIAL="$("$ADB" devices | awk '/^emulator-/ {print $1}' | head -n 1)"
if [ -n "$SERIAL" ] && [ "$("$ADB" -s "$SERIAL" get-state 2>/dev/null || true)" = "device" ]; then
  echo "$SERIAL"
  exit 0
fi
LOG="$HIL_BUILD_DIR/emulator-audio.log"
: > "$LOG"
# Note: no -no-audio. We keep audio host so the emulator exposes a virtual mic.
"$EMULATOR_BIN" -avd "$AVD" -no-window -no-boot-anim -no-snapshot -gpu swiftshader_indirect >>"$LOG" 2>&1 &
for _ in $(seq 1 180); do
  SERIAL="$("$ADB" devices | awk '/^emulator-/ {print $1}' | head -n 1)"
  if [ -n "$SERIAL" ] && [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    "$ADB" -s "$SERIAL" shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
    "$ADB" -s "$SERIAL" shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
    "$ADB" -s "$SERIAL" shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
    echo "$SERIAL"
    exit 0
  fi
  sleep 2
done
echo "Emulator $AVD (audio) did not boot" >&2
exit 1
