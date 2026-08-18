# Android Material 3 Migration Plan

Last audited: 2026-07-15

This is the authoritative plan for migrating CyanBridge's current Android app to Material 3 without regressing its glasses, media, AI, billing, and local-agent behavior. The old Compose branch remains useful as a UI prototype, but it is no longer a safe integration base. A narrow Kotlin Multiplatform module now owns proven portable models and bridge contracts; Compose Multiplatform is being added so both Android and iOS render from the same shared `@Composable` screens, achieving full UI convergence across platforms.

## Executive Decision

Do not merge or rebase `compose_material3_migration` or `memomind-adapter` wholesale into current `main`.

Preserve the old branch tips as archive references and selectively port screen structure and visual ideas into current Android code. Current business logic must remain authoritative.

Recommended branch workflow for future isolated migration work:

```bash
git branch archive/compose-material3-2026-07 compose_material3_migration
git branch archive/memomind-adapter-2026-07 memomind-adapter
git switch main
git switch -c compose-material3-kmp-v2
```

Do not delete the old branches. They contain useful screen implementations and the only full history of the earlier UI experiments.

Keep `:shared` narrow. It may contain proven platform-neutral models, route identifiers, contracts, and tests, but not Android framework wrappers or speculative iOS abstractions. Compose Multiplatform UI remains deferred; native iOS targets are opt-in for the framework host and remain gated on Mac/Xcode and transport validation below.

## Implementation Status

Implemented on 2026-07-14 in the current working migration:

- Compose enabled in the Android `:app` module without changing Kotlin, AGP, KAPT, Room, vendor AAR, or launcher routing.
- Material 3 dependencies and Compose UI test dependencies added.
- `AppearanceSettings`, stable accent IDs, Android preference persistence, and live preference observation added.
- Six curated neutral-surface light/dark accent schemes added with optional Android dynamic color and high-contrast mode.
- Automated contrast checks cover normal text, container text, actions, and meaningful control outlines.
- A semantic `AppIcon` registry replaces inline placeholder icon selection for migrated screens.
- A Material 3 Appearance screen is reachable from the existing Settings Activity.
- Welcome is migrated to Compose while preserving onboarding completion and next-step routing.
- Chat history is migrated to Compose while preserving open, delete, new-chat, daily-review, local-model gating, chat appearance, and legacy Activity navigation behavior.
- Migrated scaffolds use `WindowInsets.safeDrawing`, apply scaffold padding once, and consume delegated insets.
- A `:shared` KMP module now builds Android and host-JVM portability targets.
- `AppearanceSettings`, accent metadata, semantic `AppIcon` identifiers, typed `AppDestination` identifiers, immutable `ChatThreadSummary`, `ChatThread`, `ChatMessage`, and `ChatRole` now live in `commonMain` and are consumed by Android.
- `GlassesSyncFlow`, Community Plugin card metrics, publish-form state, and the shared plugin-category catalog now live in `commonMain`; Android retains all transfer, networking, and persistence effects.
- The chat vertical slice now has a portable suspend `ChatRepository` contract, immutable thread reducer, composer/attachment/progress state, and semantic appearance-menu actions. `ChatStoreRepository` adapts the current Room-backed Android store; the production chat thread and composer are now Material 3 Compose with Android-only inference, media, and daily-review callbacks retained in the Activity.
- Existing glasses display, connection-state, capability, input-event, device-info, error, and adapter contracts now compile from `commonMain` under their established `bridge.core` package. Android vendor adapters still own all hardware calls.
- Opt-in `iosX64`, `iosArm64`, and `iosSimulatorArm64` framework targets are configured to produce `CyanBridgeShared.framework` on a Mac. `kotlin.native.distribution.downloadFromMaven=true` avoids Kotlin 1.9.24's project-level Ivy repository and preserves settings-owned dependency resolution.
- The tracked Xcode project now contains `CyanBridgeKMPHost`, a simulator-targeted SwiftUI KMP shell that does not link the vendor archive. `QCSDKDemo` is isolated as a device-reference target; `ios/scripts/verify_kmp_host.py` statically checks that separation on Linux. A GitHub Actions macOS workflow validates framework linking, Xcode compilation, simulator install/launch, and captures a screenshot on every push.
- Meeting-summary request/response contracts, deterministic Markdown formatting, and the offline rule-based summarizer now compile from `commonMain`. Android retains Room persistence and its test-only fake summarizer.
- Common tests lock down appearance defaults, stable accent IDs, fallback behavior, and destination identifiers.
- The P2P sync-flow picker and chat appearance overflow menu now render as Compose dialogs. The Android Activities still own their BLE/P2P, preference, picker, and external-app callbacks.
- Community Plugin cards now render server-provided community metadata, including the ChatGPT/Gemini Tasker assistant as a normal plugin card rather than a special banner. The recordings screen renders its four recent synced photos as one full-width weighted row, and the publish-plugin action uses a semantic add icon.
- Automated migration verification currently passes `:shared:portabilityTest`, debug/release unit tests, debug/release lint, release assembly, and 19/19 Android instrumentation tests on an SM-F956B.

Still pending:

