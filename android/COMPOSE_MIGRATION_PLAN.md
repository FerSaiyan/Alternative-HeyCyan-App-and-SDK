# Compose / Material 3 Migration Plan

**Branch:** `compose_material3_migration`
**Goal:** Migrate CyanBridge from XML-based Activities to Jetpack Compose with Material 3

## Migration Constraints
- Keep work off `main` during development
- Build APK with Android Studio JBR (`/opt/android-studio/jbr`)
- Use direct IP `http://177.95.92.150:48787` for relay server
- **`force push`** to GitHub origin/main after merge (remote has old commits)
- `_local_termux_server/` must be gitignored
- Keep termux server files on phone only

## Tech Stack
- Kotlin **2.0.0** + Compose compiler plugin (`org.jetbrains.kotlin.plugin.compose`)
- Compose BOM `2024.06.00`
- Material 3
- Navigation `2.8.5` → **downgraded to `2.7.7`** (2.8.5 had NavHost signature issues with Kotlin 2.0)
- `androidx.activity:activity` forced to `1.8.0` (Compose BOM 1.10.1 makes `onNewIntent` final)
- Icons: **Filled/Outlined base set only** — `material-icons-extended` excluded (30MB). Available icons: Home, List, Settings, Star, Add, ArrowDropDown, Search, Delete, Refresh, Check.

## Phases

### Phase 0 — Setup & Foundation ✅ (commit f1bd840)
- Kotlin 2.0.0 + Compose compiler plugin
- Compose BOM `2024.06.00`, Material 3, Navigation `2.7.7`, ViewModel Compose
- `androidx.activity:activity` forced to `1.8.0`
- `CyanBridgeTheme.kt` — cyan `#00E5FF`, dark bg `#0B0F14`, card `#161B22`, full dark/light Material 3 schemes
- `NavigationRoutes.kt` — all route constants
- `MainNavScreen.kt` — NavHost with bottom nav (Chat/History/Settings/Pro) and placeholder screens

### Phase 1 — Nav Shell + Settings ✅ (commit 9f760be)
- `SettingsViewModel.kt` — StateFlow-based state management
- `SettingsScreen.kt` — Full Compose UI with collapsible sections: Pro Subscription banner, Dark/Light theme toggle, AI Provider type selector, Relay URL + backend config, Model selection dropdowns, Quick links
- `AboutScreen.kt` — Version info and app credits
- NavHost updated: SettingsScreen at `/settings`, AboutScreen at `/about`

### Phase 2 — Chat Screen ✅ (commit e3f9695)
- `ChatViewModel.kt` — StateFlow managing thread loading, message sending via `CliRelayClient.chat()`, model selection, error handling
- `ChatScreen.kt` — Scaffold with TopAppBar (model picker dropdown), LazyColumn for messages, typing indicator, input bar with send button, empty state, SnackbarHost for errors
- Model picker DropdownMenu with model list
- NavHost wired: ChatScreen at `/chat`, `chat_thread/{chatId}` for thread-specific chats
- `loadByThreadId()` added to ChatViewModel for navigation from History

### Phase 3 — History Screen ✅ (commit ceee6cb)
- `HistoryViewModel.kt` — StateFlow with thread loading, search filtering, delete
- `HistoryScreen.kt` — Scaffold with TopAppBar, search bar, LazyColumn with thread items, swipe-to-delete, FAB for new chat, empty state
- `onNavigateToChat(chatId)` callback navigates to `chat_thread/{chatId}` route
- NavHost wired: HistoryScreen at `/history`

### Phase 4 — Pro Subscription ✅ (commit 6a19d29)
- `ProViewModel.kt` — Full state management for subscription purchase, free trial activation, quota, account info, beta cloud signup, AI model preferences
- `ProScreen.kt` — Unified screen with two modes:
  - **Non-subscribed:** Plan selection (free_trial/cheap/standard/max), subscribe button, web checkout launch
  - **Subscribed:** Status banner with quota, plan details, AI model preferences (requests/questions/tasks), beta cloud signup, account info, change plan
- NavHost wired: ProScreen at `/pro`

### Phase 5 — Onboarding ✅ (commit e55ebd5)
- `OnboardingViewModel.kt` — State management for onboarding completion status
- `WelcomeScreen.kt` — Welcome screen with logo, title, description, Start Setup button
- `BatteryOptimizationScreen.kt` — Full battery optimization guide with status, disable button, system settings links, lock-in-Recents instructions, Done/Skip buttons
- NavHost wired: WelcomeScreen at `/welcome`, BatteryOptimizationScreen at `/battery_optimization`
- Onboarding flow: Welcome → Battery Opt → Main Chat

### Phase 6 — Remaining Screens (Placeholders) ✅ (commit b12f993)
- `RecordingsScreen.kt` — Placeholder for recordings list
- `PluginsScreen.kt` — Placeholder for community plugins
- `NotesScreen.kt` — Placeholder for meeting notes
- `LocalModelsScreen.kt` — Placeholder for local model configuration
- All wired into NavHost at their respective routes

### Phase 7 — Cleanup & Integration ✅ (current)
- `ComposeMainActivity.kt` — Compose-based entry point hosting `CyanBridgeTheme` + `ComposeNavHost`
- `ComposeNavHost` — Central NavHost with all Compose routes wired in
- `WelcomeActivity` — Simplified to redirect to `ComposeMainActivity`
- `AndroidManifest.xml` — `ComposeMainActivity` registered as LAUNCHER entry point
- `BatteryOptimizationScreen` — Marks onboarding complete in SharedPreferences on completion
- All Compose screens now accessible from a single entry point

## What Remains (Post-Migration)

### Full Feature Parity (not blocking app launch)
These legacy Activities still exist as separate routes but are wrapped in `LegacyScreenPlaceholder` composables:
- `ProSettingsActivity` → Full Compose ProSettings screen
- `DailyFactsActivity` → Compose Daily Facts screen
- `DailySummaryActivity` → Compose Daily Summary screen
- `RecordingsListActivity` → Compose Recordings screen (audio sessions, transcription)
- `CommunityPluginsActivity` → Compose Plugins screen
- `NotesListActivity` + `NoteDetailActivity` → Compose Notes screens
- `LocalModelsConfigureActivity` → Compose Local Models screen
- `SyncedMediaGalleryActivity` → Compose Synced Media gallery

### MainActivity Integration
- `MainActivity` (3993 lines) still uses XML views and XML bottom navigation
- Long-term: replace with `ComposeMainActivity`-based navigation
- Short-term: `ComposeMainActivity` handles the new Compose screens while legacy Activities remain accessible

## Final Steps
1. Merge `compose_material3_migration` into `main`
2. Force push to origin/main: `git push origin main --force`
3. Test APK on device: verify onboarding flow, chat, history, settings, pro screens
4. Iterate on Phase 6 placeholder screens for full feature parity
