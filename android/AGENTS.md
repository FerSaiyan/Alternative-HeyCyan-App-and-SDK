# HeyCyan Smart Glasses (Android) - Data Transfer Notes

This file is the living reference for how CyanBridge transfers media from the glasses to the phone.
It replaces older/incorrect assumptions from the initial reverse-engineering phase.

## Repository layout

- `android/CyanBridge/` - Android app (CyanBridge) we modify and ship.
- `android/HeyCyanOfficialApp/` - Decompiled sources from the official app, used as a protocol reference.
- `android/CyanBridge/app/libs/glasses_sdk_*.aar` - Vendor SDK used by both apps.

## Working media transfer (BLE + Wi-Fi Direct HTTP)

The glasses expose an HTTP server during transfer mode. The phone uses BLE to trigger transfer mode + obtain the glasses' Wi-Fi IP, then downloads media over Wi-Fi Direct.

### Endpoints (confirmed)

- `http://<glasses-ip>/files/media.config` - plaintext file listing filenames (one per line).
- `http://<glasses-ip>/files/<filename>` - binary media payload.

### Trigger sequence (CyanBridge)

Code lives in `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/MainActivity.kt`.

1. Ensure BLE is connected.
2. Start Wi-Fi P2P discovery/connection.
3. Over BLE, ask the glasses to enter transfer mode and report their IP:
   - `LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, 0x04))`
4. Listen for notify frames:
   - `loadData[6] == 0x08`: glasses Wi-Fi IP present in bytes `[7..10]` as IPv4.
   - `loadData[6] == 0x09`: P2P/Wi-Fi error; `loadData[7] == 0xFF` (255) is common/noisy.
5. Bind the app process to the P2P network when connected (important on Samsung/multi-network devices).
6. Fetch `media.config`, parse filenames, then download each file.

### Critical gotcha: groupOwnerAddress is usually the phone

On many devices, `WifiP2pInfo.groupOwnerAddress` is `192.168.49.1` and refers to the phone (group owner), not the glasses.
Do not use it as the HTTP target.

Prefer:

- BLE-reported IP from notify `0x08`.
- Any other device-reported IP channel (e.g., `BleIpBridge` if present).

## File types and how we save them

The `media.config` list can include images, video, and audio.

### JPG/JPEG

- Downloaded over HTTP and inserted into `MediaStore.Images`.
- Saved under `DCIM/CyanBridge` via `RELATIVE_PATH` (Android 10+) so they always appear in Gallery.

### MP4

- Downloaded over HTTP and inserted into `MediaStore.Video`.
- Saved under `DCIM/CyanBridge`.
- Uses longer HTTP read timeouts.

### OPUS recordings

The glasses' `.opus` files are not guaranteed to be standard Ogg/Opus.
In practice, many are raw Opus packets (often fixed-size 40-byte blocks) without an Ogg container.

What we do:

- Download the `.opus` bytes.
- If it already starts with `OggS`, keep as-is.
- Otherwise try to wrap into an Ogg/Opus container using heuristics:
  - Length-prefixed packets (u16 LE/BE or u8).
  - Fixed packet size (includes 40 bytes; the official app uses `packetSize=40`).
- Write the resulting bytes into `MediaStore.Audio` with `MIME_TYPE=audio/ogg`.
- Save under `DCIM/CyanBridge` (same folder preference as images/videos).

This makes the recordings playable in common players (e.g., VLC) when wrapping succeeds.

## Android platform requirements / pitfalls

### Cleartext HTTP must be allowed

The transfer server is HTTP (not HTTPS), so Android may block it unless configured.

- `android/CyanBridge/app/src/main/res/xml/network_security_config.xml` permits cleartext so `http://192.168.49.x/...` works.

### Bind network for correct routing

On some devices (notably Samsung), sockets may route over the wrong default network unless the process is bound.
We locate the P2P network via `ConnectivityManager` and bind with `bindProcessToNetwork()`.

### Broadcast receiver compatibility

Do not call the API 33+ `registerReceiver(..., flags)` overload directly on pre-33 devices.
Use `ContextCompat.registerReceiver()`.

