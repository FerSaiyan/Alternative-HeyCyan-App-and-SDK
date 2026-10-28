# Mentra integration audit (Mentra Live and the SDK display glasses)

Sources checked: the cached Android SDK `com.mentraglass:bluetooth-sdk:3.2.0-dev.227` (public API read with `javap`), and the open-source Mentra repository `Mentra-Community/MentraOS`. From that repository I read the SDK sources (`mobile/modules/bluetooth-sdk`, version 3.4.0), the SDK docs (`mintlify-docs/bluetooth-sdk`) and the official Android, iOS and React Native examples (`examples/bluetooth`). Behavior notes come from the 3.4 sources. Every API call in CyanBridge was checked against the 3.2.0-dev.227 AAR.

## The SDK's intended flow

| Step | What the SDK expects |
|---|---|
| Instance | Keep one `MentraBluetoothSdk` per process. Creating it starts the SDK's own foreground service (`com.mentra.bluetoothsdk.services.ForegroundService`, "Mentra Connected"), which picks its service types from the granted permissions. The SDK manifest declares the Bluetooth, location, audio and foreground-service permissions. |
| Discovery | `scan(DeviceModel.MENTRA_LIVE, timeoutMs) { devices -> }`. The callback gets the full stable-order list on every change. The SDK matches the names `XyBLE_*`, `MENTRA_LIVE_BLE*`, `MENTRA_LIVE_BT*` and `mentra_live*`. Docs: show an explicit picker and never auto-connect to the first result. |
| Pairing | `connect(device)`. The SDK does the BLE bond (CTKD audio bond on Android; accept the system dialog if one appears). `ConnectOptions` defaults to `saveAsDefault = true`. The pairing window is opened on the glasses: they enter it on their own when no phone is paired, or after three presses of the power button (a 120 s window with a spoken 4-character code). The app has no separate pairing or provisioning step, and no Wi-Fi provisioning is needed to pair. |
| Reconnect on launch | The app persists a small default-device record, restores it with `setDefaultDevice(Device(model, name, address))`, then calls `connectDefault()`. The example app does this on start. On Android the SDK reconnects by Bluetooth name. |
| Lifecycle | The SDK itself reconnects after link loss (up to 20 attempts) and when Bluetooth turns back on. `disconnect()` is the user's disconnect. `forget()` removes the bond. `clearDefaultDevice()` stops saved reconnects. State: `GlassesRuntimeState` (`Connected`/`Disconnected`, plus `connection` DISCONNECTED/SCANNING/CONNECTING/BONDING/CONNECTED and `ready`). |

## Capability surface (SDK 3.2.0-dev.227)

Camera: `requestPhoto` (LOW/MEDIUM/HIGH/MAX, compression, `save` to the glasses gallery, webhook delivery over Wi-Fi or BLE fallback through the phone SDK, PHOTO/TEXT mode, manual exposure), `warmUpCamera`, camera FOV, `startVideoRecording`/`stopVideoRecording` (saved on the glasses), recording defaults, gallery mode, `queryGalleryStatus`.
Streaming: `startStream`/`stopStream` (RTMP, SRT, WHIP, keep-alive and status run by the SDK).
Audio: `setMicState` gives continuous 16 kHz PCM (`onMicPcm`), LC3 (`onMicLc3`) and local transcription. The SDK also offers VAD, the loudness gate, `setOwnAppAudioPlaying`, and reading or setting the media volume (0-15). The speaker is the phone's Bluetooth media output.
Input: `onButtonPress` (short/long/double), touch, swipe, head-up (`setHeadUpAngle`).
Status: battery and charging, firmware/build/serial (`requestVersionInfo`, the connected-state device info), Wi-Fi and hotspot status, signal.
Network: Wi-Fi scan and credentials, saved networks, hotspot on/off. The gallery HTTP server is reachable only over the glasses hotspot (see "Gallery download").
Maintenance: OTA (`checkForOtaUpdate`, `startOtaUpdate`, status events). The glasses firmware must match the SDK version.
Other: RGB LED control, system time (the SDK also syncs it on every connect), display APIs for display models (no-ops on Live).

## What CyanBridge did, and what was wrong

- **A separate pairing screen.** `MentraPairingActivity` was a 123-line View-based screen (the only non-Compose screen). It opened from a "Pair Mentra Live" button that showed on Device Bind for every user. It ran the SDK scan in isolation from the normal list, saved the profile only after the link came up, and then finished. It existed because the integration was written as a standalone SDK demo ("Mentra SDK discovery is deliberately independent from the Oudmon scanner"). Creating the SDK also starts its foreground service, so it was kept away from the shared scan. The SDK does not need a separate step. Pairing is `scan` → user choice → `connect`, the same shape as Device Bind.
- **No reconnect on launch.** `AutoPairManager` returned early for Mentra everywhere, and the app never called `setDefaultDevice`/`connectDefault`. After an app restart the glasses stayed disconnected until the user paired again.
- **Mentra ran on `LegacySdkGlasses`.** Photo, battery, version, volume and the media buttons clicked the HeyCyan/Oudmon controls, which talk to a different SDK. Reconnect clicked the HeyCyan connect button.
- **The button went nowhere.** `onButtonPress` emitted into `GlassesBridge.bridgeEvents`, which nothing reads.
- **The microphone fed Gemini Live only.** A single PCM listener meant voice questions recorded the phone microphone, and two users would have clobbered each other.
- **AI photo was Gemini-only.** Walking Aid, visual notes and the shared AI-photo service (`GlassesAiPhotoCapture`) did not know Mentra.
- **Gallery mode was left on.** The glasses would take their own photo or video on a press while CyanBridge treated the press as an assistant request.
- iOS opened the same Mentra button. The Swift bridge connected to the first Mentra Live it found, not the device the user picked.

