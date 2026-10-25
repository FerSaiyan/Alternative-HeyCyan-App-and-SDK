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

## Phase 2: process audio sessions (`audio.AudioSessionCoordinator`)

Filter logcat with:
`adb logcat -s AudioSession GeminiLiveClient PluginVoiceRecognizer MeetingCaptureService AmbientSpeech AudioQuestionRecorder LocalAgentApprovalVoice StudioApprovalHandler HandsFreeTranslator WalkingAidService SpeechQueueController ImageQuestionAudio`

Every session logs `"<owner> <KIND> granted state=…"`, `"<owner> route plan=… users=N mode=…, sco=…, communication=…"`, `"<owner> <KIND> -> SUSPENDED|ACTIVE|ENDED"` and `"<owner> <KIND> released restoredRoute=…"`. MainActivity's voice and image questions still do their own routing (not migrated yet).

**Shared route (applies everywhere)**
- The Bluetooth route is restored only when the last session using it ends (`restoredRoute=true` once). Before, ending any feature reset the mode and dropped SCO under the others.
- Restoration clears the communication device instead of re-selecting the "previous" one (Live, Local Agent approvals), and restores the previous audio mode instead of forcing NORMAL (meeting, ambient, plugins, Studio). A phone-mic meeting or ambient check no longer touches the mode or SCO at all.
- Android 12+: every feature accepts LE Audio (`BLE_HEADSET`) as well as SCO headsets. Legacy SCO is used only when no communication device is offered and a headset looks connected.

**Gemini Live** (glasses and earbuds, phone-only)
1. Start Live: audio goes to the glasses mic/speaker as before. Log: `"Live audio route selectedBluetooth=…"`. Android 12+ no longer also calls `startBluetoothSco`; check the mic still comes from the glasses.
2. During Live, trigger a Local Agent spoken approval (or a Studio approval, a voice question, or a chat voice note): expect `GeminiLiveClient -> SUSPENDED`, Live stops sending audio (`audioStreamEnd`), the prompt is heard and answered, then `-> ACTIVE` and Live hears you again.
3. Start Live while a spoken approval is listening: `"Live capture waiting for the microphone"`, and Live starts listening when the approval ends.
4. Phone call / other app taking focus: unchanged (focus loss pauses, permanent loss stops).
5. Mentra Live: the PCM path and the playback signal are now chosen from the family audio profile (`VENDOR_PCM_STREAM` / `BLUETOOTH_WITH_PLAYBACK_SIGNAL`); behavior should be identical.

**Meeting capture**
1. With glasses: notification says "Source: Bluetooth mic". Android 12+ now waits up to 3 s for the communication device to become current (plus 250 ms) instead of a fixed 250 ms; if it never does, the source is "Phone mic".
2. Android 11 and older: SCO now waits for a real "connected" broadcast (up to 6 s) and ignores the stale sticky state, which used to fail instantly.
3. Start Gemini Live, a voice question or a translator during a meeting: the meeting keeps recording (never interrupted). Stop that feature: the meeting route must stay on the glasses (`users=` drops, no `restoredRoute=true`). Listen to the recording for gaps.
4. Stop the meeting while Live runs: Live keeps its route.

**Auto Audio speech check (AmbientSpeech)**
- While Live, a meeting, a plugin or a spoken prompt holds the mic: `"Skipping speech detection: the microphone is in use"` and the loop does not extend.
- If one starts during the 60 s check: `"Speech check stopped early: another session took the microphone"`; the result uses the frames so far.
- After a check, a concurrent plugin or Live keeps its Bluetooth route (previously the check reset it).

**Voice plugins (Hands-Free Translator, Live Caption Relay, Errand Brain, Meeting Spark Notes)**
1. Start one plugin, then another: "Another voice plugin is already listening" (unchanged).
2. Plugin running, then start Live / a spoken approval / a voice question: `"pausing recognition while another session uses the microphone"`, recognition resumes ~350 ms after it ends.
3. Start a plugin while Live runs: it shows Listening but hears nothing until Live stops.
4. Translator speech output still uses voice-communication audio on the glasses when the BT mic route is up, media otherwise.

