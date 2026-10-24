# cyan-refactor hardware test checklist

Phase 1 (MainActivity extraction, GeminiLiveClient and LocalAgentService splits, plugin adapter removal) was meant to keep behavior the same. A pixel comparison on an emulator showed identical screens. Phase 2 changes behavior on hardware paths on purpose. This list covers every change that has to be checked with real glasses or earbuds.

## Phase 1 flows to smoke-test

These moved without logic changes, but only JVM tests and screenshots cover them so far:

- **HeyCyan media sync.** Official and adaptive flows. JPG/MP4/Opus land in the gallery and Recordings. The exit-transfer command is sent. BT loss mid-sync.
- **High-quality image question.** Success, then each failure-dialog choice (Retry / Fast preview / Cancel).
- **Eyevue, TuneBuds and W620 sync.** Stop and close the Activity while each one is syncing.
- **OTA.** Personal two-step picker, catalog OTA (stealth and debug), the combined OTA reconnect, BT loss during preparation and during the OTA, and the dashboard Cancel.
- **Live preview.** HeyCyan, Eyevue and TuneBuds. The notification Stop action.
- **Gemini Live.** Audio route, SCO and focus; reconnect and resumption; free and economy 429/402 handling.
- **Local Agent.** TTS, the approval flow, read-screen-aloud, and stop on screen-off.
- **Pro settings and subscription.** Plan selection after rotation.
- **Android 10.** Top-bar inset on the plugin settings screens.

## Phase 2: unified glasses network session

Filter logcat with:
`adb logcat -s GlassesNetSession DataDownload OtaManager LivePreview WifiP2pManagerSingleton EyevueWifi TuneBudsP2p TuneBudsMediaSync WifiAdbDebug`

**Every teardown**
- Expect `"<client>: group removal accepted on attempt 1; waiting for disconnect"`, then `"confirmed the group is gone"` or `"…confirmed by a disconnect event"`.
- New: removal that can't be confirmed is retried while quarantined (`"…retrying every 5000ms or on Bluetooth reconnect"`). Before, media sync and live preview stayed stuck until a BT disconnect.

**HeyCyan adaptive sync**
1. Normal sync looks the same. The transfer-mode command now goes out one main-loop hop later, after the link claim.
2. With a VPN on, process binding is now attempted (`"VPN active — attempting bindProcessToNetwork"`). Check that media.config still resolves, and that app internet works after the sync.
3. Stop and immediately sync again. The second sync may wait for the first one's teardown (`"waiting for HeyCyan media sync to finish its link teardown"`).
4. Error 255 is still left to adaptive recovery.

**HeyCyan official sync**
- Peer selection is unchanged. Error 255 still stops, resets and shows a toast.
- New: if the link can't be claimed within 15 s, the toast says "Wi-Fi Direct is still busy with another glasses session. Please retry sync."

**OTA Wi-Fi stage**
- The peer matcher is broader: official name, full MAC suffix, case-insensitive name and MAC, AVAILABLE peers first. Check that `"Found glasses P2P peer"` names your glasses.
- Expect `TEARING_DOWN_P2P`, then `"Confirmed OTA P2P group is gone"`, then BLE DFU. The 25 s stage limit is unchanged.

**HeyCyan live preview**
- The peer matcher adds the `_<last4>` rule. Route lookup also accepts 192.168.49.x.
- On stop, the lease is released only after `"cleanup: confirmed P2P group is gone"`.

**Eyevue (P2P models)**
- `disconnect()` now waits for a confirmed, retried removal and closes the channel, and the next connect waits for it.
- VPN networks are excluded from route selection.
- AP models now bind through the coordinator.

**TuneBuds media sync (P2P mode)**
- `close()` now waits for a confirmed removal, up to about 17 s, before `"TuneBuds P2P group teardown timed out"`. It used to wait 5 s and treat a failure as done, so the hotspot fallback can now start later.

**BT loss mid-session**
- Media sync, live preview and Wi-Fi ADB abandon the link as before.
- The next HeyCyan session first removes the leftover group (`"removing a group left by an abandoned link before connecting"`).
- OTA keeps the lease and retries every 5 s, and immediately on reconnect.

**VPN on/off**
- Expect `"process bound to the glasses network"` / `"restored the default process network"`. AI requests must work after any session.

## Phase 2: per-family glasses devices (shared `GlassesDevice` / `GlassesFamilyProfile`)

Dashboard dispatch was ported one-to-one. Families without their own transport still go through the hidden HeyCyan controls, as before. The intended changes only affect controls the dashboard already hides for that family.

**Every family**
- Scan, Reconnect and Disconnect behave as before.
- Status line, battery and storage update.
- Dashboard sections are unchanged after switching profiles in Device Bind.

**HeyCyan**
- Photo, video, audio, Count, Sync and Stop sync.
- Live preview start and stop.
- Advanced: battery toast, version, time, volume, Add listener, BT scan, and OTA dump, pull, request and cancel.
- Image quality picker and wake-word route.
- Wi-Fi ADB in a debug build.
- Battery polling every 60 s.
- Tasker `camera` and `data_download`.
- Plugin shortcut SYNC.

**Eyevue**
- Photo, video and audio toggles. The Count toast ("Media count: N").
- Sync and stop.
- Live preview start and stop, the notification Stop, and the lease released afterwards.
- Battery toast and battery request on selection.
- Volume, Add listener, wearing toggle.
- Wake-word route.
- Closing the activity mid-sync finishes the transfer and disconnects.

**TuneBuds**
- Same media actions as Eyevue, plus live preview.
- Status refreshes on selection.
- Unsupported actions are still ignored; the log line now says "Ignoring unsupported TUNEBUDS dashboard action".

**MoYoung W620**
- Reconnect works, and Disconnect suppresses auto-reconnect.
- Battery and media count are requested on selection.
- Plugin SYNC and Tasker `data_download` start the MoYoung sync, which now goes through the family hook.
- The transfer card Stop works, and closing the activity stops the sync.

**Meta Ray-Ban**
- Reconnect opens Meta pairing, and Disconnect stops the session.
- HeyCyan controls and Tasker commands show the same "…unavailable for Meta Ray-Ban" toast.
- Tasker `camera` saves a DAT photo.
- Walking Aid and Visual Diary wait for the Meta camera. Auto Audio is refused with the same message.
- A voice question is not blocked during a Meta camera session.

**Meizu MYVU**
- Reconnect is user-initiated.
- onResume creates the manager.
- The notification permission is requested for the connection service.
- The background-feature restart reconnects.
- The failure dialog appears only while MYVU is selected.
- Camera and Auto Audio plugins are refused as before.

**Mentra, generic audio, unknown**
- Connection buttons go through the legacy SDK controls, as before.

**Changes to controls that are already hidden**
- StopLivePreview no longer calls the HeyCyan stop for families without live preview.
- RefreshRecordingSettings no longer toasts "Connect HeyCyan…" for families other than HeyCyan or Eyevue.
- The SetWearingDetection log text changed.
