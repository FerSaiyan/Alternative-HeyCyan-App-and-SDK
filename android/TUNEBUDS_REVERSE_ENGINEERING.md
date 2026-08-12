# TuneBuds / AB Mate Reverse-Engineering Findings

Date: 2026-08-11

## Source APK

- Package: `com.xyheyo.tunebuds`
- Version: `1.0.9` (`versionCode=109`)
- Installed form: split APK, pulled from a Samsung SM-F956 device
- Preserved reference artifacts: `android/TuneBudsOfficialApp/`
- Clean JADX output: `android/TuneBudsOfficialApp/tunebuds-jadx-clean`
- JADX version: `1.5.6`
- `base.apk` SHA-256: `93414ca56aaf9cc8a9f00a37f7842520f476b769d93b1920e1ff5bc3e6bde330`

The app is Flutter AOT with Android integration in `com.topstep.aibuds`, the AB Mate WearKit adapter in `com.topstep.wearkit.abmate`, and the command protocol in `com.bluetrum.devicemanager`.

The original pulled APKs and the cleaned single-APK input are preserved under `android/TuneBudsOfficialApp/tunebuds-apk/` and `android/TuneBudsOfficialApp/tunebuds-clean-apk/`. The clean decompile used the copied `base.apk` with the unrelated macOS `dump_syms` binary removed. The earlier full JADX output is also preserved under `android/TuneBudsOfficialApp/tunebuds-jadx/`. JADX still reported method-level decompilation failures in third-party code, but the AB Mate transport and file-transfer paths described below decompiled successfully.

## Scope Finding

TuneBuds bundles several device SDKs. The glasses path analyzed here is `WKDeviceType.AB_MATE`, not the separate `SJGlass` / ShenJu stack. AB Mate identifies glasses internally as product type `501`; earphones and speakers are separate product types.

## Discovery And Connection

The AB Mate scanner accepts manufacturer company IDs:

- `0x475A` (`18266`)
- `0x455A` (`17754`)
- `0x535A` (`21338`)
- `0x4D5A` (`19802`)

The Flutter layer does not connect to the scanner's visible address directly. It derives a six-byte address from manufacturer data:

1. Read the data little-endian.
2. Skip one byte and one two-byte value.
3. Read the next six bytes.
4. XOR each byte with `0xAD`.
5. Format the result as a Bluetooth MAC address.

Runtime testing confirmed that the app connects to the derived Classic address. CyanBridge should still retain and log the advertisement address for diagnostics.

The connector ignores the generic WearKit `AuthMode`, auth code, and user ID arguments for AB Mate. It does, however, run an Android bonding operation during connection preparation. `BLUETOOTH_CONNECT` is required on Android 12 and later. The official manifest requests `BLUETOOTH_PRIVILEGED`, but a normal third-party app cannot receive that signature permission and CyanBridge must not depend on it.

## Runtime Transport Correction

Live validation on 2026-08-11 established that this TuneBuds build calls `AbMateSDK.setBLE_CONNECTION(false)`. The connected `xk one Pro` therefore uses bonded Classic Bluetooth RFCOMM/SPP with UUID `00001101-0000-1000-8000-00805f9b34fb` (channel 1), not the BLE GATT path below. The verified Classic address is `FA:00:11:15:A1:7B`; Android bonding succeeded without privileged permissions.

Read-only values observed through the official app were firmware `0.1.0.6`, co-processor `1.0.1.1.4.2607281652.131`, battery `51%`, storage `60 MiB` used / `418 MiB` free, and zero image/video/audio files. CyanBridge now treats the manufacturer-derived address as the RFCOMM target and retains the advertisement address only as scan identity.

## Alternate BLE GATT Path

This path exists in the bundled SDK but is not active for the validated `xk one Pro` configuration.

| Role | UUID |
| --- | --- |
| Main service | `0000fdb3-0000-1000-8000-00805f9b34fb` |
| Command write | `0000ff17-0000-1000-8000-00805f9b34fb` |
| Command notify | `0000ff18-0000-1000-8000-00805f9b34fb` |

The official adapter:

- Discovers the main service and both characteristics.
- Enables `FF18` with normal GATT notifications (`setupNotification`, default mode).
- Writes framed commands to `FF17` with `writeCharacteristic` and waits for each write operation to complete.
- Requests ATT MTU `515`; if negotiation fails, it falls back to a 20-byte ATT batch size.
- Separately queries protocol device-info type `0xFF` for the maximum complete protocol packet size.