### Permissions

- Android 13+: `NEARBY_WIFI_DEVICES` is required for Wi-Fi Direct discovery.
- Location permission is also commonly required for peer discovery on many builds.

## Error handling notes

- `loadData[6] == 0x09` with error 255 is common and not necessarily fatal.
- Resetting P2P while an HTTP transfer is active can drop the connection; CyanBridge suppresses aggressive resets during active transfer.
- Disconnect/retry logic is split between:
  - Wi-Fi P2P state machine (`WifiP2pManagerSingleton`).
  - Higher-level resolver in `MainActivity` that waits for a real glasses IP and a reachable `media.config`.

## Code references (CyanBridge)

- Main flow: `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/MainActivity.kt`
  - `startDataDownload()`: orchestrates BLE + P2P.
  - HTTP + parsing: downloads `media.config`, then downloads JPG/MP4/OPUS.
  - MediaStore saves: images/videos/audio saved to `DCIM/CyanBridge`.

- P2P controller: `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/ui/wifi/p2p/WifiP2pManagerSingleton.kt`
  - Includes discovery/connect timeouts and uses WPS PBC.
  - `resetDeviceP2p()` sends `glassesControl([0x02,0x01,0x0F])`.

- Network policy: `android/CyanBridge/app/src/main/res/xml/network_security_config.xml`

## How the official app differs (relevant parts)

Use this only as a protocol reference in `android/HeyCyanOfficialApp/`.

- Album import registers a notify listener with `cmdType=2`.
- It downloads `media.config` from `/files/media.config` and then files from `/files/<name>`.
- For recordings, it uses a native Opus pipeline (`OpusManager`, `hasHead=false`, `packetSize=40`) and often converts to PCM/WAV for playback.

## Logcat

Preferred tags:

- `DataDownload`
- `DeviceNotify`
- `WifiP2pManagerSingleton`
- `WifiP2pBroadcastReceiver`

Example:

```bash
adb logcat -s DataDownload DeviceNotify WifiP2pManagerSingleton WifiP2pBroadcastReceiver
```

## Development Environment

- **Java Requirements**: The project (specifically the Android Gradle Plugin 8.12.1) requires **Java 17+** to build.
- **JDK Location**: Use the JDK bundled with Android Studio, found at:
  - `/opt/android-studio/jbr` (Java 21)
- **Build Command**: Always set `JAVA_HOME` when running Gradle:
  ```bash
  JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
  ```

## Logcat conventions

When investigating or reproducing behavior, prefer the following tags:

- `DataDownload` – all our high‑level logging for the BLE+WiFi data‑download flow in `MainActivity`.
- `DeviceNotify` – decoded glasses notify frames (battery, Wi‑Fi IP, P2P errors, etc.).
- `WifiP2pManagerSingleton` – P2P lifecycle logs (init, discovery, connect, timeouts).
- `WifiP2pBroadcastReceiver` – raw P2P broadcasts (peers, connection state).
- `LDHMethods` – reflection dump of all `LargeDataHandler` methods (used once to discover SDK capabilities).
- `BleIpBridge` – raw BLE payloads and any IPv4 addresses detected via regex.

Typical command used in this project:

```bash
adb logcat -d -s DataDownload DeviceNotify WifiP2pManagerSingleton WifiP2pBroadcastReceiver BleIpBridge LDHMethods
```

## How to compare official vs. sample app behavior

1. **Find relevant decompiled classes**
   - In `HeyCyanOfficialApp/`, search with `rg` or your IDE for:
     - `LargeDataHandler.getInstance().glassesControl(` – to see how official flows drive the glasses.
     - `writeIpToSoc`, `syncPictureThumbnails`, `syncHeartBeat`, etc.
     - UI entrypoints like `PictureFragment`, `AlbumDepository`, or OTA activities that handle imports.
   - Map the commands (payload arrays) and the order in which they’re called.

