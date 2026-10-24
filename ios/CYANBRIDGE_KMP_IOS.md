# CyanBridge KMP iOS Host

`CyanBridgeKMPHost` is the iOS host for the `:shared` Kotlin Multiplatform framework. It runs on the simulator and on iPhone; device builds link `QCSDK.framework` for HeyCyan glasses. `QCSDKDemo` remains an isolated vendor/device reference target. Neither target is a claim of feature parity or vendor framework support.

## Current Scope

- Builds and links `CyanBridgeShared.framework` directly from Gradle through an Xcode run-script phase (or via GitHub Actions CI).
- Embeds a Compose Multiplatform `ComposeUIViewController` via `UIViewControllerRepresentable` in SwiftUI. Both Android and iOS render from the same shared `@Composable` screens in `shared/commonMain`.
- Shares appearance models, semantic icons, navigation destinations, chat models, immutable chat-thread presentation state, meeting-summary contracts, deterministic Markdown formatting, the offline rule-based summarizer, and shared Compose Multiplatform UI screens.
- The KMP host now renders the shared Chats, Media, Plugins, and Settings destinations directly. Android keeps its existing Activity presenters unless it opts into the same shared route.
- The iOS adapter now has a CoreBluetooth scan/connect/discovery path, standard battery/firmware characteristic reads, readiness-gated NEHotspotConfiguration hotspot joining, durable JSON-backed repositories, and the glasses `media.config`/file download flow.
- All iOS targets use a dynamic framework (`isStatic = false`). The simulator needs it for Skiko compatibility, and the device needs it because `embedAndSignAppleFrameworkForXcode` embeds the framework in `Frameworks/`, where a static archive makes installation fail.
- Leaves CoreBluetooth, NetworkExtension, Photos, audio, StoreKit, local inference, and QCSDK.framework calls in native adapters.
- A shared `CyanBridgeKMPHost.xcscheme` is tracked in the Xcode project for reproducible CI builds.

The Objective-C QCSDK demo remains protocol evidence. It is not the new app architecture and must not become a second copy of Android's state machines.

## iOS Transfer Readiness

The shared iOS sync path follows the device-reported transfer sequence:

1. Wait for CoreBluetooth service discovery to expose a writable characteristic.
2. Request transfer mode with the already documented `[0x02, 0x01, 0x04]` command when the host has not prepared it through QCSDK.
3. Wait for the glasses BLE notification containing their Wi-Fi IP.
4. Join the glasses hotspot with `NEHotspotConfiguration` when credentials are available.
5. Re-check the active iOS SSID before requesting `/files/media.config`.

Device builds of `CyanBridgeKMPHost` link `QCSDK.framework` (see "Vendor SDK bridge" below). The host should pass the successful `QCSDKCmdCreator.openWifiWithMode:success:fail:` callback's credentials to the exported Kotlin seam:

```text
IosTransferModeConfiguration.configurePreparedHotspot(ssid, passphrase)
```

When the host also receives the IP from QCSDK's `getDeviceWifiIPSuccess` callback, it may pass it as the third argument. Otherwise the shared BLE notification listener still waits for the normal device-reported IP.

The seam can also be configured with `configureHotspot` when the host has credentials but still wants the shared flow to send the documented transfer command. It never supplies the legacy hard-coded password. If neither credentials nor an already-connected iOS transfer network is available, the UI reports the missing host setup instead of claiming that Wi-Fi Direct is connected. The BLE-reported IP and `/files/media.config` handling remain the source of truth for the HTTP transfer.

The KMP host target includes the Hotspot Configuration and Wi-Fi information entitlements, plus the CoreBluetooth and NetworkExtension link flags required by the iOS adapters. These capabilities do not turn iOS into an Android-style Wi-Fi Direct peer; `supportsTrueWifiDirect` remains false on the iOS adapter.

