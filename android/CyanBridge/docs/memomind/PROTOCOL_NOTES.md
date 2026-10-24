# MemoMind BLE / RFCOMM Protocol Notes

## Device Tested
- **Device model:** MemoMind One 209 (H001Y)
- **Firmware version:** 1.0.0.267
- **Tested by:** Frida on Samsung SM-F956B (rooted)
- **Date:** 2026-06-09

## Transport Architecture

**Critical finding:** The primary command/control path is **Classic Bluetooth RFCOMM (SPP)**, not BLE GATT.

The app opens **three simultaneous RFCOMM sockets** on connection:

| Socket | UUID | Purpose |
|--------|------|---------|
| Primary | `00001101-0000-1000-8000-00805f9b34fb` (0x1101) | Main command/control channel |
| Extra | `00002026-0000-1000-8000-00805f9b34fb` (0x2026) | Secondary data channel |
| Record | `00002024-0000-1000-8000-00805f9b34fb` (0x2024) | Record/audio channel |

All confirmed via Frida hook on `BluetoothDevice.createRfcommSocketToServiceRecord()`.

The BLE GATT UUIDs (0x2001, 0x2002, 0x2020-0x2026, 0x7033) from the original reverse-engineering are valid but likely serve **secondary roles** (OTA, low-power background mode). The main control path is RFCOMM.

## Wire Protocol Format

### Control frame format (observed on primary socket)

All writes and most reads follow this format:

```
fa 00 00 <len:uint16 BE> <seq:uint8> <group:uint8> <opcode:uint8> <type:uint8> [payload...] [crc:uint16 BE?]
```

| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| 0 | 3 | `fa 00 00` | Frame header prefix (constant) |
| 3-4 | 2 | `len` | Total frame length including this field (uint16 BE) |
| 5 | 1 | `seq` | Sequence number (per-action counter) |
| 6 | 1 | `group` | Command group / command family |
| 7 | 1 | `opcode` | Specific command within the group |
| 8 | 1 | `type` | Message type (see below) |
| 9.. | N | `payload` | Command-specific payload |
| end-2 | 2 | `crc` | Optional checksum (uint16 BE, seen in most frames) |

### Message types (observed)

| Value | Meaning |
|-------|---------|
| 0x01 | Request (no payload) |
| 0x02 | Response (with payload) |
| 0x05 | Subscription/list config |
| 0x06 | Success ACK |
| 0x07 | Push/write with payload |
| 0x08 | Push/write with payload (variant) |

### Binary stream format (audio/mic data from glasses)

Inbound binary data from the glasses microphone uses a different format:

```
52 91/92 00 00 <frame_id:uint32 LE> <...binary audio data...>
```

- Starts with `52 91` or `52 92`
- Contains 4-byte frame ID (little-endian)
- Typically 404 or 808 byte payload chunks
- Used for recorder/microphone audio stream

### Toggle markers

Simple 3-byte control markers precede some commands:

```
52 01 00  — start / enable toggle
52 00 00  — stop / pause toggle
52 11 00 00 <xx xx> — secondary status marker (seen after some commands)
```

## Confirmed Commands (from Frida Live Capture)

### Connection / Handshake (automatic)

| Timestamp | Group | Opcode | Type | Payload | Description |
|-----------|-------|--------|------|---------|-------------|
| +0.7s | 0x01 | 0x03 | 0x08 | varint list | App version info / capabilities |
| +0.8s | 0x02 | 0x07 | 0x08 | `[],[2,9]` | Capability subscription |
| +0.9s | 0x02 | 0x08 | 0x08 | JSON schedule | Sync schedule/calendar cards |
| +0.95s | 0x01 | 0x02 | 0x01 | (empty) | Device info request |
| +1.0s | 0x01 | 0x02 | 0x02 | device JSON | Device info response |
| +1.7s | 0x01 | 0x06 | 0x01 | (empty) | Battery/settings request |
| +1.8s | 0x01 | 0x06 | 0x02 | settings JSON | Battery/settings response |

