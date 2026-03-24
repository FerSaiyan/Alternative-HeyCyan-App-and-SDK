# Compose / Material 3 Migration Plan

**Branch:** `compose_material3_migration`
**Working Directory:** `/home/fertroll10/Documents/ML/HeyCyanSmartGlassesSDK/android/`
**Repo:** CyanBridge (subdirectory `CyanBridge/`)
**Build command:** `cd CyanBridge && ./gradlew assembleDebug 2>&1 | tail -50`
**Status:** Phases 14–16 merged — fixing bugs found in user testing round 2
**Goal:** Full feature-parity migration from XML Activities to Jetpack Compose with Material 3

---

## Completed Phases

- ✅ **Phases 0–13**: Foundation, all screens, navigation, GlassesScreen dashboard
- ✅ **Phase 14**: Battery optimization fix, permission requests after onboarding
- ✅ **Phase 15**: Restore Plugins in bottom nav, remove Notes from nav, fix icons
- ✅ **Phase 16**: Fix Pro navigation, remove model selection from Settings, add FAQ

---

## Bugs Found in User Testing (Round 2)

### Bug 10: Pro Subscription Screen Missing Promotional Content
- **Expected:** The original `ProSubscriptionActivity` has promotional cards with emojis and descriptions:
  - "💜 Help Fund the Project" — supports ongoing development, expanding to more smartglasses
  - "🛠️ Support Plugin Developers" — contribute to plugin ecosystem
  - "🔒 Encrypted Cloud Syncing" — sync memory and transcripts across devices
  - "📸 Smart Image Automation" — Gemini vision image analysis
  - "🌐 Website Checkout" with Subscribe on Website button
  - "Prefer Local Models?" — one-time $5 donate option
  - Status text, "Back" + "Subscribe In App" buttons at bottom
  - Plan radio group: Trial $3.99, Cheap $5.99, Standard $9.99, Max $19.99
- **Actual:** Current `ProScreen` is a simplified plan selector with just "Choose your plan" and plan buttons. No promotional cards.
- **Reference:** `android/CyanBridge/app/src/main/res/layout/activity_pro_subscription.xml` (full layout)
- **File to modify:** `ui/pro/ProScreen.kt` — the `ProSubscribe` composable needs promotional cards

### Bug 11: "Configure Local Models" Button Missing from Settings
- **Expected:** In the original `SettingsActivity`, the AI/AUTOMATION section has a "Configure Local Models" button (`btn_configure_local_models`) that launches `LocalModelsConfigureActivity`. This is in the same section as the AI provider radio group (Tasker, Local Agent, Pro Subscription).
- **Actual:** The current `SettingsScreen` AI/AUTOMATION section doesn't have this button. The LocalModelsScreen exists but isn't accessible from Settings.
- **Reference:** Original `activity_settings.xml` line ~252: `"Configure Local Models"` button
- **File to modify:** `ui/settings/SettingsScreen.kt` — add a "Configure Local Models" button in the `AiProviderSection`, and wire it to navigate to `Routes.LOCAL_MODELS`

### Bug 12: Dark Mode Toggle Doesn't Work
- **Expected:** Toggling dark mode should change the app's theme from dark to light
- **Actual:** The `ThemeSection` in `SettingsScreen` calls `onToggle` which updates `state.isDarkTheme`, but `CyanBridgeTheme` in `CyanBridgeTheme.kt` uses `darkTheme: Boolean = isSystemInDarkTheme()` — it defaults to the system setting and ignores the user's toggle.
- **Root cause:** `CyanBridgeTheme` needs to accept the `darkTheme` parameter from the caller. The `ComposeMainActivity` (or `MainNavScreen`) needs to pass the theme state.
- **Files to modify:**
  - `ui/theme/CyanBridgeTheme.kt` — accept `darkTheme` parameter properly
  - `ui/navigation/MainNavScreen.kt` — read theme state from SharedPreferences and pass to `CyanBridgeTheme`
  - OR `ui/ComposeMainActivity.kt` — pass theme state to `CyanBridgeTheme` wrapper

