# Shared Core Boundaries

This file defines what can be extracted into shared core (`heycyan-android-core`) and what must remain app-private.

## Scope categories

- `core`: safe to extract to shared repo
- `needs-refactor`: potentially shareable, but coupled to app shell/resources today
- `app-private`: must stay in app repo (especially private Ninja features)

## Initial package-level classification

### core

- Connectivity primitives and helpers currently duplicated in both apps:
  - `.../net/WifiP2pController.kt`
  - `.../ui/wifi/**`
  - `.../ui/wifi/p2p/**`
  - `.../ui/wifi/utils/**`
  - `.../ui/wifi/wifiConnect/**`
  - `.../ui/wifi/wifiDisconnect/**`
  - `.../ui/wifi/wifiRemove/**`
  - `.../ui/wifi/wifiScan/**`
  - `.../ui/wifi/wifiState/**`
  - `.../ui/wifi/wifiWps/**`

### needs-refactor

- Shared-looking but app-shell-coupled classes:
  - `.../MainActivity.kt`
  - `.../ui/SettingsActivity.kt`
  - `.../ui/MyApplication.kt`
  - `.../ui/BaseActivity.kt`
  - `.../ui/DeviceBindActivity.kt`
  - `.../ui/DeviceListAdapter.kt`
  - `.../ui/PermissionUtil.kt`
  - `.../ui/AutoPairManager.kt`
  - `.../ui/BluetoothReceiver.kt`
  - `.../ui/MyBluetoothReceiver.kt`

These should be split into:

- pure/domain/service pieces moved to core
- thin activity/application wrappers left in each app

### app-private

- Ninja-only private features (must not be extracted to public shared core):
  - `.../localagent/**`
  - `.../agent/**`
  - private prompt/policy logic
  - private automation and proprietary UX flows

- Ninja product differentiators to keep private unless explicitly approved:
  - product-specific `chat/`, `notes/`, `privacy/` behavior differences
  - private provider wiring and strategy classes

## Review rule

Before extracting any file to shared core, verify all of the following:

1. No private product behavior is embedded.
2. No app-branding/resource assumptions are embedded.
3. API is reusable by both apps with no private dependency.
4. Tests exist in source app and shared core for parity.
