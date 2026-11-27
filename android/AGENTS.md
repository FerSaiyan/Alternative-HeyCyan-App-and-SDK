# HeyCyan Smart Glasses (Android) - Data Transfer Notes

This file is the living reference for how CyanBridge transfers media from the glasses to the phone.
It replaces older/incorrect assumptions from early protocol work.

## Repository layout

- `android/CyanBridge/` - Android app (CyanBridge) we modify and ship.
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

## TuneBuds AI Photo Transfer Notes

TuneBuds uses its independent AB Mate Classic Bluetooth RFCOMM protocol, not the HeyCyan vendor AAR. Detailed image capture also uses a phone local-only hotspot and therefore needs the Wi-Fi transport permission flow.

Hardware testing on 2026-09-02 found that the Detailed permission rationale appeared but could not launch Android's permission sheet because `TuneBudsDashboardActionPolicy` rejected `RequestTransportPermission` and `DismissTransportPermissionDialog`. This was an action-gating bug, not a crash or ANR. Keep both actions in the TuneBuds allowlist while Detailed capture is exposed.

The same test produced an incomplete 4,084-byte Clearer image and then lost the next `0xE1` response. The cause matched how the official AB Mate stack behaves:

- `PacketParser` removes `55 AA ... A5 5A` AI envelopes from the shared RFCOMM stream.
- `ResponseHandler` logs top-level sequence gaps but still processes structurally valid command frames.
- CyanBridge must not discard a valid frame solely because its transport sequence skipped. Doing so can lose `0xE3` JPEG chunks and subsequent responses.
- Logical fragment indexes and command/type/total metadata should remain strict.

Hardware verification of the patched BLE and Wi-Fi paths is still pending.

The first patched retest validated Clearer with a 192x144, 8,597-byte JPEG in 4,872 ms. Detailed exposed a second permission bug: Android 13+ successfully granted `NEARBY_WIFI_DEVICES`, but `TuneBudsLocalHotspot` separately required ungranted fine location and stopped before calling `startLocalOnlyHotspot`. Keep its permission check aligned with `PermissionUtil.hasWifiP2pPermission()`: Nearby Wi-Fi on Android 13+, fine location only on older releases. Detailed endpoint/HTTP validation is still pending.

After correcting that gate, the phone hotspot started, but the glasses returned status `1` to `0xE7` on two attempts. The official `startPullFileInternal` maps `0xE7` statuses `1` and `3` to file-transfer `ERROR_BUSY`. Detailed had captured through `0xE1` but never closed the camera before requesting file-manager mode. Ensure recording-mode capture finishes with the `0xE2` busy-saving retry loop before sending hotspot credentials and `0xE7`; an arbitrary save delay alone does not release the camera subsystem.

The next run still returned `0xE7` busy after `0xE2` reached closed state. It also showed that failing immediately tears down `swlan0` within roughly one second, before the glasses can associate. Keep the hotspot alive and retry only `0xE7` statuses `1` and `3` during startup; do not weaken handling for unknown statuses or accept transfer readiness without the `0xE7` endpoint notification.

Hardware validation of that retry succeeded end to end. Three busy responses preceded hotspot association and endpoint `http://10.230.84.141:80/files/`; CyanBridge then downloaded `20250102155119977.JPG`, a valid 1,616x1,216, 350,372-byte JPEG. Total Detailed capture/transfer time was 11,672 ms. Trace: `/tmp/opencode/tunebuds-detailed-e7-busy-retry-retest-20260902.log`.

The same APK passed a Clearer regression with a valid 192x144, 10,221-byte JPEG in 4,349 ms while tolerating three transport-sequence warnings before completion. Trace: `/tmp/opencode/tunebuds-clearer-final-regression-20260902.log`.

### TuneBuds livestream integration (2026-10-03)