2. **Compare P2P controller**
   - Look for the vendor `WifiP2pManagerSingleton` in `HeyCyanOfficialApp` and ensure:
     - Intent actions (`WIFI_P2P_CONNECTION_STATE_CHANGE_ACTION` vs. `WIFI_P2P_CONNECTION_CHANGED_ACTION`).
     - Retry logic (`discoveryTimeOut`, `connectTimeOut`) matches what we run in the sample.
   - Confirm where the vendor app actually calls `resetDeviceP2p()` and where it sends `[2,1,15]` over BLE.

3. **Compare notify handling**
   - In the decompiled app, locate the `GlassesDeviceNotifyListener` used for album/import flows.
   - See how they interpret `loadData[6] == 0x08` and `0x09`:
     - When do they treat errors as fatal?
     - When do they just retry P2P and keep waiting for an IP?

4. **Compare HTTP behavior**
   - Search `\"media.config\"` or `\"/files/\"` in `HeyCyanOfficialApp`:
     - Identify the exact URLs, timeouts, and error handling for media and OTA downloads.
   - Match our `downloadMediaList`/`AlbumDownloader` behavior to those URLs and paths.

## Investigation hints / pitfalls

- **Do not over‑edit the P2P layer**:
  - Keep `WifiP2pManagerSingleton.kt` behavior as close as possible to the vendor version.
  - If you must experiment, prefer *adding* logs or small hooks rather than changing connection/discovery logic.

- **Beware of IP sources**:
  - The glasses can communicate the IP in multiple ways:
    - As a dedicated 0x08 notify frame.
    - Embedded as text/bytes in BLE notifications that `BleIpBridge` can parse.
  - When starting HTTP, prefer device‑reported IPs (`0x08` or `BleIpBridge`) over hard‑coded fallbacks.

- **Error 255 (`0xFF`) is noisy, not always fatal**:
  - Both our sample and the official app regularly see `P2P/WiFi error 255` via `0x09`.
  - The vendor app handles this by resetting P2P and continuing; it still later receives the Wi‑Fi IP and downloads successfully.
  - Don’t treat this as “abort everything”; instead, log and rely on subsequent IP+P2P success signals.

- **Official app may piggy‑back on sample’s state**:
  - We’ve observed cases where:
    - The demo app appears to trigger download mode on the glasses.
    - The official HeyCyan app (running in the background) actually performs the HTTP transfer and shows the new media.
  - When testing our changes, ensure the official app is either:
    - Force‑stopped, if we want to confirm the sample can download on its own, or
    - Intentionally left running, if we’re trying to see cooperative behavior.

## OTA investigation notes

- OTP/OTA support is present in the SDK and official app:
  - Look for classes like `OTAActivity`, `startSocOtaServer`, or HTTP URLs containing firmware filenames.
  - `writeIpToSoc("http://<ip>:8080/<firmwareName>", ...)` is used to tell the glasses where to fetch OTA data.
- For future work on OTA logging/dumping:
  - Identify OTA configuration endpoints and firmware download URLs.
  - Log the full HTTP requests (host, path, headers) from the official app.
  - Observe how OTA binaries are chunked and written to the glasses via BLE or Wi‑Fi (glassesControl/BigData handlers).

### OTA HTTP APIs (current understanding)

- Base API host (from decompiled app + MITM):
  - `https://www.qlifesnap.com/glasses/`
- Relevant endpoints we have observed via MITM:
  - `POST /glasses/encryption/getKeys`
  - `GET  /glasses/device/scanConfig?app=HeyCyan`
  - `POST /glasses/app-update/appLastVersion`
  - `POST /glasses/app-update/last-ota`
  - `POST /glasses/app-update/last-ota/china`
- The OTA metadata call uses a `LastOtaRequest` JSON body:

  ```jsonc
  {
    "appId": 1,
    "country": "US",          // or "CN" for the China endpoint
    "dev": 2,
    "hardwareVersion": "WIFIAM01G1_V9.2",
    "mac": "C4:E3:BF:C3:A4:02",
    "os": 1,
    "romVersion": "WIFIAM01G1_1.00.23_2510111600"
  }
  ```

