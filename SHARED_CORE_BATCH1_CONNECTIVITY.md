# Shared Core Batch 1 - Connectivity Extraction Queue

This is the first concrete extraction batch for `heycyan-android-core`.

Goal: move low-risk, duplicated Wi-Fi/P2P primitives that are already near-identical in both apps.

Status snapshot (2026-03-10):

- Extracted now in shared core and consumed by Ninja + CyanBridge:
  - `Logger.java`, `TypeEnum.java`, `LocationUtils.java`, `WeakHandler.java`, `Elvis.java`, `VersionUtils.java`
  - `wifiConnect/ConnectionErrorCode.java`
  - `wifiConnect/ConnectionScanResultsListener.java`
  - `wifiConnect/ConnectionSuccessListener.java`
  - `wifiConnect/WifiConnectionCallback.java`
  - `wifiDisconnect/DisconnectionErrorCode.java`
  - `wifiDisconnect/DisconnectionSuccessListener.java`
  - `wifiRemove/RemoveErrorCode.java`
  - `wifiRemove/RemoveSuccessListener.java`
  - `wifiScan/ScanResultsListener.java`
  - `wifiScan/WifiScanCallback.java`
  - `wifiState/WifiStateCallback.java`
  - `wifiState/WifiStateListener.java`
  - `wifiWps/ConnectionWpsListener.java`
  - `WifiConnectorBuilder.java`
  - `wifiScan/WifiScanReceiver.java`
  - `wifiState/WifiStateReceiver.java`
- Remaining files in this batch are higher-risk/stateful and intentionally deferred after Phase 2.

Namespace-divergent adapter migration in progress:

- `SSIDUtils` behavior is now implemented in shared-core (`com.heycyan.core.connectivity.wifi.SSIDUtils`) and both app-local `SSIDUtils` classes delegate to it.
- `WifiUtils` behavior is now implemented in shared-core (`com.heycyan.core.connectivity.wifi.WifiUtils`) and both app-local `WifiUtils` classes delegate to it.
- `ConfigSecurities` behavior is now implemented in shared-core (`ConfigSecuritiesCore`) and both app-local `ConfigSecurities` classes delegate to it.
- `ConnectorUtils` behavior is now implemented in shared-core (`ConnectorUtilsCore`) and both app-local `ConnectorUtils` classes delegate to it.
- `DirectActionListener` contract is now implemented in shared-core (`com.heycyan.core.connectivity.p2p.DirectActionListener`) and both app-local interfaces extend it.
- `WifiP2pCallback` contract is now implemented in shared-core (`com.heycyan.core.connectivity.p2p.WifiP2pCallback`) and both app-local nested interfaces extend it.
- `WifiP2pBroadcastReceiver` behavior is now centralized in shared-core (`CoreWifiP2pBroadcastReceiver`) with app-local wrappers delegating via `WifiP2pBroadcastHandler`.
- `WifiP2pManagerSingleton` retry state tracking now delegates to shared-core (`com.heycyan.core.connectivity.p2p.WifiP2pRetryState`) in both apps.
- `WifiP2pManagerSingleton` connection/connecting state tracking now delegates to shared-core (`com.heycyan.core.connectivity.p2p.WifiP2pConnectionState`) in both apps.
- Regression tests now cover extracted retry/connection state helpers in shared-core:
  - `WifiP2pRetryStateTest`
  - `WifiP2pConnectionStateTest`

## Target module

- `core-connectivity`
- package namespace target: `com.heycyan.core.connectivity.*`

## Candidate files (direct overlaps)

- `com/ninjaconcepts/manager/net/WifiP2pController.kt`
- `com/ninjaconcepts/manager/ui/wifi/ConfigSecurities.java`
- `com/ninjaconcepts/manager/ui/wifi/ConnectorUtils.java`
- `com/ninjaconcepts/manager/ui/wifi/LocationUtils.java`
- `com/ninjaconcepts/manager/ui/wifi/Logger.java`
- `com/ninjaconcepts/manager/ui/wifi/TypeEnum.java`
- `com/ninjaconcepts/manager/ui/wifi/WeakHandler.java`
- `com/ninjaconcepts/manager/ui/wifi/WifiConnector.java`
- `com/ninjaconcepts/manager/ui/wifi/WifiConnectorBuilder.java`
- `com/ninjaconcepts/manager/ui/wifi/WifiUtils.java`
- `com/ninjaconcepts/manager/ui/wifi/p2p/DirectActionListener.java`
- `com/ninjaconcepts/manager/ui/wifi/p2p/DirectBroadcastReceiver.java`
- `com/ninjaconcepts/manager/ui/wifi/p2p/WifiP2pBroadcastReceiver.kt`
- `com/ninjaconcepts/manager/ui/wifi/p2p/WifiP2pManagerSingleton.java`
- `com/ninjaconcepts/manager/ui/wifi/p2p/WifiP2pManagerSingleton.kt`
- `com/ninjaconcepts/manager/ui/wifi/utils/Elvis.java`
- `com/ninjaconcepts/manager/ui/wifi/utils/SSIDUtils.java`
- `com/ninjaconcepts/manager/ui/wifi/utils/VersionUtils.java`

## Extraction constraints

- Keep Android resource references out of core module.
- If any class needs app context/resources, split into:
  - pure logic class in core
  - app wrapper class in each app repo
- Preserve behavior and public method signatures where possible.
- Add parity tests before and after moving code.

## Acceptance for batch completion

- Core module builds and tests pass.
- CyanBridge integrates via composite build and passes `testDebugUnitTest`.
- Ninja integrates via composite build and passes `testDebugUnitTest`.
- No private Ninja-only code appears in core repo.
