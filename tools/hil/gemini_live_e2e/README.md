# Gemini Live emulator E2E (fake Live server + HeyCyan live lane)

Drives a debug build's Gemini Live against a scripted fake Live server while the simulated HeyCyan
glasses stream video from their storage (firmware_dump `sim/e2e_bt_p2p`, `LIVE=1`, add.244).

- `fake_live.py`: WebSocket :18900 (setup -> setupComplete; records audio loudness, every image with its
  arrival time, tool responses) and control :18901 (`ctl.sh 'toolcall end_session'`,
  `ctl.sh 'toolcall phone_task open Spotify'`, `ctl.sh 'reply Goodbye.'`, `ctl.sh status`).
- `inject_audio.py`: speech into the app AVD microphone over emulator gRPC (needs `APP_EMU_AUDIO=1`).
- `scenario.sh`: points Live at the fake server and selects live video via the debug-only
  `GeminiLiveDebugReceiver`, then: two questions -> one newest frame each; phone_task without a
  Local Agent -> "unavailable"; end_session + goodbye -> Live and the glasses stream stop.

Run (device lane + Gradle lock rules from firmware_dump `docs/AVD_BLE_P2P_HOWTO.md`):

    python3 fake_live.py --out /tmp/live-e2e/run &
    WORK=... LIVE=1 V821_DL_CMD=3 V821_ENV=... APK=<debug apk> APP_EMU_AUDIO=1 bash sim/e2e_bt_p2p/run_e2e.sh start
    T=<this dir> OUT=/tmp/live-e2e/run bash scenario.sh   # audio clips: $T/audio/*.wav (16 kHz mono)

Speech clips are not committed; copy them from the glasses_questions_v1 fixture set (`audio/en_*.wav`).
