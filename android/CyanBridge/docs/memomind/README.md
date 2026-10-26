# MemoMind integration

CyanBridge talks to MemoMind glasses with the protocol the vendor published in its open
platform: [memomind-open/plugin-open-platform](https://github.com/memomind-open/plugin-open-platform)
(reviewed at commit `171098b`). The `GlassSDK/docs/` pages there are the source of truth; this
page summarizes them and records what CyanBridge implements. The older Frida notes in
[PROTOCOL_NOTES.md](PROTOCOL_NOTES.md) predate the official docs and are wrong in several places
(see [Discrepancies](#discrepancies-with-the-older-notes)).

## Links

| Link | Android | iOS |
| --- | --- | --- |
| GM commands | Classic SPP `00007033-…` (fallback `00001101-…`) | iAP2 `ql.iap2.protocol02` (MFi), or BLE GATT: service `fb349b5f-8000-0080-0010-002020000000`, write `0x2021`, notify `0x2022` |
| Recording (Opus) | Classic SPP `00002024-…` | iAP2 `ql.iap2.protocol01`; BLE recording (`0x2025`) is not implemented in current firmware |
| Speaker / call mic | HFP (system Bluetooth audio) | HFP |

Docs: `BLUETOOTH_DEVELOPER_GUIDE.md` (links, BLE discovery and its gaps), `PROTOCOL.md` (framing).

## GM framing (`PROTOCOL.md`, `WIRE_EXAMPLES.md`)

`FA len24 event service command TLVs… sum16`: the 24-bit length is the logical length (TLVs + 9),
the sum is the additive checksum of the physical frame. Messages longer than one frame (512 bytes
on SPP, the ATT write limit on BLE) repeat the 7-byte header with heads `01, 02, …` and carry
their own sums; on BLE each fragment waits for an empty flow-control response. TLVs:
`type len24 value` with BYTES 1, STRING 2, INT32 3, JSON 5, STATUS 6, INT16 7, INT8 8 (big-endian).

## Services

| Service | Commands (published) | CyanBridge |
| --- | --- | --- |
| `01` system | heartbeat `01`, device info `02` (STRING JSON), status `06` (STRING JSON: `bat`, `charging`, …), media fullscreen `09`, height `0A`, optical distance `0B`, head-up angle `0C`, brightness `0E` (0–10), auto brightness `0F`, recording processing `2F`, screenshot `65`/`66`/`6A`, AI voice reply `71`, media control `76` | All except the screenshot upload (request only; LZ4 decoding not implemented) |
| `03` translation | start/stop/provisional/final, conversation mode, pause/resume, source, glasses pause `16` | start, stop, results; `16` surfaces as an event |
| `04` teleprompter | start, stop, page request `03`, display update, text, highlight, mode, font, width, pause/resume | start, text, highlight, stop, pause/resume; page requests surface as events |
| `05` notifications | push `01` (INT8 style + JSON `id,a,type,ts,c,ti,pkg_name`), switch `03`, scroll speed `04` | push and switch |
| `06` navigation | map images in a product codec | not implemented (no public image codec) |
| `0F` plugin messages | phone→glasses `28` (INT16 channel, BYTES), ack INT8 status + INT32; glasses→phone `29` | full; Web Bridge channels 1–6 drawing and the `0x0100`–`0x0104` events decoded |
| `10` HOGP accessory gateway | ring/remote scan, GATT, HID, input maps | not implemented (no accessory to drive yet) |
| GMP install | **withheld** by the vendor ("only executable GMP delivery is withheld") | not possible: `.gmp` plugins can only be installed by the official App |

## Recording link (`AUDIO_PROTOCOL.md`)

Raw bytes, no GM envelope: `52 12 mode` selects legacy (0, 49-byte records) or compact (1, 40-byte
records) and is answered `52 13 mode 31 28 00`; `52 01 00` starts (answered `52 11 noise ts24`);
`52 00 00` stops without an ACK. Audio packets: `52 cmd 00 00 xx xx count xx xx xx xx FF` then
`count` records; stream `92` is the capture this client started (`90/91` VAD, `93/94` call). Each
record carries one 40-byte Opus packet (mono, 16 kHz input, 20 ms). Send `01/2F` first.

## Input (`examples/web_bridge`, `include/gm_plugin.h`)

Without a plugin the firmware does not report buttons or head gestures to the phone. The public
**Web Bridge** glasses plugin (installed and started by the official App) sends them on plugin
channels: `0x0100` button (`button:u16 action:u16`, PRIMARY = 1, LONG = 3), `0x0101` head gesture,
`0x0102` raw IMU, `0x0103` connection, `0x0104` frame status, after a 10-byte header. `INPUT.md`
and `SYSTEM_EVENTS.md` are glasses-plugin ABI, not phone protocol.

## What CyanBridge implements

Shared (`shared/src/commonMain/.../devices/memomind/`):

- `MemoMindGm.kt`: frames, TLVs, a stream/notification reassembler with resync.
- `MemoMindCommands.kt`: every published request above, Web Bridge payloads, recording controls.
- `MemoMindModels.kt`: device info/status, events (plugin messages, Web Bridge input, teleprompter
  page, translation pause, unsolicited messages).
- `MemoMindRecording.kt`: the recording stream parser (legacy and compact).
- `MemoMindSession.kt`: `MemoMindTransport` / `MemoMindRecordingLink` interfaces, request
  correlation, BLE flow control, bring-up, HUD, plugin messages, Web Bridge probe, microphone.
- `MemoMindHud.kt`: display policy (Web Bridge drawing when a plugin runs, else teleprompter for
  text and notification cards), the `GlassesDeviceAdapter` for the display bridge, and the
  assistant trigger (primary long press).
- `webplugin/`: the Web plugin host and `.mmpkg` reader, see [Web plugins](#web-plugins).

Android (`app/.../devices/memomind/`, `ui/main/glasses/MemoMindGlasses.kt`): SPP transport,
manager (bring-up, reconnect, battery polling, notifications forwarding, button → voice question),
MediaCodec Opus decoder to 16 kHz PCM, and the microphone as an `AudioSessionCoordinator` session.

iOS (`shared/src/iosMain/`): see [iOS](#ios) below.

Not implemented: GMP install/list/remove (withheld), screenshot download, navigation, HOGP.

## iOS

The iOS host runs the same `MemoMindSession`, `MemoMindHud` and `MemoMindDisplayAdapter` over one
of two links, chosen when connecting (`platform/IosMemoMindGlasses.kt`):

| Link | Class | When it is used |
| --- | --- | --- |
| iAP2 `ql.iap2.protocol02` (commands) and `ql.iap2.protocol01` (recording) | `ble/IosMemoMindAccessoryTransport.kt` (ExternalAccessory, `EASession` streams on the main run loop; 512-byte frames, no fragment acks, like SPP) | Only when the app's Info.plist declares the protocol **and** a connected accessory offers it (`findAccessory()`); otherwise never tried |
| BLE GATT, write `0x2021` / notify `0x2022` | `ble/IosMemoMindBleTransport.kt` over the shared `IosBleManager` (0x2021 is its preferred write characteristic for MemoMind) | Every other case: the glasses picked in Scan connect like other BLE families |

BLE details: frames are sized to the ATT write limit (`maximumWriteValueLength`, MTU - 3, capped at
512), one complete GM frame per write, and `awaitsFragmentAcks = true` (each fragment waits for the
empty flow-control response). Bring-up waits until 0x2021 is discovered and 0x2022 notifies (the
firmware ignores writes from a central that has not subscribed), then heartbeat, device info,
status and the Web Bridge probe; the discovered service UUID is logged. Scan also lists MemoMind
peripherals that are already connected to iOS (they stop advertising). The BLE link has no
recording channel (`recordingLink = null`).

What works on iOS:

- Connect/disconnect (Scan, Reconnect, the MemoMind panel), battery (5-minute poll and on demand),
  HUD text, cards and clear through `MemoMindHud` (Web Bridge drawing when the plugin runs),
  comfort brightness, the panel's test notification.
- Button questions: a primary long press from the Web Bridge plugin starts the assistant's voice
  question (`GlassesAssistant.askByVoice`), which listens and answers through the glasses' HFP
  microphone and speaker (the existing iOS voice path).
- `IosAppController.memoMindSession` / `memoMindDisplay` expose the session and the display adapter
  for a web plugin host.
- Over iAP2 only: the Opus recording stream (the panel's microphone test counts frames).

Not available on iOS, and why:

- **Phone notification forwarding.** iOS apps cannot read other apps' notifications. ANCS shares
  them with BLE accessories, not apps: only glasses firmware that subscribes to ANCS could show
  them, which the published protocol does not describe.
- **Microphone stream over BLE.** The firmware's BLE recording path is not implemented
  (`BLUETOOTH_DEVELOPER_GUIDE.md`, "Current implementation versus the BLE release promise");
  voice input uses HFP.
- **BLE on MFi firmware builds.** The firmware registers the GM GATT service only when MFi is
  disabled, so a release built for MFi may have no 0x2021/0x2022 at all. Connecting then fails with
  a message naming the missing service; only iAP2 can reach such glasses.
- **Long uplink messages over BLE** may be cut: the firmware packs notifications up to 512 bytes,
  not the negotiated MTU. Short replies (heartbeat, status) are unaffected; record device-info
  behavior per firmware.

### iAP2 requirements

`ios/CyanBridgeKMPHost/Info.plist` declares both MemoMind protocols in
`UISupportedExternalAccessoryProtocols` (next to Meta's `com.meta.ar.wearable`), and the
`external-accessory` background mode is present, so iAP2 is tried whenever a paired accessory
offers them. To use it:

1. Pair the glasses in iOS Settings ▸ Bluetooth (iAP2 runs over Classic Bluetooth) and close the
   official MemoMind app: iOS gives each protocol to one app session at a time.
2. For App Store or TestFlight distribution, the accessory maker (MFi licensee) must add the app's
   bundle ID to the accessory's MFi product plan and supply the PPID for App Review; Apple rejects
   apps that declare accessory protocols without it. Development builds do not need it. Remove
   the two `ql.iap2.*` strings from a store build if that approval is not in place; the app then
   falls back to BLE.
   The vendor's guide notes that publishing a protocol name does not grant an accessory session.

## Web plugins

MemoMind's PhoneSDK (`PhoneSDK/` in the open platform) lets developers write phone-side Web
plugins: an HTML/JS app packaged as `.mmpkg` that runs in the companion app's WebView and talks
to the glasses through the Bridge (`packages/bridge-contract`, revision p7-handoff-r1; the
contract calls itself Bridge **2.0**: `bridgeVersion "2.0"`, `schemaVersion 2`,
`permissionPolicyVersion 1`). CyanBridge hosts these plugins: MemoMind panel ▸ **Web plugins**.

**Platform limit.** A Web plugin draws and receives input through a *glasses* plugin: the stock
Web Bridge (`com.gm.example.web-bridge`, scene channels 1–9, input channels `0x0100`–`0x0104`)
or the plugin's own paired `.gmp`. Installing and starting glasses plugins is withheld by the
vendor; only the official MemoMind app can do it. So: start Web Bridge (or the paired plugin) in
the official app, force-stop the official app, and connect CyanBridge. The plugin list and the
running plugin say so while `webBridgeRunning` is not true. Without it, `display.updateText`,
`display.rebuildPage` (text only) and `display.closePage` fall back to the native teleprompter
(results carry `fallback: "teleprompter"`, capabilities report `display: ["text", "clear"]`);
images, atomic frames, glasses input and messages to a missing plugin fail with
`CAPABILITY_UNAVAILABLE` (or the plugin's GM status). The microphone does not need a glasses
plugin.

### Bridge coverage

Shared host: `shared/src/commonMain/.../devices/memomind/webplugin/` (`MemoMindWebPluginHost`,
`WebPluginPolicy`, `MmpkgPackage`); Android WebView glue: `app/.../devices/memomind/webplugin/`.

| Contract | CyanBridge |
| --- | --- |
| `runtime.ready/ping/getBridgeVersion/getCapabilities/getLifecycleState` | Implemented. Capabilities list only granted, implemented methods; `audio` only when granted and the WebView can transfer binary ports. |
| `storage.get/set/remove/clear` | Implemented: JSON values private to the plugin (`files/memomind/webplugins/<key>/data/storage.json`), 1 MiB per plugin, keys up to 256 characters; never the app's preferences. |
| `display.createPage/closePage/updateText/updateImage/updateImageLz4/rebuildPage` | Implemented on Web Bridge channels 1, 2, 6, 7 (regions checked against 600×350, payloads ≤ 81,901 bytes, LZ4 tiles not decoded on the phone). Teleprompter fallback for text, see above. |
| `display.beginFrame/updateFrameImageLz4` | Implemented on channels 8/9; each call waits (5 s) for the `0x0104` frame status, `tileIndex` must follow `nextIndex`. |
| `device.getInfo` | Implemented: connected, model, name, firmware, battery, `webBridgeRunning`. Serial number and MAC are not exposed. |
| `device.subscribeEvents/unsubscribeEvents`; events `device.button/imuGesture/rawImu/connection` | Implemented from Web Bridge input (`0x0100`–`0x0103`) and the link state; at most 16 subscriptions; types limited to the granted scope. While a plugin is in the foreground, glasses input goes to it only (no assistant voice question). |
| `plugin.sendMessage`; event `plugin.message` | Implemented on GM `0F/28` and `0F/29`; both directions filtered by the granted channels plus the extra `display` / `device.events` rule for standard channels; payload 1..81,901 bytes; success means the GM delivery status was 0. |
| `audio.openCapture/stopCapture`; event `audio.captureState` | Implemented: the Opus microphone (`52 …` recording link) through the app's audio arbitration (`AUDIO_BUSY` when another feature holds it), profiles `interactive/balanced/reliable/custom`, pickup mode and noise reduction mapped to `01/2F`, chunks in the V1 `GMAU` envelope over a WebView message port with pull flow control, queue overflow strategies, `maxDurationMs`, tail flush on stop. A recording indicator shows outside the WebView; leaving the screen stops capture. |
| `runtime.lifecycleChanged` | `starting` → `running`; `suspended` when the screen is in the background (events stop, audio stops); `stopped` on close. Every reload is a new runtime generation and session token. |
| `files.*` | Not implemented: answer `CAPABILITY_UNAVAILABLE` (after the permission check) and are left out of capabilities. |
| `location.*`, events `location.position/error` | Not implemented: `CAPABILITY_UNAVAILABLE`. |
| `audio.playback`, `network` (no methods) | Enforced as WebView policy: without `audio.playback` the page's CSP has `media-src 'none'`; without `network` every non-package request is refused and the CSP limits `connect-src` to the package (WebRTC is not covered by CSP, as in the official app). |

Errors use the contract's codes; device failures map to `DEVICE_DISCONNECTED`, `TIMEOUT`, `BUSY`
or `CAPABILITY_UNAVAILABLE` (Web Bridge plugin status 2/6/7/8).

### Packages

`MmpkgPackage.read` verifies a picked file before anything is written: at most 10 MB, a strict
ZIP reader (one disk, no ZIP64 or encryption, STORED/DEFLATE only, CRC-32 checked, sizes from
the central directory, inflation capped at the declared size), at most 500 regular files and
30 MB expanded (10 MB each), safe relative paths only (no `..`, absolute paths, backslashes,
symbolic links or other Unix file types, no duplicates even by case). The manifest must be the
final packager output: `schemaVersion 2`, `bridgeVersion "2.0"`, `permissionPolicyVersion 1`,
reverse-domain `id`, semantic `version`, an `.html` `entry`, permissions normalized with the
contract's policy (known names, scopes for `device.events` / `device.messaging`), optional
`deviceRequirements`, and a `files` table that covers every payload file exactly with matching
SHA-256. `signature.sig` is ignored: MemoMind publishes no signing keys, so every package is an
unsigned sideload and the install dialog says so. Installing the same `id` replaces the package
atomically and keeps its data; removing deletes package, data, choices and its WebView storage.

Permissions are asked before the first launch (nothing is pre-checked; required ones gate Start)
and again when the declaration changes; the plugin list has a Permissions action to change them.

### Sandbox (Android)

- Each plugin has its own origin `https://<sha256(id)[0..32]>.mmplugin.androidplatform.net`,
  answered from the package by `shouldInterceptRequest`, which serves only files listed in the
  manifest (`nosniff`, `no-store`, CSP on HTML). Web storage is per origin, so plugins cannot
  read each other's or other app pages' storage.
- File and content access, file-URL universal access, geolocation, pop-ups, mixed content,
  third-party cookies, camera/microphone prompts and file choosers are off; navigation outside
  the origin is blocked; a renderer crash closes the plugin instead of the app.
- The Bridge is `MemoPluginBridge` from an origin-scoped `addWebMessageListener` (androidx.webkit),
  only main frames of the plugin origin are accepted, and no `addJavascriptInterface` object
  exists. A document-start script asks for the bootstrap as soon as the SDK installs
  `__memoPluginBootstrap`. Requests over 128 KiB, more than 300 per second or 64 in flight, a
  wrong session token or a stale runtime generation are rejected before dispatch; every argument
  is validated.
- WebViews without `WEB_MESSAGE_LISTENER` refuse to run plugins; without binary message ports
  the audio capability is absent.

### Web plugins on iOS

Not hosted yet (the panel hides the entry: `MemoMindPanelLabels.webPlugins` stays null). The
shared host is platform-neutral; an iOS host needs, in `iosMain` or Swift:

1. A `WKWebView` with a non-persistent or per-plugin `WKWebsiteDataStore`, a
   `WKURLSchemeHandler` for a custom scheme (for example `mmplugin://<key>/`) that serves only
   manifest files with the same headers and CSP as Android, and `WKContentRuleList` rules that
   block other loads unless `network` is granted.
2. A `WKScriptMessageHandler` named `MemoPluginBridge` added to a `WKContentWorld.page` user
   content controller, accepting only main-frame messages from that origin and passing the body
   string to `MemoMindWebPluginHost.onMessage`; `WKUserScript` at document start that defines
   `window.MemoPluginBridge = { postMessage: s => webkit.messageHandlers.MemoPluginBridge.postMessage(s) }`
   plus the bootstrap hook from `WebPluginRuntime.BOOTSTRAP_HOOK`.
3. `WebPluginPage` calls as `evaluateJavaScript` on the main thread; the host's scope on
   `Dispatchers.Main`.
4. Audio: WKWebView cannot transfer native message ports, so either pass
   `audioPortsSupported = false` (capability absent) or emulate the port in JavaScript (a page
   `MessageChannel` fed by `evaluateJavaScript` with base64 chunks) and post the
   `gm-plugin:stream-port` message from the page itself.
5. Device: `MemoMindWebPluginDevice(IosAppController.memoMindSession, <ready flow>, <opener on the
   iAP2 recording link>)`; BLE has no recording link, so capture fails there.
6. Store packages in Application Support with `MmpkgPackage.read` and an inflater over
   `compression_decode_buffer(COMPRESSION_ZLIB)` (raw DEFLATE), capped at the declared size.

## Discrepancies with the older notes

| Older Frida notes | Official docs (followed) |
| --- | --- |
| Header `fa 00 00 len16 seq group op type`, "type" 01/02/06/08 | `FA len24 event service command`, then TLVs; the "type" byte is the first TLV type (or the checksum's high byte on empty requests) |
| Primary SPP `0x1101`; `0x7033` "OTA" | Commands on SPP `0x7033`, `0x1101` is the legacy fallback |
| BLE command `0x2001`/`0x2002` | BLE command `0x2021` (write) / `0x2022` (notify) in service `fb349b5f-…` |
| Brightness `01/0B`, "font size" `01/0E` | Brightness `01/0E` (0–10); `01/0B` is optical distance |
| Auto brightness `0` = auto, `1` = manual | `0` off, `1` on |
| `52 91/92 …` with `00 00 00 00 28` separators = "fullscreen renderer / lyrics" | Opus recording packets: `91` VAD long recording, `92` normal capture; the `…28` is the legacy record's length marker |
| Notification payload is JSON with "type 08" | INT8 style TLV, then a JSON TLV |
| Opus at 24 kHz | 16 kHz input, 20 ms frames, 40-byte packets |
| Card group `02/08`, recorder/ASR group `0C` | Not in the published docs (product-specific); not used |
| Name patterns included "heycyan"/"cyan"/"xgimi" | Only "MemoMind" names are classified; "cyan" belongs to HeyCyan |