- Manual SM-F956B hardware acceptance on 2026-07-15 passed connection, scan, photo/video/audio capture, battery readout, and Wi-Fi Direct P2P media sync. Compose chat thread/composer validation remains useful for IME, gesture and three-button navigation, landscape, split screen, 200 percent font scale, and TalkBack. The old XML layout remains in resources as a rollback reference only and is no longer the production view tree.
- AI image-question capture needs a focused physical retest. Device logs showed a newly requested capture racing the glasses `0x02` photo-ready event, then falling back to an old `Glasses_AI_*` file. The Android flow now serializes one fresh thumbnail request after `0x02`, retries an incomplete/non-decodable response once, and only then considers the age-limited fallback; verify this with a real image question before claiming the feature accepted.
- The Glasses dashboard, device binding, onboarding, battery guidance, notes, Local Agent tools, transcription diagnostics, EvenHub host, Pro subscription, Settings, media, and plugins now render through Compose. The Glasses dashboard deliberately keeps the existing Android control handlers behind a non-visible compatibility adapter so BLE, Wi-Fi Direct, capture, and transfer behavior is unchanged.
- Local-model configuration and Pro subscription settings now render as Material 3 Compose screens. Their existing Android Activity handlers remain non-visible compatibility adapters for downloads, local runtimes, billing, encrypted credentials, permissions, and Studio Bridge lifecycle work.
- The compatibility adapters for Local Models and Pro now mount as explicit hidden siblings behind the visible `ComposeView`; Compose no longer replaces a briefly visible XML root during Activity startup.
- Curated local-model catalog metadata and lookup now compile from `:shared`; Android-only download, storage, runtime, preference, and device-capability adapters remain in `:app`.
- Physical screenshot, TalkBack, font-scale, keyboard, gesture-navigation, and three-button-navigation acceptance runs remain release-quality validation, not blockers for this UI-only migration.
- Compose Multiplatform (CMP) is being integrated so both Android and iOS render from shared `@Composable` screens. The `:shared` module gains CMP Material 3 dependencies; existing Android Compose screens will be migrated to `commonMain` with `org.jetbrains.compose.material3` imports. The iOS host will embed a `ComposeUIViewController` rendered from the same shared composables. A GitHub Actions macOS workflow validates the full CMP stack (framework link, Xcode build, simulator launch).
- A Kotlin upgrade from 1.9.24 to 2.3.0 is planned as part of CMP integration. This unlocks CMP 1.8.0+, requires KAPT-to-KSP migration for Room, and keeps AGP 8.12.1 unchanged. The vendor AAR `-Xskip-metadata-version-check` flag still exists in Kotlin 2.3.0.
- A GitHub Actions macOS workflow now validates all non-device iOS gates (framework link, Xcode compilation, simulator launch). A physical-device acceptance run is still required before treating the iOS framework host as working for device-specific features (Bluetooth, hotspot, QCSDK).

### Toolchain Compatibility Record

The initial Android slice deliberately uses the versions already accepted by the current vendor-sensitive build:

| Component | Current Version | Planned Version |
|---|---|---|
| AGP | 8.12.1 | 8.12.1 (unchanged) |
| Kotlin | 1.9.24 | 2.3.0 |
| Compose compiler extension | 1.5.14 | Built into Kotlin 2.3.0 (via `kotlin.plugin.compose`) |
| Compose BOM (Android) | 2024.04.01 | 2025.06.01 |
| Compose Multiplatform | — | 1.8.0 |
| Activity Compose | 1.10.1 | 1.10.1 (unchanged) |
| Room | 2.6.1 (TOML) / 2.7.0 (hardcoded) | 2.7.0 (unified) |
| Annotation processing | KAPT | KSP |
| Coroutines | 1.7.3 | 1.10.1 |
| Java | 17+ | 17+ |

This is a compatibility baseline, not a claim that every pin is the newest available. Upgrade this set separately from screen migration, with the forked vendor integration, Room/KSP, local inference runtimes, unit tests, and debug APK verified after each version step.

AGP 8.12.1 requires Kotlin 2.3.0+ (not 2.0, 2.1, or 2.2). Kotlin 2.3.0 requires KSP for Room annotation processing (KAPT is removed). The vendor AAR `-Xskip-metadata-version-check` flag still exists in Kotlin 2.3.0. CMP 1.8.0 requires Kotlin 2.0+ and uses Compose compiler 1.5.14 (matching the current extension version).

Kotlin 1.9.24 reports that AGP 8.12.1 is newer than the KMP plugin's maximum tested AGP 8.2. The current Android and portability builds pass, but this warning is not suppressed. Kotlin/Native's default Ivy download repository conflicts with this repository's settings-only dependency policy; `kotlin.native.distribution.downloadFromMaven=true` makes the compiler distribution resolve through the existing Maven Central repository instead. Apple targets are opt-in through `-PenableAppleTargets=true` because Linux cannot link them; macOS framework linking is handled by GitHub Actions.

## Audit Snapshot

Audit baseline:

- `main`: `ecfb1ae` before the current working migration implementation.
- `compose_material3_migration`: `15f810c`.
- `memomind-adapter`: `f3387b9`.
- Common ancestor: `3de11e8`.
- Compose branch divergence at the implementation baseline: 50 commits exist only on current main and 26 commits exist only on the Compose branch.
- MemoMind branch divergence at the implementation baseline: 50 commits exist only on current main and 28 commits exist only on the MemoMind branch.

This is not a small update. Current main added or substantially changed:

- BLE and Wi-Fi Direct media synchronization and retry behavior.
- Local-agent observation, safety, action execution, daily review, and summary flows.
- LiteRT/Gemma multimodal inference and expanded transcription paths.
- Auto-capture, audio ingestion, image-query, and local-model behavior.
- Asaas subscription, cancellation, quota, email, donation, and checkout behavior.
- Studio Bridge voice approval support and encrypted remote-model credentials.
- MemoMind, EvenHub, Mentra, terminal HUD, and Meta Ray-Ban bridge groundwork.
- New settings, onboarding, plugin, debugging, and media behaviors.

Any migration that starts from the old Compose branch would have to reconstruct these changes and is likely to regress production behavior.

## What The Old Compose Branch Already Implements

The old branch is a substantial prototype, not a failed empty branch. It includes:

