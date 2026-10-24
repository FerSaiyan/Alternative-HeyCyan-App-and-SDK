# Live Preview Integration

**Date:** 2026-10-22
**Author:** CyanBridge App Team
**Status:** Integrated; physical-device verification remains pending

---

## 1. Background

Analyzed V821 firmware images include a dedicated live streaming application (`ai_glass_livestream`), and the mode dispatcher script (`/etc/media/rtc_init.sh`) maps mode 8 to that binary. Static analysis indicates that it is built on the live555 RTSP/RTP stack and is intended to stream encoded camera video over Wi-Fi; runtime behavior remains unverified.

The HeyCyan live-preview action sends the requested BLE start command, joins the glasses' Wi-Fi Direct group with the phone preferred as group owner, and consumes the RTSP stream. Hardware behavior still needs validation against supported glasses and firmware.

---

## 2. Firmware Findings

### 2.1 Mode Dispatcher

The script `/etc/media/rtc_init.sh` reads `/sys/kernel/aglink_mode` and launches the corresponding application:

| Mode | Application | Description |
|---|---|---|
| 0 | `ai_glass_photo` | Photo capture |
| 1 | `ai_glass_video` | Video recording |
| 2 | `ai_glass_download` | Media transfer (HTTP server) |
| 3 | `ai_glass_ota` | OTA firmware update |
| 4 | *(no app)* | AI-only mode |
| 5, 7 | `ai_glass_normal` | Normal/standby |
| 6 | `ai_glass_audio` | Audio recording |
| **8** | **`ai_glass_livestream`** | **Live streaming (RTSP)** |

Mode 8 explicitly loads the WLAN kernel module before launching the livestream binary, confirming it is designed for network streaming.

### 2.2 `ai_glass_livestream` Binary Analysis

The binary was found in the analyzed WIFIAM01G1, WIFIAM01C, WIFIAM01W, and WIFIA02E02 images. Key findings from static analysis:

- **Protocol:** Contains live555 RTSP/RTP server components
- **Port candidate:** Static analysis found RISC-V `ADDI` instructions materializing **554** (the standard RTSP port) at two locations. No CLI flag, config file, or environment variable changing it was identified.
- **Stream-name candidate:** `testH264VideoStreamer` appears as a live555 session name. The first URL to test is `rtsp://<ip>:554/testH264VideoStreamer`.
- **Codec:** CLI strings indicate H.264, JPEG, and H.265 selections via `--encode_format0` / `--encode_format1` (0=H.264, 1=JPEG, 2=H.265)
- **Transport:** Includes live555 code for RTP over UDP and RTP over TCP; enabled runtime transports are unverified
- **Capture stack:** References the Allwinner CedarX `AWVideoInput` API, consistent with a camera-to-encoder-to-RTSP pipeline
- **WiFi:** Links against `libwifimg-v2.0.so` and `libaglink.so` for P2P/AP management
- **Log string:** The binary contains a log format for `rtsp://<ip>:554/testH264VideoStreamer`; only a device test can confirm that startup reaches it and serves the stream

The binary accepts video encoder configuration arguments (`--bitrate`, `--srcsize`, `--dstfps`, `--rotate`, etc.) but has no RTSP-specific options — the port and stream name are compile-time constants.

These are historical static-analysis candidates. The active integration uses the requested runtime endpoint `rtsp://<glasses-ip>:8554/ch0`; physical-device verification is still required.

### 2.3 Mode String Table

The `libaglink.so` library contains a mode-to-string table used by `aglink_mode_to_string()`. The strings in order are:

```
Unknown, PHTOTO, DOWNLAOD, OTA, AI, NORMAL, AG_MODE_RECORD_AUDIO, IDLE, LIVESTREAM, VIDEO
```

`LIVESTREAM` appears at index 8 in this table, confirming it corresponds to `aglink_mode = 8`.

---

## 3. BLE Command Rationale

### 3.1 Command Pattern

Existing media control buttons use this pattern via `LargeDataHandler.glassesControl()`; the livestream payloads below include an additional trailing `0x01`:

```
Bytes: [0x02, 0x01, CMD]
       ^       ^     ^
       |       |     └─ Command/action byte
       |       └─────── Sub-command type
       └─────────────── Glasses control command
```

### 3.2 Known Command Mapping

| CMD byte | Function | System mode |
|---|---|---|
| `0x01` | Photo capture | Mode 0 |
| `0x02` | Video start | Mode 1 |
| `0x03` | Video stop | — |
| `0x04` | Transfer mode (P2P sync) | Mode 2 |
| `0x05` | OTA mode | Mode 3 |
| `0x06` | AI photo (with preview) | Mode 4 |
| `0x08` | Audio recording start | Mode 6 |
| `0x09` | Exit transfer mode | — |
| `0x0b` | Stop AI audio stream | — |
| `0x0c` | Audio recording stop | — |
| `0x0f` | P2P reset | — |
| `0x14` | Live stream start (`02 01 14 01`) | Mode 8 |
| `0x15` | Live stream stop (`02 01 15 01`) | — |

`workTypeIng` describes a work state for command types supported by the SDK parser. It must not be treated as a universal command echo or as proof that an unsupported command entered a particular firmware mode.

### 3.3 Reserved Commands and SDK Parsing

The live-preview flow uses the requested `0x14`/`0x15` command payloads. Other numeric gaps are not safe candidates:

- `0x09` is confirmed in the official app as exit-transfer after media download.
- `0x0a` is confirmed in the official app as factory reset.
- `0x0e` is confirmed in the official app as device restart.
- `0x0d` remains unknown and must not be sent without firmware-side confirmation.

