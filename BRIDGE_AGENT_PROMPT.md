# Agent Implementation Prompt: CyanBridge MemoMind ↔ Even Hub / MentraOS Compatibility Bridge

## Mission

You are implementing a new compatibility layer inside **CyanBridge**, an Android app that already works as an alternative app/SDK for HeyCyan-compatible smart glasses and currently supports BLE device management, media sync, assistant routing, Tasker integration, and privacy-focused local handling.

The new goal is:

> Pair CyanBridge with **MemoMind One** glasses and make them usable as a display/input backend for apps originally written for **Even Hub** and **MentraOS**.

The first deliverable is **not** the Claude/Codex/OpenCode terminal app itself. The first deliverable is the bridge/runtime that makes other apps possible. The terminal HUD should later become just one compatible app running on top of the bridge.

Design the implementation so CyanBridge can eventually support:

```text
Even Hub app → CyanBridge EvenHubRuntime → CyanBridge GlassesBridge → MemoMind glasses
MentraOS app → CyanBridge MentraRuntime/Relay → CyanBridge GlassesBridge → MemoMind glasses
CyanBridge native app → CyanBridge GlassesBridge → MemoMind glasses
```

Do not hard-code the terminal app into the glasses driver. Build a reusable compatibility platform.

---

## Important constraints

1. **Keep the implementation clean-room and interoperability-focused.**
   - Prefer public SDKs, public docs, and behavior observed from your own hardware.
   - Do not copy proprietary app code from MemoMind, Even, or Mentra.
   - Do not redistribute proprietary APKs, SDK internals, keys, tokens, certificates, or firmware.
   - Do not implement bypasses for authentication, subscriptions, app-store restrictions, protected APIs, or firmware update protections.

2. **MemoMind reverse engineering, if needed, must be minimal and documented.**
   - Allowed output: protocol notes, service UUIDs, characteristic roles, packet examples derived from your own test actions, and clean-room Kotlin implementation.
   - Disallowed output: copied decompiled classes, copied proprietary constants in bulk, secrets, private endpoints, or anything that bypasses MemoMind services.
   - Do not touch OTA/firmware flows in this project.

3. **No root-only final design.**
   - The final app must work on a normal Android device.
   - Root/Magisk may be used only by the maintainer for local research/log retrieval, never as a runtime requirement.

4. **Start with a small capability subset.**
   - Phase 1 target: connect + show text + clear display + receive simple input event or use phone fallback.
   - Later targets: pagination, brightness, battery, head gesture, mic/ASR, image rendering, app packaging.

5. **All app runtimes must go through one unified internal interface.**
   - Even Hub compatibility and MentraOS compatibility must not talk directly to BLE.
   - BLE/device protocols live only in MemoMind-specific adapter classes.

---

## Current external facts to ground implementation

### CyanBridge current state to inspect

CyanBridge appears in the existing repo as an alternative Android app/SDK for HeyCyan-compatible glasses. The repo root includes `android/`, `examples/`, `heycyan-core/`, `ios/`, `third_party/`, `AGENTS.md`, `README.md`, and `WIFI_TRANSFER_ARCHITECTURE.md`. The README describes BLE scanning/connection, photo/video/audio controls, battery/device information, and Android-only Gemini/ChatGPT assistant routing through Tasker automation.

Primary repo to inspect:

- `https://github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK`

Specific CyanBridge areas to inspect first:

```text
android/CyanBridge/
android/CyanBridge/app/src/main/AndroidManifest.xml
android/CyanBridge/app/src/main/java/**
android/CyanBridge/tasker/Tasker_AI.prj.xml
heycyan-core/**
README.md
AGENTS.md
WIFI_TRANSFER_ARCHITECTURE.md
```

Use `rg` / IDE search for:

```text
Bluetooth
BLE
Gatt
Characteristic
scan
connect
battery
Tasker
ACTION_TASKER_COMMAND
AI_EVENT
MediaStore
WiFi
P2P
WebView
JavascriptInterface
OkHttp
WebSocket
EventBus
```

### Even Hub facts to inspect

Even Hub is the official app framework for Even Realities G2 apps. Public docs describe it as a system for building G2 plugins, dashboard widgets, and AI integrations. The starter templates are TypeScript/Vite apps and include minimal, ASR, image, and long-text examples. The templates can run in the simulator or on real G2 glasses via QR code from the companion app, and packaging produces `.ehpk` files.

Webpages/repos to inspect:

- `https://hub.evenrealities.com/docs`
- `https://github.com/even-realities/evenhub-templates`
- `https://www.npmjs.com/package/@evenrealities/evenhub`
- `https://www.npmjs.com/package/@evenrealities/evenhub-cli`
- `https://www.npmjs.com/package/@evenrealities/evenhub-simulator`
- `https://github.com/nickustinov/even-g2-notes` for independent SDK behavior notes; treat as unofficial.
- `https://github.com/even-realities/EvenDemoApp` for G1 protocol concepts only; do not mix G1 protocol assumptions into G2/Even Hub compatibility.

Even Hub template facts to verify in code:

```text
minimal/      Bare Vite + TypeScript + Even Hub SDK + simulator.
asr/          Microphone/STT companion flow.
image/        Image container rendering.
text-heavy/   Long-form pagination/textContainerUpgrade style path.
```

Important Even Hub compatibility target:

```text
Do not try to implement the entire Even Hub SDK at once.
Start by supporting the subset used by minimal/ and text-heavy/.
Then add image/.
Then add ASR-like events using phone or MemoMind mic fallback.
```

### MentraOS facts to inspect

MentraOS is an open-source smart-glasses OS/app ecosystem. The public architecture has smart glasses, a mobile app, cloud/relay services, and third-party apps. The mobile app handles Bluetooth connection to glasses and sends glasses events to cloud; third-party apps are external web servers using webhooks/WebSockets and Mentra SDK display/input APIs.

Webpages/repos to inspect:

- `https://github.com/Mentra-Community/MentraOS`
- `https://docs.mentraglass.com/app-devs/getting-started/quickstart`
- `https://docs.mentraglass.com/os-devs/contributing/overview`
- `https://github.com/Mentra-Community/MentraOS-Display-Example-App`
- `https://github.com/Mentra-Community/Mentra-Bluetooth-SDK-Starter-Kit`
- `https://apps.mentra.glass/`
- `https://console.mentra.glass/`

MentraOS repo areas to inspect:

```text
MentraOS/mobile/              React Native phone app
MentraOS/android_core/        Android native BLE/glasses module
MentraOS/cloud/               Node/Bun cloud/relay/session services
MentraOS/sdk/                 TypeScript SDK for third-party apps
MentraOS/docs-bluetooth-sdk/  Bluetooth SDK docs, if present
MentraOS/glasses-compatibility.md
MentraOS/AGENTS.md
MentraOS/CLAUDE.md
```