The protocol packet size and ATT MTU are different values. The command fragment payload is `protocolMaxPacketSize - 5`; it defaults to 15 bytes before the device-info query completes.

## Command Frame

Every request, response, and notification uses the same five-byte header:

```text
byte 0: frame sequence, low nibble, modulo 16
byte 1: command ID
byte 2: command type (1=request, 2=response, 3=notification)
byte 3: high nibble=(fragment count - 1), low nibble=fragment index
byte 4: payload length for this fragment
byte 5...: fragment payload
```

Outbound sequence numbers increment for every fragment, not every logical command. A zero-payload request is exactly five bytes and still increments the sequence.

Reassembly requires:

- Consecutive frame sequence values modulo 16.
- First fragment index zero.
- Stable command ID, command type, and total fragment count.
- Consecutive fragment indexes.
- Exactly the payload length declared by byte 4.

The decompiled vendor merger contains a suspicious combined `&&` consistency check that would accept some mismatched fragment metadata. CyanBridge should implement the intended strict checks rather than reproduce that apparent bug.

The vendor queues response-bearing requests one at a time, matches responses by command ID, and uses a default 10-second timeout. Command `0x27` is a container whose response payload consists of repeated device-info TLVs (`type`, one-byte length, value).

## Initial Device Information

After the transport is prepared, the app sends command `0x27` with repeated two-byte queries (`infoType`, `0x00`). Before normal connection state is exposed, it queries:

- `0xFF`: maximum complete protocol packet size, one unsigned byte.
- `0xA0`: AI Kit support, one byte. A missing value is treated as supported/default `1`.

The default glasses query also requests battery, firmware, key settings, volume, in-ear/TWS state, model, serial number, camera capabilities, Wi-Fi capability flags, display settings, recording limits, authorization state, and AI support.

Wi-Fi support is a bit mask:

- Bit `0x01`: phone-created local hotspot, Android 8+
- Bit `0x02`: glasses station/hotspot mode, Android 10+
- Bit `0x04`: Wi-Fi Direct P2P

The app defaults the mask to `0x06` when the device does not report it.

## Glasses Commands

All values below are unsigned command IDs.

| ID | Operation | Request payload | Result / notification |
| ---: | --- | --- | --- |
| `0xB3` | Translation mode | One mode byte | Generic response |
| `0xE0` | Camera co-processor type | Empty | `0` none, `1` QZ V821, `2` TX W81x, `3` Realtek |
| `0xE1` | Turn on camera / take photo | One mode byte: `0` recording, `1` AI | Response: `0` success, `1` busy, `2` error, `3` no space, `4` invalid mode; completion notify is `0xF3` |
| `0xE2` | Close camera subsystem | Empty | `0` closed, `1` saving, `2` transferring, `3` updating |
| `0xE3` | AI picture data | Notification only | Parsed as an `AIPicPackage` |
| `0xE4` | Start video recording | Empty | `0` success, `1` busy, `2` init failure, `3` no space |
| `0xE5` | Start audio recording | Empty | Same status values as video |
| `0xE6` | Configure Wi-Fi | TLV-like structure described below | Response `0` accepted; state notifications arrive on `0xEC` |
| `0xE7` | Start file manager | Empty | Response starts transfer; UTF-8 HTTP base address arrives as an `0xE7` notification |
| `0xE8` | Start RTSP camera stream | Three quality bytes | Response starts stream; UTF-8 RTSP URL arrives as an `0xE8` notification |
| `0xE9` | Camera co-processor version | Empty | UTF-8 version notification on `0xE9` |
| `0xEA` | Storage information | Empty | Notification is two little-endian u32 values: used MiB, free MiB |
| `0xEB` | Format storage or delete file | Empty formats; UTF-8 filename deletes | Integer response/notification |
| `0xEC` | Wi-Fi state | Notification only | `1` device hotspot open, `2` P2P open; `0`, `4`, and `5` are treated as failures |
| `0xED` | Start camera OTA | Empty prepares OTA; UTF-8 URL tells glasses where to fetch | `0` accepted; `1` format, `2` space, `3` battery, `4` connection, `5` state conflict |
| `0xEE` | Camera OTA progress | Notification only | Two bytes: state and progress/error |
| `0xEF` | Media counts | Empty | Notification is three little-endian u32 values: images, videos, audio |
| `0xF0` | Set time | Unix seconds u32 LE, UTC offset minutes s16 LE | Integer response |
| `0xF2` | P2P peer address | Notification only | Six raw bytes converted to a colon-separated MAC address |
| `0xF4` | Retry authorization | One mode byte; app uses `1` | Integer response |
| `0xF6` | Work state | Empty | Integer response/notification |
| `0xF7` | Glass configuration | Standard TLV (`key`, `length`, `value`) | TLV response |
| `0xF8` | Teleprompter data | Standard TLV | TLV response |
| `0xFB` | Teleprompter control | Standard TLV | TLV response |