**Local Agent spoken approval and Studio approval**
1. The prompt is spoken, then listening starts after the glasses route is confirmed (up to 3 s + 500 ms on Android 12+, up to 6 s SCO on older phones). Listening may start slightly later than before.
2. Studio now uses `setCommunicationDevice` on Android 12+ instead of legacy SCO; check the beep and the reply both go through the glasses.
3. Two approvals at once: the second one gets no microphone and falls back to its existing no-reply path (external reply / "No response received. Denied.").
4. Prompt speech that is stopped (e.g. screen off) no longer waits for its timeout.

**Voice questions / chat voice notes**
- A voice question recorded while another spoken prompt is listening fails with "The microphone is busy; please ask again" (spoken). A chat voice note in that case shows "Unable to start recording".
- Live and voice plugins pause during question and voice-note recording.

**TTS (all features)**
- Walking Aid warnings/explore, Walking Aid chat, translator, notification announcements, Live announcements, Studio and Local Agent speech sound and route exactly as before. Streaming local-model replies still request transient focus once per reply and release it at the end.

## Wave 3: shared database (Room in `:shared`)

The schema is byte-identical: same file `cyanbridge-db`, version 8, identity hash `1f2b5479c1d0fe0282817bfd8e009967`. Still, run the upgrade test once on a phone that has real data:

1. Install the previous build, signed with the release key, using `scripts/install_debug_keep_data.sh`.
2. Create some data: chats with messages, a note, a meeting recording with a transcription, and memory/screen captures. Search memory once.
3. Install this branch over it with `install -r` (no uninstall).
4. Check that chats, messages, notes, recordings and transcriptions are intact and memory search returns the same results. Logcat should show no Room integrity or migration errors.
5. Optional: `adb shell run-as com.fersaiyan.cyanbridge sqlite3 databases/cyanbridge-db "PRAGMA user_version; SELECT identity_hash FROM room_master_table"` should print `8` and the hash above.

Known issue, not caused by this change: the memory search index needs SQLite FTS5. On phones whose system SQLite lacks it, fresh installs get no search index, and an upgrade from database version 4 or older fails at migration 4→5.

**iOS (needs a Mac build):** existing chats appear after updating (a one-time import from NSUserDefaults), new messages persist across restarts, and a deleted chat stays deleted.

## Wave 3: AI relay and providers in shared code

- **Android relay chat, voice and image questions:** same fields, headers, timeouts, retries and error texts. The JSON bytes differ slightly (`/` is no longer escaped, and a whole-number temperature is written as `0.0`), but the values are the same.
- **Relay errors:** test a real 402, 403 and 401 and check you get the same localized quota/subscription/auth messages. A 2xx response that isn't JSON now shows a different "Relay error: …" text.
- **OpenAI-compatible endpoints:** URLs, payload with image/audio parts, streaming, and the health text should be unchanged.
- **Local agent:** one UI-control run and one Telegram command, to confirm model-output parsing is as tolerant as before.
- **iOS (intentional changes to match Android):**
  - Relay chat retries up to 3 times and checks `/capabilities` first.
  - Errors read "Error: <relay error>".
  - Remote OpenAI requests send `max_tokens` 2048 and `temperature` 0.7.
  - Meeting notes use Android's prompt.

## Wave 3: shared Eyevue protocol (iOS only)

iOS now uses the protocol code Android already ran, so check with an iPhone and Eyevue glasses:
- Start video sends `0x00`, as the vendor app does.
- Replies in `AC 55` frames are accepted. The old shared parser probably dropped every reply from real glasses.
- AI photos are capped at 4 MiB.

## Wave 4: TuneBuds through the shared `TuneBudsSession`