- The server response shape when **no update is available** (what we see today) is:

  ```json
  { "message": "No upgraded version", "retCode": 60001 }
  ```

  When an update exists, we expect a success `retCode` and a `downloadUrl` pointing at an `.swu` in the `qcwxfactory.oss-cn-beijing.aliyuncs.com` bucket. We have not yet observed such a response for the current glasses firmware.

### Example curl for `last-ota`

> NOTE: Tokens are short-lived account secrets; never commit real tokens.

```bash
curl -v \
  -H 'Content-Type: application/json; charset=UTF-8' \
  -H 'token: <token>' \
  --data '{"appId":1,"country":"US","dev":2,"hardwareVersion":"WIFIAM01G1_V9.2","mac":"C4:E3:BF:C3:A4:02","os":1,"romVersion":"WIFIAM01G1_1.00.23_2510111600"}' \
  'https://www.qlifesnap.com/glasses/app-update/last-ota'
```

On the current firmware this returns:

```json
{"message":"No upgraded version","retCode":60001}
```

Changing `country` (`US` ↔ `CN`) or tweaking `romVersion` (e.g. pretending to be older) did **not** produce a `downloadUrl`, which strongly suggests the backend decides “latest vs. not” based on server‑side state, not the client‑supplied version string.

### OSS bucket and `.swu` downloads

- The official app’s debug code uses a pattern like:

  ```text
  https://qcwxfactory.oss-cn-beijing.aliyuncs.com/bin/glasses/<wifiHwVersion>.swu
  ```

- We attempted:

  ```bash
  curl -L -o WIFIAM01G1_V9.2.swu \
    'https://qcwxfactory.oss-cn-beijing.aliyuncs.com/bin/glasses/WIFIAM01G1_V9.2.swu'
  ```

  and received an XML `AccessDenied` error from OSS (“no right to access this object because of bucket acl”), which means these objects require a signed or otherwise authorized URL coming from the OTA API (`downloadUrl` in a successful `last-ota` response).

- Until the server actually advertises a Wi‑Fi OTA (i.e. `last-ota` returns a success `retCode` plus `downloadUrl`), we cannot legitimately pull a real `.swu` for this hardware from the vendor’s infrastructure.

### MITM workflow for capturing OTA traffic and tokens

- We successfully used **mitmproxy** plus a Magisk module to intercept HeyCyan’s HTTPS traffic:
  - Magisk module: `Always Trust User Certificates` (or any equivalent “trust user CAs as system CAs” module).
  - Install the mitmproxy CA as a **CA certificate**, not as a Wi‑Fi/identity cert:
    - Visit `http://mitm.it/` in the phone browser (with the proxy configured).
    - Download the Android CA and install it under security / trusted credentials.
  - Configure the phone’s Wi‑Fi proxy to point at the PC running mitmproxy:

    ```text
    Proxy host: <PC LAN IP>   # e.g. 192.168.1.50
    Proxy port: 8080
    ```

  - Turn **mobile data off** so HeyCyan can’t bypass via LTE.
  - Run mitmproxy on the PC:

    ```bash
    mitmproxy --listen-port 8080
    ```

  - In mitmproxy, filter for the vendor domain:

    ```text
    f
    ~d qlifesnap.com
    ```

  - Force‑stop HeyCyan, then reopen it and navigate to the About / OTA screen. You should see:
    - `POST /glasses/encryption/getKeys`
    - `GET /glasses/device/scanConfig?app=HeyCyan`
    - `POST /glasses/app-update/last-ota` (and sometimes `/last-ota/china`).

- The `token` header used by Retrofit is visible in these flows. This is the same value that `QcRetrofitClient` injects from `UserConfig.userToken`. Because user data in `/data/data/com.glasssutdio.wear` is stored in encrypted MMKV, MITM is currently the most practical way to retrieve a working token.

#### Pitfalls we hit with MITM

- **Wrong certificate type**:
  - Installing the mitmproxy cert as a *Wi‑Fi* credential causes TLS handshake failures and “no Internet” errors. It must be installed as a CA certificate and trusted system‑wide (with the Magisk module).
- **Mixed app behavior**:
  - Some apps (YouTube, Reddit) still reject the MITM cert and show “no connection”. Chrome and HeyCyan do work once the CA is correctly installed.