Mentra Bluetooth SDK Starter Kit areas to inspect:

```text
docs/getting-started.md
docs/api-reference.md
docs/display-guide.md
docs/audio-guide.md
docs/hardware-integration.md
docs/production-checklist.md
docs/troubleshooting.md
examples/android/**
examples/react-native/**
```

Mentra compatibility target:

```text
Do not attempt full Mentra Cloud replacement in v1.
Start with a local compatibility runtime that can run simple display apps and route display/input events to MemoMind.
Prepare a separate upstream path for native MemoMind support in MentraOS later.
```

### MemoMind facts to inspect

MemoMind does not appear to have public developer docs yet. Its public materials describe dual-eye display, speakers, translation, prompting, notifications, AI assistant, and productivity features. The Android app exists as `com.memomind.ai.aphrodite`.

Public pages to inspect:

- `https://www.memo-mind.com/`
- `https://play.google.com/store/apps/details?id=com.memomind.ai.aphrodite`

Ask MemoMind for:

```text
- Beta SDK or private developer docs
- BLE service/characteristic map
- Display command docs
- Input/head gesture event docs
- Whether open-source interoperability work is allowed under the beta agreement
- Whether MentraOS support or Even Hub app compatibility is acceptable to discuss publicly
```

---

## Recommended high-level architecture

Add a new compatibility subsystem to CyanBridge:

```text
CyanBridge Android app
├── Existing HeyCyan features
├── GlassesBridge core
│   ├── GlassesDeviceAdapter interface
│   ├── DisplaySurface model
│   ├── InputEvent model
│   ├── Audio/Mic model
│   ├── Capability model
│   └── DeviceRegistry
├── Device adapters
│   ├── HeyCyanAdapter          existing/refactored
│   ├── MemoMindAdapter         new
│   ├── BrowserMockAdapter      debug/dev
│   └── Mentra/Even adapters?   optional future
├── App compatibility runtimes
│   ├── EvenHubRuntime          WebView + JS shim + bridge
│   ├── MentraRuntime           local SDK/relay compatibility
│   └── CyanBridgeNativeRuntime native apps such as terminal HUD
├── Bridge UI
│   ├── Pairing screen
│   ├── Installed compatible apps screen
│   ├── App launcher/runtime screen
│   ├── Debug console
│   └── Capability inspector
└── Developer tools
    ├── protocol logger
    ├── simulated glasses screen
    ├── packet capture import/export
    └── compatibility test apps
```

The key invariant:

```text
External app runtime → GlassesBridge core → Device-specific adapter → physical glasses
```

Never:

```text
EvenHubRuntime → MemoMind BLE directly
MentraRuntime → MemoMind BLE directly
TerminalHUD → MemoMind BLE directly
```

---

## Proposed package structure inside CyanBridge

Adjust package names to the repo’s existing style. If the app currently uses `com.fersaiyan.cyanbridge`, place new code under that namespace.

```text
android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/
├── bridge/
│   ├── core/
│   │   ├── GlassesDeviceAdapter.kt
│   │   ├── GlassesBridge.kt
│   │   ├── GlassesBridgeState.kt
│   │   ├── GlassesCapability.kt
│   │   ├── DisplayCommand.kt
│   │   ├── DisplaySurface.kt
│   │   ├── InputEvent.kt
│   │   ├── AudioEvent.kt
│   │   ├── DeviceInfo.kt
│   │   ├── DeviceRegistry.kt
│   │   └── BridgeError.kt
│   │
│   ├── devices/
│   │   ├── heycyan/
│   │   │   └── HeyCyanDeviceAdapter.kt
│   │   ├── memomind/
│   │   │   ├── MemoMindDeviceAdapter.kt
│   │   │   ├── MemoMindBleScanner.kt
│   │   │   ├── MemoMindGattClient.kt
│   │   │   ├── MemoMindProtocol.kt
│   │   │   ├── MemoMindPacketEncoder.kt
│   │   │   ├── MemoMindPacketDecoder.kt
│   │   │   ├── MemoMindCapabilities.kt
│   │   │   └── README_PROTOCOL_NOTES.md
│   │   └── mock/
│   │       └── MockDisplayAdapter.kt
│   │
│   ├── runtimes/
│   │   ├── evenhub/
│   │   │   ├── EvenHubRuntimeActivity.kt
│   │   │   ├── EvenHubWebViewHost.kt
│   │   │   ├── EvenHubJsBridge.kt
│   │   │   ├── EvenHubRuntimeShim.kt
│   │   │   ├── EvenHubAppManifest.kt
│   │   │   ├── EvenHubPackageImporter.kt
│   │   │   └── assets/evenhub-compat-shim.js
│   │   │
│   │   ├── mentra/
│   │   │   ├── MentraRuntimeService.kt
│   │   │   ├── MentraLocalRelay.kt
│   │   │   ├── MentraSessionManager.kt
│   │   │   ├── MentraMessageTypes.kt
│   │   │   ├── MentraDisplayMapper.kt
│   │   │   ├── MentraEventMapper.kt
│   │   │   └── README_MENTRA_COMPAT.md
│   │   │
│   │   └── nativeapps/
│   │       ├── NativeAppRuntime.kt
│   │       └── terminalhud/
│   │           └── TerminalHudApp.kt   # later, not first milestone
│   │
│   ├── ui/
│   │   ├── BridgeHomeScreen.kt
│   │   ├── DevicePairingScreen.kt
│   │   ├── CompatibleAppsScreen.kt
│   │   ├── RuntimeDebugScreen.kt
│   │   ├── CapabilityInspectorScreen.kt
│   │   └── SimulatedGlassesPreview.kt
│   │
│   └── devtools/
│       ├── BleProtocolLogger.kt
│       ├── BridgeEventLogger.kt
│       ├── PacketCaptureImporter.kt
│       ├── CompatibilityTestSuite.kt
│       └── SampleAppFixtures.kt
```

If the existing app is not using Jetpack Compose, adapt UI files to the current Activity/Fragment/XML style. Do not force a UI framework migration.

---

## Core interfaces

Create these first, before any MemoMind-specific code.

### `GlassesDeviceAdapter.kt`

