# Mentra Live integration audit

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
| Image question (button / dashboard) | partly | The button and dashboard reach `startImageQuestionFromUi`, but the capture branch is in the frozen MainActivity region. See below. |
| Voice question | wired | Long press → voice question. `AudioQuestionRecorder` records the SDK PCM (`VendorPcmMicrophone`) instead of an Android input, inside the same `VoiceCapture` audio session. |
| Gemini Live mic and speaker | wired | Conversation session with `DEVICE_SDK` route, multi-listener `openMicrophone`/`closeMicrophone`, `setOwnAppAudioPlaying` |
| Button | wired | press → image question, long press → voice question; gallery mode is turned off on ready so presses only reach the app |
| Video recording | wired | `startVideoRecording`/`stopVideoRecording`, saved on the glasses |
| Media count | wired | `queryGalleryStatus` (photos and videos) |
| Firmware version, time sync, volume | wired | `requestVersionInfo`, `setSystemTime`, `getGlassesMediaVolume` (Advanced → Device info) |
| Gallery download | wired | Dashboard Sync: `setHotspotState(true)`, an app-scoped join of the glasses hotspot, HTTP download from the glasses camera server into the synced gallery, optional delete, hotspot off. See "Gallery download" below. |
| Live preview and streaming | not wired | The glasses push RTMP/SRT/WHIP to a URL they can reach over Wi-Fi. CyanBridge's live preview pulls RTSP over Wi-Fi Direct, so this needs an on-phone ingest server (the SDK example uses GStreamer/MediaMTX). |
| Firmware update (OTA) | wired | Advanced → Device info → Firmware update opens its own screen (`MentraFirmwareUpdateActivity`), never the HeyCyan OTA section. See "Firmware update (OTA)" below. |
| Glasses Wi-Fi setup | partly | `requestWifiScan` and `sendWifiCredentials`, only inside the firmware update screen, because the glasses download the update over their own Wi-Fi. Saved networks and forget are not wired. |
| LED, head-up, touch/swipe, LC3, local transcription, camera FOV and warm-up | not wired | Nothing in CyanBridge consumes them yet (the hotspot is used only by the gallery download). |

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

## Calls the frozen MainActivity region must add later

1. **Image question capture.** In `handleGlassesImageButtonPressed`, after the TuneBuds branch, add `if (DeviceProfileStore.selectedClass(this) == DeviceClass.MENTRA_LIVE) { captureMentraImageForQuestion(sourceTag, offerSpokenQuestion); return }`. Model it on `captureEyevueImageForQuestion`: run `MentraPhotoCapture(this).capture()` on IO, write it to `cacheDir/Mentra_AI_<ts>.jpg`, then call `onImageReadyForQuestion(path, ImageQuestionSource.FAST_PREVIEW, …)`. Until then an image question with Mentra selected falls through to the HeyCyan path and shows "Glasses are not connected".
2. **Voice question route.** In `captureOptionalImageQuestionFromBluetoothMic`, skip `startBluetoothMicRouteAndAwait` and its `check(ready)` when `AudioRoutePolicy.forDevice(COMMUNICATION_BLUETOOTH_PREFERRED, profile.audio) == DEVICE_SDK`. The recorder already uses the SDK PCM, and opening SCO next to it is what the Mentra docs warn against.
3. **Playback signal.** Around TTS answers, call `MentraLiveManager.instanceOrNull()?.setOwnAudioPlaying(true/false)` when `AudioRoutePolicy.needsPlaybackSignal(profile.audio)`. Gemini Live already does this.