Signing: the `CyanBridgeKMPHost` scheme (Debug/Release) uses the bundle ID `com.fersaiyan.cyanbridge`, the same as the Android `applicationId`, and the paid team `MZ7DJZ88T2`, which the Hotspot and Wi-Fi information entitlements require. The `CyanBridgeKMPHost-Free` scheme (`Debug-Free`) keeps `com.murilosampaio.cyanbridge.kmp.free` on a free personal team and joins the glasses hotspot manually. `QCSDKDemo` uses `com.fersaiyan.cyanbridge.qcsdkdemo` so it doesn't claim the app's ID.

## Vendor SDK bridge

HeyCyan glasses are driven through QCSDK, the iOS counterpart of Android's `glasses_sdk` AAR:

- `shared/src/iosMain/.../ble/VendorGlassesBridge.kt` defines the contract (`attach`, `setDeviceMode`, battery, version, media counts, time sync, and pushed events).
- `ios/CyanBridgeKMPHost/QCSDKGlassesBridge.swift` implements it with `QCSDKManager` / `QCSDKCmdCreator` and registers it in `VendorGlassesRegistry` at app start.
- `IosBleManager` still owns scanning and connecting. When the selected class is HeyCyan, it hands the connected `CBPeripheral` to `QCSDKManager` instead of running generic service discovery, and falls back to the generic path if QCSDK rejects it.
- Simulator builds have no bridge; the dashboard reports that vendor controls need a device build.

## Other glasses SDKs

Every family is a shared `GlassesDevice` (`IosGlassesDevice`): `IosHeyCyanGlasses` (also used for unclassified and audio-only glasses, like Android), `IosEyevueGlasses`, and the TuneBuds, SDK and Meizu devices of `IosOtherGlasses`. Dashboard actions go through the shared `GlassesDashboardController` (session policy, family gating, device capabilities); `IosDashboardHost` runs the iOS effects, and `withIosFamilyGating` applies Android's gating with the iOS differences (sections need a live link; QCSDK-only sections show for HeyCyan alone).

TuneBuds, MoYoung, Meta Ray-Ban, Mentra Live and Meizu run through `IosOtherGlasses.kt`:

- **TuneBuds**: the shared `TuneBudsSession` (AB Mate frames) over BLE service `FDB3` (write `FF17`, notify `FF18`), through `IosBleManager`.
- **MoYoung / W620**: `MoyoungGlassesBridge.swift` with `CRPSmartGlasses.framework`. It is arm64 iPhone-only and not committed: run `ios/scripts/fetch_moyoung_sdk.sh` once. The script downloads the pinned plugin archive (checksum verified) into `ios/Vendor/MoYoung` and builds the dynamic `SwiftProtobuf` and `AFNetworking` frameworks the SDK links against. The "Embed MoYoung frameworks" build phase copies and signs them for device builds only.
- **Meta Ray-Ban**: `MetaGlassesBridge.swift` with the `meta-wearables-dat-ios` 1.0.0 Swift package (requires iOS 17.2). Registration goes through the Meta AI app and returns through the `cyanbridge://` URL. `Info.plist` runs the toolkit in developer mode (`MetaAppID` 0), with analytics and crash reporting opted out. A production release needs the app ID and client token from the Wearables Developer Center.
- **Mentra Live**: `MentraGlassesBridge.swift` with the `mentra-bluetooth-sdk-ios` 3.2.0-dev.227 Swift package, the same version as Android.
- **Meizu MYVU**: not drivable on iOS, because its app relay is RFCOMM-only. The dashboard card explains this.

The Swift bridges implement the shared `SdkGlassesBridge` contract and register in `SdkGlassesSetup.swift`. Each bridge is wrapped in `#if canImport(...)`, so a build without one of the SDKs still compiles and the dashboard says which SDK is missing.

## On-device models

The "Local" AI provider runs models on the iPhone through `ios/LocalModels`, a local Swift package linked into `CyanBridgeKMPHost`:

- **llama.cpp** for `.gguf` files. `Frameworks/llama.xcframework` is not committed: build it once with `ios/scripts/build_llama_xcframework.sh` (needs `cmake`). The official release zip has no iOS-simulator slice, so the script builds from source.
- **LiteRT-LM** for `.litertlm` files: Google's prebuilt `CLiteRTLM.xcframework` (downloaded by SwiftPM with a pinned checksum) plus its Apache-2.0 Swift wrapper copied into `Sources/LiteRTLM`. Depending on the LiteRT-LM repository directly makes SwiftPM clone several GB of history.
- `LocalModelBridgeImpl.swift` implements the shared `LocalModelBridge`; `IosLocalModels.kt` owns storage (Application Support, excluded from backup), the test-model catalog, import from Files and the Settings ▸ Local screen.
- Routing: remote OpenAI-compatible server (when enabled) → selected on-device model → CyanBridge relay. On-device image questions are not supported yet.
- Self-test: launch with `CYANBRIDGE_LOCAL_MODEL_SELFTEST=1` (or a model file name) to load the model, ask one question and write the reply to `Documents/cyanbridge-debug.log`.

## Mac Setup

1. Install Xcode and a Java 17+ JDK. Android Studio's bundled JBR is suitable. Run `ios/scripts/build_llama_xcframework.sh` once (see On-device models).
2. From `android/CyanBridge`, run the framework task once for the appropriate simulator or device destination:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew -PenableAppleTargets=true :shared:linkDebugFrameworkIosSimulatorArm64
```

3. Open `ios/QCSDKDemo.xcodeproj` in Xcode and select the `CyanBridgeKMPHost` scheme to build the KMP shell on a simulator.
4. The Xcode build phase invokes:

```bash
./gradlew -PenableAppleTargets=true :shared:embedAndSignAppleFrameworkForXcode
```

5. Confirm the simulator renders the shared CMP `CyanBridgeApp` composable (Material 3 theme, shared navigation shell, shared screens).
6. Confirm the shared meeting-summary preview has stable Markdown headings and does not require a native transport or persistence adapter.

Use `linkDebugFrameworkIosX64` on Intel simulators and `linkDebugFrameworkIosArm64` for a physical device.

## GitHub Actions CI

A hosted macOS workflow (`.github/workflows/ios-kmp-host.yml`) validates the KMP iOS stack on every push to the migration branch. It runs:

1. **Static structure check** — `ios/scripts/verify_kmp_host.py` confirms the Xcode project wiring is correct and `CyanBridgeKMPHost` is isolated from vendor transport code.
2. **Portable shared tests** — `./gradlew :shared:portabilityTest` runs KMP common tests.
3. **Apple-Silicon simulator framework link** — `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64` produces `CyanBridgeShared.framework`.
4. **Unsigned Xcode build** — `xcodebuild -scheme CyanBridgeKMPHost` compiles the SwiftUI host against the shared framework without code signing.
5. **Simulator smoke test** — A fresh iPhone 16 simulator is created, the app is installed and launched, and a screenshot is captured and uploaded as a build artifact.

The workflow triggers automatically on changes to KMP sources, Gradle configuration, iOS host files, or the workflow itself. It can also be dispatched manually from the GitHub Actions tab (`workflow_dispatch`).

No Apple Developer Program membership is required for simulator-only validation. The workflow uses GitHub's `macos-14` (Apple Silicon) runner with Xcode 16.2 and Java 17.

## Required Hardware Validation

- Confirm the framework links on current Xcode for arm64 device and simulator targets.
- Confirm `QCSDK.framework` has a compatible arm64 slice and can scan, connect, issue commands, and transfer media on real hardware.
- Validate Bluetooth, hotspot joining, cleartext local HTTP, Photos, microphone, background modes, and keyboard/safe-area behavior.
- Replace the demo's retry-heavy media flow only after recording successful device behavior. Never log hotspot credentials.
- Do not reuse Android Play Billing or web checkout assumptions. iOS billing requires a separate StoreKit decision.

## Compose Multiplatform Integration

The iOS host renders shared `@Composable` screens from `shared/commonMain` via a `ComposeUIViewController`. The SwiftUI host wraps this view controller using `UIViewControllerRepresentable`:

```swift
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
```

The `MainViewController()` function is exported from the Kotlin/Native framework and creates a `ComposeUIViewController` that renders the shared `CyanBridgeApp` composable.

### Dynamic vs Static Framework

CMP on iOS uses Skiko (Skia) for rendering. Skiko ships as a static library (`.a`) for device targets but as a dynamic library (`.dylib`) for simulator targets. The `:shared` module is configured accordingly:

- `iosArm64` (device): `isStatic = false` — dynamic framework. A static archive gets copied into `Frameworks/` by `embedAndSignAppleFrameworkForXcode` and the device rejects the install (`MIInstallerErrorDomain` 73).
- `iosSimulatorArm64` (simulator): `isStatic = false` — dynamic framework, Skiko `.dylib` is embedded in the `.app` bundle.
- `iosX64` (Intel simulator): `isStatic = false` — same as above.

The `embedAndSignAppleFrameworkForXcode` Gradle task handles both cases transparently. No Xcode project changes are needed when switching between static and dynamic frameworks.

### Theme Discipline

Both Android and iOS share the same CMP `MaterialTheme` from `org.jetbrains.compose.material3`. The `:app` module also uses CMP Material 3 (not Jetpack Material 3) to ensure the theme provider is the same type across both platforms. This guarantees identical rendering of colors, typography, shapes, and components.

## Known Limits

- Linux can configure the Apple targets and compile shared common code, but cannot link or run iOS binaries. A Mac with Xcode is required for those checks.
- On Linux, run `python3 ios/scripts/verify_kmp_host.py` from the repository root to statically confirm project wiring. The full build pipeline requires the GitHub Actions macOS runner or a local Mac.
- `QCSDK.framework` is presently an opaque static archive from the vendor demo. Its objects are arm64 device only, so `CyanBridgeKMPHost` links it only for `sdk=iphoneos*` and the Swift bridge compiles behind `#if canImport(QCSDK)`. License and full device behavior remain unverified.
- `GlassesWiFiHandler` contains a legacy hard-coded hotspot-password workaround. It is reference-only; the KMP host requires credentials from its QCSDK integration or an already-connected hotspot.
- The GitHub Actions CI uses GitHub's hosted Apple Silicon runners. Private repositories consume billed Actions minutes (macOS is charged at $0.062/minute after the free quota). Public repositories run macOS jobs free of charge.
- Simulator validation can verify CMP rendering, framework linking, Swift compilation, SwiftUI rendering, and app launch, but it cannot validate Bluetooth pairing, Wi-Fi hotspot joining, QCSDK behavior, or media transfer from physical glasses. Those require a real iPhone near the glasses and are deferred.
- CoreBluetooth intentionally discovers writable/notifying characteristics by properties because the vendor's proprietary UUIDs are not exposed in the public iOS headers. The generic adapter must be validated against a real glasses firmware before it can be considered protocol-complete. A host integrating QCSDK should use `configurePreparedHotspot` rather than relying on the generic command fallback.
- iOS cannot provide an Android-style screen `AccessibilityService`; AutoDiary therefore remains unavailable on iOS. Auto Audio and Visual Diary still need iOS background scheduling, vendor media semantics, and foreground/background privacy validation before being enabled in the iOS plugin catalog.
- iOS repository durability currently uses namespaced `NSUserDefaults` JSON stores. It is safe for the current host shell but is not yet a migration to SQLite/SQLDelight for large memory or media catalogs.
- The shared iOS host uses the relay AI adapters for chat, voice, and image requests. Offline Moonshine/Vosk/LiteRT/llama.cpp inference remains Android-specific.
- CMP Material 3 (`org.jetbrains.compose.material3`) and Jetpack Material 3 (`androidx.compose.material3`) have 99% identical APIs but different import paths. The migration changes imports from `androidx.compose.*` to `org.jetbrains.compose.*` for shared screens. Stable components (Scaffold, NavigationBar, Card, Button, TextField, AlertDialog) are fully compatible; experimental or very new Jetpack APIs may lag in CMP.