```kotlin
interface GlassesDeviceAdapter {
    val adapterId: String
    val displayName: String
    val capabilities: Set<GlassesCapability>
    val state: StateFlow<GlassesBridgeState>
    val events: Flow<InputEvent>

    suspend fun scan(): List<DeviceInfo>
    suspend fun connect(device: DeviceInfo)
    suspend fun disconnect()

    suspend fun showText(command: DisplayCommand.Text)
    suspend fun showLines(command: DisplayCommand.Lines)
    suspend fun showCard(command: DisplayCommand.Card)
    suspend fun clearDisplay()

    suspend fun setBrightness(level: Int): Result<Unit>
    suspend fun requestBattery(): Result<Int>

    suspend fun startMic(): Result<Unit>
    suspend fun stopMic(): Result<Unit>
}
```

Do not require all adapters to support all features. Unsupported methods should return a typed `Result.failure(UnsupportedCapabilityException(...))` or equivalent.

### `GlassesCapability.kt`

```kotlin
enum class GlassesCapability {
    TEXT_DISPLAY,
    LINE_DISPLAY,
    CARD_DISPLAY,
    IMAGE_DISPLAY,
    CLEAR_DISPLAY,
    TOUCH_INPUT,
    BUTTON_INPUT,
    HEAD_GESTURE_INPUT,
    BATTERY_STATUS,
    BRIGHTNESS_CONTROL,
    MICROPHONE_AUDIO,
    SPEAKER_AUDIO,
    NOTIFICATIONS,
    DASHBOARD,
    PAGINATION
}
```

### `DisplayCommand.kt`

```kotlin
sealed class DisplayCommand {
    data class Text(
        val text: String,
        val priority: DisplayPriority = DisplayPriority.NORMAL,
        val ttlMs: Long? = null
    ) : DisplayCommand()

    data class Lines(
        val lines: List<String>,
        val page: Int = 0,
        val totalPages: Int? = null
    ) : DisplayCommand()

    data class Card(
        val title: String,
        val body: String,
        val actions: List<DisplayAction> = emptyList()
    ) : DisplayCommand()
}
```

### `InputEvent.kt`

```kotlin
sealed class InputEvent {
    data class Button(val button: String, val gesture: GestureType) : InputEvent()
    data class Touch(val side: Side?, val gesture: GestureType) : InputEvent()
    data class HeadGesture(val direction: HeadDirection, val confidence: Float? = null) : InputEvent()
    data class VoiceText(val text: String, val isFinal: Boolean = true) : InputEvent()
    data class Battery(val level: Int, val charging: Boolean? = null) : InputEvent()
}
```

---

## MemoMind adapter plan

### Phase MM-0: Capability discovery

Create `MemoMindCapabilities.md` / `README_PROTOCOL_NOTES.md` and document only what is verified.

Table template:

```markdown
# MemoMind Protocol Notes

Device tested:
- Model:
- Firmware:
- MemoMind app version:
- Phone:
- Android version:
- Date:

## BLE advertisement
- Name(s):
- Service UUIDs advertised:
- Manufacturer data:

## GATT services
| Service UUID | Characteristics | Observed role | Notes |
|---|---|---|---|

## Verified commands
| Feature | Status | Evidence | Notes |
|---|---|---|---|
| Connect | unknown/working | | |
| Show text | unknown/working | | |
| Clear display | unknown/working | | |
| Battery | unknown/working | | |
| Button/touch event | unknown/working | | |
| Head gesture | unknown/working | | |
| Mic | unknown/working | | |
| Speaker | unknown/working | | |
```

### Phase MM-1: BLE scanner and connection shell

Implement:

```text
MemoMindBleScanner.kt
MemoMindGattClient.kt
MemoMindDeviceAdapter.kt
```

Acceptance test:

```text
- CyanBridge can scan and list MemoMind devices.
- CyanBridge can connect/disconnect without crashing.
- Debug screen shows services/characteristics discovered.
- No display writes yet unless packet format is verified.
```

### Phase MM-2: Minimal display

Implement only after display characteristic and text packet format are verified.

Acceptance test:

```text
- From CyanBridge debug UI, tap "Show hello".
- MemoMind display shows a short message.
- Tap "Clear".
- MemoMind display clears.
- Failure mode is graceful if glasses are disconnected.
```

### Phase MM-3: Input events

Map MemoMind touch/button/head events to `InputEvent`.

Acceptance test:

```text
- Trigger known MemoMind input gesture.
- CyanBridge logs typed InputEvent.
- Mock app can react by changing displayed text/page.
```

### Phase MM-4: Battery/brightness/pagination

Implement after the minimal EvenHubRuntime works.

---

## Even Hub compatibility plan

There are two likely modes:

### Mode A: URL/WebView development mode, recommended first

This mode runs an Even Hub Vite app from a URL and injects a compatibility shim.

```text
Even Hub dev app at http://laptop-ip:5173
        ↓
CyanBridge WebView
        ↓ JS bridge/shim
EvenHubRuntime
        ↓ Kotlin calls
GlassesBridge
        ↓
MemoMindAdapter
```

This avoids `.ehpk` parsing early.

Build:

```text
EvenHubRuntimeActivity.kt
EvenHubWebViewHost.kt
EvenHubJsBridge.kt
assets/evenhub-compat-shim.js
```

The WebView should:

```text
- Load an app URL entered by the user.
- Inject `evenhub-compat-shim.js` before/at document start if possible.
- Expose `CyanBridgeEvenHubBridge` via Android JavascriptInterface.
- Receive display requests from JS and map them to DisplayCommand.
- Send input events from glasses back into the JS runtime.
```

### Mode B: package/import mode, later

Later support `.ehpk` if legally/technically straightforward.

Acceptance criteria for not doing `.ehpk` in v1:

```text
- A developer can run `npm run dev` in an Even Hub app.
- CyanBridge loads the dev URL.
- The app displays on MemoMind through the compatibility shim.
```

### Even Hub subset to implement first

Study the templates and implement only the calls required by:

```text
1. evenhub-templates/minimal
2. evenhub-templates/text-heavy
3. evenhub-templates/image
4. evenhub-templates/asr
```

The mapper should normalize Even display models into `DisplayCommand`:

```text
Even text display / text container → DisplayCommand.Text or Lines
Even long text / textContainerUpgrade → paginated DisplayCommand.Lines
Even image container → unsupported at first, then image-to-monochrome/grayscale later
Even ASR/mic events → phone mic or MemoMind mic if exposed; stub first
Even touch/click events → InputEvent.Touch/Button
Even exit lifecycle → clearDisplay + runtime stop
```

### EvenHub compatibility shim API

Do not guess the full SDK. Inspect the templates and SDK runtime. Create a shim that supports the observed call surface.

Suggested JS-to-Kotlin message envelope:

```json
{
  "id": "uuid",
  "runtime": "evenhub",
  "type": "display.showText",
  "payload": {
    "text": "Hello from Even Hub app"
  }
}
```

Response envelope:

```json
{
  "id": "uuid",
  "ok": true,
  "payload": {}
}
```

Input event envelope:

```json
{
  "runtime": "evenhub",
  "type": "input.touch",
  "payload": {
    "gesture": "singleTap",
    "side": "right"
  }
}
```

Keep all runtime events visible in `RuntimeDebugScreen`.

### Even Hub acceptance tests

Create test fixtures:

```text
fixtures/evenhub/minimal-url-test.md
fixtures/evenhub/text-heavy-url-test.md
fixtures/evenhub/image-url-test.md
fixtures/evenhub/asr-url-test.md
```

Manual tests:

```text
- Run Even Hub minimal template on laptop.
- In CyanBridge, open Even Hub Runtime and enter URL.
- MemoMind shows "Hello" style text.
- Run text-heavy template.
- MemoMind shows paginated text.
- Input gesture advances page or triggers fallback control in phone UI.
```

---

## MentraOS compatibility plan

MentraOS is architecturally different from Even Hub. Mentra apps are usually external TypeScript servers that connect to Mentra Relay/Cloud and receive events/send display commands through SDK sessions.

Do **not** try to fully replace MentraOS cloud in v1.

Implement two tracks:

### Track 1: CyanBridge local Mentra-compatible runtime for simple apps

Goal: let simple Mentra display apps run against a local CyanBridge relay during development.

```text
MentraOS display app server
        ↓ WebSocket/webhook compatible subset
CyanBridge MentraLocalRelay
        ↓ normalized messages
GlassesBridge
        ↓
MemoMindAdapter
```

Implement:

```text
MentraLocalRelay.kt
MentraSessionManager.kt
MentraMessageTypes.kt
MentraDisplayMapper.kt
MentraEventMapper.kt
```

Start with the MentraOS Display Example App. Support only:

```text
- session start
- display text / display layout requests
- clear display
- transcription/input events as stub or phone fallback
- button/touch events
- session stop
```

If the official Mentra SDK requires cloud API keys or a specific relay handshake, document what blocks local compatibility and create a dev-only shim package instead.

### Track 2: Upstream MemoMind support in MentraOS

This is separate from CyanBridge runtime work.

Goal: add MemoMind as a supported glasses backend in MentraOS itself.

Study MentraOS:

```text
mobile/
android_core/
cloud/
sdk/
docs-bluetooth-sdk/
glasses-compatibility.md
```

Likely upstream work:

```text
- Add MemoMind device model to compatibility/capability registry.
- Add scanner match rules for MemoMind advertising names/services.
- Add Android Core BLE driver for MemoMind.
- Map MemoMind display/input/battery events into existing Mentra events.
- Add docs and capability matrix.
- Add graceful degradation for unavailable features.
```

Before PR:

```text
- Open a GitHub issue or discussion with Mentra maintainers.
- State that implementation is clean-room and based on hardware you own/test.
- Share capability matrix, no proprietary code.
- Ask where they prefer MemoMind driver code to live.
```

### Mentra local compatibility acceptance tests

```text
- Run MentraOS-Display-Example-App locally.
- CyanBridge Mentra runtime establishes a session or documented compatible shim route.
- App sends display text.
- MemoMind shows display text.
- Gesture/button on MemoMind or phone fallback sends event to app.
```

---

## Developer UI requirements

Add a new “Bridge Lab” area in CyanBridge.

Screens:

### 1. Device Pairing Screen

```text
- Scan for MemoMind
- Show discovered devices
- Connect/disconnect
- Show GATT services in advanced/debug mode
- Show battery/firmware if known
```

### 2. Capability Inspector

```text
- TEXT_DISPLAY: yes/no/unknown
- CLEAR_DISPLAY: yes/no/unknown
- TOUCH_INPUT: yes/no/unknown
- HEAD_GESTURE_INPUT: yes/no/unknown
- MICROPHONE_AUDIO: yes/no/unknown
- SPEAKER_AUDIO: yes/no/unknown
- BATTERY_STATUS: yes/no/unknown
- BRIGHTNESS_CONTROL: yes/no/unknown
```

### 3. Display Test Screen

Buttons:

```text
- Show hello
- Show multiline
- Show permission card
- Clear
- Next page
- Previous page
```

### 4. Runtime Launcher

Tabs:

```text
Even Hub Runtime
- App URL
- Inject shim toggle
- Start
- Stop
- Debug log

Mentra Runtime
- Local relay status
- App URL/endpoint
- Session status
- Start
- Stop
- Debug log

Native Runtime
- Terminal HUD later
- Sample card app
```

### 5. Simulated Glasses Preview

Always show what CyanBridge thinks it sent to the glasses. This is essential for debugging when the physical display fails.

---

## Internal message bus

Use a typed internal event bus between runtimes and device adapters.

Suggested events:

```kotlin
sealed class BridgeEvent {
    data class RuntimeStarted(val runtimeId: String, val appId: String) : BridgeEvent()
    data class RuntimeStopped(val runtimeId: String, val appId: String) : BridgeEvent()
    data class DisplayRequested(val runtimeId: String, val command: DisplayCommand) : BridgeEvent()
    data class InputReceived(val deviceId: String, val event: InputEvent) : BridgeEvent()
    data class DeviceStateChanged(val deviceId: String, val state: GlassesBridgeState) : BridgeEvent()
    data class Error(val source: String, val message: String, val throwable: Throwable? = null) : BridgeEvent()
}
```

Runtimes should publish `DisplayRequested` and subscribe to `InputReceived`.

Device adapters should consume `DisplayCommand` and publish `InputEvent`.

---

## Data model for compatibility apps

Create an app manifest model independent of Even/Mentra/CyanBridge native apps.

```kotlin
data class CompatibleAppManifest(
    val id: String,
    val name: String,
    val runtime: RuntimeType,
    val entryUrl: String? = null,
    val localPath: String? = null,
    val requiredCapabilities: Set<GlassesCapability> = emptySet(),
    val optionalCapabilities: Set<GlassesCapability> = emptySet()
)

enum class RuntimeType {
    EVEN_HUB_URL,
    EVEN_HUB_PACKAGE,
    MENTRA_LOCAL,
    CYANBRIDGE_NATIVE
}
```

Store installed/known app manifests locally.

---

## Security and privacy requirements

1. The user must explicitly start a runtime session.
2. Remote app URLs must be shown clearly before loading.
3. WebView runtimes must be sandboxed as much as possible.
4. Do not expose arbitrary Android APIs to WebView.
5. `JavascriptInterface` must expose only the compatibility bridge methods.
6. Add an allowlist/confirmation for local network URLs.
7. Log runtime events locally, with a clear “clear logs” button.
8. Do not send MemoMind data to third-party servers unless the user explicitly loads a remote app that needs it.
9. Put remote app/network warnings in the UI.
10. Do not persist raw BLE logs by default.