Filter logcat with: `adb logcat -s TuneBudsManager TuneBudsSpp TuneBudsSession TuneBudsMediaSync TuneBudsP2p TuneBudsLive GlassesNetSession`

1. **Connect over SPP (Device Bind and Reconnect)**
   - Expect `RFCOMM connected`, then "TuneBuds connected".
   - Battery, firmware, model, coprocessor, storage and counts fill in.
   - New: if the optional coprocessor-version query (E9) fails, startup continues (`Optional TuneBuds capability query failed`), as on iOS.
2. **Photo:** it saves about 2.5 s after E1 and the camera closes.
3. **Video and audio**
   - Start and stop.
   - A firmware or button stop resets the toggle.
   - Stopping while still saving retries for up to 15 s.
   - New: recording flags read false whenever RFCOMM isn't connected.
4. **Battery, storage, counts, time sync, version**
   - All update.
   - New: an E9 timeout under "read versions" no longer shows an error.
5. **AI photo (voice image question and Live photo button):** expect `TuneBudsSession: TuneBuds AI photo completed bytes=N`. These log lines moved from the `TuneBudsManager` tag to `TuneBudsSession`.
6. **Media sync over file-manager Wi-Fi**
   - Hotspot mode imports files with no immediate "disconnected".
   - P2P mode, if the Wi-Fi mask has bit 4, also works.
   - Low battery shows the "status 4" message.
7. **RTSP live preview:** start and stop. Video arrives in about 300 ms, and the camera closes on stop.
8. **Reconnect**
   - Power-cycle or toggle Bluetooth: the status says "disconnected" and the toggles reset.
   - Reconnect: battery and versions refill.
   - Disconnect suppresses auto-reconnect.
   - The Device Bind probe still identifies the glasses.
9. **iOS (needs a Mac build)**
   - AI photos use the strict JPEG assembler, so malformed streams are dropped.
   - A camera-close notification clears the recording flags.

## Wave 4: typed shared preferences

Every prefs file, key, type and default is declared once in `shared/prefs`, and `PrefsInventoryTest` pins them. Run the upgrade check once:
1. Install the previous build and change several settings:
   - language, wake-word route, thumbnail quality, assistant mode and provider;
   - relay URL, Pro Live economy;
   - one plugin's settings, appearance and accent;
   - remote server with an API key, the Telegram token, and Pro login.
2. Install this build over it (`adb install -r`).
3. Check:
   - Every setting is unchanged.
   - The remote API key and Pro token still work, which proves the encrypted prefs are still read.
4. Optional: diff `run-as … shared_prefs/*.xml` before and after. File names, keys and value types must be identical.

**iOS (needs a Mac build):**
- Wake word route, thumbnail quality, assistant mode, language and the remote server settings keep their existing NSUserDefaults names.
- The appearance and welcome flags still load.

## Wave 5: shared Glasses dashboard controller

**Android:** behavior is unchanged. Meta and Meizu panel actions now run only while that family is selected, which is already the only time the panel renders. The per-family checks above cover this.

**iOS (compile on a Mac first: `-PenableAppleTargets=true :shared:compileKotlinIosSimulatorArm64`)**

Deliberate changes:
- **Eyevue**
  - Handles actions whenever it is selected.
  - Media count, start sync and volume now show "not implemented in the iOS host yet"; they no longer fall through to HeyCyan BLE or hotspot commands.
  - The image question listens for a spoken question while the photo transfers.
  - Shows the Advanced and Device info cards when linked.
- **Battery and storage cards:** shown by family profile, with "--" until data arrives. Meta, audio-only and unknown glasses have no battery card.
- **TuneBuds, SDK glasses, Meizu:** undeclared actions show "not implemented" instead of the HeyCyan fallback messages.
- **Wake word and thumbnail quality:** saved only for families that support them.
- **QCSDK assistant hooks:** apply only while HeyCyan is selected.