### Bug 13: More Accent Color Presets (Pastel Colors)
- **Expected:** User wants more color presets beyond just cyan-dark. Examples: pastel pink, pastel green, pastel purple, pastel orange, pastel blue.
- **Actual:** Only cyan accent (`#00E5FF`) is available.
- **Solution:** Add a color picker/preset section in the Appearance settings. Define a set of pastel `ColorScheme`s and let the user select one. Store the selection in SharedPreferences.
- **Files to modify:**
  - `ui/theme/CyanBridgeTheme.kt` — define preset color schemes (pastel variants)
  - `ui/settings/SettingsScreen.kt` — add color preset selector in Appearance section
  - `ui/navigation/MainNavScreen.kt` — read selected accent color and apply to theme

### Bug 14: FAQ and About Sections Not Collapsible
- **Expected:** FAQ and About sections should be collapsible like the other sections (AI/AUTOMATION, Local Agent, etc.)
- **Actual:** `FaqSection` and `AboutSection` in `SettingsScreen` use `SettingsCard(title = "FAQ")` without `expanded`/`onExpandToggle` parameters, so they're always visible.
- **File to modify:** `ui/settings/SettingsScreen.kt` — add `expanded` state and `onExpandToggle` to both `FaqSection` and `AboutSection`

### Bug 15: Chat Input Hidden Behind Bottom Nav Bar
- **Expected:** The chat input box should be visible above the bottom navigation bar when typing
- **Actual:** The input box is hidden behind the navigation bar. This happens because:
  1. `MainNavScreen`'s `Scaffold` wraps the `NavHost` with `modifier = Modifier.padding(innerPadding)` which includes the bottom bar height
  2. The `ChatScreen`'s `Scaffold` also has its own input box at the bottom
  3. The `ChatScreen` applies `imePadding()` on its Column, but the outer Scaffold's bottom bar padding is still applied
  4. Result: double padding or input box obscured
- **Fix:** Move `imePadding()` from `ChatScreen` to the outer `MainNavScreen`'s `Scaffold`, and use `Modifier.navigationBarsPadding()` + `Modifier.imeHeight()` instead of `Modifier.imePadding()`. Or simpler: remove `imePadding()` from `ChatScreen` and add it to the `NavHost` modifier in `MainNavScreen`.
- **Files to modify:**
  - `ui/navigation/MainNavScreen.kt` — add `Modifier.imePadding()` to `NavHost` modifier
  - `ui/chat/ChatScreen.kt` — remove `imePadding()` from the Column modifier

---

## New Phases

### Phase 17 — Pro Subscription Promotional Screen (Bug 10)

Rewrite the `ProSubscribe` composable in `ProScreen.kt` to match the original promotional cards:

1. **Header**: "Pro Subscription" title + subtitle "Support the project, unlock premium features, and help us expand to more smartglasses."
2. **Card 1**: "💜 Help Fund the Project" — text about ongoing development, expanding to Meta Ray-Ban, Rokid, etc.
3. **Card 2**: "🛠️ Support Plugin Developers" — text about plugin ecosystem
4. **Card 3**: "🔒 Encrypted Cloud Syncing" — text about syncing memory across devices
5. **Card 4**: "📸 Smart Image Automation" — text about Gemini vision
6. **Card 5**: "🌐 Website Checkout" — "Subscribe on Website" button
7. **Card 6**: "Prefer Local Models?" — "Donate $5 ☕" button
8. **Plan radio group**: Trial $3.99/mo, Cheap $5.99/mo, Standard $9.99/mo, Max $19.99/mo
9. **Status text**: "Not subscribed" or "Subscribed"
10. **Bottom buttons**: "Back" + "Subscribe In App"

Keep the existing `ProDashboard` composable for subscribed users.

**Reference:** `origin/main:android/CyanBridge/app/src/main/res/layout/activity_pro_subscription.xml`
**File:** `ui/pro/ProScreen.kt`

### Phase 18 — Settings Fixes (Bugs 11, 14)