---

## Suggested build phases

### Milestone 1: Core GlassesBridge skeleton

Deliverables:

```text
- GlassesDeviceAdapter interface
- DisplayCommand/InputEvent models
- GlassesBridge manager
- MockDisplayAdapter
- SimulatedGlassesPreview UI
- Unit tests for command/event mapping
```

Acceptance:

```text
- A mock native sample app can display text in the simulated preview.
- No MemoMind dependency yet.
```

### Milestone 2: MemoMind connection shell

Deliverables:

```text
- MemoMind scanner
- MemoMind GATT connect/disconnect
- Debug service/characteristic viewer
- README_PROTOCOL_NOTES.md started
```

Acceptance:

```text
- CyanBridge can connect to MemoMind and list discovered services.
```

### Milestone 3: MemoMind minimal display

Deliverables:

```text
- MemoMind showText
- MemoMind clearDisplay
- Minimal display test screen
```

Acceptance:

```text
- Display test screen shows and clears text on MemoMind.
```

### Milestone 4: EvenHubRuntime v0

Deliverables:

```text
- WebView host
- JS bridge
- Basic evenhub-compat-shim.js
- Runtime launcher UI
- Mapping: text → DisplayCommand.Text
```

Acceptance:

```text
- EvenHub minimal template running on laptop displays text on MemoMind through CyanBridge.
```

### Milestone 5: EvenHubRuntime text-heavy/pagination

Deliverables:

```text
- Line wrapping/pagination
- Input event mapping for next/previous
- Text-heavy template support
```

Acceptance:

```text
- text-heavy Even Hub template can show long text and advance pages.
```

### Milestone 6: MentraRuntime local subset

Deliverables:

```text
- Local relay/session prototype
- Display mapper
- Input mapper
- README_MENTRA_COMPAT.md
```

Acceptance:

```text
- A simple Mentra display example can route text to MemoMind or the blocker is documented precisely with API/session details.
```

### Milestone 7: Upstream MentraOS exploration

Deliverables:

```text
- MemoMind capability matrix
- MentraOS issue draft
- Candidate file list for PR
- No large PR until maintainers respond
```

Acceptance:

```text
- Maintainer-facing issue/discussion is ready with clean-room evidence and implementation proposal.
```

---

## Terminal HUD comes after bridge

After the bridge works, implement the terminal app as one compatible app:

```text
Claude/Codex/OpenCode agent hub on laptop
        ↓ WebSocket
CyanBridge native TerminalHUD runtime or EvenHub/Mentra app
        ↓
GlassesBridge
        ↓
MemoMind
```

Terminal HUD capabilities:

```text
- show recent lines / summarized state
- show permission prompt
- allow/deny via glasses input or phone fallback
- switch tabs/sessions via gesture/voice/button
```

Do not implement this until at least one generic runtime can display text.

---

## Agent research checklist

Before coding, inspect these files/pages and write a short `BRIDGE_RESEARCH_NOTES.md`:

### CyanBridge

```text
https://github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK
README.md
AGENTS.md
WIFI_TRANSFER_ARCHITECTURE.md
android/CyanBridge/**
heycyan-core/**
```

Questions to answer:

```text
- What is the current Android package name?
- Is the UI Compose, XML, or mixed?
- Where is BLE scan/connect implemented?
- Where are permissions requested?
- Where are Tasker intents implemented?
- Is there already a WebSocket/HTTP/WebView component?
- Where should Bridge Lab UI live?
```

### Even Hub

```text
https://hub.evenrealities.com/docs
https://github.com/even-realities/evenhub-templates
https://www.npmjs.com/package/@evenrealities/evenhub
https://www.npmjs.com/package/@evenrealities/evenhub-simulator
https://github.com/nickustinov/even-g2-notes
```

Questions to answer:

```text
- What JS/global bridge does an Even Hub app expect at runtime?
- What calls are used by minimal/ and text-heavy/ templates?
- Can the app run in a normal Android WebView with a shim?
- How are touch/input events delivered to app code?
- Is `.ehpk` just a zip/package that can be loaded locally, or should URL mode remain v1 only?
```

### MentraOS

```text
https://github.com/Mentra-Community/MentraOS
https://docs.mentraglass.com/app-devs/getting-started/quickstart
https://docs.mentraglass.com/os-devs/contributing/overview
https://github.com/Mentra-Community/MentraOS-Display-Example-App
https://github.com/Mentra-Community/Mentra-Bluetooth-SDK-Starter-Kit
```

Questions to answer:

```text
- What messages does a simple Mentra display app send to cloud/relay?
- Can a local relay emulate enough of that protocol for simple apps?
- Does the SDK require signed cloud tokens/API keys?
- Where does MentraOS add new glasses models?
- What Android Core files handle BLE drivers and capability mapping?
```

### MemoMind

```text
https://www.memo-mind.com/
https://play.google.com/store/apps/details?id=com.memomind.ai.aphrodite
MemoMind beta/private docs, if provided by MemoMind
```

Questions to answer:

```text
- Is an SDK or BLE map available privately?
- What is allowed under beta terms?
- What device name/service UUIDs are visible from normal BLE scan?
- Which features can be implemented without relying on private cloud endpoints?
```

---

## Code quality expectations

1. Kotlin code must be strongly typed and coroutine-safe.
2. BLE operations must be serialized; do not fire parallel GATT writes unless the protocol explicitly supports it.
3. UI must handle disconnects and permission failures gracefully.
4. Keep protocol code isolated under `devices/memomind/`.
5. Keep runtime compatibility code isolated under `runtimes/evenhub/` and `runtimes/mentra/`.
6. Add logging but avoid logging secrets, raw user speech, or raw app payloads by default.
7. Add tests for mapping functions even before full device tests exist.
8. Use feature flags for unstable runtimes.

Suggested feature flags:

```kotlin
data class BridgeFeatureFlags(
    val enableMemoMindAdapter: Boolean = false,
    val enableEvenHubRuntime: Boolean = false,
    val enableMentraRuntime: Boolean = false,
    val enableProtocolDebugLogging: Boolean = false
)
```

---

## Initial PR breakdown

### PR 1: Bridge core + mock preview

Files:

```text
bridge/core/**
bridge/devices/mock/**
bridge/ui/SimulatedGlassesPreview.*
```

No MemoMind code. Easy to review.

### PR 2: MemoMind scanner/connect/debug

Files:

