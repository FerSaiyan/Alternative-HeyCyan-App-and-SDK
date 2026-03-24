# Shared Core Phase 1 Log

Date: 2026-03-10

## Phase 2 completion checkpoint

- Completed migration-plan Phase 2 checklist items:
  - move pure helpers/models/interfaces first
  - add compatibility adapters in source apps
  - replace direct app-internal imports with core module imports
  - keep package moves incremental
  - add regression tests around extracted behavior
- Added shared-core regression tests for extracted P2P state helpers:
  - `core-connectivity/src/test/java/com/heycyan/core/connectivity/p2p/WifiP2pRetryStateTest.java`
  - `core-connectivity/src/test/java/com/heycyan/core/connectivity/p2p/WifiP2pConnectionStateTest.java`

Validation:

- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew :heycyan-android-core:core-connectivity:assembleDebug :heycyan-android-core:core-connectivity:testDebugUnitTest` (from `android/NinjaConceptsManagerApp`) -> PASS
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest` (from `android/NinjaConceptsManagerApp`) -> PASS
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest` (from `android/CyanBridge`) -> PASS
- `python3 tools/check_public_safety.py` (from `heycyan-android-core`) -> PASS
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew :heycyan-android-core:core-connectivity:assembleRelease` (from `android/NinjaConceptsManagerApp`) -> PASS
- `python3 /home/fertroll10/Documents/ML/heycyan-android-core/tools/check_binary_compat.py --baseline-version 0.1.0` -> PASS (`added classes: 5`, `no removed classes`)

## Phase 3 completion checkpoint (CyanBridge integration)

- Confirmed no regressions in key shared connectivity user flows after batch-1 extraction updates:
  - P2P broadcast handling path (`WifiP2pBroadcastReceiver` wrapper -> core receiver)
  - P2P callback contract path (`WifiP2pCallback`)
  - P2P retry + connection-state path (`WifiP2pRetryState`, `WifiP2pConnectionState`)
- Verified both integration modes:
  - local composite-build mode (`useLocalSharedCore=true`)
  - artifact fallback mode (`useLocalSharedCore=false`) using published `0.1.0-SNAPSHOT`

Validation:

- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest assembleDebug` (from `android/NinjaConceptsManagerApp`) -> PASS
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest assembleDebug` (from `android/CyanBridge`) -> PASS
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew :heycyan-android-core:core-connectivity:publishReleasePublicationToMavenLocal --rerun-tasks` (from `android/NinjaConceptsManagerApp`) -> PASS
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew -PuseLocalSharedCore=false -PheycyanCoreConnectivityVersion=0.1.0-SNAPSHOT testDebugUnitTest assembleDebug` (from `android/NinjaConceptsManagerApp`) -> PASS
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew -PuseLocalSharedCore=false -PheycyanCoreConnectivityVersion=0.1.0-SNAPSHOT testDebugUnitTest assembleDebug` (from `android/CyanBridge`) -> PASS

Operational note:

- The pinned release version (`heycyanCoreConnectivityVersion=0.1.0`) does not yet include the newly extracted P2P APIs; fallback checks for latest extraction work were run against published `0.1.0-SNAPSHOT` pending next release bump.

## Release alignment + public-safety audit checkpoint

- Published `core-connectivity` version `0.2.0` to local Maven for artifact-mode verification.
- Added release notes:
  - `heycyan-android-core/releases/0.2.0.md`
- Updated consumer app pins:
  - `android/NinjaConceptsManagerApp/gradle.properties` -> `heycyanCoreConnectivityVersion=0.2.0`
  - `android/CyanBridge/gradle.properties` -> `heycyanCoreConnectivityVersion=0.2.0`
- Updated smoke script to publish the currently pinned version before fallback checks:
  - `tools/shared_core_smoke_check.sh`
- Created migration branches in both app repos:
  - `NinjaConceptsSDK`: `shared-core-migration`
  - `HeyCyanSmartGlassesSDK`: `shared-core-migration`

Weekly public-safety audit (2026-03-11):

- `python3 tools/check_public_safety.py` (from `heycyan-android-core`) -> PASS
- Searched shared-core for forbidden app-private package references (`com.ninjaconcepts.manager`, `com.fersaiyan.cyanbridge`) -> none found
- Searched shared-core for explicit API-key token string (`OPENAI_API_KEY`) -> no usage in core sources; only expected rule pattern in `tools/check_public_safety.py`

Validation:

- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew :heycyan-android-core:core-connectivity:assembleDebug :heycyan-android-core:core-connectivity:testDebugUnitTest -Pversion=0.2.0` (from `android/NinjaConceptsManagerApp`) -> PASS
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew :heycyan-android-core:core-connectivity:publishReleasePublicationToMavenLocal -Pversion=0.2.0 --rerun-tasks` (from `android/NinjaConceptsManagerApp`) -> PASS
- `python3 /home/fertroll10/Documents/ML/heycyan-android-core/tools/check_binary_compat.py --baseline-version 0.1.0` -> PASS (`added classes: 5`, `no removed classes`)
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew -PuseLocalSharedCore=false testDebugUnitTest assembleDebug` (from `android/NinjaConceptsManagerApp`) -> PASS
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew -PuseLocalSharedCore=false testDebugUnitTest assembleDebug` (from `android/CyanBridge`) -> PASS
- `bash tools/shared_core_smoke_check.sh` (from `NinjaConceptsSDK`) -> PASS

## Repository bootstrap

- Created local shared repository scaffold at:
  - `/home/fertroll10/Documents/ML/heycyan-android-core`
- Initialized repository and base files:
  - `settings.gradle.kts`
  - `build.gradle.kts`
  - `gradle.properties`
  - `.gitignore`
  - `README.md`
  - `CORE_NAMESPACE_POLICY.md`
  - `.github/workflows/core-ci.yml`
  - `.github/CODEOWNERS`
  - `tools/check_public_safety.py`

## First shared module

- Module: `core-connectivity`
- Android library module created with namespace:
  - `com.heycyan.core.connectivity`
- First extracted classes moved into shared module:
  - `com.glasssutdio.wear.wifi.Logger`
  - `com.glasssutdio.wear.wifi.TypeEnum`
  - `com.glasssutdio.wear.wifi.LocationUtils`
  - `com.glasssutdio.wear.wifi.WeakHandler`
  - `com.glasssutdio.wear.wifi.utils.Elvis`
  - `com.glasssutdio.wear.wifi.utils.VersionUtils`

## App integration status

### Ninja Concepts Manager

- Added composite build include in:
  - `android/NinjaConceptsManagerApp/settings.gradle.kts`
- Added dependency:
  - `implementation("com.heycyan.core:core-connectivity:0.1.0-SNAPSHOT")`
- Removed local duplicates for extracted classes from Ninja app source.

Validation:

- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest` (in `android/NinjaConceptsManagerApp`) -> PASS
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug` (in `android/NinjaConceptsManagerApp`) -> PASS

### CyanBridge

- Added composite build include in:
  - `android/CyanBridge/settings.gradle.kts`
- Added dependency:
  - `implementation("com.heycyan.core:core-connectivity:0.1.0-SNAPSHOT")`
- Removed local duplicates for extracted classes from CyanBridge app source.

Validation:

- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest` (in `android/CyanBridge`) -> PASS
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug` (in `android/CyanBridge`) -> PASS

## Pending

- Continue batch-1 extraction with additional connectivity classes.

## Artifact fallback verification

- Published `core-connectivity` release artifact to `mavenLocal` via composite build task:
  - `JAVA_HOME=/opt/android-studio/jbr ./gradlew :heycyan-android-core:core-connectivity:publishReleasePublicationToMavenLocal`
    (run from `android/NinjaConceptsManagerApp`)
- Verified Ninja fallback path (no composite include):
  - `JAVA_HOME=/opt/android-studio/jbr ./gradlew -PuseLocalSharedCore=false testDebugUnitTest` -> PASS
  - `JAVA_HOME=/opt/android-studio/jbr ./gradlew -PuseLocalSharedCore=false assembleDebug` -> PASS
- Verified CyanBridge fallback path (no composite include):
  - `JAVA_HOME=/opt/android-studio/jbr ./gradlew -PuseLocalSharedCore=false testDebugUnitTest` -> PASS
  - `JAVA_HOME=/opt/android-studio/jbr ./gradlew -PuseLocalSharedCore=false assembleDebug` -> PASS

## Public-safety guardrail

- Added `tools/check_public_safety.py` in shared-core.
- CI now runs the guardrail script before module build/test.

## Operational hardening additions

- Added binary compatibility checker in shared-core:
  - `tools/check_binary_compat.py`
- Added shared-core release/policy docs:
  - `RELEASE_RUNBOOK.md`
  - `DEPRECATION_POLICY.md`
  - `RELEASE_NOTES_TEMPLATE.md`
  - `OWNERSHIP.md`
- Added manual cross-repo CI matrix workflow in Ninja repo:
  - `.github/workflows/shared-core-consumer-matrix.yml`
- Added shared-core publish workflow:
  - `.github/workflows/publish-core.yml`

## Cross-repo smoke script

- Added `tools/shared_core_smoke_check.sh` in Ninja repo to run:
  1. publish shared-core artifact to `mavenLocal`
  2. Ninja tests with `-PuseLocalSharedCore=false`
  3. CyanBridge tests with `-PuseLocalSharedCore=false`

## Version discipline bootstrap

- Added pinned shared-core version property in both app repos:
  - `heycyanCoreConnectivityVersion=0.1.0-SNAPSHOT`
- App dependencies now read this property instead of hardcoding version strings.
- Added upgrade/rollback instructions in `SHARED_CORE_UPGRADE_PLAYBOOK.md`.

## Extra validation

- Verified shared-core local publication still works:
  - `JAVA_HOME=/opt/android-studio/jbr ./gradlew :heycyan-android-core:core-connectivity:publishReleasePublicationToMavenLocal` -> PASS
- Verified binary compatibility script against local baseline:
  - `python tools/check_binary_compat.py --baseline-version 0.1.0-SNAPSHOT` -> PASS

## Post-extraction inventory snapshot

- Updated overlap report after first extraction:
  - `SHARED_CORE_INVENTORY_CURRENT.md`
  - Current direct overlaps reduced from 58 -> 39 after second extraction pass.

## Batch-1 second extraction pass

- Moved 13 additional Wi-Fi callback/enums interfaces into shared core:
  - `wifiConnect/*` callback and error interfaces
  - `wifiDisconnect/*` callback and error interfaces
  - `wifiRemove/*` callback and error interfaces
  - `wifiScan/*` callback interfaces
  - `wifiState/*` callback interfaces
  - `wifiWps/ConnectionWpsListener`
- Removed duplicate copies from Ninja and CyanBridge app source trees.

Validation:

- `JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest` (Ninja) -> PASS
- `JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest` (CyanBridge) -> PASS
- `JAVA_HOME=/opt/android-studio/jbr ./gradlew -PuseLocalSharedCore=false testDebugUnitTest` (Ninja) -> PASS
- `JAVA_HOME=/opt/android-studio/jbr ./gradlew -PuseLocalSharedCore=false testDebugUnitTest` (CyanBridge) -> PASS

## Batch-1 third extraction pass

- Moved 3 additional shared connectivity classes:
  - `WifiConnectorBuilder.java`
  - `wifiScan/WifiScanReceiver.java`
  - `wifiState/WifiStateReceiver.java`
- Removed duplicate copies from both app repos.

Validation:

- `JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest` (Ninja) -> PASS
- `JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest` (CyanBridge) -> PASS
- `JAVA_HOME=/opt/android-studio/jbr ./gradlew -PuseLocalSharedCore=false testDebugUnitTest` (Ninja) -> PASS
- `JAVA_HOME=/opt/android-studio/jbr ./gradlew -PuseLocalSharedCore=false testDebugUnitTest` (CyanBridge) -> PASS

Inventory impact:

- Direct namespace-mapped overlaps reduced to 36 (`SHARED_CORE_INVENTORY_CURRENT.md`).

## Namespace-divergent adapter step

- Added shared-core utility:
  - `com.heycyan.core.connectivity.wifi.SSIDUtils`
- Updated both app-local `SSIDUtils` classes to delegate to shared-core implementation,
  preserving existing app package APIs while centralizing behavior.

Validation:

- `JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest` (Ninja) -> PASS
- `JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest` (CyanBridge) -> PASS
- `JAVA_HOME=/opt/android-studio/jbr ./gradlew -PuseLocalSharedCore=false testDebugUnitTest` (Ninja) -> PASS
- `JAVA_HOME=/opt/android-studio/jbr ./gradlew -PuseLocalSharedCore=false testDebugUnitTest` (CyanBridge) -> PASS

## Namespace-divergent adapter expansion

- Added shared-core utilities/core logic classes:
  - `com.heycyan.core.connectivity.wifi.WifiUtils`
  - `com.heycyan.core.connectivity.wifi.ConfigSecuritiesCore`
  - `com.heycyan.core.connectivity.wifi.ConnectorUtilsCore`
- Updated both app-local wrappers to delegate behavior to shared-core:
  - `ui/wifi/WifiUtils.java`
  - `ui/wifi/ConfigSecurities.java`
  - `ui/wifi/ConnectorUtils.java`

Validation:

- `JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest` (Ninja) -> PASS
- `JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest` (CyanBridge) -> PASS
- `JAVA_HOME=/opt/android-studio/jbr ./gradlew -PuseLocalSharedCore=false testDebugUnitTest` (Ninja) -> PASS
- `JAVA_HOME=/opt/android-studio/jbr ./gradlew -PuseLocalSharedCore=false testDebugUnitTest` (CyanBridge) -> PASS
- `python tools/check_public_safety.py` (shared-core) -> PASS
- `python tools/check_binary_compat.py --baseline-version 0.1.0-SNAPSHOT` (shared-core) -> PASS

## Shared P2P contract extraction

- Added shared-core contract:
  - `com.heycyan.core.connectivity.p2p.DirectActionListener`
- Updated both app-local `DirectActionListener` interfaces to extend shared-core contract.

Validation:

- `JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest` (Ninja) -> PASS
- `JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest` (CyanBridge) -> PASS
- `JAVA_HOME=/opt/android-studio/jbr ./gradlew -PuseLocalSharedCore=false testDebugUnitTest` (Ninja) -> PASS
- `JAVA_HOME=/opt/android-studio/jbr ./gradlew -PuseLocalSharedCore=false testDebugUnitTest` (CyanBridge) -> PASS

## Release pin progression (0.1.0)

- Updated app version pins:
  - `android/NinjaConceptsManagerApp/gradle.properties` -> `heycyanCoreConnectivityVersion=0.1.0`
  - `android/CyanBridge/gradle.properties` -> `heycyanCoreConnectivityVersion=0.1.0`
- Added first stable release notes in shared-core:
  - `releases/0.1.0.md`
- Added shared-core PR template privacy checkbox:
  - `.github/pull_request_template.md`

## Remaining Phase-1 module scaffolds completed

- Added new Android library modules in shared-core:
  - `core-ble`
  - `core-audio`
  - `core-transcription-api`
  - `core-summarization-api`
  - `core-data`
  - `core-utils`
- Added namespace-aligned marker classes under `com.heycyan.core.*` for each module.
- Added Maven publication metadata for each new module (artifact IDs match module names).
- Updated shared-core settings and CI:
  - `settings.gradle.kts` now includes all seven core modules.
  - `.github/workflows/core-ci.yml` now builds/tests all shared-core modules.

Validation:

- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew :heycyan-android-core:core-connectivity:assembleDebug :heycyan-android-core:core-ble:assembleDebug :heycyan-android-core:core-audio:assembleDebug :heycyan-android-core:core-transcription-api:assembleDebug :heycyan-android-core:core-summarization-api:assembleDebug :heycyan-android-core:core-data:assembleDebug :heycyan-android-core:core-utils:assembleDebug` (from `android/NinjaConceptsManagerApp`) -> PASS
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew :heycyan-android-core:core-connectivity:testDebugUnitTest :heycyan-android-core:core-ble:testDebugUnitTest :heycyan-android-core:core-audio:testDebugUnitTest :heycyan-android-core:core-transcription-api:testDebugUnitTest :heycyan-android-core:core-summarization-api:testDebugUnitTest :heycyan-android-core:core-data:testDebugUnitTest :heycyan-android-core:core-utils:testDebugUnitTest` (from `android/NinjaConceptsManagerApp`) -> PASS
- `python3 tools/check_public_safety.py` (from `heycyan-android-core`) -> PASS
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew :heycyan-android-core:core-connectivity:assembleRelease` (from `android/NinjaConceptsManagerApp`) -> PASS
- `python3 /home/fertroll10/Documents/ML/heycyan-android-core/tools/check_binary_compat.py --baseline-version 0.1.0` -> PASS

## Shared P2P connection-state extraction

- Added shared-core connection state helper:
  - `com.heycyan.core.connectivity.p2p.WifiP2pConnectionState`
- Updated both app-local P2P manager implementations to use shared connection state logic:
  - `com.ninjaconcepts.manager.ui.wifi.p2p.WifiP2pManagerSingleton`
  - `com.fersaiyan.cyanbridge.ui.wifi.p2p.WifiP2pManagerSingleton`

Validation:

- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew :heycyan-android-core:core-connectivity:assembleDebug` (from `android/NinjaConceptsManagerApp`) -> PASS
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest` (from `android/NinjaConceptsManagerApp`) -> PASS
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest` (from `android/CyanBridge`) -> PASS
- `python3 tools/check_public_safety.py` (from `heycyan-android-core`) -> PASS
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew :heycyan-android-core:core-connectivity:assembleRelease` (from `android/NinjaConceptsManagerApp`) -> PASS
- `python3 /home/fertroll10/Documents/ML/heycyan-android-core/tools/check_binary_compat.py --baseline-version 0.1.0` -> PASS (`added classes: 5`, `no removed classes`)

## Shared P2P retry-state extraction

- Added shared-core retry helper:
  - `com.heycyan.core.connectivity.p2p.WifiP2pRetryState`
- Updated both app-local P2P manager implementations to use shared retry state logic:
  - `com.ninjaconcepts.manager.ui.wifi.p2p.WifiP2pManagerSingleton`
  - `com.fersaiyan.cyanbridge.ui.wifi.p2p.WifiP2pManagerSingleton`

Validation:

- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew :heycyan-android-core:core-connectivity:assembleDebug` (from `android/NinjaConceptsManagerApp`) -> PASS
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest` (from `android/NinjaConceptsManagerApp`) -> PASS
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest` (from `android/CyanBridge`) -> PASS
- `python3 tools/check_public_safety.py` (from `heycyan-android-core`) -> PASS
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew :heycyan-android-core:core-connectivity:assembleRelease` (from `android/NinjaConceptsManagerApp`) -> PASS
- `python3 /home/fertroll10/Documents/ML/heycyan-android-core/tools/check_binary_compat.py --baseline-version 0.1.0` -> PASS (`added classes: 4`, `no removed classes`)

## Shared P2P broadcast receiver extraction

- Added shared-core P2P receiver adapter contract:
  - `com.heycyan.core.connectivity.p2p.WifiP2pBroadcastHandler`
- Added shared-core receiver implementation:
  - `com.heycyan.core.connectivity.p2p.CoreWifiP2pBroadcastReceiver`
- Updated app-local broadcast receiver wrappers to delegate to shared-core logic:
  - `com.ninjaconcepts.manager.ui.wifi.p2p.WifiP2pBroadcastReceiver`
  - `com.fersaiyan.cyanbridge.ui.wifi.p2p.WifiP2pBroadcastReceiver`

Validation:

- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew :heycyan-android-core:core-connectivity:assembleDebug` (from `android/NinjaConceptsManagerApp`) -> PASS
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest` (from `android/NinjaConceptsManagerApp`) -> PASS
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest` (from `android/CyanBridge`) -> PASS
- `python3 tools/check_public_safety.py` (from `heycyan-android-core`) -> PASS
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew :heycyan-android-core:core-connectivity:assembleRelease` (from `android/NinjaConceptsManagerApp`) -> PASS
- `python3 /home/fertroll10/Documents/ML/heycyan-android-core/tools/check_binary_compat.py --baseline-version 0.1.0` -> PASS (`added classes: 3`, `no removed classes`)

## Shared P2P callback contract extraction

- Added shared-core contract:
  - `com.heycyan.core.connectivity.p2p.WifiP2pCallback`
- Updated both app-local nested callback interfaces to extend shared-core contract:
  - `com.ninjaconcepts.manager.ui.wifi.p2p.WifiP2pManagerSingleton.WifiP2pCallback`
  - `com.fersaiyan.cyanbridge.ui.wifi.p2p.WifiP2pManagerSingleton.WifiP2pCallback`

Validation:

- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew :heycyan-android-core:core-connectivity:assembleDebug` (from `android/NinjaConceptsManagerApp`) -> PASS
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest` (from `android/NinjaConceptsManagerApp`) -> PASS
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest` (from `android/CyanBridge`) -> PASS
- `python3 tools/check_public_safety.py` (from `heycyan-android-core`) -> PASS
- `ANDROID_HOME=/home/fertroll10/Android/Sdk JAVA_HOME=/opt/android-studio/jbr ./gradlew :heycyan-android-core:core-connectivity:assembleRelease` (from `android/NinjaConceptsManagerApp`) -> PASS
- `python3 /home/fertroll10/Documents/ML/heycyan-android-core/tools/check_binary_compat.py --baseline-version 0.1.0` -> PASS