`0xF7` configuration keys observed in the app:

- `0x8C`: brightness, one byte
- `0x8E`: volume type and volume, two bytes
- `0x93`: resolution, one byte
- `0x9A`: screen switch, one byte
- `0x82`: video limit duration, one byte
- `0x83`: audio limit duration, one byte
- `0x8B`: AI service authorization, app sends `01 01`

Standard TLVs are `key`, unsigned one-byte length, then value. Teleprompter keys include length `0x01`, UTF-8/content packets `0x02`, query speed `0x09` (`09 00`), set speed `0x0A`, start `0x02`, and stop `0x03`.

### Wi-Fi Configuration Payload

Command `0xE6` encodes:

```text
01 01 <mode>
02 <ssidLength> <ssid UTF-8>
03 <passwordLength> <password UTF-8>
04 01 <channel>
```

The current WearKit modes are `0` phone local hotspot, `1` glasses station/hotspot, and `2` Wi-Fi Direct P2P. These supersede older constants still present in one low-level request class.

### RTSP Quality Payload

Command `0xE8` sends `<bitrateTier> <qualityFactor> <iFrameInterval>`. A quality outside `0..100`, including the default `-1`, sends `00 00 00`. Otherwise the ranges are:

| Quality | Payload |
| --- | --- |
| `0..24` | `01 1E 3C` |
| `25..49` | `02 14 1E` |
| `50..74` | `04 0A 0F` |
| `75..99` | `06 05 0A` |
| `100` | `08 00 05` |

The first field is named `bitrate` by the app but its exact physical unit is not established.

## Wi-Fi Transfer State Machine

The app supports three connection topologies and retries across them. File transfer prefers P2P, then station, then a phone-created local hotspot. RTSP prefers the local hotspot, then P2P, then station.

### P2P

1. Generate SSID `AiGlassDb_<random 0..9999>`.
2. Send Wi-Fi mode `2`, that SSID, empty password, channel zero with `0xE6`.
3. Start file (`0xE7`), RTSP (`0xE8`), or OTA preparation (`0xED`).
4. Wait for Wi-Fi state `2` and the six-byte peer MAC notification `0xF2`.
5. Discover peers and match either the reported peer MAC or generated SSID.
6. Connect with WPS PBC. On Android 10+, request phone group-owner intent `15`.
7. Combine Android's group-owner IP with the endpoint address delivered over BLE.

For this protocol, the endpoint address is authoritative for HTTP/RTSP. The Android group-owner address is retained separately because OTA runs an HTTP server on the phone and tells the glasses to fetch from that owner IP.

### Glasses Station / Hotspot

The app derives SSID `AiGlass<12 uppercase MAC hex digits>` and uses fixed password `12345678`. It sends mode `1`, waits for Wi-Fi state `1`, connects the phone to that network, then combines the local owner IP with the BLE-delivered endpoint.

### Phone Local Hotspot

The phone creates a local-only hotspot, sends its SSID/password to the glasses with mode `0`, starts the requested mode, and waits for the endpoint URL. This path is attempted first for RTSP.

## Media HTTP

The glasses send the full media base address as a UTF-8 notification on command `0xE7`. The app ensures a trailing slash and requests:

```text
<base-address>/media.config
<base-address>/<filename>
```

The base address therefore supplies the scheme, host, optional port, and any path prefix. Static analysis must not replace it with a hard-coded `/files/` path. If a URL has no explicit port, the legacy transfer client defaults to port `8080`.

`media.config` can be:

- A JSON array of filenames.
- A JSON object with a `files` array.
- One filename per non-empty line.