## What changed

- Device Bind is the only pairing path. The shared classifier recognizes Mentra Live names (`BleDeviceClassifier.isMentraLiveName`, also used by the Android `DeviceClassifier`). When a Mentra row appears, or Mentra was used before, `DeviceBindActivity` also runs the SDK scanner and merges its results into the same list. Rows it reports are Mentra Live. The SDK is not created for users without Mentra glasses, so its foreground service never starts for them. Choosing a row saves the profile (`selectedClass = MENTRA_LIVE`) and calls `MentraLiveManager.connect`, which prefers the SDK's scan record and saves it as the SDK default. The type picker offers "Mentra Live" only for devices detected as Mentra (`DeviceBindViewModel.detectedOnlyChoicesFor`). Choosing another family calls `MentraLiveManager.releaseIfStarted`, which disconnects and clears the SDK default.
- Reconnect: `AutoPairManager` start, Bluetooth-on and ACL events call `MentraLiveManager.reconnectSaved`. That restores the saved profile as the SDK default (`setDefaultDevice`) and calls `connectDefault(cancelExistingConnectionAttempt = false)`. It skips when the SDK is already connected or busy. The SDK keeps owning link-loss reconnects, so the periodic loop still skips Mentra.
- `MentraPairingActivity`, its manifest entry, the button and `onPairMentraGlasses` are gone (Android and iOS).
- `MentraLiveGlasses` replaces `LegacySdkGlasses` for Mentra. See the matrix below.
- iOS: picking a Mentra row connects through the SDK bridge with that row's CoreBluetooth identifier, with the name as a fallback, instead of the first Mentra found.

## Capability matrix

| Capability | Android | How |
|---|---|---|
| Pair from Device Bind, reconnect on launch | wired | above |
| Battery and charging | wired | the `GlassesRuntimeState` battery, shown on the dashboard and in the Battery action |
| Photo (dashboard) | wired | `requestPhoto(size = HIGH, save = true)` into the glasses gallery |
| AI photo | wired | `GlassesAiPhotoCapture` → `MentraPhotoCapture`: BLE delivery to a one-shot loopback webhook, under the shared camera lease. Used by Gemini Live, Walking Aid and visual notes. |
| Image question (button / dashboard) | wired | `MentraLiveGlasses.questionPhoto` = `MentraQuestionPhoto`: `MentraPhotoCapture` on IO, `cacheDir/Mentra_AI_<ts>.jpg`, answered as a fast preview, with the ask window open during the transfer. See "Question flow hooks" below. |
| Voice question | wired | Long press → voice question. `AudioQuestionRecorder` records the SDK PCM (`VendorPcmMicrophone`) instead of an Android input, inside the same `VoiceCapture` audio session; no Bluetooth call route is opened next to it. Spoken answers send `setOwnAppAudioPlaying`. |
| Gemini Live mic and speaker | wired | Conversation session with `DEVICE_SDK` route, multi-listener `openMicrophone`/`closeMicrophone`, `setOwnAppAudioPlaying` |
| Button | wired | press → image question, long press → voice question; gallery mode is turned off on ready so presses only reach the app. Without the main screen the manager starts the same questions (`GlassesButtonQuestions`). |
| Video recording | wired | `startVideoRecording`/`stopVideoRecording`, saved on the glasses |
| Media count | wired | `queryGalleryStatus` (photos and videos) |
| Firmware version, time sync, volume | wired | `requestVersionInfo`, `setSystemTime`, `getGlassesMediaVolume` (Advanced → Device info) |
| Gallery download | wired | Dashboard Sync: `setHotspotState(true)`, an app-scoped join of the glasses hotspot, HTTP download from the glasses camera server into the synced gallery, optional delete, hotspot off. See "Gallery download" below. |
| Live preview | wired (Android 10+) | Dashboard live preview card: the glasses publish RTMP (`startStream`) to a pure-Kotlin ingest on the phone, over their hotspot (app-scoped join, link client `MENTRA_LIVE_STREAM`) or a shared Wi-Fi. LibVLC plays the remuxed MPEG-TS inline. Walking Aid can use it as "Mentra Live continuous video". See "Live preview and broadcast" below. |
| Broadcast | wired | "Broadcast to my server" under the live preview card opens `MentraBroadcastActivity`: the glasses publish to the user's RTMP/RTMPS/SRT URL over their own Wi-Fi with internet. Status events, stop, URL in secure typed prefs. |
| Firmware update (OTA) | wired | Advanced → Device info → Firmware update opens its own screen (`MentraFirmwareUpdateActivity`), never the HeyCyan OTA section. See "Firmware update (OTA)" below. |
| Glasses Wi-Fi setup | partly | `requestWifiScan` and `sendWifiCredentials`, only inside the firmware update screen, because the glasses download the update over their own Wi-Fi. The broadcast screen links to it when the glasses have no Wi-Fi. Saved networks and forget are not wired. |
| LED, head-up, touch/swipe, LC3, local transcription, camera FOV and warm-up, WHIP | not wired | Nothing in CyanBridge consumes them yet (the hotspot is used by the gallery download and the live preview). |