The shipped SDK's `GlassModelControlResponse` parser only populates `errorCode` and `workTypeIng` for an allowlist that excludes command types `0x09`, `0x0a`, `0x0d`, and `0x0e`. A command can therefore execute while the parsed response retains its default error value. Parser-driven fallback retries are unsafe and are prohibited in the Live Preview flow.

The requested start and stop commands are sent by `LivePreviewManager`. The SDK response parser may not fully interpret this command, so its parsed response is logged for diagnostics and is not treated as proof that the glasses entered or exited livestream mode.

---

## 4. Implementation

### 4.1 Flow

The live-preview flow is:

1. Register the BLE notification listener, then send start command `02 01 14 01`.
2. Discover the glasses and connect with `groupOwnerIntent = 15`.
3. Read the glasses' IPv4 address from BLE notification type `0x08` (45s timeout).
4. Bind stream sockets to the P2P route and connect to `rtsp://<ip>:8554/ch0`.
5. Decode H.264 to JPEG and serve the latest frame at `http://127.0.0.1:8080/mjpeg`. The MJPEG server listens on loopback only; the preview dialog consumes this local endpoint instead of opening a second RTSP session.

The BLE layer sends `syncHeartBeat(4)` every five seconds while livestream mode is active. Stopping closes the RTSP decoder and MJPEG server, cancels the heartbeat, sends stop command `02 01 15 01` while BLE is connected, restores Android's default network, and attempts to tear down the P2P group. If Android does not confirm group removal, the implementation retains the preview lease rather than claiming cleanup succeeded.

### 4.2 Probe Strategy

The stream endpoint is `rtsp://<glasses-ip>:8554/ch0`. RTSP uses interleaved RTP over TCP; the client sends periodic RTSP options requests and RTCP receiver reports. The local consumer endpoint is `http://127.0.0.1:8080/mjpeg`.

### 4.3 Dependencies

The RTSP decoder uses Android `MediaCodec`; no new library dependency is required. Existing Media3 dependencies remain in use by other preview integrations.

### 4.4 Logging

All livestream activity is logged under the `LivePreview` tag. To monitor during testing:

```bash
adb logcat -s LivePreview
```

Every step logs elapsed time, P2P events, BLE notification raw bytes, command responses, RTSP state, and stream failures.

---

## 5. Open Questions

The following items are unclear and will need to be resolved during field testing. Doubts may be forwarded to the CyanBridge engineering team for clarification:

1. Confirm that the requested start/stop command payloads are accepted by each supported hardware/firmware combination.
2. Verify that the phone's group-owner preference produces the expected topology and that BLE event `0x08` reports the glasses' reachable address.
3. Validate the H.264 packetization, decoder output, and RTCP keepalive behavior on physical glasses.
4. Verify that stop command and P2P teardown return the hardware to its expected idle state after user stop, Bluetooth loss, and app interruption.

---

## 6. Test Procedure

### Prerequisites
- Glasses connected to phone via BLE (standard CyanBridge app connection)
- A supported HeyCyan glasses device and its paired BLE connection

### Steps
1. Ensure the official HeyCyan app is not connected to the glasses.
2. Open CyanBridge and navigate to the Glasses dashboard.
3. Run `adb logcat -s LivePreview WifiP2pManagerSingleton` and start Live Preview.
4. Verify the log shows the start command, phone `groupOwnerIntent=15`, BLE IP notification, and connection to port `8554` at `/ch0`.
5. Confirm the dialog displays video and optionally fetch `http://127.0.0.1:8080/mjpeg` from another local app on the handset.
6. Stop preview and verify the stop command, RTSP close, local server shutdown, and P2P teardown.

### Expected Outcomes

| Outcome | Likely cause | Next step |
|---|---|---|
| Stream plays successfully | BLE command, P2P, RTSP, and decoder path work | Record hardware and firmware versions |
| RTSP connection fails after IP received | Mode activation, route, endpoint, or decoder negotiation may have failed | Check `LivePreview` logs and verify port `8554`/path `/ch0` on the device |
| "No IP" timeout | Start command did not produce a BLE IP notification or P2P setup stalled | Check BLE response and peer discovery logs |
| MJPEG endpoint does not serve frames | Decoder failed or local server could not bind port `8080` | Check RTSP errors and local port availability |
| P2P discovery finds no peers | Glasses not advertising P2P | Verify BLE is connected; try toggling Wi-Fi on phone |

---

## 7. Files

| File | Purpose |
|---|---|
| `app/src/main/java/com/fersaiyan/cyanbridge/ota/LivePreviewManager.kt` | BLE commands, P2P/IP discovery, RTSP and MJPEG lifecycle |
| `app/src/main/java/com/fersaiyan/cyanbridge/RtspFrames.java` | RTSP/RTP TCP receiver, H.264 decoder, JPEG frame producer |
| `app/src/main/java/com/fersaiyan/cyanbridge/MjpegServer.java` | Loopback-only latest-frame MJPEG endpoint |
| `shared/src/commonMain/.../GlassesDashboardPresentation.kt` | `LivePreviewUiState`, `StartLivePreview`/`StopLivePreview` actions |
| `shared/src/commonMain/.../GlassesDashboardScreen.kt` | "Live preview" UI section in HeyCyanControls |
| `app/src/main/java/com/fersaiyan/cyanbridge/MainActivity.kt` | Dashboard action wiring and local MJPEG dialog |