- TuneBuds-profile Live preview is exposed on Android 8+ through the shared dashboard/action allowlist and the existing transport/notification permission flow.
- Transport: local-only phone hotspot → AB Mate `0xE6` mode 0 → `0xE8` quality payload → exact device-reported RTSP URL. Busy replies 1/3/5 retry; 6 reports unsupported. Never infer the upstream address from HeyCyan/EyeVue defaults.
- `TuneBudsLiveConnection` feeds the existing `GlassesRtspRelay` at `rtsp://127.0.0.1:8554/live/` with ordinary live PLAY. TuneBuds and EyeVue share `LibVlcLivePreviewManager`; EyeVue retains its rotated view/default caching, while TuneBuds uses stream orientation and 300 ms network/live caching.
- `TuneBudsLiveForegroundService` keeps preview/relay alive behind external VLC. Dashboard/notification Stop, Bluetooth/Wi-Fi failure, and activity release clean up relay/camera/hotspot/player before releasing the `LIVE_PREVIEW` lease. Vendor-AAR BLE events do not clear the independent Classic/SPP session.
- Documentation: `CyanBridge/docs/LIVE_STREAM_INTEGRATION.md`. Focused verification: 38 tests passed (TuneBuds codec/startup/action gate, shared relay/lifecycle, device gating, EyeVue viewport) and debug APK built. No ADB device was connected; TuneBuds hardware playback/latency remains unverified.

### TuneBuds media command re-audit (2026-10-04)

- The complete official `startPullFileInternal` treats `0xE7` statuses **1/3/5** as busy and **4** as low battery. The earlier hardware investigation observed only 1/3; keep the full vendor mapping.
- Ordinary TuneBuds sync now tries an isolated P2P path when Wi-Fi capability bit `0x04` is reported: `0xE6` mode 2 with generated `AiGlassDb_` SSID, empty `0xE7`, `0xEC=2`, six-byte `0xF2`, peer MAC/name matching, WPS PBC, group-owner intent 15 on Android 10+, and HTTP sockets bound to the group Network. The device's `0xE7` URL is the target, never the phone group-owner IP.
- P2P transport failure can fall back to the previously hardware-validated local-only hotspot after camera/group cleanup. Unknown capabilities keep the hotspot path. Glasses-hosted AP mode 1 is still not implemented.
- HTTP uses the vendor's omitted-port fallback 8080, preserves explicit port 80/path prefixes, strips accidental manifest headers before JSON detection, and sends `Connection: close` / `TSClient/1.0` for all media. Stop interrupts blocked reads and removes incomplete files; progress counts distinguish photos/videos/audio.
- Manual saved photos now finish with the existing saving-aware `0xE2` loop; dashboard multi-step commands are serialized and closed `0xE2` notifications clear recording flags. New P2P and manual-photo cleanup changes need a hardware retest; no ADB device was connected during the audit.

## Shared photo ownership (2026-10-04)

- HeyCyan, EyeVue and TuneBuds AI-photo acquisition share Android
  `GlassesPhotoCapture` and device-tagged `GlassesSessionCoordinator` permits.
  Use `GlassesAiPhotoCapture` for selected-device consumers, or the native
  manager's `capturePhotoForAi()`; never acquire an image from a broadcast
  `photos.first()` or `aiPhotos.first()` subscription.
- Live, Walking Aid periodic photos and visual notes all use the selected-device
  adapter. Native push streams have a command-plus-image deadline, bounded JPEG
  buffers, complete-image validation and noncancellable late-packet draining.
  Native manual AI streams reserve a receive-only owner at stream start and
  expose acquisition outcomes to Live without another shutter.
- Pass the owning device when clearing permits on disconnect. Vendor-AAR events
  cannot release an EyeVue/TuneBuds background capture. Native disconnect cancels
  its own capture; release happens after cleanup.
- Implementation, protocol limitations and hardware acceptance:
  `CyanBridge/docs/UNIFIED_GLASSES_PHOTO_CAPTURE.md`. The website diagnostics
  endpoint is deployed; hardware retesting of this capture extension is pending.

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

## Compose Multiplatform (CMP)

The `:shared` module uses JetBrains Compose Multiplatform so both Android and iOS render from the same `@Composable` screens in `commonMain`.

### UI rule: Material 3 Expressive everywhere, including every popup

- Every screen and every popup is Compose Material 3 in the app theme (`CyanBridgeExpressiveTheme`, via
  `CyanBridgeTheme` on Android): dialogs, confirmations, lists, pickers, text prompts, date pickers.
- No View-based dialogs: no `AlertDialog.Builder` (AppCompat or framework), `MaterialAlertDialogBuilder`,
  `android.app.DatePickerDialog` / `TimePickerDialog` / `ProgressDialog`. `ArchitectureGuardrailSourceTest
  .popupsAreMaterial3ComposeDialogs` fails the build on any of them.