### Group 0x04 — Teleprompter

| Sub-action | Group | Opcode | Preceded by | Payload / Notes |
|------------|-------|--------|-------------|-----------------|
| Config | 0x04 | 0x01 | — | `[464, 350, 1, 3, 1, 3, 1]` — viewport/layout |
| Push text | 0x04 | 0x05 | — | Text content, up to ~180 bytes |
| Params | 0x04 | 0x04 | — | `[6, 80]` — likely speed/size settings |
| Start | 0x04 | 0x0b | `52 01 00` | Begin teleprompter playback |
| Pause | 0x04 | 0x0a | `52 00 00` | Pause teleprompter |
| Stop | 0x04 | 0x02 | — | Stop teleprompter (may also clear) |

### Group 0x05 — Notifications

| Sub-action | Group | Opcode | Type | Payload | Notes |
|------------|-------|--------|------|---------|-------|
| Push notification | 0x05 | 0x01 | 0x08 | JSON | App/media notification metadata |

Notification JSON fields:
```json
{"id":521,"a":"YouTube ReVanced","type":0,"ts":1781015296,
 "c":"GOT good old tech",
 "ti":"The New Motorola Fold Makes The Z Fold 7 Look old!",
 "pkg_name":"app.revanced.android.youtube"}
```

| Field | Meaning |
|-------|---------|
| `id` | Incremental notification ID |
| `a` | App display name |
| `type` | Notification type (0 = standard) |
| `ts` | Unix timestamp (seconds) |
| `c` | Notification body / content text |
| `ti` | Notification title |
| `pkg_name` | Source app package name |

### Group 0x02 — High-Level Cards / Components

These payloads are higher-level UI components, not raw draw primitives. The phone sends structured JSON and the glasses appear to render the card internally.

| Component | Group | Opcode | Type | Inner marker | Payload shape | Notes |
|-----------|-------|--------|------|--------------|---------------|-------|
| Stock card | 0x02 | 0x08 | 0x08 | `0x04 0x05` | `[ { c, n, chg, h, l, p, pc, pct, pts[] }, ... ]` | Confirmed with `GOOGL` and `TSLA` in the same payload |
| News card | 0x02 | 0x08 | 0x08 | `0x06 0x05` | `[ { c, id, src, t }, ... ]` | Headline list |
| Schedule / to-do card | 0x02 | 0x08 | 0x08 | `0x09 0x05` | `[ { id, ti, ts, c, do }, ... ]` | Mixed reminders and to-do items |
| Calendar single-entry card | 0x02 | 0x08 | 0x08 | `0x0a 0x05` | `[ { id, ti, ts, c } ]` | Focused event card |

Examples observed:

```json
[{"c":"GOOGL","n":"GOOGL","chg":"+1.770","p":"365.080","pct":"+0.49%","pts":[...]}]
```

```json
[{"id":3,"ti":"Calendar entry test 1","ts":"15:55","c":""}]
```

```json
[{"id":5,"ti":"pet to do","ts":"8 de jun. All Day","c":"pet to do","do":0}]
```

Notes:
- Large stock payloads can be split across multiple writes after the initial `fa 00 ...` frame.
- The `pts[]` series strongly suggests the glasses render charts from structured data rather than from explicit `DrawPath` commands sent by the phone.
- Several of these cards are pushed immediately after inbound glasses requests such as `fa 00 00 0e <seq> 02 0c 08 ...`, likely triggered by a side button or head/look-up gesture.

### Music / Lyrics Behavior (current understanding)

Music metadata still arrives through the normal notification path:

```json
{"id":570,"a":"Spotify","type":0,"ts":1781018474,
 "c":"ADONA","ti":"Here Come The Monsters",
 "pkg_name":"com.spotify.music"}
```