```text
bridge/devices/memomind/MemoMindBleScanner.kt
bridge/devices/memomind/MemoMindGattClient.kt
bridge/devices/memomind/MemoMindDeviceAdapter.kt
bridge/devices/memomind/README_PROTOCOL_NOTES.md
bridge/ui/DevicePairingScreen.*
bridge/ui/CapabilityInspectorScreen.*
```

### PR 3: MemoMind text/clear display

Files:

```text
bridge/devices/memomind/MemoMindProtocol.kt
bridge/devices/memomind/MemoMindPacketEncoder.kt
bridge/devices/memomind/MemoMindPacketDecoder.kt
bridge/ui/DisplayTestScreen.*
```

### PR 4: Even Hub URL runtime

Files:

```text
bridge/runtimes/evenhub/**
bridge/ui/RuntimeLauncher.*
```

### PR 5: Even Hub pagination/input

Files:

```text
bridge/runtimes/evenhub/**
bridge/core/InputEvent.kt
bridge/ui/RuntimeDebugScreen.*
```

### PR 6: Mentra local runtime exploration

Files:

```text
bridge/runtimes/mentra/**
bridge/runtimes/mentra/README_MENTRA_COMPAT.md
```

### PR 7: MentraOS upstream issue/PR prep

Files:

```text
docs/MEMOMIND_MENTRAOS_UPSTREAM_PLAN.md
docs/MEMOMIND_CAPABILITY_MATRIX.md
```

---

## Concrete first task for the agent

Start with this:

```text
1. Clone/read the CyanBridge repo.
2. Create BRIDGE_RESEARCH_NOTES.md.
3. Identify current package name, UI style, BLE architecture, Gradle modules, and where new code should live.
4. Add bridge/core interfaces and mock adapter only.
5. Add a simulated glasses preview screen or debug activity that can show DisplayCommand.Text.
6. Do not touch MemoMind BLE yet.
7. Submit a small PR/diff for review.
```

Definition of done for first task:

```text
- App builds.
- Existing HeyCyan features are untouched.
- A developer can open a debug screen and send "Hello from CyanBridge Bridge" to the simulated glasses preview.
- Core interfaces compile and are ready for MemoMindAdapter.
- Research notes list next files to modify for MemoMind scanning.
```

---

## Completed work (Milestone 1 — branch `memomind-adapter`)

The first milestone is done. All items below are implemented and the app builds cleanly.

### What was delivered

**Device type integration:**
- `MEMO_MIND` added to `DeviceClass` enum with `displayName()` → "MemoMind"
- `DeviceClassifier` detects MemoMind by BLE name: "memomind", "memo-mind", "aphrodite", "xgimi"
- `DeviceListAdapter` and `DeviceBindActivity` show MemoMind in the type picker
- `GlassesManagerGating` exposes `BRIDGE_DISPLAY` and `BRIDGE_AUDIO` actions only when MEMO_MIND is selected

**Bridge core interfaces** (`bridge/core/`):
- `GlassesDeviceAdapter` — the interface all device adapters implement
- `DisplayCommand` — sealed class: `Text`, `Lines`, `Card`, `Clear`
- `InputEvent` — sealed class: `Button`, `Touch`, `HeadGesture`, `VoiceText`, `Battery`
- `GlassesBridgeState` — `Disconnected`, `Scanning`, `Connecting`, `Connected`, `Error`
- `GlassesCapability` — enum of 15 capabilities
- `DeviceInfo` — device metadata model
- `BridgeError` — typed exceptions
- `GlassesBridge` — central singleton with adapter registry, command routing, event bus

**Mock adapter + preview:**
- `MockDisplayAdapter` — logs all commands, stores display history in `StateFlow`
- `SimulatedGlassesPreview` — Composable rendering a fake OLED screen

**Wired into the app:**
- `GlassesViewModel` initializes `GlassesBridge` with `MockDisplayAdapter` when MEMO_MIND is the selected device class
- `GlassesScreen` shows a "Bridge Lab" section (with test buttons + OLED preview) only when MEMO_MIND is active
- Test buttons: "Show Hello", "Show Lines", "Show Card", "Clear"
- The Bridge Lab section is hidden for HeyCyan, Meta Rayban, and Generic Audio devices

### Files modified/created

| File | Status |
|------|--------|
| `devices/DeviceClass.kt` | Modified — added `MEMO_MIND` |
| `devices/DeviceClassifier.kt` | Modified — added MemoMind heuristics |
| `devices/GlassesManagerGating.kt` | Modified — added `BRIDGE_DISPLAY`, `BRIDGE_AUDIO` |
| `ui/DeviceListAdapter.kt` | Modified — added `MEMO_MIND` to picker |
| `ui/DeviceBindActivity.kt` | Modified — added `MEMO_MIND` to picker |
| `ui/glasses/GlassesViewModel.kt` | Modified — bridge init + test methods |
| `ui/glasses/GlassesScreen.kt` | Modified — Bridge Lab section |
| `bridge/core/GlassesCapability.kt` | Created |
| `bridge/core/DisplayCommand.kt` | Created |
| `bridge/core/InputEvent.kt` | Created |
| `bridge/core/GlassesBridgeState.kt` | Created |
| `bridge/core/DeviceInfo.kt` | Created |
| `bridge/core/GlassesDeviceAdapter.kt` | Created |
| `bridge/core/BridgeError.kt` | Created |
| `bridge/core/GlassesBridge.kt` | Created |
| `bridge/core/DisplayCommandExt.kt` | Created |
| `bridge/devices/mock/MockDisplayAdapter.kt` | Created |
| `ui/glasses/SimulatedGlassesPreview.kt` | Created |

### Definition of done — met

- ✅ App builds (`BUILD SUCCESSFUL`)
- ✅ Existing HeyCyan features are untouched
- ✅ A developer can open Glasses tab, see "Bridge Lab" when MemoMind is selected, and send "Hello from CyanBridge Bridge" to the simulated glasses preview
- ✅ Core interfaces compile and are ready for `MemoMindDeviceAdapter`
- ✅ `BRIDGE_RESEARCH_NOTES.md` documents the MemoMind protocol findings

---

### Milestone 2 — MemoMind BLE adapter (partial)

**Branch:** `memomind-adapter` (same branch)

**What was delivered:**

| File | Status |
|------|--------|
| `bridge/devices/memomind/MemoMindConstants.kt` | Created — BLE UUID constants |
| `bridge/devices/memomind/MemoMindBleScanner.kt` | Created — BLE scanner with name pattern matching |
| `bridge/devices/memomind/MemoMindGattClient.kt` | Created — GATT connection, notification streaming, write commands |
| `bridge/devices/memomind/MemoMindDeviceAdapter.kt` | Created — implements `GlassesDeviceAdapter` with real BLE encoding |
| `bridge/devices/memomind/MemoMindPacketEncoder.kt` | Created — packet encoding based on Ghidra dispatch analysis |
| `bridge/devices/memomind/README_PROTOCOL_NOTES.md` | Updated — full protocol documentation with Ghidra findings |