| Area | Implemented prototype | Reuse guidance |
|---|---|---|
| Foundation | Kotlin 2.0, Compose compiler plugin, Material 3, Navigation Compose | Recreate only compatible pieces in the Android app; do not copy old version pins blindly |
| App shell | `ComposeMainActivity`, `MainNavScreen`, bottom navigation | Reuse route concepts only; replace inset and icon handling |
| Chat | Messages, model picker, history shortcut, input composer, loading and errors | Reuse visual decomposition; reconnect to current chat and inference logic |
| History | Thread list, search, delete, open thread | Reimplemented from current main behavior in the first Android slice |
| Settings | Large Compose settings screen and settings ViewModel | Use as a feature checklist, not as source of truth |
| Theme | Dark/light choice and six accent presets | Replace the color generation and persistence architecture |
| Pro | Subscription and account screens | UI reference only; current billing and Asaas behavior is newer |
| Onboarding | Welcome, battery optimization, permission screens | Reconcile with current onboarding before porting |
| Glasses | Large dashboard with current-at-that-time controls | Split into capability sections; current main has newer devices and actions |
| Recordings | Recording and synced-media screens | Port after current media contracts are isolated |
| Local models | A full Compose configuration screen | Current model engines and settings have changed heavily |
| Plugins | Plugin browsing and management screen | Recheck current publish and patcher behavior |
| Notes | List and detail screens | Suitable for Compose after repository cleanup |
| Local agent | Daily facts, summary, blacklist, captures, pending actions, synced media | Android-only capabilities must remain behind platform interfaces |

The branch has Compose test dependencies but no meaningful Compose UI test suite. The only relevant test found under the branch UI area is the existing `ChatStoreTest`. Runtime layout and accessibility regressions were therefore found manually.

## MemoMind Branch Relationship

`memomind-adapter` is based on the old Material 3 branch. It is not an independent modern base.

It adds two commits after `15f810c`:

- `fec245e`: notification channel adjustment.
- `f3387b9`: signed release build documentation.

Current main now contains most of the bridge core, protocol, runtime, audio, and notification groundwork. The old branch still contains `MemoMindDeviceAdapter.kt`, which current main does not. If that adapter is revived, port that file selectively only after validating it against the current protocol notes and current `GlassesDeviceAdapter` contract. Do not merge the MemoMind branch to obtain it.

## iOS Foundation And Decision Gate

The repository already has a native Objective-C iOS demo under `ios/QCSDKDemo/`.

Code present in the demo suggests these intended capabilities:

- `QCSDK.framework` integration.
- CoreBluetooth scan, connect, reconnect, and device state handling.
- HeyCyan command integration through `QCSDKManager` and `QCSDKCmdCreator`.
- iOS hotspot joining through `NEHotspotConfiguration`.
- HTTP media discovery and download from `/files/media.config` and `/files/<name>`.

These files are protocol evidence, not proof that a production iOS SDK path works. `QCSDKDemo` remains isolated from the KMP framework so vendor-reference changes cannot silently become production shared-state dependencies.

The KMP iOS host (`CyanBridgeKMPHost`) is now validated by a hosted GitHub Actions macOS workflow (`.github/workflows/ios-kmp-host.yml`). The workflow confirms that `CyanBridgeShared.framework` links for `iosSimulatorArm64`, the Xcode project compiles the unsigned SwiftUI host, and the app launches in an iPhone 16 simulator. This does not require an Apple Developer Program membership or a local Mac.

No production transport choice has been made until device flows work on a current physical iPhone.

Static inspection found arm64 Mach-O objects throughout the bundled `QCSDK.framework` archive. That rules out Intel simulator support and does not prove Apple Silicon simulator compatibility, so `CyanBridgeKMPHost` deliberately does not link the vendor archive.

The future decision must compare three options:

| Option | Evidence required before selection |
|---|---|
| Vendor framework wrapper | Current Xcode link succeeds; required architectures exist; scan/connect/command/media flows work on hardware; redistribution terms are acceptable |
| Kotlin protocol implementation | Android packet behavior is documented and covered by transport-independent tests; CoreBluetooth and hotspot behavior can be supplied as thin native adapters |
| Hybrid workaround | Exact vendor functions that must remain native are identified; protocol and application state can otherwise remain Kotlin-owned without duplicated state machines |

Until that evidence exists, portable state and contracts may move into `:shared`, while shared Compose Multiplatform screens move into `shared/commonMain`. The `:app` module and the iOS host both consume these shared composables. The initial direct Xcode hosts deliberately contain no CocoaPods configuration, cinterop binding, or iOS vendor adapter; they only consume `CyanBridgeShared.framework` and are documented in `ios/CYANBRIDGE_KMP_IOS.md`.

With CMP integration, the iOS host transitions from a SwiftUI smoke test to a `ComposeUIViewController` that renders the same shared composables as Android. The `UIViewControllerRepresentable` wrapper embeds the CMP view controller in SwiftUI. iOS-specific features (BLE, hotspot, QCSDK) remain behind platform adapters injected at the host level.

Before iOS release work, verify:

- The license permits bundling `QCSDK.framework` in a new app.
- The framework contains the required device and simulator architecture slices, or a device-only development workflow is documented.
- Its module map and Objective-C headers can be consumed from Swift and, if desired, Kotlin/Native cinterop.
- Apple Bluetooth, local-network, hotspot, microphone, photo-library, and background-mode declarations are complete.
- App Store billing uses StoreKit and does not assume that Play Billing or the Android web checkout is valid on iOS.
- Root ignore rules currently match `*.xcodeproj` and `QCSDK.framework`; the existing tracked demo project is used for the initial host. Explicitly allow any future app-project metadata and define how the vendor binary is supplied without accidentally omitting required files.
- The legacy transfer code still contains aggressive retry paths. Keep credentials redacted and replace retries with a bounded, testable state machine before production reuse.

