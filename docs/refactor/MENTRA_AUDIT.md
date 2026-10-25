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
Network: Wi-Fi scan and credentials, saved networks, hotspot on/off. The gallery HTTP server is reachable only over the glasses hotspot.
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
| Gallery download | not wired | The SDK serves files only over the glasses Wi-Fi hotspot (`setHotspotState` plus HTTP `/api/gallery`). Joining that hotspot needs a phone-side Wi-Fi client session that does not fit the current P2P-based network session without new work. |
| Live preview and streaming | not wired | The glasses push RTMP/SRT/WHIP to a URL they can reach over Wi-Fi. CyanBridge's live preview pulls RTSP over Wi-Fi Direct, so this needs an on-phone ingest server (the SDK example uses GStreamer/MediaMTX). |
| OTA | not wired | The SDK has its own OTA flow and the firmware must match the SDK. Mixing it into the HeyCyan OTA UI risks bricking. It needs its own screen and an owner decision. |
| Wi-Fi provisioning, hotspot, LED, head-up, touch/swipe, LC3, local transcription, camera FOV and warm-up | not wired | Nothing in CyanBridge consumes them yet. |

## Calls the frozen MainActivity region must add later

1. **Image question capture.** In `handleGlassesImageButtonPressed`, after the TuneBuds branch, add `if (DeviceProfileStore.selectedClass(this) == DeviceClass.MENTRA_LIVE) { captureMentraImageForQuestion(sourceTag, offerSpokenQuestion); return }`. Model it on `captureEyevueImageForQuestion`: run `MentraPhotoCapture(this).capture()` on IO, write it to `cacheDir/Mentra_AI_<ts>.jpg`, then call `onImageReadyForQuestion(path, ImageQuestionSource.FAST_PREVIEW, …)`. Until then an image question with Mentra selected falls through to the HeyCyan path and shows "Glasses are not connected".
2. **Voice question route.** In `captureOptionalImageQuestionFromBluetoothMic`, skip `startBluetoothMicRouteAndAwait` and its `check(ready)` when `AudioRoutePolicy.forDevice(COMMUNICATION_BLUETOOTH_PREFERRED, profile.audio) == DEVICE_SDK`. The recorder already uses the SDK PCM, and opening SCO next to it is what the Mentra docs warn against.
3. **Playback signal.** Around TTS answers, call `MentraLiveManager.instanceOrNull()?.setOwnAudioPlaying(true/false)` when `AudioRoutePolicy.needsPlaybackSignal(profile.audio)`. Gemini Live already does this.