Observed behavior:
- Song changes only produced **Group 0x05 / Opcode 0x01** notification-style JSON payloads with track metadata.
- No plain lyric lines, timestamps, or progress values were observed in text/JSON form.
- Entering the lyric/full-screen music surface coincided with large inbound binary bursts of `52 91 ...` frames, similar in shape to recorder binary traffic but not obviously audio.
- Those binary bursts are the best current candidate for the special lyric/full-screen rendering path.

Current hypothesis:
- Normal now-playing updates use the notification card path.
- The full-screen lyric mode is a separate glasses-driven rendering mode that requests or streams opaque binary content, rather than receiving lyric text as ordinary JSON.
- The side button and look-up gesture likely trigger the same high-level music surface through different upstream request timings; both eventually fell back to the same Spotify metadata push plus opaque `52 91 ...` binary bursts.

### Group 0x03 — Side-Button Utility Menu (partial)

The double-tap side-button utility menu does not expose readable option labels in plain JSON, but one capture did show a distinct `group 0x03` route that is separate from cards, notifications, teleprompter, and recorder.

Observed sequence from the menu-focused session:

| Group | Opcode | Type | Payload | Interpretation |
|-------|--------|------|---------|----------------|
| 0x03 | 0x01 | 0x02 | `EN > EN` | Translation option / source-target language pair |
| 0x03 | 0x0c | 0x08 | empty | Utility action trigger (likely recorder/translate handoff) |
| 0x03 | 0x0a | 0x01 | empty, preceded by `52 01 00` | Recorder-mode request |
| 0x03 | 0x0b | 0x08 | empty | Recorder-mode follow-up / activation |
| 0x03 | 0x0d | 0x08 | empty | Additional recorder-mode state/control step |

Important constraints:
- The menu itself still appears to render through the opaque binary/full-screen path, not as readable text labels.
- `play/pause` can trigger ordinary music notifications, so menu captures may contain both utility-menu traffic and normal Spotify metadata pushes.
- The translation option is the only menu item we have seen with a readable payload so far.

### Group 0x0c — Recorder / Voice / ASR

| Sub-action | Group | Opcode | Preceded by | Description |
|------------|-------|--------|-------------|-------------|
| Start session | 0x0c | 0x01 | `52 01 00` | Enter record mode |
| Begin capture | 0x0c | 0x07 | — | Start actual recording |
| Pause | 0x0c | 0x03 | `52 00 00` | Pause recording |
| Stop | 0x0c | 0x02 | — | Stop recording |
| ASR partial | 0x0c | 0x24 | — | Partial ASR transcription (phone→glasses) |
| ASR final | 0x0c | 0x25 | — | Final ASR transcription |
| Assistant reply | 0x0c | 0x26 | — | Streaming assistant response text |

### Group 0x01 — Device Info, Battery & Settings

| Sub-action | Group | Opcode | Type | Payload | Description |
|------------|-------|--------|------|---------|-------------|
| Device info request | 0x01 | 0x02 | 0x01 | — | Request device info |
| Device info response | 0x01 | 0x02 | 0x02 | JSON | model, sn, mac, name, ver, cver, hver, fver |
| Battery/config request | 0x01 | 0x06 | 0x01 | — | Request battery and settings |
| Battery/config response | 0x01 | 0x06 | 0x02 | JSON | Full settings map + battery |
| Set auto-brightness | 0x01 | 0x0f | 0x08 | `0x00`=auto, `0x01`=manual | Toggle auto-brightness on/off |
| Set manual brightness | 0x01 | 0x0b | 0x08 | `0x00`–`0x0a` (0–10) | Set brightness level |
| Set lookup wake angle | 0x01 | 0x0c | 0x08 | `0x00`–`0x3c` (0–60) | Wake-screen angle threshold |
| Set font size (likely) | 0x01 | 0x0e | 0x08 | `0x01`–`0x06` | Font/display size |