- From Activity or coordinator code use `ui/dialog/ExpressiveDialogs` (`alert`, `list`, `singleChoice`, `textInput`,
  or `show { close -> ExpressiveAlert(...) }` for custom bodies). Inside Compose use material3 `AlertDialog`,
  `DatePickerDialog`, etc. directly. Buttons: `TextButton(..., shapes = ButtonDefaults.shapes())`, touch targets
  at least 48 dp.

### Key Facts

- **CMP Version**: 1.13.0-rc01 with Material 3 1.5 (Expressive), Kotlin 2.4.21, AGP 9.3.3, compileSdk 37.1 (since 2.5.0; bump to the stable 1.13.0 / material3 1.5.0 when published).
- **Material 3**: Uses `org.jetbrains.compose.material3` as Maven coordinate but `import androidx.compose.*` in Kotlin sources (same API surface as Jetpack Compose). All migrated screen files keep `import androidx.compose.*` — do NOT use `import org.jetbrains.compose.*`.
- **iOS Framework**: All iOS targets use a dynamic framework (`isStatic = false`). A static device framework gets embedded in `Frameworks/` by `embedAndSignAppleFrameworkForXcode` and the iPhone rejects the install.
- **Skiko**: CMP's rendering layer (Skia). Ships as `.dylib` for simulators, `.a` for device. The JetBrains Compose Maven repository hosts Skiko native binaries.
- **Test dependency**: `compose.uiTest` requires `@OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)` on the `commonTest.dependencies` block. CMP UI tests (`runComposeUiTest`) need a rendering backend — they cannot run on the JVM "portability" target. Write pure state/logic tests for `commonTest` and use Android instrumentation tests for full Compose UI testing.

### Build Commands

```bash
# Build shared framework for iOS simulator (dynamic)
JAVA_HOME=/opt/android-studio/jbr ./gradlew -PenableAppleTargets=true :shared:linkDebugFrameworkIosSimulatorArm64

# Build shared framework for iOS device (dynamic)
JAVA_HOME=/opt/android-studio/jbr ./gradlew -PenableAppleTargets=true :shared:linkDebugFrameworkIosArm64

# Run shared portability tests
JAVA_HOME=/opt/android-studio/jbr ./gradlew :shared:portabilityTest

# Build Android app (uses shared CMP composables)
JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:assembleDebug
```

### iOS Host Architecture

The iOS host (`CyanBridgeKMPHost`) embeds a `ComposeUIViewController` via `UIViewControllerRepresentable`. The `MainViewController()` function is exported from the Kotlin/Native framework. Both platforms call the same shared composables from `shared/commonMain`.

### Kotlin Upgrade (Completed)

Kotlin 1.9.24 → 2.3.10 is complete. Changes made:
- AGP 8.12.1 unchanged.
- KAPT → KSP migration for Room (`room.schemaLocation` annotation processor arg removed; Room 2.7.0 with KSP).
- `-Xskip-metadata-version-check` kept for vendor AAR compatibility.
- Compose BOM 2024.04.01 → 2025.06.01.
- `kotlin.plugin.compose` replaces `composeOptions { kotlinCompilerExtensionVersion }`.
- Room 2.6.1 → 2.7.0; coroutines 1.7.3 → 1.10.1.
- K2 compiler fix: explicit `Array<Any>` needed in `MemoryChunkDao` queries.

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

## Hardware simulators

End-to-end simulator lanes for the glasses families are maintained outside this repository; do not add simulator
code, firmware images or their notes here.

Rules for every emulator lane:
- One lane at a time. Never run an emulator while a Gradle build runs (emulator + large builds have OOM-killed
  other work).
- Use the existing AVDs only, as `-read-only` instances. Never create AVDs; the disk is nearly full.
- Emulator >= 36.5 (Wi-Fi Direct between AVDs needs the netsim Wi-Fi medium).
- Use emulator ports 5555-5585: adb rediscovers only that range after a server restart.
- The AVD logcat ring rotates within seconds under SDK logging: stream `adb logcat` to a file and grep it with `-a`.
- Killing the app while one of its GATT requests is still outstanding, on a link that stays up, can wedge Android's
  GATT client for that device until Bluetooth is toggled. Wait for the link to go idle before a force-stop in tests.

## General guidance for future Codex agents