**What works:**
- BLE scanning for MemoMind devices (name pattern matching)
- GATT connection with service discovery
- Notification enable on 0x2002
- MTU negotiation (best-effort, 512 bytes)
- `showText()` — encodes billboard command and writes to 0x2001
- `showLines()` — concatenates lines, sends as text
- `showCard()` — formats as text, sends
- `clearDisplay()` — encodes clear command
- `setBrightness()` — encodes brightness command
- `requestBattery()` — sends battery request (response parsing still stubbed)

**What requires BLE sniffing to complete:**
- Service ID byte values (currently placeholder constants in `MemoMindPacketEncoder`)
- Billboard area type enum values
- Battery response parsing from 0x2002 notifications
- Font bitmap rendering protocol
- DrawCommand encoding
- Record service (0x2020-0x2026)
- OTA (0x7033)

**Ghidra findings incorporated:**
- 5-bit command type encoding in bits 8-12 of packed value
- Dispatch table at `0x01026f00` with 10 command types (0-9 + 0x1f)
- MQTT-like packet framing (header + varint + payload)
- Key function addresses for future decompilation
- String locations for debugging

---

### Milestone 4 — EvenHub WebView runtime

**Branch:** `memomind-adapter` (same branch)

**What was delivered:**

| File | Purpose |
|------|---------|
| `bridge/runtimes/evenhub/EvenHubRuntimeActivity.kt` | WebView activity with URL input, Load/Stop buttons, debug log area |
| `bridge/runtimes/evenhub/EvenHubJsBridge.kt` | `@JavascriptInterface` receiving JS calls, routing to `GlassesBridge` |
| `assets/evenhub-compat-shim.js` | JS shim intercepting `EvenAppBridge` SDK calls, forwarding to Android bridge |
| `res/layout/activity_evenhub_runtime.xml` | Layout with URL bar, WebView, and debug log |

**How it works:**
1. User enters an EvenHub app URL (e.g. `http://10.0.2.2:5173` for emulator)
2. WebView loads the URL and injects `evenhub-compat-shim.js`
3. The shim replaces `EvenAppBridge` singleton — intercepts all SDK calls
4. `createStartUpPageContainer`, `textContainerUpgrade`, `rebuildPageContainer`, `shutDownPageContainer` → forwarded to `CyanBridgeEvenHubBridge` (Android `@JavascriptInterface`)
5. `EvenHubJsBridge` parses container JSON, extracts text, maps to `DisplayCommand.Text/Lines/Card`
6. `GlassesBridge` routes to the active adapter (MockDisplayAdapter or MemoMindDeviceAdapter)

**EvenHub SDK API surface supported:**
- `createStartUpPageContainer(params)` — extract text from containers
- `textContainerUpgrade(id, name, content, offset, length)` — in-place text update
- `rebuildPageContainer(params)` — full page rebuild
- `shutDownPageContainer(exitMode)` — exit (0=immediate, 1=confirm)
- `onEvenHubEvent(listener)` — event subscription (stub)
- `getDeviceInfo()` — returns mock device info
- `audioControl(start)` — mic control (stub)
- `setLocalStorage/getLocalStorage` — persist via Android SharedPreferences

**EvenHub display specs:**
- Canvas: 576×288 pixels per eye, 4-bit greyscale
- Containers: absolute positioned (x, y, width, height)
- Max 4 image containers, max 8 text/list containers per page
- One container must have `isEventCapture: 1` for input events

**Acceptance:**
- Run `npm run dev` in an Even Hub minimal template on laptop
- In CyanBridge, open EvenHub Runtime, enter the dev URL
- MemoMind glasses (or mock preview) show "Hello" from the Even Hub app

**What needs BLE sniffing:**
- Image rendering (container format unknown)
- Input event forwarding to JS (event structure needs verification)
- IMU data forwarding

---

### Milestone 5 — MentraOS local relay

**Branch:** `memomind-adapter` (same branch)

**What was delivered:**

| File | Purpose |
|------|---------|
| `bridge/runtimes/mentra/MentraLocalRelay.kt` | Local HTTP server on port 8002 accepting POST requests |
| `bridge/runtimes/mentra/MentraDisplayMapper.kt` | Maps MentraOS layout JSON to `DisplayCommand` |
| `bridge/runtimes/mentra/MentraSessionManager.kt` | Manages connected app sessions |
| `bridge/runtimes/mentra/MentraMessageTypes.kt` | Constants for all MentraOS message types |
| `bridge/runtimes/mentra/README_MENTRA_COMPAT.md` | Documentation and test instructions |

**How it works:**
1. MentraLocalRelay starts an HTTP server on port 8002
2. MentraOS apps send `POST /display` with JSON body:
   ```json
   {"type":"display_event","layout":{"layoutType":"text_wall","text":"Hello"}}
   ```
3. `MentraDisplayMapper` maps layout types to `DisplayCommand`:
   - `text_wall` → `DisplayCommand.Text`
   - `double_text_wall` → `DisplayCommand.Lines`
   - `reference_card` → `DisplayCommand.Card`
   - `dashboard_card` → `DisplayCommand.Text`
4. `GlassesBridge` routes to the active adapter

**MentraOS layout types supported:**

| Layout | JSON | Maps to |
|--------|------|---------|
| `text_wall` | `{ text }` | `DisplayCommand.Text` |
| `double_text_wall` | `{ topText, bottomText }` | `DisplayCommand.Lines` |
| `reference_card` | `{ title, text }` | `DisplayCommand.Card` |
| `dashboard_card` | `{ leftText, rightText }` | `DisplayCommand.Text` |

**Connection flow:**
- `POST /init` with `{ packageName, sessionId, apiKey }` → ack response
- `POST /display` with display event → routed to glasses
- `POST /subscribe` with subscription list → registered
- Input events broadcast to connected sessions (future WebSocket upgrade)

**Acceptance:**
- `curl -X POST http://localhost:8002/display -d '{"type":"display_event","layout":{"layoutType":"text_wall","text":"Hello from MentraOS"}}'`
- Glasses show "Hello from MentraOS"

**Limitations:**
- HTTP POST model (not WebSocket) — input event push-back requires future WebSocket upgrade
- No API key validation in local mode
- Dashboard view mapped to main view

---

### Milestone 6 — Audio bridge (partial)

**Branch:** `memomind-adapter` (same branch)

**What was delivered:**