- **OTA button not always doing a fresh network call**:
  - The “check for update” UI in HeyCyan can display “already latest” even with all radios off, which suggests it sometimes uses cached state rather than hitting `last-ota` on demand. For clean captures, force‑stop the app before opening the OTA screen.

### Local tooling for future `.swu` analysis

- There is a helper script at the Android repo root:
  - `parse_swu.py`
  - Usage:

    ```bash
    cd android
    python parse_swu.py <firmware>.swu
    ```

  - It:
    - Detects obvious container types (gzip/zip/tar/ELF/squashfs).
    - Warns if the file is actually an XML error (e.g. OSS `AccessDenied`).
    - Scans for chip‑related markers (e.g. `JL7018`, `ALLWINNER`, `V821`) to help distinguish JL7018F vs. Allwinner V821L2 payloads.

Once a real `.swu` is available (via a successful `last-ota` response with `downloadUrl`), future agents should:

1. Download it with curl.
2. Run `file` and `parse_swu.py` on it.
3. Use binwalk / custom scripts to carve out the JL7018F and Allwinner V821L2 images.

### Future firmware‑dump directions (high‑level)

If the cloud OTA API continues to return “No upgraded version” for a long time, there are still a few *local* angles that look promising. Treat the notes below as ideas to explore, not as fully baked procedures:

1. **Leverage “pull‑mode” OTA over Wi‑Fi**
   - The SDK path used by the official app includes calls like `writeIpToSoc("http://<ip>:8080/<firmwareName>", ...)` and helpers such as `startSocOtaServer(...)` in `OTAActivity`.
   - Conceptually, this lets the **phone tell the glasses where to fetch an OTA image over HTTP**, using the existing Wi‑Fi P2P link.
   - A future agent could:
     - Run a small HTTP server on the phone/PC (e.g. `python -m http.server 8080`).
     - Place a known test file (dummy `.swu`) in that directory.
     - Call `writeIpToSoc("http://<phone-ip>:8080/dummy.swu", ...)` from a thin wrapper in the demo app or from a test activity modeled after `OTAActivity`.
     - Observe the incoming HTTP requests from the glasses (range/offset patterns, headers, etc.) to better understand how the SoC expects OTA payloads to be structured.
   - This does **not** depend on the vendor OTA servers and should work even when the cloud reports “already up to date”.

2. **Explore LargeDataHandler / BigData op‑codes for diagnostic dumps**
   - The same SDK namespace (`LargeDataHandler`, `BigData*`, etc.) already streams large media files and OTA state over BLE.
   - Decompiled code shows that there are additional op‑codes beyond the ones used in the sample app (some appear to relate to logs or diagnostics).
   - Future work:
     - Use reflection or static analysis on `glasses_sdk_*.aar` and the decompiled HeyCyan app to catalog `LargeDataHandler` methods and their op‑codes.
     - Compare those to how the official app handles crash logs or internal diagnostics (often gated behind “engineer” / debug UI).
     - If safe, add a narrow Kotlin wrapper in the sample app that invokes *documented* diagnostic/dump methods and writes the resulting bytes to storage on the phone for analysis.
   - Keep changes minimal and well‑logged; avoid blindly poking unknown op‑codes on user hardware.

3. **Hardware‑level investigation (ISP / FEL / debug pads)**
   - The headset uses a JL7018F main controller and an Allwinner V821L2 co‑processor; both families are known (from other products) to expose low‑level USB/boot modes for firmware provisioning.
   - PCB‑level investigation (only for experienced hardware folks, with full awareness of warranty and legal implications):
     - Carefully identify any labelled test pads / boot jumpers on the board (e.g. pads near USB‑C that might correspond to “BOOT”, “FEL”, etc.).
     - With the battery disconnected and ESD precautions in place, verify via `lsusb` whether holding certain buttons or shorting documented pads at power‑on exposes a new USB device (indicating a ROM loader or download mode).
     - If such a mode exists, consult chip‑family documentation and community tooling for **read‑only** flash inspection first (before attempting any writes).
   - This is invasive work; it should be treated as a last resort and documented carefully if attempted. Do **not** assume that methods used on other JL/Allwinner boards are safe here without verification.

