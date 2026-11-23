#!/bin/bash
# Gemini Live E2E on the HeyCyan live lane (lane already started). Results in $OUT.
set -u
S=emulator-5562; OUT=${OUT:-/tmp/live-e2e/run}; PY=${PY:-python3}; T=${T:-$(cd "$(dirname "$0")" && pwd)}
ev() { grep -c "\"kind\": \"$1\"" $OUT/events.jsonl 2>/dev/null; }
apppid() { adb -s $S shell pidof com.fersaiyan.cyanbridge | tr -d '\r'; }
adb -s $S logcat -c
adb -s $S shell am broadcast -n com.fersaiyan.cyanbridge/.ai.live.GeminiLiveDebugReceiver -a com.fersaiyan.cyanbridge.debug.GEMINI_LIVE \
  --es url ws://10.0.2.2:18900 --ei vision_mode 3 --ez start true > /dev/null
for i in $(seq 1 40); do [ "$(ev setup)" -ge 1 ] && break; sleep 1; done
echo "setup: $(grep '"setup"' $OUT/events.jsonl | tail -1 | cut -c1-140)"
for i in $(seq 1 60); do adb -s $S logcat -d --pid=$(apppid) | grep -q "LIVESTREAM SUCCESS" && break; sleep 1; done
echo "stream: $(adb -s $S logcat -d --pid=$(apppid) | grep -c 'LIVESTREAM SUCCESS') success line(s)"
sleep 4
# Warm-up take: the first injection after boot often lands before the recorder (known emulator quirk).
timeout 30 $PY -I $T/inject_audio.py ${AUDIO:-$T/audio}/en_what_do_you_think__kokoro-af_heart.wav --port 5562; sleep 3
for clip in en_color_of_object__kokoro-af_heart.wav en_what_do_you_think__kokoro-af_heart.wav; do
  sleep 4; echo "inject $clip at $(date +%s.%N | cut -c1-14)"
  timeout 30 $PY -I $T/inject_audio.py ${AUDIO:-$T/audio}/$clip --port 5562; sleep 2
done
echo "frames: $(ev video)"; grep '"video"' $OUT/events.jsonl | cut -c1-120
$T/ctl.sh toolcall phone_task open Spotify; sleep 2
grep '"tool_response"' $OUT/events.jsonl | tail -1 | cut -c1-220
$T/ctl.sh toolcall end_session; sleep 1; $T/ctl.sh reply Goodbye.
for i in $(seq 1 15); do [ "$(ev disconnect)" -ge 1 ] && break; sleep 1; done
grep -E '"tool_response"|"disconnect"' $OUT/events.jsonl | tail -2 | cut -c1-200
sleep 4
P=$(apppid); adb -s $S logcat -d --pid=$P > $OUT/app_logcat.txt
echo "--- checks"
echo "vision restarted after stop: $(grep -c 'Stop the current glasses transport' $OUT/app_logcat.txt)"
echo "ending line: $(grep -c 'Ending Live after a tool call' $OUT/app_logcat.txt)"
echo "crashes: $(grep -c -E 'FATAL EXCEPTION|AndroidRuntime: ' $OUT/app_logcat.txt)"
echo "stream stop: $(grep -c 'live stop response' $OUT/app_logcat.txt)"