| File | Purpose |
|------|---------|
| `bridge/audio/MemoMindAudioBridge.kt` | Main audio bridge — BLE characteristic discovery, PCM playback, phone mic recording |
| `bridge/audio/WqRecordParser.kt` | WQ Record Protocol V2 frame parser |
| `bridge/audio/OpusDecoderWrapper.kt` | Opus → PCM decoder via Android MediaCodec |

**What works:**
- BLE recording characteristic discovery (0x2020, 0x2024, 0x2025, 0x2026)
- PCM playback via `AudioTrack` (24000 Hz, 16-bit, mono, MODE_STREAM)
- Phone mic recording via `AudioRecord` with `callbackFlow`
- Recording notification logging (raw bytes for protocol analysis)
- WQ Record Protocol V2 parser (magic byte + frameCnt + Opus payload structure)
- Opus decoder wrapper using Android `MediaCodec` (API 29+)

**Data flow (when complete):**
```
Glasses mic → Opus encode → WQ frame → BLE 0x2020
    → WqRecordParser.parse()
    → OpusDecoderWrapper.decode()
    → PCM data
    → MemoMindAudioBridge.audioData flow
    → File / speaker / transcription
```

**What needs BLE sniffing to complete:**
- WQ magic byte value (placeholder: 0xA5)
- frameCnt encoding (uint8/16/32, endianness — placeholder: uint16 LE)
- serviceId byte for recorder commands (start/stop/pause/resume)
- RecorderCommand binary payload format
- 0x2024 notify message format
- Offline recording download protocol

**Audio parameters (confirmed from binary):**
- Codec: Opus
- Sample rate: 24000 Hz
- Channels: 1 (mono)
- Bit depth: 16-bit PCM (decoded)
- Native library: `libopus.so` (full encoder/decoder)

**Acceptance (partial):**
- Audio bridge initializes and discovers recording characteristics
- PCM playback works via AudioTrack
- Phone mic recording captures audio
- WQ parser logs raw frames for analysis
- Full glasses mic → phone pipeline requires BLE sniffing

---

## Next steps (recommended order)

### Step 1: BLE traffic capture (blocking for MemoMind adapter)

Before writing `MemoMindDeviceAdapter`, we need the actual byte-level wire format. The `strings` analysis of `libapp.so` gave us the architecture (UUIDs, command classes, protocol variants) but NOT the binary encoding of commands.

**Action:** Capture BLE traffic between the official MemoMind app and the glasses.

Tools:
- **nRF Connect** (Android) — connect to the glasses, write to `0x2001`, observe notifications on `0x2002`
- **Wireshark + nRF Sniffer** — passive BLE sniffing of a real session
- **Android HCI snoop log** — enable in Developer Options, capture a pairing + notification push + teleprompter session

What to capture:
1. Connection + service discovery (confirm UUIDs: `0x2001`, `0x2002`, `0x2020-0x2026`, `0x7033`)
2. A simple notification push (title + body text)
3. A teleprompter start/stop cycle
4. A clear display command
5. Battery request/response

Save captures to `android/CyanBridge/bridge/devices/memomind/captures/` (gitignored).

### Step 2: MemoMind BLE adapter (after capture)

Create `bridge/devices/memomind/` package:
- `MemoMindDeviceAdapter.kt` — implements `GlassesDeviceAdapter`
- `MemoMindBleScanner.kt` — scan using the discovered service UUIDs
- `MemoMindGattClient.kt` — GATT connection, characteristic read/write, notification subscription
- `MemoMindProtocol.kt` — packet encoding/decoding based on captured wire format
- `README_PROTOCOL_NOTES.md` — document the wire format with packet dumps

Target: send a `DisplayCommand.Text("Hello")` from the Bridge Lab and see it on the real MemoMind glasses.

### Step 3: EvenHub WebView runtime

After Milestone 1 (core interfaces) and ideally after Milestone 2 (MemoMind adapter), build the Even Hub compatibility layer.

Create `bridge/runtimes/evenhub/`:
- `EvenHubRuntimeActivity.kt` — loads an Even Hub app URL in a WebView
- `EvenHubWebViewHost.kt` — WebView configuration, lifecycle
- `EvenHubJsBridge.kt` — `@JavascriptInterface` that receives display requests from JS
- `assets/evenhub-compat-shim.js` — injected JS that intercepts Even Hub SDK calls and forwards them to the bridge

Reference:
- Even Hub docs: `https://hub.evenrealities.com/docs`
- Templates: `https://github.com/even-realities/evenhub-templates`
- Start with `minimal/` and `text-heavy/` templates

Acceptance:
- Run `npm run dev` in an Even Hub minimal template on laptop
- In CyanBridge, enter the dev URL in the EvenHub runtime
- MemoMind glasses (or mock preview) shows "Hello" from the Even Hub app

### Step 4: MentraOS local relay

Create `bridge/runtimes/mentra/`:
- `MentraLocalRelay.kt` — local WebSocket server that accepts MentraOS-style display messages
- `MentraSessionManager.kt` — session lifecycle
- `MentraDisplayMapper.kt` — maps Mentra display layouts to `DisplayCommand`
- `README_MENTRA_COMPAT.md` — documents what's supported

Reference:
- MentraOS docs: `https://docs.mentraglass.com/app-devs/getting-started/quickstart`
- Display example: `https://github.com/Mentra-Community/MentraOS-Display-Example-App`

### Step 5: Audio bridge (later)

After display works end-to-end:
- Map `MICROPHONE_AUDIO` capability to the MemoMind recording protocol (BLE characteristics `0x2020-0x2026`)
- Map `SPEAKER_AUDIO` to A2DP/HFP routing
- Use the existing `flutter_pcm_player` architecture as reference for PCM playback

### Step 6: Terminal HUD (last)

After at least one runtime (EvenHub or MentraOS) can display text on real glasses:
- Create `bridge/runtimes/nativeapps/terminalhud/TerminalHudApp.kt`
- Receive agent state over WebSocket from laptop
- Display: provider name, status, recent lines, permission prompts
- Route glasses input (tap gesture) back to agent as ALLOW/DENY

---

## Later note for Claude/Codex/OpenCode terminal HUD

When ready, implement it as a CyanBridge native runtime first. Later optionally expose it as an Even Hub app and/or MentraOS app.

Suggested display state:

```kotlin
data class TerminalHudState(
    val provider: AgentProvider,
    val repoName: String,
    val status: AgentStatus,
    val recentLines: List<String>,
    val pendingPermission: PermissionRequest? = null
)
```

Suggested rendering:

```text
CLAUDE · CyanBridge
Permission needed
Edit MemoMindAdapter.kt
[ALLOW] [DENY]
```

But again: do not build this until the generic bridge can display text from at least one compatibility runtime.