Smoke test per family:
- connect;
- photo or AI photo;
- battery;
- image question;
- sync (HeyCyan only);
- the Meta and Meizu panels.

## Wave 6: dead-code removal

No glasses needed. Deleted code had no caller, so behavior is unchanged apart from one deliberate fix:
- **Telegram `/task` while a task runs:** the guard now asks `TaskerLocalAgentService` (the service that actually runs tasks) instead of the never-started `LocalAgentService`. With a Local Agent task or a screen read in progress, a second `/task` should reply "A Local Agent task is already running…" instead of "Task accepted". The automation diagnostics in the debug log show the same running state.
- **Pairing:** `DeviceBindActivity` lost its unused protocol auto-detection probes; pair one glasses family through manual selection to confirm the dialog still finishes on connect.

## Wave 7: shared screen ViewModels — chats, notes, recordings

**Android**

Screens and strings are meant to be identical, and the emulator pixel comparison will check this. Known changes:
- A generation or a voice-note recording now continues through a rotation. Before, rotating stopped and attached the recording.
- Closing the chat screen while recording now discards the voice note silently. There is no late "Voice note attached" toast.
- `KnowledgeSyncWorker.schedule` runs once per screen ViewModel instead of on every `onCreate`.

Smoke test:
- Send text, an image and a voice note in a chat.
- Title generation.
- Delete a chat.
- The daily-facts review chat.
- Note edit and save.
- Recordings list, gallery open and share.
- Transcription debug.
- Knowledge integrations sync.

**iOS (Mac build; chats now run on Android's logic)**
- Empty chats are hidden. A new chat is created on the first send.
- Titles are generated the Android way: the first 32 characters, then a 2–6 word AI title, with one extra AI call.
- Voice records a voice note (30 s auto-stop) that is transcribed when sent. Before, the transcript went straight into the composer.
- Errors read `message ?: "Failed to get response"`.
- A note save with an empty body is ignored.

## Wave 7: shared screen ViewModels — plugins and devices

**Android**

Screens and strings are meant to be identical. Known changes:
- Plugin settings, Walking Aid, Community Plugins, Publish, Pending actions, Daily summary, battery guide and EvenHub keep their state through a rotation. Before, rotating reloaded it. Community Plugins no longer re-fetches the catalog on rotation.
- Toasts from these screens use the application context. Service stops and history clears are unchanged; Auto Audio and Walking Aid still start from the Activity so their permission prompts appear.
- The community catalog is parsed by the shared parser. Only malformed entries differ: a JSON `null` field now counts as missing instead of the text "null".
- The device bind screen still resets on rotation and rescans, as before.

Smoke test:
- Device bind for each family: HeyCyan, EyeVue, TuneBuds and MoYoung through the second picker; Meta (opens Meta pairing); Meizu (bond dialog); audio-only.
- Meta pairing and Mentra pairing are unchanged; open each once from device bind.
- Plugin switches on/off from Community Plugins and from each settings screen: Live Caption, Translator, Meeting notes (also "summarize now"), Errand Brain, Auto Audio (with and without microphone permission), Walking Aid.
- Clear history on each plugin settings screen.
- Walking Aid: model download progress for the detector and depth model, latency and acquisition benchmarks, focus translate and save, cloud model pickers, readiness dialog with a missing model, Explore/Walking mode switch, "Ask about surroundings" chat with speech.
- Community plugin refresh, Tasker profile download, plugin publish (validation errors, then a successful submit).
- Pending actions approve/reject; daily summary regenerate and share; battery guide answers; EvenHub app load and display.

**iOS (Mac build; device bind now runs on Android's pairing choices)**
- Selecting a device: Mentra Live keeps Mentra (and connects through the Mentra SDK); other unknown classes still map to HeyCyan.
- Confirming EyeVue, TuneBuds or MoYoung in the first picker now connects as that protocol instead of HeyCyan.
- Confirming an unknown class closes the screen with "Unknown device type."