**Bug 11 fix:**
- Add a "Configure Local Models" button in the `AiProviderSection` of `SettingsScreen`
- The button should call `onNavigate(Routes.LOCAL_MODELS)` to navigate to the LocalModelsScreen
- Place it after the AI provider radio group

**Bug 14 fix:**
- Make `FaqSection` collapsible: add `var expanded by remember { mutableStateOf(false) }` and pass `expanded`/`onExpandToggle` to `SettingsCard`
- Make `AboutSection` collapsible: same pattern

**File:** `ui/settings/SettingsScreen.kt`

### Phase 19 — Theme & Color Presets (Bugs 12, 13)

**Bug 12 fix — Dark mode:**
- Read `state.isDarkTheme` from SettingsViewModel state
- Pass it to `CyanBridgeTheme(darkTheme = state.isDarkTheme)` in the theme wrapper
- Store the preference in SharedPreferences so it persists
- Update `ComposeMainActivity` or `MainNavScreen` to read and apply the theme

**Bug 13 fix — Color presets:**
- Define 5-6 pastel accent color options in `CyanBridgeTheme.kt`:
  - Cyan (default): `#00E5FF`
  - Pastel Pink: `#FFB6C1`
  - Pastel Green: `#98FB98`
  - Pastel Purple: `#DDA0DD`
  - Pastel Orange: `#FFDAB9`
  - Pastel Blue: `#87CEEB`
- Create a function that generates a `ColorScheme` from a chosen accent color
- Add a color preset selector in `ThemeSection` of `SettingsScreen` (horizontal row of color dots)
- Store selected accent in SharedPreferences
- Read it in `MainNavScreen` and apply to `CyanBridgeTheme`

**Files:**
- `ui/theme/CyanBridgeTheme.kt` — add preset colors, color scheme generator
- `ui/settings/SettingsScreen.kt` — add color preset selector
- `ui/navigation/MainNavScreen.kt` — read theme prefs and apply

### Phase 20 — Chat Input Fix (Bug 15)

- Remove `Modifier.imePadding()` from `ChatScreen`'s Column modifier
- Add `Modifier.imePadding()` to the `NavHost` modifier in `MainNavScreen`
- This ensures the entire nav host content moves up when the keyboard opens, and the bottom bar is pushed up too (or hidden)

**Files:**
- `ui/chat/ChatScreen.kt` — remove `.imePadding()`
- `ui/navigation/MainNavScreen.kt` — add `.imePadding()` to NavHost modifier

---

## Reference Files

### Phase 17 (Pro Screen)
- Original: `origin/main:android/CyanBridge/app/src/main/res/layout/activity_pro_subscription.xml`
- Current: `ui/pro/ProScreen.kt`
- Original activity: `origin/main:android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/agent/ProSubscriptionActivity.kt`

### Phase 18 (Settings)
- Original: `origin/main:android/CyanBridge/app/src/main/res/layout/activity_settings.xml` (lines ~230-260 for Configure Local Models, lines 1176-1292 for FAQ)
- Current: `ui/settings/SettingsScreen.kt`

### Phase 19 (Theme)
- Current: `ui/theme/CyanBridgeTheme.kt`
- Current: `ui/settings/SettingsScreen.kt` (Appearance section)
- Current: `ui/navigation/MainNavScreen.kt`

### Phase 20 (Chat)
- Current: `ui/chat/ChatScreen.kt`
- Current: `ui/navigation/MainNavScreen.kt`

---

## Technical Constraints

- Build command: `cd CyanBridge && ./gradlew assembleDebug 2>&1 | tail -50`
- Kotlin 2.0.0, Compose BOM 2024.06.00, Navigation 2.7.7
- Icons: Filled/Outlined base set only. Custom drawables via `ImageVector.vectorResource()`.
- Use `RECEIVER_EXPORTED`/`RECEIVER_NOT_EXPORTED` flags on Android 14+
- All BLE SDK calls wrapped in try-catch
- **Do NOT force push to main** — user will request manually