> **Brightness/auto/angle confirmed** from Frida capture `memomind_bt_frida_20260609T191753Z_brightness_angle.log`.
> Values in the settings snapshot response (`"brt":10,"autobrt":0,"angle":5`) confirmed against observed write payloads.

Device info JSON fields:
```json
{"pver":2,"model":"H001Y","sn":"HBFSF5MS0005","mac":"4C:3C:8F:66:1E:98",
 "name":"MemoMind One 209","ver":"1.0.0.267","cver":"1.1.9","hver":"2.1.2","fver":"1.3"}
```

Battery/settings JSON fields:
```json
{"c_bat":0,"c_charge":0,"c_status":1,"c_g_status":0,"lang":10,"font":20,
 "pos":2,"dist":0,"angle":5,"brt":5,"autobrt":1,"wear_det":1,"charging":0,
 "light_ut":15,"light_dt":1,"nod_shark":1,"dnd":0,"ktone":1,"ndet":0,
 "ndtime":30,"bat":21,"noti_read":0,"eq_mode":1,"auto_vol":0,"psm":0,
 "sdm":0,"tf":1,"tmp_unit":0,"noise_mode":0,"long_record":1,"kws_wakeup":1,
 "float_lv":2,"hd_screenon":1,"hd_feature":"[1,4,3]","call_display":1,
 "media_full":1,"led_light":2}
```

| Field | Meaning |
|-------|---------|
| `bat` | Glasses battery % |
| `charging` | Glasses charging status (0/1) |
| `c_bat` | Case battery % |
| `c_charge` | Case charging status |
| `c_status` | Case status code |
| `c_g_status` | Glass status code |
| `brt` | Brightness level |
| `lang` | Language setting |
| `font` | Font size setting |
| `angle` | Display angle |
| `pver` | Protocol version |

## Implementation Status

### Confirmed via Frida (for adapter rewrite)
- ✅ **Transport**: RFCOMM SPP on 0x1101 (primary), 0x2026, 0x2024
- ✅ **Frame format**: `fa 00 00 <len> <seq> <group> <opcode> <type> [payload] [crc]`
- ✅ **Device info**: Group 0x01, Opcode 0x02 (req/resp)
- ✅ **Battery/config**: Group 0x01, Opcode 0x06 (req/resp)
- ✅ **Teleprompter**: Group 0x04 (config, text, start/pause/stop)
- ✅ **Notifications**: Group 0x05, Opcode 0x01 (JSON payload)
- ✅ **High-level cards**: Group 0x02, Opcode 0x08 (stock/news/schedule/calendar)
- ✅ **Utility menu route**: Group 0x03 (partial mapping for translate/recorder flow)
- ✅ **Recorder**: Group 0x0c (start, capture, pause, stop)
- ✅ **ASR display**: Group 0x0c, Opcodes 0x24 (partial), 0x25 (final), 0x26 (reply)
- ✅ **Binary mic stream**: `52 91/92 ...` frame format
- ✅ **Battery JSON response**: Full settings map with `bat`, `charging`, etc.
- ✅ **Brightness control**: Group 0x01, Opcode 0x0b (manual 0–10), Opcode 0x0f (auto toggle)
- ✅ **Wake angle threshold**: Group 0x01, Opcode 0x0c (0–60 degrees)

### Not yet confirmed
- ❌ **Display primitives** (draw image, line, circle, rectangle, text at position)
- ❌ **DrawCommand opcode values** — Dart object pool constants, not statically recoverable
- ❌ **Fullscreen renderer** (`52 91/92 ...` binary frames) — structure partially decoded, opcodes unknown
- ❌ **WQ magic byte** (expected by WQRecordBluetoothProtocolParserV2)
- ❌ **FrameCnt encoding** in WQ audio frames
- ❌ **Font bitmap header** layout
- ⏳ **OTA update protocol** — Ghidra analysis complete, live capture blocked (glasses on latest firmware)