## Gallery download

Sources checked: the 3.2.0-dev.227 AAR (`javap`: `setHotspotState(Boolean): HotspotStatusEvent`, `HotspotStatus.Enabled(ssid, password, localIp)`, `GlassesRuntimeState.Connected.hotspot`) and, in MentraOS, the official app's gallery client (`mobile/modules/engine/src/services/asg/asgCameraApi.ts`, `gallerySyncService.ts`, `mediaProcessingQueue.ts`, `galleryTransferLedger.ts`), its scoped hotspot join (`mobile/modules/glasses-media/.../ScopedSoftApNetwork.kt`) and the Android SDK example (`GlassesHotspotConnector.kt`, `MentraExampleController.kt`). The glasses-side server (`asg_client`, "AsgCameraServer") is not in that checkout, so the endpoints below are what the phone code calls, not what the firmware source says.

**What the glasses serve.** `setHotspotState(true)` starts a WPA2 access point on the glasses and returns its SSID, password and the glasses' IP (the official app also reads them from the `hotspot_status_change` event). The camera server listens on `http://<localIp>:8089` with no authentication; the hotspot password is the only gate. The glasses stop the hotspot after about 120 s without HTTP traffic.

| Firmware | List | Download | Delete |
|---|---|---|---|
| v3 (`GET /api/v3/capabilities` answers `api_version >= 3`) | `GET /api/v3/manifest?limit=&cursor=` (capture groups, paged) | `GET /api/download?file=<name>` (Range supported) | `POST /api/v3/ack {capture_id, ack_id}`: recoverable trash on the glasses |
| v2 | `GET /api/sync?client_id=&include_thumbnails=false` (`api_version: 2`, `captures`) | same | `POST /api/delete-files {files: [capture_id]}` |
| older | `/api/sync` `changed_files`, or `GET /api/gallery` (`photos`) | same | `POST /api/delete-files {files: [name]}` |

A capture group (`IMG_<date>_<time>_<ms>_<id>`) holds a `primary` file (`base.jpg` or the MP4) plus optional HDR `bracket` exposures and `sidecar` files. The official app always deletes (v3: acknowledges) a capture after it has verified and indexed the download.

**What CyanBridge does** (`MentraMediaSyncController` → `MentraGallerySync`):

1. Takes the exclusive `MEDIA_SYNC` glasses session and shows the shared transfer card. Needs Android 10+, the Nearby devices (13+) or Location permission, a ready link and no running video recording.
2. Reuses an already enabled hotspot, or calls `setHotspotState(true)` (30 s timeout) and waits 3 s for the AP to broadcast, as the official app does.
3. `MentraHotspotNetwork` claims the glasses link from `GlassesNetworkSessionCoordinator` as `GlassesLinkClient.MENTRA_GALLERY`, so it can never overlap a HeyCyan Wi-Fi Direct group, the Eyevue/TuneBuds transports or Wi-Fi ADB. It then requests the network with a `WifiNetworkSpecifier` (SSID and WPA2 passphrase) without `NET_CAPABILITY_INTERNET`. Android never makes it the default network, and the process is not bound to it. Only the OkHttp client built with `networkBoundHttpClient` (socket factory and DNS of that `Network`) uses it.
4. Waits for `GET /api/status`, then lists the gallery (v3 manifest, else `/api/sync`, else `/api/gallery`). The shared parser and planner (`shared/devices/mentra/MentraGallery.kt`) keep the primary JPEG/MP4 of each capture. HDR brackets, sidecars and other formats (AVIF, HEIC, MOV) are left on the glasses. Each file is named `MentraLive_<capture>.jpg|mp4`, and files already in the synced gallery under that name are skipped.
5. Downloads each file to the cache. It is kept only if the byte count equals the size the glasses listed. It is then saved through `GlassesMediaLibrary` into `DCIM/CyanBridge` (the same Gallery screen as HeyCyan and Eyevue media) with the capture time as `DATE_TAKEN`.
6. If any file is verified on the phone (downloaded now, or already there with the same size), it asks "Delete from glasses?", the same question the iOS HeyCyan sync asks. While the dialog is open it pings `/api/status` every 30 s to keep the hotspot alive. Delete uses the v3 ack or `/api/delete-files`. Deleting a capture removes its bracket files too.
7. Always: evicts the HTTP pool, unregisters the network request and releases the link claim, then calls `setHotspotState(false)`, but only if this sync turned the hotspot on. Then it refreshes the media counts.

**iOS.** The Swift Mentra bridge (`ios/CyanBridgeKMPHost/MentraGlassesBridge.swift`) has no hotspot or gallery calls, and the iOS Sync button still reports that it is not implemented. The shared parser, URLs and planner are ready for it. iOS still needs: a bridge action that calls `setHotspotState` and returns SSID/password/IP; a `NEHotspotConfiguration(ssid:passphrase:isWEP:false)` join with `joinOnce = true` (the user approves the system prompt; the app needs the Hotspot Configuration entitlement, and Local Network usage text for iOS 14+); downloads with `URLSession`; saving through `IosMediaPlatform.saveToPhotoLibrary`; and `NEHotspotConfigurationManager.removeConfiguration` plus `setHotspotState(false)` afterwards. Unlike Android, iOS joins the hotspot for the whole device, so other traffic loses internet until it leaves.