The legacy transfer implementation also strips accidental HTTP status/header lines before parsing. `.opus` files are saved under an `Audio` subdirectory. A filename beginning `video-` without an extension is assigned `.mp4`.

The P2P HTTP client must bind its sockets to the Android network whose interface starts with `p2p`. The app sends `Connection: close` and `User-Agent: TSClient/1.0`. CyanBridge already allows cleartext HTTP and has the required Wi-Fi/network permissions.

After each successful download, the official file ability sends command `0xEB` with the original device filename to delete it from the glasses. This destructive behavior should be opt-in in CyanBridge, not automatic.

## RTSP

The glasses send the complete RTSP URL as a UTF-8 notification on command `0xE8`; the app does not synthesize a fixed path. The sequence is:

1. Establish one of the Wi-Fi topologies.
2. Send `0xE8` with a quality payload.
3. Wait for response `0`.
4. Wait for the `0xE8` URL notification.
5. Play the URL with VLC over RTSP/TCP, using roughly 200-300 ms network/live caching.
6. On completion or failure, send `0xE2` and tear down the Wi-Fi session.

## Camera OTA

Camera/co-processor OTA is pull-based over Wi-Fi:

1. Establish Wi-Fi in OTA mode; `0xED` with an empty payload prepares the subsystem.
2. Start an HTTP server on the phone's Wi-Fi owner IP, trying ports `8080..8100`.
3. Serve the firmware as `GET /firmware.swu` with `application/octet-stream`.
4. Send `0xED` again with `http://<phone-owner-ip>:<port>/firmware.swu` as UTF-8.
5. Observe `0xEE` notifications.

OTA states are `0` none, `1` downloading, `2` installing, `3` success, and `255` failure. Failure codes are `0` network, `1` file not found, `2` file error, `3` Wi-Fi disconnected, `4` upgrade timeout, `5` invalid Wi-Fi, `6` aborted, and `7` Wi-Fi timeout.

This camera OTA path is distinct from the bundled earphone BLE OTA service (`9966` / `FFB1` / `FFB2`) and should not be mixed into the initial glasses implementation.

## CyanBridge Integration Plan

Implement the smallest isolated vertical slice first:

1. Add a TuneBuds/AB Mate device class and classify by the four manufacturer company IDs. Use service UUID `FDB3` only as a secondary signal because it may not be advertised.
2. Add `devices/tunebuds/TuneBudsProtocol.kt` containing pure frame encoding, strict stream decoding, TLV helpers, command builders, and response models.
3. Add unit tests for frame fragmentation/reassembly, modulo-16 sequencing, malformed lengths/metadata, time encoding, Wi-Fi TLV encoding, media/storage parsing, and RTSP quality tiers.
4. Add `TuneBudsSppClient.kt` for bonding, serialized RFCOMM writes, stream decoding, and the initial `0x27` max-packet query. Keep GATT as a future transport option only if another model is observed with `BLE_CONNECTION=true`.
5. Add `TuneBudsManager.kt` for connection state, command/response correlation, battery/device info, camera controls, media count, and storage state.
6. Reuse CyanBridge's existing P2P and network-binding infrastructure, but add a TuneBuds-specific coordinator because its BLE state machine and endpoint notifications differ from HeyCyan and Eyevue.
7. Media sync is implemented behind the TuneBuds profile, but its local-hotspot endpoint flow still requires hardware validation. RTSP remains intentionally unexposed for this camera-only product.
8. Keep OTA disabled until normal media/RTSP cleanup is proven on hardware.

Do not route TuneBuds through the HeyCyan vendor AAR or Eyevue protocol. The UUIDs, framing, commands, and Wi-Fi signaling are independent.

## CyanBridge Runtime Validation

On 2026-08-11, a clean CyanBridge process selected the persisted TuneBuds profile and established one RFCOMM channel-1 connection to `FA:00:11:15:A1:7B`. The startup device-info sequence decoded model `E1749`, firmware `0.1.0.6`, co-processor `1.0.1.1.4.2607281652.131`, battery `51%`, and storage `60 MiB` used / `418 MiB` free. These values match the official app observations.

The read-only media-count, battery, and version controls completed without transport errors or reconnecting the socket. CyanBridge now suppresses duplicate startup connection attempts for the same address, does not issue dashboard refresh requests before RFCOMM is connected, and hides the generic RTSP lab probe for TuneBuds. The official TuneBuds app was force-stopped during this validation.