## Why The Earlier UI Fixes Did Not Stabilize

### Chat Composer

The final branch still uses all of the following:

- An outer navigation `Scaffold` with a bottom `NavigationBar`.
- An inner chat `Scaffold`.
- `Modifier.padding(innerPadding)` around the `NavHost`.
- `imePadding()` on the chat composer.
- A hard-coded `bottom = 68.dp` padding on the composer.

The history shows repeated changes between `navigationBarsPadding()`, `imePadding()`, `60.dp`, and `68.dp` offsets. These were compensating for multiple owners of the same bottom inset rather than fixing the layout model. The result is device-, navigation-mode-, and keyboard-dependent.

### Icons

The prototype initially used unrelated Material icons such as Home, List, Star, and Settings for camera, audio, battery, model, and device actions. Commit `a70b438` removed many of those random icons instead of defining a semantic icon system.

The final bottom bar mixes Material icons with Android-only `ImageVector.vectorResource(R.drawable...)`. Migrated screens instead resolve controls through semantic `AppIcon` names.

### Themes And Accent Profiles

The branch eventually added six accent presets, but the light scheme generates surfaces by blending 30 to 40 percent of the accent into white. This makes the whole app strongly tinted and does not guarantee readable contrast for arbitrary colors.

Theme state is also read directly from Android `SharedPreferences` in both `ComposeMainActivity` and `SettingsViewModel`. Dynamic color exists as a parameter but is not a complete user-facing policy. This architecture is Android-specific and duplicates ownership of theme state.

## Android MVP Product Scope

- Preserve every current production behavior.
- Replace the main shell, Chat, History, Appearance settings, and selected low-risk screens with Material 3.
- Keep unported Activities reachable through explicit platform navigation.
- Add reliable adaptive layout, keyboard, icon, and accessibility tests.
- Keep Android framework and vendor operations outside composables.
- Make presentation state immutable where practical and expose UI actions as callbacks/events.
- Defer all iOS parity commitments until the decision gate above is resolved.

## Android-First Target Architecture

Keep the current Android app module and organize migrated UI so a later extraction remains possible:

```text
android/CyanBridge/
    shared/src/commonMain/kotlin/
    appearance/                portable settings and accent metadata
    chat/                      immutable presentation models
    icons/                     semantic icon identifiers
    navigation/                typed destination identifiers
    notes/                     transcript summary contracts and formatting
  app/src/main/java/com/fersaiyan/cyanbridge/
    ui/appearance/             appearance state, persistence adapter, and screen
    ui/theme/                  Material 3 schemes and tokens
    ui/icons/                  semantic icon registry
    ui/<feature>/              state-driven migrated screens
    <feature>/                 current repositories, services, and Android adapters
```

Do not split `:shared` into more modules merely to simulate future architecture. Move only proven platform-neutral files after establishing clean Android state/event seams and tests.

### Portability Rules

Preferred in portable models, reducers, and composables:

- Immutable UI state and events.
- Chat, thread, theme, device-capability, and display-command models.
- Repository and platform-service interfaces.
- Coroutines and Flow.
- Serialization and HTTP contracts that do not expose Android types.
- Compose Material 3 UI without `Context`, Activities, Services, Intents, or direct preference access.

Keep out of portable presentation code:

- `android.*`, Android `Context`, Activities, Services, Intents, or `R` references.
- Direct `SharedPreferences` access.
- Android BLE, Wi-Fi P2P, MediaStore, Accessibility, Tasker, Play Billing, or Meta DAT calls.
- Global service creation, internally owned application coroutine scopes, and direct singleton mutation from composables.

Use constructor/callback interfaces for platform actions. Decide whether any small primitives justify `expect`/`actual` only when a second native platform requires them.

### Existing Code That Is Close To Shareable

- `bridge/core/DisplayCommand.kt`.
- `bridge/core/InputEvent.kt`.
- `bridge/core/GlassesCapability.kt`.
- `bridge/core/GlassesBridgeState.kt`.
- `bridge/core/DeviceInfo.kt`.
- `bridge/core/GlassesDeviceAdapter.kt`.
- `chat/ChatModels.kt` after removing mutable fields where practical.

`GlassesBridge.kt` is not yet common-ready because it imports `android.util.Log`, owns a global singleton, and creates an IO scope internally. Replace it with an injected instance, a logger interface, and an owned lifecycle before moving it.

`ChatStore.kt` remains Android-specific because it uses application state, Room entities, blocking calls, and `java.util.UUID`. A suspend `ChatRepository` contract and `ChatStoreRepository` adapter now isolate that implementation for the next chat-thread migration step.

### Android Platform Adapters

Android adapters remain responsible for:

- Vendor AAR and HeyCyan BLE callbacks.
- Wi-Fi Direct process binding and media download.
- Android permissions and Activity Result APIs.
- Accessibility and local-agent services.
- MediaStore and Android audio APIs.
- Play Billing, web checkout, and deep links.
- Meta DAT and Android MemoMind transports.

## Toolchain Gate

Current main uses Kotlin 1.9.24. The old branch moved to Kotlin 2.0.0 and old Compose/Navigation pins. Do not reuse those pins.

Before a future toolchain upgrade:

1. Select one mutually compatible stable set of Kotlin, Compose compiler/plugin, AGP, KAPT or KSP, coroutines, navigation, and database versions.
2. Record the versions in `libs.versions.toml` and add a short compatibility note to this document.
3. Upgrade current main without changing the launcher Activity or production UI.
4. Run Android unit tests and `assembleDebug` after each toolchain step.
5. Verify the forked vendor integration and all local inference runtime variants used by development builds.

## Migration Phases

### Phase 0: Preserve And Inventory