## OTA Protocol (from Ghidra Analysis)

### Architecture
The OTA flow is: **Phone checks API → Downloads firmware → Block-by-block transfer to glasses over BLE/SPP**

```
App (Dart) ──HTTP/JWT──► Server (memo-mind.com)
    │                          │
    │  jwt/ota/check-update    │
    │  jwt/ota/get-release-note│
    │                          │
    ▼                          ▼
  Firmware URL + MD5 ◄────────┘
    │
    ▼
  Download firmware binary
    │
    ▼
  Block-by-block BLE/SPP transfer
  (WQOtaBluetoothProtocolParser)
    │
    ▼
  Glasses verify + reboot
```

### Key Dart Packages
| Package | Purpose |
|---------|---------|
| `module_ota` | OTA state machine, UI, receiver |
| `biz_ota_info` | API client, repository, models |
| `common_blue` | BLE/SPP transport layer |

### OTA State Machine
`IdleOtaState → CheckingOtaState → ConnectStateOta → DownloadOtaState → VerifyingStateOta → RebootOtaState → FinishOtaState`

### API Endpoints
- `jwt/ota/check-update` — POST, JWT-authenticated
- `jwt/ota/get-release-note` — POST, release notes

### BLE OTA Characteristics
- `0x7033` — OTA write/notify characteristic
- `ota_spp` — OTA over SPP (RFCOMM) path

### Firmware Transfer
- `sendFirmwareUpdateBlock` / `sendOtaFileBlock` — sends chunks
- `WQOtaBluetoothProtocolParser` — parses OTA protocol
- MD5 verification after transfer
- Error codes: disconnect, enterMode, file, firmwareInfo, keyMismatch, lowBattery, space, uboot, updateFail, verify

### Capturing Strategy
Glasses are on latest firmware (`V1.0.0.267`), so no update is available. To capture:
1. **Firmware version spoofing** — modify device info response to report older version
2. **MITM proxy** — intercept HTTP API calls at network level
3. **Older firmware unit** — find glasses that haven't been updated

## Fullscreen Renderer (`52 91/92 ...`)

### Structure
```
Header:  52 91 00 00 <counter:16> 08 <seq:16> <ts:24>
Payload: 8 blocks × 43 bytes (40 data + 5-byte separator 00 00 00 00 28)
```

### Key Observations
- `52 91` = draw command stream magic
- `00 00 00 00 28` = block separator (0x28 = 40 = block size)
- `b8` appears at byte 4 of ~90% of blocks — likely type/flag marker
- Data changes completely between frames → rendering data confirmed
- Used for lyrics, menu, and immersive UI states

### DrawCommand System (Ghidra)
- `_readDrawImage`, `_readDrawText`, `_readDrawPath`, `_readDrawVertices` — deserializers
- `_addCommandsTag` — writes opcode byte
- Opcode values are Dart object pool constants — NOT statically recoverable
- Requires runtime hooking (Frida → `libapp.so`) or correlation capture to decode

## Connection Flow (Observed via Frida)

1. **Scan**: Bluetooth device scan by name pattern
2. **Create RFCOMM socket**: `createRfcommSocketToServiceRecord(0x1101)`
3. **Connect**: `BluetoothSocket.connect()`
4. **Handshake**: App sends version/capability info (group 0x01)
5. **Open secondary sockets**: Connect to UUIDs 0x2026 and 0x2024
6. **Subscribe to capabilities**: Send subscription request
7. **Sync schedule**: Push calendar/reminder data
8. **Query device info**: Request/response
9. **Query battery**: Request/response

## Key Differences from Original BLE Assumptions