## Firmware update (OTA)

Sources: the decompiled `MentraBluetoothSdk` OTA paths of 3.2.0-dev.227 (`checkForOtaUpdate`, `startOtaUpdate`, `OtaStatusEvent`, the pinned manifest), the SDK docs (`software-update.mdx`, the API reference's OTA section), the native Android example (`MentraExampleController`) and the React Native Engine coordinator the Mentra app uses (`useMentraLiveOta`, `OtaInstallCoordinator`, `otaInstallPolicy`, `OtaErrorMapping`). The glasses-side ASG client is not in the reference checkout.

**How it works.** The update is glasses-owned. Each SDK release pins one update manifest (3.2.0-dev.227: `mentra-live-ota-3.2.0-dev.227.json` on the MentraOS GitHub releases). The glasses software must match the SDK release; connecting does not prove compatibility. Glasses apps older than build 39 get the SDK's legacy production manifest instead. `checkForOtaUpdate()` fetches the manifest on the phone and compares it with the glasses app build, the MTK system version and the BES firmware version. `startOtaUpdate()` sends `ota_start` with the manifest URL and resolves on `ota_start_ack`. The glasses then download every part over their own Wi-Fi and report `ota_status` (`session_id`, `step_type` apk/mtk/bes, `phase` download/install, `current_step`/`total_steps`, `step_percent`/`overall_percent`, `status` in_progress/step_complete/complete/failed, `error_message`). Installing the glasses app restarts it, the system install can stay silent for minutes, and the glasses power-cycle right after a BES `complete`. A manifest can need more than one pass, so the flow checks again after each one. The official app's hotspot update (the phone downloads and serves the files) lives in the React Native Engine only; the native SDK has no public API for it. Because of that, CyanBridge requires glasses Wi-Fi and offers a minimal Wi-Fi setup on the update screen.

**Code.** The state machine and preconditions are in shared `devices/mentra/MentraOtaMachine.kt` and `MentraOtaRules.kt` (pure and unit-tested). `MentraFirmwareUpdateSession` runs them over a `MentraOtaPort`. `MentraFirmwareUpdateScreen` and `MentraFirmwareUpdateViewModel` are in `shared/ui/glasses`. On Android, `MentraOtaAdapter` implements the port over `MentraLiveManager` and holds the process-wide session; `MentraFirmwareUpdateActivity` hosts the screen. The entry point is the `SDK_FIRMWARE_UPDATE` capability. It is separate from `FIRMWARE_UPDATE`, the HeyCyan/Eyevue OTA section, and only Mentra Live declares it.

**Preconditions (start is disabled until all pass).** Glasses connected and ready. Glasses battery at least 25% (the official app's minimum; an unknown level does not block, and the glasses refuse below 5% themselves). Glasses Wi-Fi joined to a network. No CyanBridge feature streaming the glasses microphone. No other exclusive glasses workflow (the `GlassesSession.OTA` lease must be free). The manifest must not move the glasses app to an older build. Charging is recommended but not required, as in the official app. The user then confirms a dialog explaining the 10 to 20 minutes, the restarts and "keep the screen open and the glasses on".

**Safety rules.**
- Every `ota_start` follows a successful check on the current link: an offer is dropped when the glasses disconnect and checked again after they reconnect. A failure never retries the start directly; "Check again" runs a fresh check first.
- Once a BES install step has started, any failure (reported or a phone-side timeout) hides retry until the glasses restart, as the official app does. The BES may be in its raw update mode.
- Phone-side failures (no progress for 90 s, a step silent for 140 s or 320 s for the system install, no reconnect 140 s after a BES restart or 5 min after another drop, a pass longer than 20 min 20 s, an `ota_start` without an ack) start a 90 s cool-down. A late `ota_status` revives the update instead of allowing a second start. These are the official timings plus the 20 s the official app adds for older glasses.
- Progress from the glasses while the screen did not start an update (for example after the screen was reopened) moves to the update view and takes the lease too.
- A new glasses app build during an update ends the app step (the restarted app may not report the old session). After a 10 s settle, the screen re-checks, up to 4 times, 10 s apart.
- While the update runs, `MentraLiveManager.firmwareUpdateActive` refuses photos, video, the microphone, the action button and the user's disconnect. The `GlassesSession.OTA` lease blocks the other dashboard actions. The screen keeps the display on and refuses back. The session lives in the process, which the SDK's foreground service keeps alive, so leaving the screen does not lose the state.
- The check fails closed when CyanBridge cannot read the manifest itself, so the downgrade guard always runs. A sideloaded glasses app (`unofficial_client`) is reported and not updated.

**Not confirmed from the sources.** How the 3.2 glasses app answers an `ota_start` while it already runs an update. Whether it keeps or drops the BLE link during the app install. Whether it ends its session itself after a mid-pass app restart. The exact manifest field for MTK applicability (the SDK's check is internal, so the screen names the system patch only when the app and BES already match). The native SDK's OTA status query is internal, so the screen cannot ask the glasses for their session state after a reconnect; it waits for progress instead.

**iOS.** Not wired. The shared screen and session are ready, but the Swift `MentraGlassesBridge` would need an `MentraOtaPort` implementation: `checkForOtaUpdate`, `startOtaUpdate`, `.otaStatus` delegate events, `requestVersionInfo`, the Wi-Fi scan and credentials, and a Kotlin bridge in `SdkGlassesBridge`. The iOS host also needs navigation to the screen and the `bluetooth-central` background mode the SDK docs ask for. The iOS dashboard keeps the button hidden.

## Live preview and broadcast

Sources: the 3.2.0-dev.227 AAR (`javap`: `startStream(StreamRequest)`, `stopStream()`, `onStreamStatus(StreamStatusEvent)`, `StreamState`, `StreamVideoConfig`, `GlassesRuntimeState.Connected.wifi`), and in MentraOS the SDK sources (`MentraBluetoothSdk.kt`, `streaming/StreamModels.kt`), the docs (`camera-streaming.mdx`), the Android example (`MentraExampleController.kt`, `GStreamerWhipReceiver.kt`), the official app's SoftAP stream (`engine/src/services/SoftapCallTransport.ts`, `PhoneStreamCoordinator.ts`, `glasses-media/.../ScopedSoftApNetwork.kt`), `scripts/local-stream/README.md` (MediaMTX) and `agents/rtmp_*.md`. The glasses-side `asg_client` is not in the checkout.

**What the SDK offers.** `startStream` sends `start_stream` over BLE; the glasses pick RTMP (`rtmp://`, `rtmps://`), SRT (`srt://`) or WHIP (`http(s)://`) from the URL and publish over their own Wi-Fi. It resolves on `streaming` (30 s timeout); `stopStream` resolves on `stopped`. The SDK sends the keep-alives itself (`stream_keep_alive`, 3 missed acks fail the stream) and reports `stream_status` (`initializing`, `streaming`, `reconnecting`, `reconnected`, `reconnect_failed`, `stopping`, `stopped`, `error` with `willRetry`), `resolvedConfig` (encoded size, fps, bitrate) and live stats. There is no SDK-provided local preview: the Mentra app shows "No preview available for unmanaged streams", and its own phone preview (SoftAP calls) is a WHIP listener on the phone, inside a WebRTC stack. The glasses publish RTMP with StreamPack, which needs a `/app/streamKey` path.

**Protocol choice: RTMP to the phone.**

| | Phone-side cost | Latency | Decode |
|---|---|---|---|
| RTMP (chosen) | ~1,100 lines of pure Kotlin (handshake, chunks, AMF0, FLV/AVC, MPEG-TS), no native code | TCP on a one-hop link; the glasses encoder plus ~300 ms player caching | H.264 through the existing LibVLC (avcodec) |
| SRT | UDP with ACK/NAK retransmission and timestamp-based delivery to implement, or libsrt (MPL-2.0, native) | similar on a clean hop | MPEG-TS, same decode |
| WHIP | a WebRTC stack (libwebrtc, ~10 MB per ABI) or GStreamer (LGPL, the example's 100+ MB SDK) plus ICE/DTLS | lowest | WebRTC |

RTMP over TCP needs no loss recovery on our side, adds no native library or licence, and its demuxing is small enough to unit-test in `:shared`. LibVLC (already shipped, LGPL) plays the result, so the preview reuses the card, the player lifecycle and the headless frame output of the Eyevue/TuneBuds previews.

**Preview pipeline.** `MentraLivePreviewManager` (a `LibVlcLivePreviewManager`) runs `MentraLiveStreamConnection`:

1. Route: when the glasses report Wi-Fi (`wifi.localIp`) in the subnet of the phone's Wi-Fi, the ingest listens on the phone's Wi-Fi address. Otherwise the glasses turn their hotspot on (reused if already on), the phone joins it with `MentraHotspotNetwork` as `GlassesLinkClient.MENTRA_LIVE_STREAM` (no `NET_CAPABILITY_INTERNET`, default network unchanged), and the ingest listens on the phone's hotspot IPv4 from `LinkProperties`.
2. `MentraRtmpIngest` binds port 1935 (or a free port) on that address. A random per-session stream key is the publish path: `rtmp://<phone-ip>:<port>/live/<key>`; other keys get `NetStream.Publish.BadName`. Connections that close before publishing (the glasses' reachability probe) are ignored; a new publisher with the key replaces the old one.
3. `startStream` with 1280x720, 15 fps, 2 Mbit/s, `captureAudio = false` (video only: the glasses microphone stays free for the assistant).
4. The shared `RtmpPublishSession` answers `connect`/`releaseStream`/`FCPublish`/`createStream`/`publish`, acknowledges bytes, answers pings. `RtmpToTsRemuxer` turns FLV H.264 (legacy and Enhanced RTMP `avc1`) into Annex B with an access unit delimiter and SPS/PPS before keyframes, waits for the first keyframe and keeps timestamps monotonic across publisher reconnects. `MpegTsMuxer` writes PAT/PMT before keyframes and the PCR on the video PID.
5. `LoopbackTsServer` serves `http://127.0.0.1:<port>/<token>.ts`; a new player gets the frames since the last keyframe. LibVLC plays it with 300 ms caching. Frames for vision come from the same decoder (640x360, no rotation).
6. Failure: Mentra disconnects, `stopped`/`reconnect_failed`/non-retried `error` for this stream, the hotspot drops, or no video for 10 s. Close: `stopStream`, stop the servers, leave the hotspot, turn it off if the preview turned it on.

It holds the shared camera lease (`GlassesSession.LIVE_PREVIEW`), so AI photos and the gallery sync wait. HEVC (`hvc1`) is reported, not decoded.

**Walking Aid.** `WalkingAidVideoMode.MENTRA_VIDEO` ("Mentra Live continuous video", Android 10+) uses its own `MentraLivePreviewManager` through `startFrames`, under the same lease. The Walking Aid connection check now knows Mentra (it used to check the HeyCyan BLE link only). Gemini Live keeps its opportunistic stills for Mentra (`GeminiLiveVisionPolicy`), as it does for HeyCyan.

**Broadcast.** `MentraBroadcastController` (process-wide) checks the URL (`MentraStreamUrls.checkBroadcastUrl`: rtmp/rtmps with app and key, srt with a port, no loopback hosts), requires the glasses on Wi-Fi (`wifi()`), takes the `LIVE_PREVIEW` lease, and opens an `AudioSessionRequest.Recording` session with the `DEVICE_SDK` route for the glasses microphone. If the arbiter refuses (another recording), the broadcast runs without audio. `stream_status` drives the screen (phase, resolved video, bitrate/fps/dropped frames, error text); the stream key is masked in logs and on screen. The URL is stored in the secure typed preference `MediaPrefs.MentraBroadcast.streamUrl`. The dashboard card shows "Broadcasting to …" and its Stop ends the broadcast. Glasses Wi-Fi is set up on the firmware update screen's Wi-Fi step, which the broadcast screen opens.

**iOS.** Not implemented; the shared protocol code is ready for it. iOS would need: bridge actions for `startStream`/`stopStream` and `stream_status` in `MentraGlassesBridge.swift`; for the preview, the hotspot join from "Gallery download" (`NEHotspotConfiguration`, which takes over the device's Wi-Fi), a listener on the hotspot address (`NWListener` on port 1935, `requiredInterfaceType = .wifi`) feeding `RtmpPublishSession`/`RtmpToTsRemuxer`, and a player: `AVPlayer` cannot play a raw TS over HTTP without HLS, so either wrap the TS in a rolling HLS playlist on loopback (adds 1–2 s) or feed Annex B to `VTDecompressionSession` (lowest latency, ~150 lines of Swift). The Local Network usage text (iOS 14+) is required for the listener. The broadcast needs only the bridge actions and a screen.

**Not confirmed from the sources.** Whether the glasses' "no Wi-Fi" preflight accepts RTMP while they are in hotspot mode (the MentraOS local-stream README says a laptop on the Mentra hotspot works, and the Mentra app's SoftAP path streams WHIP to the phone this way, but neither proves RTMP from the 3.2 firmware). Which RTMP client the 3.2 glasses app uses (StreamPack per the docs; its handshake and command order are assumed to match librtmp/FFmpeg). Whether `captureAudio = false` is honoured (audio is ignored if sent). Whether the glasses' RTMP watchdog (network validation) reconnects in a loop on a hotspot without internet. The unit of `stats.duration` (not shown). Whether streaming and the SDK microphone PCM can run together.

## Question flow hooks (done with A3)

The question flows left MainActivity for `app/.../ai/questions` (A3), and these calls, listed here while that region was frozen, are in:

1. **Image question capture.** Through the device contract, not a device-class branch: `AndroidGlassesDevice.questionPhoto` is the family's photo transport, and `MentraLiveGlasses` returns `MentraQuestionPhoto` (`ai/questions/QuestionPhotoSources.kt`). It checks that the SDK is ready (else "Connect Mentra Live first."), opens the ask window like Eyevue, runs `MentraPhotoCapture(context).capture()` on IO, writes `cacheDir/Mentra_AI_<ts>.jpg` and calls `onImageReadyForQuestion(path, FAST_PREVIEW, …)`. A failure clears the question and toasts "Mentra Live photo failed: …". While the photo is taken, voice questions wait, as for Eyevue and Meta.
2. **Voice question route.** `QuestionMicrophone` asks `AudioRoutePolicy.forDevice(COMMUNICATION_BLUETOOTH_PREFERRED, profile.audio)`; for `DEVICE_SDK` it skips `startBluetoothMicRouteAndAwait`, its `check(ready)` and the SCO cleanup, and the Classic voice query does not register a call route to clear. The beep and the cue still play on the voice-call stream, outside a call; whether Android sends that to the glasses or the phone earpiece is a hardware check.
3. **Playback signal.** `AssistantSpeaker.speakVision` (relay answers, Classic voice answers and decision-flow answers) and the local model's streamed photo answers call `VendorPlaybackSignal.forSelectedGlasses(context)?.setAppAudioPlaying(true/false)` around the answer; the lookup returns Mentra's manager only when `AudioRoutePolicy.needsPlaybackSignal(profile.audio)` and the manager exists. Cues are not bracketed.

The button also works without the main screen: `MentraLiveManager.onButtonPress` calls `GlassesButtonQuestions`, which starts the image or voice question in the background when the app has no main screen and Mentra Live is the selected family (see the MemoMind README, "Questions").

## Display glasses through the SDK

Sources: the 3.2.0-dev.227 AAR (`javap`, and a jadx decompile of `DeviceManager.initSGC`, `sgcs/G1`, `G2`, `MentraNex`, `Nimo`, `Ar99`), the iOS Swift package at tag `3.2.0-dev.227` (`Package.swift`, `DeviceManager.swift`, `MentraBluetoothSDK.swift`, `Events/BluetoothEvents.swift`), and in MentraOS the 3.4 SDK sources, `glasses-compatibility.md`, `mintlify-docs/app-devs/core-concepts/display/layouts.mdx` and the engine's display profiles (`mobile/modules/engine/src/utils/display/profiles`, `services/DisplayProcessor.ts`).

### What the SDK can drive

`DeviceModel` lists G1, G2, MENTRA_LIVE, MENTRA_NEX, MACH1, Z100, FRAME, NIMO, AR99, SIMULATED and R1, but a model only works when the SDK creates a driver for it (`initSGC`):

| Model | Android 3.2 | iOS 3.2 Swift package | CyanBridge |
|---|---|---|---|
| G1, G2 (Even Realities) | driver | driver | `EVEN_G1`, `EVEN_G2` |
| MENTRA_NEX ("Mentra Display") | driver | excluded from the package (`MENTRA_FEATURE_NEX`) | `MENTRA_DISPLAY`, Android only |
| NIMO | driver | driver | `NIMO` |
| AR99 (Xingyi) | driver | driver | `XINGYI_AR99` |
| MACH1, Z100 (Vuzix) | needs `com.vuzix:ultralite-sdk-android:1.8`, compile-only in the public AAR (`createOptionalMach1Sgc` returns null without it), plus the Vuzix Connect app | excluded (`MENTRA_FEATURE_VUZIX`) | not added: the Vuzix SDK is under a proprietary licence (JitPack), so adding it is an owner decision |
| FRAME (Brilliant Labs) | no driver (`// sgc = FrameManager()`) | no driver | not added |
| R1 | the Even Realities R1 ring, a controller (`controllers/R1`), not glasses | | not added |

If the owner adds the Vuzix dependency, Mach1 and Z100 would be: text display (Mentra matrix "Full"), no images, no speaker, no camera, microphone through the phone ("Partial*"), tap → head-up toggle, 390 px usable width × 7 lines (~34 columns), and pairing through the Vuzix Connect app (the SDK scan reports a single "NOTREQUIREDSKIP" entry).

### Design: one device class per model

`GlassesFamilyProfiles.forClass(DeviceClass)` is the capability lookup for the dashboard gating, plugins, audio routing, Android and iOS; Device Bind's type picker lists classes; the profile persists the class name. One class per SDK model keeps all of that unchanged and lets each model declare its own input and microphone. A single `MENTRA_LIVE` class plus a model field would have needed a second lookup key everywhere and a new persisted field. `shared/devices/mentra/MentraSdkGlasses.kt` (`MentraSdkModel`) maps class ↔ SDK `deviceType`, holds the name rules, the display limits and which models the iOS package has. Mentra Live keeps its class, profile, adapter and code paths.

### Capabilities

| Class | Display | Input reported by the 3.2 driver | Microphone | Speaker | Camera |
|---|---|---|---|---|---|
| `EVEN_G1` | text, lines, cards, pages, clear, notifications | head-up (IMU); touchpads are not forwarded | SDK PCM (LC3 decoded) | no | no |
| `EVEN_G2` | same | touch gestures | SDK PCM | no | no |
| `MENTRA_DISPLAY` | same | none (button, IMU and head events are only logged) | SDK PCM | no | no |
| `NIMO` | same | single/double tap, long press, head-up | SDK PCM | no | no |
| `XINGYI_AR99` | same | none | SDK PCM (Opus decoded) | no | no |

All five: `BATTERY_STATUS`, `showsAssistantAnswers = true`, audio `VENDOR_PCM_STREAM` in / no output (questions record the SDK stream, `DEVICE_SDK` route, no SCO; answers play on the phone), and Walking Aid, Visual Diary and Auto Audio refused like MYVU/MemoMind. Images (`IMAGE_DISPLAY`) are not declared: the SDK's bitmap paths are internal or model-specific, and the shared contract has no image command yet. Brightness: `setBrightness` is internal in 3.2.

### Pairing

The SDK drives one model at a time, and scanning a model replaces the current driver (`initSGC` cleans up the previous one, even a connected Mentra Live). So Device Bind does not run SDK scans to list display glasses: it lists them from its own BLE scan with the SDK scanners' name rules, and only scans with the SDK, for the chosen model, after the user confirms.

- Names: G1 `Even G1_<n>_L_…`/`_R_…`, G2 `Even G2_<n>_L_…`/`_R_…`, Mentra Display `Nex1-…`/`MENTRA_DISPLAY_…`, NIMO `nimo…` (the SDK pairs its Classic "nimo…" device, not the `…_ble` one).
- G1/G2 arms: the SDK connects both arms from one pick (G1 `connectById(<n>)` finds `_L_` then `_R_` with that pair number; G2 matches both arms by the serial number in their manufacturer data). Device Bind therefore drops right-arm rows (Android and iOS). The SDK's scan result names: G1 the pair number, G2 the serial number.
- AR99: never by name. Device Bind parses the advertisement like the SDK driver (first manufacturer-specific structure ≥ 21 bytes; project ID = its first 4 bytes, company ID included; BLE address = the next 6 bytes XOR 0xAD) and lists a row as AR99 only for the exact ID "AR99" (`Ar99Advertisement`). AF98, AF99, HVXM, HVXF, a missing ID or no manufacturer data fail closed. The type picker never offers AR99 from a saved choice alone, and `DeviceBindViewModel.confirmConnection` refuses a display class the row was not detected as. The SDK pick then requires the project name "AR99" and the advertised address (`MentraSdkPairing`); the SDK's own scanner also only accepts "AR99". iOS reads the manufacturer data from CoreBluetooth and checks `projectName == "AR99"` in Swift.
- Connect (`MentraLiveManager.connectDisplayGlasses`): drop another model's link, scan the model for 20 s, pick the row's glasses (address, else the SDK name derived from the BLE name, else the only result for G2/NIMO), `connect`. Several G2/NIMO and no match → "More than one … nearby"; none → "… not found". The SDK record is saved in the typed prefs `AppPrefs.MentraSdkDevice` because the SDK name is not the Bluetooth name.
- Reconnect: `AutoPairManager` (startup, Bluetooth on, ACL) calls `reconnectSavedDisplay`: the saved record becomes `setDefaultDevice`, then `connectDefault`. The SDK keeps owning link-loss reconnects. Device Bind's Mentra Live scan is skipped while display glasses hold the SDK (`scanWouldDropLink`).

### Display

The SDK's `displayText(text, x, y, size)` ignores x/y/size and sends the text as is (`sendTextWall`); the Mentra app wraps it first with per-model font tables. CyanBridge wraps and pages in shared code (`MentraDisplayText`, tested), with character limits set below Mentra's average glyph widths so mixed text does not overflow (wide CJK characters count 2):

| Model | Mentra profile | Screen used | UTF-8 budget per screen |
|---|---|---|---|
| G1 | 576 px, 5 lines, glyphs (w+1)×2 px | 40 columns × 5 lines | 390 bytes |
| G2 | G1 font, 8 lines | 40 × 8 | 390 |
| Mentra Display | 480 px, 8 lines | 44 × 8 | 450 |
| NIMO | 500 px, 8 px ASCII advance, 11 lines | 60 × 11 | 2,000 (firmware 8,192) |
| AR99 | none (Mentra falls back to G1) | 40 × 5 | 390 |

`MentraDisplayAdapter` (shared, used by Android and iOS) implements `GlassesDeviceAdapter` and `GlassesNotificationDisplay`: text, lines and cards (title on the first line) are paged, later screens follow at a reading pace (3-12 s, `dwellMs`), a tap shows the next screen at once, a new command replaces the pages, `ttlMs` clears afterwards, and notifications ("app: title" + body) clear 10 s after their last screen. Android registers it as `mentra_display` next to Mentra Live's adapter; connecting display glasses makes it the active bridge adapter, so assistant answers (`showsAssistantAnswers`), plugins, notifications and runtimes reach the glasses.

Dashboard APIs are not used: `setDashboardContent`/`showDashboard`/`setDashboardPosition` feed the SDK's head-up dashboard (status header plus content), which has no counterpart in the shared contract. A possible use is showing the last answer when the user looks up. `setHeadUpAngle` would need a settings control. `setScreenDisabled` is not needed. Unconfirmed: whether the SDK's head-up dashboard redraws over text sent with `displayText`, and whether looking down restores it (hardware checklist step 10).

### Audio and input

Questions record the SDK microphone (`VendorPcmMicrophone` covers the five classes; `AudioRoutePolicy` gives `DEVICE_SDK`, no SCO). Input (`MentraDisplayInput`): touch gesture names and button presses become `InputEvent.Button`; long press → voice question (background `GlassesButtonQuestions` without the main screen, the dashboard's voice question with it), tap → next screen, double tap → clear; head-up/down reach the bridge listeners only. Gallery mode is not touched for display glasses.

### iOS

`MentraGlassesBridge.swift` takes a model; one instance per model shares one SDK and the bridge with a listener gets the events. G1, G2, NIMO and AR99 are registered; Mentra Display shows a "needs the Android app" message. Swift handles `display_text`/`clear_display` and forwards touch gesture names; Kotlin (`IosOtherGlasses`) builds the same `MentraDisplayAdapter` over those actions as the answer display. Not on iOS: the Mentra microphone stream (as for Mentra Live), head-up events and the SDK default-device reconnect (iOS reconnects by scanning for the saved identifier). The Swift was not compiled here (no Mac); the Kotlin side passes `iosCompileCheck`.

### Not confirmed without hardware

Everything above comes from sources; no display glasses were available. Open per model: the exact line overflow of the chosen limits, G1/G2 text throttling (G1 keeps only the latest text within its throttle window), whether the PCM path works for each driver's LC3/Opus audio, the G2 and NIMO gesture names in 3.2, the NIMO Classic/BLE name pairing, AR99's advertised address equalling its scan address, and how the SDK's own dashboard interacts with `displayText`.