Status: in progress. Manifest and UI inventory is recorded below; physical baseline captures remain pending.

Tasks:

- Preserve old branch tips and make migration changes only from current main behavior.
- Generate a current feature-parity checklist from Activities, manifest entries, layouts, services, receivers, and deep links.
- Mark every feature as Compose, hybrid, legacy, Android platform, or deferred.
- Capture Android baseline screenshots and manual flows before changing UI.
- Keep `/backups/compose_material3_port/` as reference only; compare every restored file against current main before use.

Exit criteria:

- No current main feature is missing from the parity checklist.
- Baseline `testDebugUnitTest` and `assembleDebug` pass.

### Phase 1: Android Compose And KMP Foundation

Status: complete for the initial Android production slice and narrow common model layer. Native iOS targets are outside this phase.

Tasks:

- Enable Compose in the existing Android `:app` module.
- Add Material 3, tooling, and Compose test dependencies without changing production routing.
- Add a minimal Android `CyanBridgeTheme` and render a low-risk production screen.
- Add one `:shared` KMP module with Android and host-JVM portability targets.
- Move appearance, semantic icon, typed destination, and chat-history presentation identifiers into `commonMain`.
- Keep shared Compose UI deferred. Native iOS targets are opt-in under Phase 6 and require a Mac/Xcode link before they are treated as usable.

Exit criteria:

- Existing Android launcher and onboarding routing behavior is unchanged.
- Android renders Compose Material 3 UI in production Activities.
- Common models compile for Android and the host-JVM portability target.
- Common tests pass independently of Android framework state.
- Baseline unit tests and debug assembly pass.

### Phase 2: Material 3 Design System

Status: implemented for initial tokens, curated themes, persistence, dynamic color, high contrast, and semantic icons. Physical accessibility review remains pending.

Tasks:

- Implement semantic colors, typography, shapes, spacing, and elevation tokens.
- Add `ThemeMode.SYSTEM`, `ThemeMode.LIGHT`, and `ThemeMode.DARK`.
- Add stable accent IDs such as Cyan, Rose, Mint, Lavender, Peach, and Sky.
- Use complete reviewed light and dark color schemes for each profile.
- Add Android dynamic color as an optional Android-only profile, not the global default.
- Persist theme settings through `AppearancePreferences`; composables receive state and events rather than reading preferences directly.
- Build a semantic icon registry. Add custom Compose vectors only where no semantically correct Material icon exists.

Exit criteria:

- Theme changes update immediately on migrated Android screens.
- Selection survives process restart.
- Every text/background pair meets the agreed contrast threshold.
- Migrated Compose UI does not import Android `R` or `vectorResource` for controls.

### Phase 3: Navigation Shell And Chat Vertical Slice

Status: chat history, chat thread, and composer now use Material 3 Compose. The chat Activity retains current Android-only inference, attachment, permissions, and daily-review behavior behind callbacks. Automated Compose smoke coverage exists; physical keyboard/inset acceptance remains pending.

Tasks:

- Introduce typed destinations for the Material 3 shell while legacy Activities remain the actual route hosts.
- Port History first against current main logic.
- Drive the existing chat thread through the new `ChatRepository` boundary, then port the thread and composer as one layout owner. Completed with `ChatStoreRepository` reads/writes at the presentation boundary.
- Keep legacy Android Activities available for unported destinations.
- Implement the composer using one explicit inset owner and no fixed bottom offset.
- Add Android keyboard/inset tests before treating the Compose chat thread as fully accepted on physical devices.

Exit criteria:

- Chat send, response, history, new thread, model choice, errors, and daily-review entrypoints retain current behavior on Android.
- Composer remains visible with gesture navigation, three-button navigation, keyboard open/closed, landscape, split screen, and 200 percent font scaling.

### Phase 4: Appearance And Settings

Status: Appearance and the full Settings surface are implemented in Material 3 Compose. Android-owned intents, services, encrypted preferences, and dialogs remain Activity-owned effects.

Tasks:

- Port Appearance first, then settings sections one at a time.
- Drive all settings from state and events; do not access Android preferences directly from composables.
- Keep Android-only settings clearly labeled and backed by Android platform actions.
- Add reset-to-default, live preview, selected-state labels, and high-contrast-safe choices.
- Preserve current remote model, Studio Bridge, local-agent, privacy, transcription, media, and subscription settings.

Exit criteria:

- Settings parity checklist is complete.
- Theme personalization is accessible without relying only on color.
- No secret is stored in an unencrypted shared preference.

### Phase 5: Port Remaining Screens Incrementally

Status: complete for Android Activity root surfaces. XML layouts remain only as hidden compatibility adapters or rollback references where mature Android handlers still reference views; they are not the production view tree.

Completed migration order:

1. Notes.
2. Recordings and synced-media list.
3. Plugins.
4. Glasses status and capability dashboard.
5. Pro account and subscription status.
6. Local-model configuration.
7. Local-agent screens.
8. Onboarding and permission education.

Rules:

- Port from current main behavior, using the old branch only for visual reference.
- Split very large screens into state-driven sections before moving them.
- Do not delete an Activity until its Compose replacement has parity tests and deep-link coverage.
- Android-only operations stay in injected platform services.

### Phase 6: Compose Multiplatform UI Convergence

Status: in progress. CMP 1.6.11 toolchain validated; Kotlin 2.3.0 upgrade planned.

Goal: Both Android and iOS render from the same shared `@Composable` screens in `shared/commonMain`. Same screens, same look, same behavior — debug once, fix once.

#### Phase 6a: CMP Toolchain Setup

Status: in progress.

Tasks:

- Add JetBrains Compose Multiplatform plugin (`org.jetbrains.compose` 1.6.11) to `:shared`.
- Add `compose.runtime`, `compose.foundation`, `compose.material3`, `compose.ui` dependencies to `commonMain`.
- Change iOS simulator targets to dynamic framework (`isStatic = false`) for Skiko compatibility; keep `iosArm64` static for device.
- Add the JetBrains Compose Maven repository (`maven.pkg.jetbrains.space/public/p/compose/dev`) to `settings.gradle.kts` if needed.
- Create a trivial shared composable (`CyanBridgeApp`) in `commonMain` to validate the toolchain.
- Create `iosMain` source set with `ComposeUIViewController` entry point (`MainViewController`).
- Update `CyanBridgeKMPHostApp.swift` to embed the CMP `UIViewController` via `UIViewControllerRepresentable`.
- Update `verify_kmp_host.py` and CI workflow for CMP.

Exit criteria:

- `:shared:portabilityTest` passes.
- `:app:assembleDebug` passes (existing Android Compose unaffected).
- `:shared:linkDebugFrameworkIosSimulatorArm64` produces a dynamic framework.
- Xcode build + simulator launch shows CMP-rendered content.
- CI screenshot captures CMP UI (not SwiftUI strings).

#### Phase 6b: Kotlin Upgrade to 2.3.0

Status: planned. Depends on Phase 6a validation.

Tasks:

- Upgrade Kotlin from 1.9.24 to 2.3.0 in `libs.versions.toml`.
- Add `kotlin("plugin.compose")` plugin (Compose compiler is now built into Kotlin 2.0+).
- Remove `composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }` from `app/build.gradle`.
- Migrate Room annotation processing from KAPT to KSP (`com.google.devtools.ksp`).
- Update Room from 2.6.1 to 2.7.0 in `libs.versions.toml` (already hardcoded in `app/build.gradle`).
- Update Compose BOM from `2024.04.01` to `2025.06.01`.
- Update coroutines from 1.7.3 to 1.10.1.
- Keep `-Xskip-metadata-version-check` for vendor AAR compatibility.
- Update CMP from 1.6.11 to 1.8.0.
- Migrate `kotlinOptions` DSL to `compilerOptions` where needed.

Exit criteria:

- `:shared:portabilityTest` passes.
- `:app:testDebugUnitTest` passes.
- `:app:lintDebug` passes.
- `:app:assembleRelease` passes.
- Vendor AAR compiles without metadata errors.

#### Phase 6c: Extract Remaining Contracts + First Shared Screens

Status: planned. Depends on Phase 6b validation.

Tasks:

- Extract `FutureBackendContracts.kt` (92 lines) to `shared/commonMain/.../shared/memoryvault/VaultContracts.kt`.
- Extract `FakeSummarizationService.kt` (32 lines) to `shared/commonMain/.../shared/notes/FakeSummarizationService.kt`.
- Extract `DeviceProfile.kt` (12 lines) to `shared/commonMain/.../shared/devices/DeviceProfile.kt`.
- Extract `GlassesManagerGating.kt` (53 lines) to `shared/commonMain/.../shared/devices/GlassesManagerGating.kt`.
- Migrate `AppearanceScreen.kt` from `app/ui/appearance/` to `shared/commonMain/.../shared/ui/appearance/`. Change imports from `androidx.compose.*` to `org.jetbrains.compose.*`. The `:app` module imports the shared composable.
- Migrate `ChatListScreen.kt` to shared. Same import changes.
- Wire Android app to call shared composables inside `setContent {}`.
- Wire iOS `MainViewController` to call the same shared composables.

Exit criteria:

- Android renders shared Appearance screen identically to before.
- iOS simulator renders the same shared Appearance screen.
- `:shared:portabilityTest` passes with tests for extracted contracts.

#### Phase 6d: Shared Navigation Shell + Remaining Screens

Status: planned. Depends on Phase 6c.

Tasks:

- Build a shared bottom-navigation shell (`CyanBridgeNavShell`) in `commonMain` using `AppDestination` and `AppIcon`.
- Migrate screens incrementally to shared `commonMain`:
  1. Appearance (done in 6c).
  2. Chat History.
  3. Chat Thread + Composer.
  4. Settings.
  5. Recordings / Synced Media.
  6. Community Plugins / Publish Plugin.
  7. Glasses Dashboard.
  8. Local Models Configuration.
  9. Notes.
  10. Local Agent screens.
  11. Pro Subscription.
  12. Onboarding / Welcome.
  13. Transcription Debug.
  14. EvenHub.
- Each screen migration: copy composable to `shared/commonMain`, change imports, remove `android.*`/`Context`/`R.*` references, `:app` imports from shared.
- iOS gets each screen for free as it's added to shared.

Exit criteria:

- Both platforms render from the same shared composables.
- Android physical-device acceptance passes.
- iOS simulator renders all shared screens.
- CI screenshot captures the shared nav shell.

#### Phase 6e: CI Expansion + Documentation

Status: planned. Depends on Phase 6d.

Tasks:

- Update CI workflow for CMP (Skiko repos, dynamic framework, screenshot validation).
- Update `verify_kmp_host.py` for CMP entry points.
- Add optional perceptual screenshot diff step.
- Update `COMPOSE_MIGRATION_PLAN.md`, `ios/CYANBRIDGE_KMP_IOS.md`, `AGENTS.md`, `README.md`.

### Phase 7: Cutover And Cleanup

Tasks:

- Make the Material 3 host the Android launcher only after parity acceptance.
- Remove XML layouts and legacy Activities only when no manifest, deep link, service, or test references them.
- Replace temporary adapters with stable presentation/platform contracts.
- Add release builds, obfuscation checks, crash reporting policy, and migration notes.
- Keep the Android feature matrix current; add and maintain a separate iOS matrix once a production transport path begins.