Live recording tests showed that camera-close status `1` is the documented `REP_BUSY_SAVING` state. The glasses play the stop cue immediately and later return status `0` (`REP_CLOSED`) once saving finishes. CyanBridge retries this transient state instead of reporting it as a failed stop.

The remaining exposed controls were then exercised on hardware. One photo and two approximately five-second video/audio recordings increased the media counts to `1` image, `2` videos, and `2` audio files. Time sync completed without a protocol error. The resulting storage reading was `82 MiB` used / `396 MiB` free.

Non-destructive media sync also completed end-to-end. The Samsung phone created local-hotspot interface `swlan0` at `10.71.31.72`; the glasses joined as `10.71.31.247`, CyanBridge downloaded all five files through the device-reported HTTP base URL, imported them into `DCIM/CyanBridge`, sent camera-subsystem cleanup, and stopped the hotspot. A post-sync count still reported `1` image, `2` videos, and `2` audio files, confirming that CyanBridge did not delete the originals. Imported containers were verified as baseline JPEG (`1600x1200`), ISO MP4 v2, and standard Ogg/Opus (mono, 16 kHz).

The capability probe is now implemented as a read-only extension of the startup and manual refresh sequence. CyanBridge requests the official AB Mate `0x27` fields for device ability, detection, audio support, display configuration, resolution, volume support/current volume, recording limits, Opus, AI chat, and app-list support, then requests camera co-processor type with `0xE0`. Returned values are retained internally for capability decisions but are not rendered in the TuneBuds device-info panel. This does not enable display, teleprompter, RTSP, brightness, volume writes, or recording-limit writes; those remain unexposed until the `E1749` responses are captured and interpreted on hardware.

## Runtime Validation Still Required

- Capture and retain the real advertisement company ID/manufacturer bytes alongside the verified derived address.
- Capture the reported protocol packet size over SPP.
- Record raw request/response/notification frames for initial device info and one safe command such as storage/media count.
- Record the exact raw `0xE7` endpoint notification string; the successful local-hotspot HTTP host was `10.71.31.247`.
- Record `0xE8` RTSP endpoint strings only if a future product requirement enables streaming; RTSP is not exposed in CyanBridge for this model.
- Verify P2P role selection/routing separately if a future model cannot use the now-confirmed local-hotspot path.
- Do not test format (`0xEB` empty), delete (`0xEB` filename), or OTA (`0xED`) until read-only operations are stable.

## Decompiled Source References

- `com/topstep/wearkit/abmate/internal/InternalScanner.java`
- `com/topstep/wearkit/abmate/internal/InternalConnector.java`
- `com/topstep/wearkit/abmate/internal/InternalConnector$handlerCommManager$1.java`
- `com/topstep/wearkit/abmate/internal/builtin/MaxPacketSizeOperation.java`
- `com/topstep/wearkit/abmate/internal/builtin/wifi/AbMateWifiConnector.java`
- `com/topstep/wearkit/abmate/internal/builtin/wifi/AbMateWifiP2pConnector.java`
- `com/topstep/wearkit/abmate/internal/builtin/wifi/AbMateWifiStationConnector.java`
- `com/topstep/wearkit/abmate/internal/builtin/wifi/AbMateWifiLocalHotspotConnector.java`
- `com/topstep/wearkit/abmate/internal/builtin/wifi/AbMateMediaFilePuller.java`
- `com/topstep/wearkit/abmate/internal/builtin/wifi/AbMateQzOtaDownloading.java`
- `com/topstep/wearkit/abmate/internal/ability/file/InternalFileAbility.java`
- `com/topstep/aibuds/earphone/ABMateManager.java`
- `com/topstep/aibuds/earphone/DefaultDeviceCommManager.java`
- `com/topstep/aibuds/earphone/P2pConnector.java`
- `com/topstep/aibuds/earphone/MediaDownloadManager.java`
- `com/topstep/aibuds/earphone/HttpTransManagerV2.java`
- `com/bluetrum/devicemanager/RequestHandler.java`
- `com/bluetrum/devicemanager/ResponseHandler.java`
- `com/bluetrum/devicemanager/ResponseMerger.java`
- `com/bluetrum/devicemanager/cmd/Command.java`
- `com/bluetrum/devicemanager/cmd/request/`
- `com/bluetrum/devicemanager/cmd/payloadhandler/`