- Treat the vendor SDK (`.aar`) as authoritative for protocol details; keep our glue code thin and well‑logged.
- When something works in the official app but not in the sample:
  - First compare **method sequences and payloads** (what SDK calls, in what order).
  - Then compare **state machines** (when they retry, when they reset, when they treat an error as fatal).
- Always capture and reason from **logcat** before changing code; use the tag set above and keep logs alongside any code changes you make for traceability.

## CyanBridge Vercel Relay (cyanbridge.vercel.app)

The CyanBridge app uses a Vercel-hosted Next.js server for:
- Subscription management (Asaas recurring credit card)
- AI model proxying (OpenRouter)
- User authentication and quota tracking

### Server URL

`https://cyanbridge.vercel.app` — hardcoded in `AiProviderPrefs.kt` as `DEFAULT_PUBLIC_RELAY_URL`.

### Endpoints the app calls

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/billing/catalog` | GET | Public Asaas/Paddle provider offers used for price disclosure |
| `/api/billing/checkout-sessions` | POST | Authenticated provider-specific checkout-session creation |
| `/web-subscribe` | GET | Opaque checkout-session page; requires `checkout_session` |
| `/web-subscribe/status` | GET | Checks the selected Asaas or Paddle subscription state |
| `/web-subscribe/success` | GET | Callback after payment confirmation — updates user status |
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
| `/logs/submit` | POST | Stores an Android diagnostic report and sends an email notification |
| `/api/admin/debug-logs` | GET | Admin-only JSON for the newest 50 diagnostic reports |

### Subscription plans (USD)

| Plan | Price/mo |
|---|---|
| `free_trial` | $0 (30 days) |
| `cheap` | $1 |
| `standard` | $5 |
| `max` | $20 |

### Payment flow (Asaas or Paddle)

1. App lets the user choose Asaas or Paddle and verifies the account email.
2. App posts `{ plan, provider, return_url, change_plan }` to `/api/billing/checkout-sessions` with the account bearer token.
3. Server returns a short-lived opaque `/web-subscribe?checkout_session=...` URL. The browser never receives the bearer token.
4. The session page renders the Asaas BRL card form or redirects to the Paddle checkout experience for the chosen provider.
5. Provider confirmation and webhooks update the RelayUser subscription state.
6. The server returns through the verified app link with only the one-time opaque result.
7. App calls `POST /pro/verify` to fetch the authoritative entitlement.

### Cancel flow

1. App posts the stored account token to `/web-subscribe/cancel`.
2. Server cancels the selected Asaas or Paddle subscription at the next billing period.
3. Server updates the RelayUser status in Vercel KV.

### OpenRouter integration

- Default model: `deepseek/deepseek-v4-flash`
- API key stored in Vercel env vars
- All chat/voice/image queries proxied through `/chat`, `/voice-query`, `/image-query`

### Server source

The server is maintained in a separate repository at:

`/home/fertroll10/Documents/CyanBridgeLabs/Cyanbridge_website`

Do not add Flask, Termux, or staged Vercel wrappers under `android/CyanBridge`.

### Key source files

- `lib/relay-kv.ts` — RelayUser KV storage layer
- `lib/billing-catalog.ts` — canonical provider-aware plan and price catalog
- `lib/asaas.ts` — Asaas API client (foreigner customer support)
- `lib/exchange-rate.ts` — USD→BRL rate fetching
- `app/api/billing/checkout-sessions/route.ts` — authenticated checkout-session creation
- `app/api/web-subscribe/route.ts` — Checkout flow
- `app/api/web-subscribe/cancel/route.ts` — Cancel flow
- `app/api/pro/verify/route.ts` — Subscription verification
- `app/api/chat/route.ts` — AI chat proxy (OpenRouter)
- `app/api/webhooks/asaas/route.ts` — Webhook handler
- `app/logs/submit/route.ts` — Android diagnostic-log ingestion
- `lib/debug-log-store.ts` — recent-log Supabase PostgreSQL storage and retention

## Agent guardrails / lessons learned

### Untracked UI files can break Android builds

If a previous task left local Compose / Material 3 UI files in the working tree, Gradle may still pick them up even if they are **not committed to the current branch**.

Typical symptoms:

- `kaptDebugKotlin` fails
- `NonExistentClass cannot be converted to Annotation`
- failing stubs under:
  - `app/build/tmp/kapt3/stubs/debug/.../VersionUpdateDialogKt.java`
  - `app/build/tmp/kapt3/stubs/debug/.../FeatureOnboardingScreenKt.java`
  - `app/build/tmp/kapt3/stubs/debug/.../PatchEditorScreenKt.java`
  - `app/build/tmp/kapt3/stubs/debug/.../PatchListScreenKt.java`
  - `app/build/tmp/kapt3/stubs/debug/.../PublishPluginScreenKt.java`
  - `app/build/tmp/kapt3/stubs/debug/.../SimulatedGlassesPreviewKt.java`

Before changing protocol code or blaming Compose migration branches, check for untracked local folders first.

### What to check first

```bash
git status --short
```

Look for untracked directories such as:

- `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/ui/components/`
- `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/ui/glasses/`
- `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/ui/onboarding/`
- `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/ui/plugins/`

If they are marked with `??` and are not part of the current branch, treat them as suspect.

### Current branch strategy for Material 3

Material 3 / Compose migration work is intentionally kept on separate branches:

- `compose_material3_migration`
- `memomind-adapter`

`main` should stay on the current production UI approach unless the migration is deliberately merged.

### Safe backup location

To avoid losing that progress, back up the local Compose UI files to this directory before cleaning the working tree:

```text
/home/fertroll10/Documents/ML/HeyCyanSmartGlassesSDK/backups/compose_material3_port/
```

That directory preserves the local UI work that was removed from the active `main` working tree.

### Recovery workflow

When the Material 3 migration becomes the active priority again:

1. Restore from the safe backup first, or check out the dedicated branch:
   ```bash
   git checkout compose_material3_migration
   ```
2. Resolve the Compose / KAPT dependency setup intentionally on the migration branch.
3. Do not re-introduce those files into `main` unless the full UI migration is being merged.

## Current Session (July 29 2026)

### Objective
- Complete billing checkout‑session API and provider‑choice dialog (core work done).
- Fix image‑sync breakage introduced by Eyevue commits; cherry‑pick working image‑question flow improvements from the reflog.

### Key Facts
- Billing commit `0044185` is pushed to `origin/compose-material3-kmp-v2`.
- Eyevue commits (`1093f20`, `fcf4e3f`) added parallel audio recording, `onReplySpoken`, `offerSpokenQuestion` — all of which fixed image sync. They also added Eyevue‑protocol, Gemini‑Live‑routing, and TTS‑streaming classes which broke the data‑transfer flow.
- Solving approach: branch from `fcf4e3f` (known‑working commit) and surgically strip Eyevue‑protocol, `GeminiLiveImageButtonRouter`, and `StreamingSpeechSessionManager` references. Keep the image‑question improvements untouched.
- **temp-image-fix branch**: all Eyevue/TTS‑streaming removals done. `:app:compileDebugKotlin` passes.

### Completed
- Billing checkout‑session API & provider‑choice dialog: pushed.
- `ImageQuestionPrompt.kt`: `questionCueForLanguage()` added.
- MainActivity.kt: removed all Eyevue imports, `GeminiLiveImageButtonRouter`, `StreamingSpeechSessionManager.attachTtsEngine`, `downloadEyevueMediaList()`, Eyevue `startDataDownload` / `sendExitTransferModeIfRequested` intercepts, `0x02` notification‑handler Eyevue intercept, `onToken`/`onTokenDelta` streaming‑TTS path. `onReplySpoken` kept (pure image‑question flow). Dashboard‑action no‑ops added for 4 Eyevue‑sourced actions.
- `DeviceClass.kt`: `EYEVUE` enum value removed (already staged).
- `GlassesManagerGating.kt`: `DeviceClass.EYEVUE` reference removed.
- `assetlinks.json` created at `android/Carelens/website/public/.well-known/assetlinks.json`. **Not deployed**.
- `PRO_*` properties defined in `app/build.gradle`. **Not verified** for signed builds.

### Next Move
1. Sideload APK (`./gradlew assembleDebug`) to Samsung `RQCX700KSDF` and test image sync.
2. If image sync works, fold changes back onto `compose-material3-kmp-v2` via `git rebase --onto` or cherry‑pick of the cleaned diff.
3. Then add Eyevue protocol back → locate the break.
4. Then add TTS streaming back.
5. Deploy `/.well-known/assetlinks.json` to cyanbridge.vercel.app.
6. Confirm PRO_* Gradle properties for signed release build.