Exit criteria:

- Android production build passes and physical-device core flows pass.
- No known navigation, keyboard, icon, contrast, or secret-storage blocker remains.

### Phase 8: Accessible Assistive Vision And Multilingual UX

Status: in progress. This phase is driven by feedback from blind and low-vision glasses users, including real-world Android 16 and M02 Ultra testing.

Goal: Make CyanBridge dependable for hands-free scene awareness while ensuring that every primary control is usable with TalkBack and that the shared CMP UI is available in the user's language.

Tasks:

- Add first-class AI vision profiles shared by local and Pro providers. The initial profiles are Walking (short, hazard- and landmark-focused responses) and Detailed (richer scene descriptions), with user-editable instructions.
- Pass the active vision profile prompt to the first multimodal request for both local and cloud inference. Do not create an English-only image description and attempt to translate it in a second request.
- Preserve local-model system instructions for multimodal requests; LiteRT image requests must not discard the configured system prompt.
- Select TTS voice/language from the active vision profile and report unavailable voice data clearly.
- Add Walking Mode: an explicitly user-started foreground service that captures, analyzes, and speaks on a 5, 10, or 30 second cadence. Schedule from completion, never queue captures, and provide a persistent Stop action.
- Treat Walking Mode as situational awareness, not a path-safety guarantee. It must not describe a path as safe based on a single delayed image.
- Keep Walking Mode isolated from media sync, OTA, live preview, video, and meeting/audio capture using the existing glasses session coordinator.
- Persist the selected glasses MAC as the primary reconnect target. Reconnection must not depend on advertising-name heuristics that exclude devices such as M02 Ultra.
- Stabilize the scan screen for TalkBack: do not clear/reorder results on resume, throttle RSSI-only updates, preserve focus, and expose an explicit Connect button for each device.
- Replace informational plugin cards with real, accessible actions only when an install/select flow exists. Each action must be a standard semantic button with installed/enabled state and TalkBack feedback.
- Introduce shared Compose resources for English, Portuguese (Brazil), Spanish, German, French, Italian, Simplified Chinese, and Russian. Move active shared-screen literals into those resources rather than creating Android-only translations.
- Add an app-language setting using Android per-app locales and make accessibility labels, notifications, dialogs, and errors localizable alongside visible text.

Acceptance criteria:

- A Russian Walking profile produces Russian text and Russian TTS from both local LiteRT and Pro image requests.
- The initial cloud image request contains the active profile instruction and does not contain a hard-coded English translation directive.
- A saved M02 Ultra reconnects when the app opens without requiring the scan screen, provided Bluetooth and required permissions are available.
- TalkBack can reach and activate Scan, Connect, profile selection, and every shipped plugin action without focus jumping.
- The primary glasses, pairing, settings, and plugin surfaces render localized copy for all eight supported languages.
- Walking Mode has physical-device tests covering disconnect, cancellation, TTS delay, active media sync, OTA, and live preview.

## Required Fix Designs

### Chat Insets And Keyboard

Use these rules instead of another padding tweak:

- Never use a hard-coded bottom value to represent a navigation bar.
- The root shell owns the app navigation bar inset.
- The chat composer owns the IME inset.
- Apply scaffold padding once and call `consumeWindowInsets` when passing it to nested content.
- Prefer hiding app bottom navigation while the IME is visible if keeping it produces two stacked controls.
- Put the composer in the chat scaffold's `bottomBar`, not at the end of an arbitrarily padded Column.
- Use Android `WindowInsets` APIs; add platform safe-area rules only if iOS work resumes.

Required test matrix:

| Case | Expected |
|---|---|
| Android gesture navigation, keyboard closed | Composer above system gesture area and app navigation |
| Android gesture navigation, keyboard open | Composer immediately above IME, fully tappable |
| Android three-button navigation | No overlap or double bottom gap |
| Android landscape and split screen | Composer and send action remain reachable |
| Android 200 percent font scale | Input can grow without hiding send action |

iOS safe-area and keyboard cases will be added if iOS implementation resumes.

### Icons

Create semantic icon names instead of choosing icons inline:

```kotlin
enum class AppIcon {
    Glasses,
    Chat,
    Recordings,
    Settings,
    Plugins,
    Camera,
    Video,
    Microphone,
    Battery,
    Sync,
    Model,
    Send,
}
```

Implementation rules:

- Store custom icons as Compose-compatible vectors when Material has no exact semantic match.
- Use a Material icon only when its meaning is exact.
- Do not use Home for Chat, List for audio, Star for arbitrary AI actions, or emoji as control icons.
- Decorative icons have null descriptions; actionable icons have localized descriptions.
- Icon-only controls must have at least a 48 dp touch target.
- Selected navigation state must use label, color, and/or shape, not an unexplained icon swap alone.

### Theme And Accent Accessibility

Model preferences explicitly:

```kotlin
data class AppearanceSettings(
    val themeMode: ThemeMode,
    val accentProfileId: String,
    val useDynamicColor: Boolean,
    val highContrast: Boolean,
)
```

Rules:

- Keep background and surface colors neutral; accents belong primarily on actions, selection, focus, links, and small containers.
- Do not tint the entire light background 30 to 40 percent toward the accent.
- Ship curated, tested light/dark schemes before allowing arbitrary custom colors.
- If a custom color picker is added later, derive a tonal palette and reject or correct combinations that fail contrast.
- Meet at least 4.5:1 for normal text, 3:1 for large text and meaningful UI graphics, and 3:1 for control boundaries where required.
- Support system theme, font scaling, screen readers, switch control, reduced motion, and color-vision differences.
- Show a name and selected marker for each accent; color circles alone are not accessible.

## Feature Parity And Platform Matrix

Every route must be recorded in a maintained table during implementation.

