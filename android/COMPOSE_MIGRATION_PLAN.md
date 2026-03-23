# Compose / Material 3 Migration Plan

**Branch:** `compose_material3_migration`
**Working Directory:** `/home/fertroll10/Documents/ML/HeyCyanSmartGlassesSDK/android/`
**Repo:** CyanBridge (subdirectory `CyanBridge/`)
**Build command:** `cd CyanBridge && ./gradlew assembleDebug 2>&1 | tail -50`
**Status:** Phase 13 complete — fixing bugs found in user testing
**Goal:** Full feature-parity migration from XML Activities to Jetpack Compose with Material 3

---

## Completed Phases

- ✅ **Phases 0–8**: Foundation, onboarding, navigation, all screen stubs, RecordingsScreen, GlassesScreen, bottom nav structure
- ✅ **Phase 9**: Settings Completeness (Local Agent, Memory & Privacy, Transcripts, Redaction, Data)
- ✅ **Phase 10**: ProSubscriptionSettingsScreen, NotesListScreen, NoteDetailScreen
- ✅ **Phase 11**: PluginsScreen (full), LocalModelsScreen (full)
- ✅ **Phase 12**: 6 secondary screens (DailyFacts, Summary, AppBlacklist, Captures, Pending, SyncedMedia)
- ✅ **Phase 13**: Full GlassesScreen dashboard with 25+ buttons
- ✅ **Bug fixes**: ComposeMainActivity stale NavHost, registerReceiver SecurityException on Android 14+

---

## Bugs Found in User Testing

These were discovered during testing on a real device after Phases 8–13.