## General guidance for future Codex agents

- Treat the vendor SDK (`.aar`) and decompiled code as authoritative for protocol details; keep our glue code thin and well‑logged.
- When something works in the official app but not in the sample:
  - First compare **method sequences and payloads** (what SDK calls, in what order).
  - Then compare **state machines** (when they retry, when they reset, when they treat an error as fatal).
- Always capture and reason from **logcat** before changing code; use the tag set above and keep logs alongside any code changes you make for traceability.

## CyanBridge Vercel Relay Server (carelens-wine.vercel.app)

The CyanBridge app uses a **Vercel-hosted Next.js server** instead of the Termux phone server for:
- Subscription management (Asaas recurring credit card)
- AI model proxying (OpenRouter)
- User authentication and quota tracking

### Server URL

`https://carelens-wine.vercel.app` — hardcoded in `AiProviderPrefs.kt` as `DEFAULT_PUBLIC_RELAY_URL`.

### Endpoints the app calls

| Endpoint | Method | Purpose |
|---|---|---|
| `/web-subscribe` | GET | Checkout flow — renders HTML page with Asaas hosted checkout link |
| `/web-subscribe/success` | GET | Callback after payment — updates user status |
| `/web-subscribe/cancel` | GET/POST | Subscription cancellation page |
| `/pro/verify` | POST | Subscription verification — checks Asaas API |
| `/chat` | POST | AI chat proxy — forwards to OpenRouter |
| `/voice-query` | POST | AI voice proxy |
| `/image-query` | POST | AI image proxy (multimodal) |
| `/models` | GET | Lists available models from OpenRouter |
| `/quota` | GET/POST | Quota info by plan |
| `/pro/quota` | GET/POST | Same, for `/pro/quota` path |
| `/auth/register` | POST | User registration |
| `/auth/me` | GET | User info by Bearer token |
| `/transcribe` | POST | Audio transcription proxy |

### Subscription plans (USD)

| Plan | Price/mo |
|---|---|
| `free_trial` | $0 (30 days) |
| `cheap` | $1 |
| `standard` | $5 |
| `max` | $20 |

### Payment flow (Asaas recurring credit card)

1. App opens `GET /web-subscribe?plan=standard&return_url=...&api_token=...`
2. Server fetches live USD→BRL exchange rate
3. Creates Asaas customer with `foreignCustomer: true` (no CPF/CNPJ required)
4. Creates Asaas subscription with `billingType: "CREDIT_CARD"`, `cycle: "MONTHLY"`
5. If subscription returns `invoiceUrl`, renders HTML page with link to Asaas checkout
6. Otherwise creates a single payment for the first charge to get `invoiceUrl`
7. User clicks "Pay with Credit Card" → enters card on Asaas hosted checkout
8. After payment → Asaas webhook fires → user marked active in Vercel KV
9. App calls `POST /pro/verify` → checks subscription status

### Cancel flow

1. App opens `GET /web-subscribe/cancel?api_token=...`
2. Server shows confirmation page
3. User clicks "Yes, Cancel" → POST to same endpoint
4. Server calls Asaas `DELETE /subscriptions/{id}`
5. Updates user status to inactive in Vercel KV

### OpenRouter integration

- Default model: `deepseek/deepseek-v4-flash`
- API key stored in Vercel env vars
- All chat/voice/image queries proxied through `/chat`, `/voice-query`, `/image-query`

### Key source files (Carelens_website)

- `lib/relay-kv.ts` — RelayUser KV storage layer
- `lib/asaas.ts` — Asaas API client (foreigner customer support)
- `lib/exchange-rate.ts` — USD→BRL rate fetching
- `app/api/web-subscribe/route.ts` — Checkout flow
- `app/api/web-subscribe/cancel/route.ts` — Cancel flow
- `app/api/pro/verify/route.ts` — Subscription verification
- `app/api/chat/route.ts` — AI chat proxy (OpenRouter)
- `app/api/webhooks/asaas/route.ts` — Webhook handler