| Old assumption | New finding |
|----------------|-------------|
| BLE GATT over 0x2001/0x2002 | **RFCOMM SPP over 0x1101** is primary |
| MQTT-like framing (header+varint) | **`fa 00 00 <len> <seq> <group> <opcode> <type>`** framing |
| ServiceId byte needed | **Group + Opcode** pattern instead |
| Billboard text push via BLE | **Group 0x04** opcodes over RFCOMM |
| Notification via BLE | **Group 0x05** opcodes over RFCOMM |
| Recorder via BLE 0x2020-0x2026 | **Group 0x0c** over RFCOMM + bin stream on separate socket |

## File Types and Response Handling

### Device info response (Group 0x01, Opcode 0x02)
- Request: `fa 00 00 09 04 01 02 01 0a`
- Response: JSON with `pver`, `model`, `sn`, `mac`, `name`, `ver`, `cver`, `hver`, `fver`

### Battery / settings response (Group 0x01, Opcode 0x06)
- Request: `fa 00 00 09 05 01 06 01 0f`
- Response: JSON with full settings map including `bat`, `charging`

### Push notification (Group 0x05, Opcode 0x01)
- Format: `fa 00 00 <len> <seq> 05 01 08 <...>`
- Payload is JSON with `id`, `a`, `type`, `ts`, `c`, `ti`, `pkg_name`

### High-level card push (Group 0x02, Opcode 0x08)
- Format: `fa 00 00 <len> <seq> 02 08 08 <...>`
- Seen with inner markers for stock/news/schedule/calendar cards
- Stock payloads can continue in follow-up chunks starting with `01 00 ...` and `02 00 ...`

## Remaining Mapping Work

### Display primitives (highest priority for EvenHub bridge)
The MemoMind app uses **DrawCommand** objects for rendering:
- `_readDrawImage` — image rendering
- `_readDrawText` — text/glyph rendering
- `_readDrawPath` — clipping/path operations
- `_readDrawVertices` — vertex-based shapes (lines, circles, rectangles)

These are likely sent through a separate opcode path (possibly a Billboard sub-command or a different group entirely). So far, weather/news/stock/calendar/to-do surfaces all resolved to higher-level component payloads instead of raw draw operations. **Not yet observed in any Frida capture.**

### WQ Record Protocol (audio)
The glasses microphone audio uses a protocol called `WQRecordBluetoothProtocolParserV2`:
- Magic byte: Unknown (referenced in error string at `0x22f744`)
- Frame count encoding: Unknown (validated at `0x0b9b69`)
- Binary stream format: Starts with `52 91/92 ...` in observed captures
- Need to decode the 404/808-byte chunks to determine audio codec (likely Opus)

## Adapter Next Steps

### MVP path for Even Hub and MentraOS
1. Rewrite the MemoMind adapter transport around RFCOMM sockets and the confirmed `fa 00 00 ...` frame format.
2. Implement serializers for the high-level card routes first:
   - stock (`group 0x02`, inner `0x04 0x05`)
   - news (`group 0x02`, inner `0x06 0x05`)
   - schedule/to-do (`group 0x02`, inner `0x09 0x05`)
   - calendar (`group 0x02`, inner `0x0a 0x05`)
   - notifications/media metadata (`group 0x05`, opcode `0x01`)
3. Use those card routes as the first rendering targets for Even Hub apps and MentraOS rather than waiting for raw draw primitives.

### Practical mapping strategy
| Source runtime | First MemoMind target |
|----------------|-----------------------|
| Even Hub text/news cards | News card or schedule/to-do card |
| Even Hub stock/market widgets | Stock card |
| MentraOS notifications | Notification JSON route |
| MentraOS reminders/tasks | Schedule/to-do or calendar card |
| Long-form scrolling text | Teleprompter route (`group 0x04`) |

### Deferred work after MVP
1. Decode the opaque `52 91/92 ...` full-screen renderer used by music/lyrics and likely other immersive UI states.
2. Isolate the side-button menu options one by one to finish the `group 0x03` mapping.
3. Continue searching for a truly custom-drawing surface that emits raw draw primitives instead of high-level component payloads.