### Bug 1: Battery Optimization Screen — "Disable Battery Optimization" Button Doesn't Work
- **Expected:** Clicking "Disable Battery Optimization" opens the system dialog to confirm disabling battery optimization (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
- **Actual:** Button does nothing or crashes
- **Root cause:** `BatteryOptimizationScreen.openDisableBatteryOptimizationFlow()` starts an `Intent` from a `@Composable` function using `context.startActivity()`. However, the `context` might not be an Activity context, or the intent action might not be resolvable on all devices. Need to verify the intent is properly resolved before starting it.

### Bug 2: Battery Optimization "I Locked It" Button — Incorrect Behavior
- **Expected:** After setting battery optimization to unrestricted, "I Locked It" should allow proceeding
- **Actual:** Even after disabling optimization, the button still reports "Battery optimization still appears to be ON" and refuses to proceed
- **Root cause:** `isBatteryOptimizationIgnored()` checks `pm.isIgnoringBatteryOptimizations(context.packageName)`. But the system might not immediately reflect the change after returning from the settings screen. Need to re-check the state in a `LaunchedEffect` or after a delay, or use `onActivityResult` pattern to detect when the user returns.

### Bug 3: Missing Permission Popups After Onboarding
- **Expected:** After onboarding completes, permission request popups should appear for Bluetooth, Location, Microphone, etc.
- **Actual:** No permission popups appear
- **Root cause:** The old `MainActivity` had permission request logic that ran after onboarding. The new `ComposeMainActivity` navigates directly to `GlassesScreen` without requesting any permissions. Need to add a permissions request step after `BatteryOptimizationScreen` completes, before navigating to the dashboard.

### Bug 4: Plugins Page Missing from Bottom Navigation
- **Expected:** Bottom nav should have 5 tabs matching the old app: Chats, Glasses, Recordings, Settings, Plugins
- **Actual:** Current bottom nav shows: Glasses, Chats, Recordings, Settings, Notes
- **Root cause:** Phase 10 replaced the Plugins tab with Notes in the bottom navigation. Notes was not in the original bottom nav — the Plugins page was there. Need to restore Plugins as the 5th tab.

### Bug 5: Notes Screen Not in Original Design
- **Expected:** Notes screen should be accessible from a secondary location, NOT from the bottom navigation
- **Actual:** Notes has its own bottom nav tab
- **Root cause:** Same as Bug 4. Notes should be accessible via a link in Settings or another secondary screen, not as a bottom nav tab. Keep the Notes screen implementation but remove it from the bottom navigation.

### Bug 6: Bottom Nav Icons Are Random
- **Expected:** Icons should match the original design's icons
- **Actual:** Icons are random (Star, Home, List, Settings, ArrowForward)
- **Root cause:** The original bottom nav uses these icons:
  | Tab | Original Icon | Material Icon |
  |-----|--------------|---------------|
  | Chats | `@android:drawable/ic_dialog_email` | `Icons.Filled.Home` (email/chat style) |
  | Glasses | `@drawable/ic_nav_glasses` (custom) | `Icons.Filled.Star` (closest base match) |
  | Recordings | `@android:drawable/ic_menu_slideshow` | `Icons.Filled.List` |
  | Settings | `@android:drawable/ic_menu_manage` | `Icons.Filled.Settings` |
  | Plugins | `@drawable/ic_nav_plugins` (custom) | `Icons.Filled.Add` (closest base match) |
  
  Need to use `ImageVector.vectorResource()` to load the custom XML vector drawables (`ic_nav_glasses.xml`, `ic_nav_plugins.xml`) for Glasses and Plugins tabs, and use better matches for Chats and Recordings.

### Bug 7: Pro Subscription Banner Still Shown After Subscribing
- **Expected:** After subscribing to Pro, clicking "Pro Subscription" in Settings should navigate directly to ProSubscriptionSettingsScreen, not show the subscription incentives banner
- **Actual:** The `ProScreen` still shows the `ProSubscribe` section (incentives banner) even after subscribing
- **Root cause:** `ProScreen` correctly shows `ProDashboard` when `state.isSubscribed` is true. But `SettingsScreen.ProSubscriptionSection` navigates via `Intent` to legacy Activities instead of using Compose routes. Also, the SettingsScreen may not be fetching the subscription state correctly.
- **Fix needed:**
  1. `SettingsScreen` should navigate to `Routes.PRO_SETTINGS` when subscribed (Compose route), not to `ProSubscriptionSettingsActivity`
  2. When NOT subscribed, navigate to `Routes.PRO` (subscription screen)
  3. The SettingsScreen should read subscription state from `ProSubscriptionServerPrefs`

### Bug 8: Model Selection Section in Regular Settings
- **Expected:** Model selection (Requests model, Questions model, Tasks model) should be in Pro Subscription Settings, not in regular Settings
- **Actual:** `SettingsScreen.ModelSection` shows model dropdowns in the regular settings
- **Root cause:** The model selection was part of `ProSubscriptionSettingsActivity` (old design). Phase 4 put it in `ProScreen`, and Phase 9 also added it to `SettingsScreen.ModelSection`. Remove `ModelSection` from `SettingsScreen` — it should only exist in `ProSubscriptionSettingsScreen` (Phase 10).

### Bug 9: FAQ Section Missing from Settings
- **Expected:** Settings should have a collapsible FAQ section with 4 Q&A items
- **Actual:** No FAQ section exists in the new SettingsScreen
- **Root cause:** Phase 9 did not include the FAQ section. The old `SettingsActivity` has it as a collapsible card with:
  1. "How do I set Local Models?" → "Select Local Models in AI / Automation, then tap Configure Local Models and follow the setup steps on device."
  2. "Do I need a subscription to use the app?" → "No. You can use Tasker or Local Models without subscribing. Pro is optional and adds premium managed features."
  3. "How is my data handled?" → "By default data is stored locally on your phone. You can export/import your local data and clear it any time from the Data section."
  4. "Can I review the source code?" → "Yes. The app is open source and you can manually review the full source code on GitHub."

---

## New Phases

Each phase must **compare the behavior of the original design with the new design** and fix discrepancies. Each agent must:
1. Read the original Activity/layout files for the specific bug
2. Read the current Compose screen implementation
3. Identify the exact difference
4. Fix the Compose screen to match the original behavior
5. Build and verify

### Phase 14 — Onboarding Fix (Bugs 1, 2, 3)
- [ ] **Bug 1:** Fix `BatteryOptimizationScreen.openDisableBatteryOptimizationFlow()` — verify intent resolution, use `rememberLauncherForActivityResult` instead of `context.startActivity()` for more robust handling
- [ ] **Bug 2:** Fix `BatteryOptimizationScreen` battery optimization check — re-check state in `LaunchedEffect` when the activity resumes (use `LifecycleEventObserver` with `ON_RESUME`), or remove the check entirely and allow proceeding after clicking the button
- [ ] **Bug 3:** Add a permission request step after onboarding — add a new screen or use `XXPermissions` to request Bluetooth/Location/Microphone permissions before navigating to the dashboard. Reference the old `WelcomeActivity` for the exact permissions requested.
  - Must request: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`, `RECORD_AUDIO` (and optionally `POST_NOTIFICATIONS` on Android 13+)
  - Use `XXPermissions` library (already in project) for user-friendly permission dialogs

**Files to modify:**
- `ui/onboarding/BatteryOptimizationScreen.kt` — fix battery check and intent
- `ui/navigation/MainNavScreen.kt` — add permission request after onboarding
- Or create `ui/onboarding/PermissionsScreen.kt` — new screen between onboarding and dashboard

### Phase 15 — Bottom Navigation & Icons (Bugs 4, 5, 6)
- [ ] **Bug 4:** Restore correct 5-tab bottom nav: Chats, Glasses, Recordings, Settings, Plugins
  - Remove `NOTES_LIST` from `bottomNavItems`
  - Add `PLUGINS` to `bottomNavItems` (was removed in Phase 10)
- [ ] **Bug 5:** Remove Notes from bottom nav — keep Notes accessible via navigation from Settings or a secondary link, NOT as a bottom nav tab
- [ ] **Bug 6:** Fix bottom nav icons to match the original design:
  - Chats: `Icons.Filled.Home` (closest to email icon) — KEEP or use `Icons.Filled.List` as closest match
  - Glasses: Load `@drawable/ic_nav_glasses` via `ImageVector.vectorResource(R.drawable.ic_nav_glasses)` or use `Icons.Filled.Star` if vector resource doesn't work
  - Recordings: `Icons.AutoMirrored.Filled.List` — KEEP
  - Settings: `Icons.Filled.Settings` — KEEP
  - Plugins: Load `@drawable/ic_nav_plugins` via `ImageVector.vectorResource(R.drawable.ic_nav_plugins)` or use `Icons.Filled.Add` if vector resource doesn't work

**Files to modify:**
- `ui/navigation/MainNavScreen.kt` — fix `bottomNavItems` list, add icon loading via vectorResource
- `ui/navigation/NavigationRoutes.kt` — ensure PLUGINS route exists (already does)

### Phase 16 — Settings & Pro Screen Fixes (Bugs 7, 8, 9)
- [ ] **Bug 7:** Fix Pro subscription navigation in SettingsScreen:
  - When `state.isProSubscribed == true`: navigate to `Routes.PRO_SETTINGS` (Compose ProSubscriptionSettingsScreen)
  - When `state.isProSubscribed == false`: navigate to `Routes.PRO` (Compose ProScreen with subscription incentives)
  - Remove the `Intent(context, ProSubscriptionActivity::class.java)` legacy navigation
  - Read subscription state from `ProSubscriptionServerPrefs` or `ProSubscriptionVerifier`
- [ ] **Bug 8:** Remove `ModelSection` from `SettingsScreen.kt` — model selection belongs only in `ProSubscriptionSettingsScreen`. The old design had models in `ProSubscriptionSettingsActivity`.
- [ ] **Bug 9:** Add `FaqSection` to `SettingsScreen.kt` — collapsible card with 4 Q&A items matching the old `SettingsActivity` FAQ content exactly

**Files to modify:**
- `ui/settings/SettingsScreen.kt` — remove `ModelSection`, add `FaqSection`, fix Pro navigation to use Compose routes
- `ui/pro/ProSubscriptionSettingsScreen.kt` — verify it has model selection (already does from Phase 10)

---

## Reference Files for Each Phase

### Phase 14 (Onboarding Fix)
- Original: `ui/onboarding/BatteryOptimizationGuideActivity.kt` (if exists), `ui/WelcomeActivity.kt` (if exists)
- Old permissions: `ui/requestAllPermission()`, `ui/requestBluetoothPermission()`, `ui/requestLocationPermission()`
- Library: `com.hjq.permissions.XXPermissions` (already in build.gradle)
- Current: `ui/onboarding/BatteryOptimizationScreen.kt`

### Phase 15 (Navigation & Icons)
- Original: `res/menu/bottom_nav_menu.xml` (authoritative tab structure)
- Original: `res/drawable/ic_nav_glasses.xml`, `res/drawable/ic_nav_plugins.xml` (custom icons)
- Current: `ui/navigation/MainNavScreen.kt`, `ui/navigation/NavigationRoutes.kt`

### Phase 16 (Settings & Pro)
- Original: `ui/SettingsActivity.kt` (lines 185-188 for FAQ, lines 116-170 for AI/AUTOMATION sections)
- Original: `agent/ProSubscriptionSettingsActivity.kt` (model selection is here)
- Original: `agent/ProSubscriptionActivity.kt` (subscription flow)
- Current: `ui/settings/SettingsScreen.kt`, `ui/pro/ProScreen.kt`, `ui/pro/ProSubscriptionSettingsScreen.kt`

---

## Technical Constraints

- Build command: `cd CyanBridge && ./gradlew assembleDebug 2>&1 | tail -50`
- Kotlin 2.0.0 + Compose compiler plugin
- Compose BOM 2024.06.00, Navigation 2.7.7
- Icons: Filled/Outlined base set only. Use `Icons.AutoMirrored.Filled.List` for List, `Icons.AutoMirrored.Filled.Send` for Send. Custom icons can be loaded via `ImageVector.vectorResource(R.drawable.xxx)`.
- `XXPermissions` library for runtime permissions
- Use `RECEIVER_EXPORTED`/`RECEIVER_NOT_EXPORTED` flags on Android 14+
- All BLE SDK calls wrapped in try-catch

## Notes

- **Do NOT force push to main** — user will request manually
- **Keep Notes screen implementation** — just remove from bottom nav
- **Model selection** should ONLY appear in ProSubscriptionSettingsScreen, not in SettingsScreen
- **After Phase 14–16 complete**, build a debug APK and ask user to test before proceeding