Use these Android migration statuses:

- `compose`: production Material 3 screen.
- `hybrid`: Compose entry/shell with a legacy Android action or destination.
- `legacy`: current XML/View Activity retained.
- `android-platform`: Android service, receiver, deep link, or hardware adapter.
- `deferred`: intentionally outside the Android MVP.

Current route and capability inventory:

| Feature or entry point | Status | Migration note |
|---|---|---|
| `WelcomeActivity` | compose | Preserves onboarding-complete bypass and setup route |
| Battery optimization guide | compose | Material 3 guide; Android settings intents remain Activity-owned |
| Feature onboarding | compose | Material 3 disclosure and preference controls; Android accessibility intent remains Activity-owned |
| `MainActivity` glasses dashboard | compose | Material 3 dashboard; existing BLE, Wi-Fi Direct, capture, and media handlers remain Activity-owned through a compatibility adapter |
| Device binding | compose | Material 3 scan/pairing screen; Android Bluetooth scanner remains Activity-owned |
| Chat history | compose | Shared immutable summary state; current `ChatStore`, daily review, local-model gating, and appearance retained |
| Chat thread and composer | compose | One Material 3 scaffold owns content, composer, IME, and conditional app navigation; Android inference/media callbacks remain Activity-owned |
| Appearance | compose | Persisted modes, curated accents, dynamic color, high contrast, reset, preview |
| Settings | compose | Compose sections preserve automation, privacy, vault, data, support, agent, and Android permission/service operations |
| Recordings and synced media | compose | Compose list and gallery preserve playback, transcription, active-recording visibility, MediaStore queries, and external viewer actions |
| Community and publish plugins | compose | Compose browser and publishing form preserve server refresh, Tasker setup, form validation, and submission behavior |
| Notes list and detail | compose | Material 3 list, transcript note creation, copy, and share; Room repository remains Activity-owned |
| Pro subscription and callback | hybrid | Material 3 subscription and settings screens with existing billing/checkout handlers retained behind adapters; callback remains android-platform |
| Local models configuration | compose | Material 3 configuration screen; runtime, download, storage, encrypted credentials, and Studio Bridge behavior remain Activity-owned through typed presentation actions |
| Local-agent screens | compose | Material 3 facts, summaries, capture history, pending-action approval, and blacklist tools; accessibility and storage behavior remain Activity-owned |
| Transcription debug | compose | Material 3 developer surface; transcription pipeline remains Activity-owned |
| EvenHub runtime | hybrid | Material 3 host with an AndroidView WebView interoperability boundary |
| `cyanbridge://` callback | android-platform | Handled by `MainActivity` |
| `https://cyanbridge.vercel.app/web-subscribe/callback` | android-platform | Auto-verified app link handled by subscription callback Activity |
| Meeting, local-agent, auto-audio, Studio Bridge services | android-platform | Do not move into composables |
| Notification listener and daily reminder receiver | android-platform | Preserve manifest and permission behavior |
| HeyCyan BLE and Wi-Fi Direct transfer | android-platform | Forked/current Android implementation remains authoritative |
| Meta DAT and MemoMind transports | android-platform | Capability-gated Android integrations |
| iOS KMP framework host | hybrid | CMP `ComposeUIViewController` embedded in SwiftUI via `UIViewControllerRepresentable`; CI validates framework link, Xcode build, simulator launch |
| iOS application transport | deferred | Await vendor-wrapper, Kotlin-protocol, or hybrid decision after device tests |

## Verification Gates

Run on every migration phase:

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest assembleDebug
JAVA_HOME=/opt/android-studio/jbr ./gradlew :shared:portabilityTest
JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:lintDebug
JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:connectedDebugAndroidTest
```

Add as screens migrate:

- Unit tests for reducers, repositories, theme selection, contrast, persistence, and capability gating.
- Compose UI tests for navigation, Chat composer placement, settings state, and content descriptions.
- Screenshot/golden tests for each theme and accent on representative phone sizes.
- Android instrumented tests for IME and system insets.
- Physical Android tests for BLE and media transfer after any host-screen migration.

## Future Agent Start Checklist

Before writing migration code:

1. Confirm current `main` and re-run divergence counts.
2. Read this plan and `android/AGENTS.md`.
3. Read the old branch versions of `ChatScreen.kt`, `MainNavScreen.kt`, `CyanBridgeTheme.kt`, and the desired screen only.
4. Read the current main Activity, layout, ViewModel/service, manifest entries, and tests for that feature.
5. Update the parity matrix before deleting or replacing anything.
6. Keep old branches and `/backups/compose_material3_port/` read-only unless a file is deliberately restored and reconciled.
7. Make the smallest vertical slice build on Android; iOS work may add only framework-host and platform-contract groundwork until its vendor transport decision gate is resolved.
8. Do not mark a phase complete from compilation alone; run the manual and UI acceptance cases.

## Explicit Non-Goals

- No big-bang deletion of XML and Activities.
- No wholesale cherry-pick of the old Compose commits.
- No production iOS vendor transport, billing, or parity claim before the iOS decision gate and physical-device validation.
- No attempt to hide Android framework APIs behind premature portability abstractions.
- No fixed pixel/dp workaround for system insets.
- No arbitrary accent generation without contrast validation.
- No icon placeholders chosen only because they compile.

## Definition Of MVP Done

The migration MVP is complete when:

- Current Android production behavior remains available.
- The Android launcher uses the modern shell for the selected MVP screens.
- Chat input is reachable across the required keyboard and navigation test matrix.
- Semantic icons are consistent and Compose-resource compatible.
- Theme mode and accent profiles are persistent, accessible, and contrast-tested.
- Platform-only features are clearly gated rather than crashing or silently disappearing.
