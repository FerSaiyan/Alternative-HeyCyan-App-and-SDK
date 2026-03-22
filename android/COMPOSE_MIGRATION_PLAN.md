# Compose / Material 3 Migration Plan

**Branch:** `compose_material3_migration`
**Working Directory:** `/home/fertroll10/Documents/ML/HeyCyanSmartGlassesSDK/android/`
**Repo:** CyanBridge (subdirectory `CyanBridge/`)
**Build command:** `cd CyanBridge && ./gradlew assembleDebug`
**Status:** Phase 8 complete — RecordingsScreen, GlassesScreen, corrected bottom nav
**Goal:** Full feature-parity migration from XML Activities to Jetpack Compose with Material 3

---

## Known Bugs (fixed in current branch)

- ✅ **Bottom NavigationBar missing** — Fixed in Phase 7 (bottom nav added, but had wrong tab structure)
- ✅ **Bottom Navigation wrong tab count** — Fixed in Phase 8 (corrected to 5 tabs: Glasses/Chats/Recordings/Settings/Plugins)
- ✅ **Keyboard cuts off input box** — ChatScreen now has `imePadding()` + proper weight layout
- ✅ **Phase 7 bugs on test APK** — Reverted and fixed in current branch

---

## Bottom Navigation — Correct Structure (Phase 8: FIXED ✅)

The Compose bottom nav was built incorrectly. Here is the **authoritative comparison**:

### OLD — Bottom Navigation (`bottom_nav_menu.xml`)

| Tab | Title | Destination Activity |
|-----|-------|---------------------|
| 1 | **Chats** | `ChatThreadActivity` (opens most recent thread, or new chat) |
| 2 | **Glasses** | `MainActivity` — **THE HOME SCREEN** (glasses control dashboard) |
| 3 | **Transcriptions & Recordings** | `RecordingsListActivity` |
| 4 | **Settings** | `SettingsActivity` |
| 5 | **Plugins** | `CommunityPluginsActivity` |

### NEW (Phase 8 — CORRECT)

| Tab | Title | Screen | Status |
|-----|-------|--------|--------|
| 1 | Chat | ChatScreen | ✅ Done |
| 2 | History | HistoryScreen | ✅ Done (didn't exist in old version) |
| 3 | Settings | SettingsScreen | ⚠️ Partial |
| 4 | Pro | ProScreen | ✅ Done |

**Missing tabs:** `Glasses` (home dashboard), `Transcriptions & Recordings`, `Plugins`

---

## Bottom Navigation — Corrected Structure

The bottom nav must have **5 tabs**:

| Tab | Title | Route | Icon (Filled/Outlined) | Destination |
|-----|-------|-------|----------------------|-------------|
| 1 | **Glasses** | `/glasses` | `Icons.Filled.Star` | `GlassesScreen` ← **MISSING — needs building** |
| 2 | Chats | `/chat` or `/chat_thread/{id}` | Home | `ChatScreen` (opens thread or creates new) |
| 3 | Recordings | `/recordings` | List | `RecordingsScreen` ← **Missing full implementation** |
| 4 | Settings | `/settings` | Settings | `SettingsScreen` ← **Missing sections** |
| 5 | Plugins | `/plugins` | Star | `PluginsScreen` ← **Missing full implementation** |

> **Note:** "Glasses" is the home/landing screen. The app should launch here. "Chats" tab opens `ChatScreen` which creates/loads a thread (maps to old `ChatThreadActivity`). "History" (thread list) is accessible via a **top-bar icon inside Chat screen**, NOT as a bottom tab.

---

## Screen-by-Screen Comparison

### 1. Bottom Navigation

| Item | Old (XML) | New (Compose) | Status |
|------|-----------|---------------|--------|
| Chats tab | ✅ `nav_chats` → `ChatThreadActivity` | ✅ `ChatScreen` | Needs bottom nav fix |
| **Glasses tab** | ✅ `nav_glasses` → `MainActivity` (home dashboard) | ❌ **MISSING** — `GlassesScreen` not built | **PRIORITY** |
| Transcriptions & Recordings tab | ✅ `nav_transcriptions_recordings` → `RecordingsListActivity` | ⚠️ `RecordingsScreen` (placeholder only) | **PRIORITY** |
| Settings tab | ✅ `SettingsActivity` with 7 collapsible sections | ⚠️ `SettingsScreen` (partial — missing Local Agent, Memory sections) | Needs work |
| Plugins tab | ✅ `CommunityPluginsActivity` | ⚠️ `PluginsScreen` (placeholder only) | Needs work |
| Nav highlighting | Glasses tab always highlighted | Not implemented | Low priority |

---

### 2. Glasses Tab (Home Dashboard) — `MainActivity`

**Old:** `acitivyt_main.xml` — 25+ buttons + bottom nav + status cards

This is the **main home screen** and the most complex screen. It has:
- **Status cards:** Meeting recording banner, Bluetooth connection status, device class, battery, storage, transfer progress
- **Connection Controls:** Scan, Connect, Disconnect, Add Listener
- **Time Sync:** Set time, Read version, Battery, Volume, Media count, BT scan
- **Media Controls:** Camera, Video, Record (audio), Data Download
- **AI Assistant Mode:** Gemini / ChatGPT / Tasker toggle buttons
- **AI Hijack Settings:** Checkbox for enable/disable, Direct Assistant vs App Sharing
- **Meeting Capture:** Start/Stop meeting, timer spinner, recording banner
- **Local Agent Controls:** Start/Stop/Demo buttons
- **Advanced Section:** OTA info, Pull OTA test, toggle button

**Status:** ❌ **Not started** — massive screen. For MVP, build a simplified version with:
- Connection status (Bluetooth indicator)
- Battery level
- Meeting recording banner
- Quick action buttons: Camera, Record, AI Query
- Link to full legacy Activity for the full dashboard

---

### 3. Chat Screen

| Feature | Old | New | Status |
|---------|-----|-----|--------|
| Message list | `item_message_sent.xml`, `item_message_received.xml` | `MessageBubble` composables | ✅ Done |
| Input bar | `input_message` + `btn_send` | `OutlinedTextField` + send IconButton | ✅ Done |
| Model picker | Dropdown in toolbar | `ModelPickerTitle` + `DropdownMenu` | ✅ Done |
| Typing indicator | `thinking_indicator` with 3 dots | `TypingIndicator()` composable | ✅ Done |
| Chat list (history) | `ChatListActivity` | `HistoryScreen` | ✅ Done |
| Thread list item | `item_chat_thread.xml` | `ThreadItem` in HistoryScreen | ✅ Done |
| Swipe to delete | Not in old ChatListActivity | ✅ `SwipeToDismissBox` | ✅ Done |
| FAB new chat | `fab_new_chat` | FloatingActionButton in HistoryScreen | ✅ Done |
| Empty state | "No conversations yet" | `EmptyHistoryPlaceholder` | ✅ Done |
| Message bubbles | Simple TextView | Basic composables | ⚠️ Needs polish (markdown, copy, role coloring) |
| Voice input | Not in old chat screen | ❌ Not implemented | Low priority |
| Daily Facts cards | Not in old chat screen | ❌ Not implemented | Low priority |
| Thread title in toolbar | `tv_toolbar_title` | ❌ Not shown | Low priority |
| Local model badge | `tv_local_model_badge` | ❌ Not shown | Low priority |
| Chat appearance | `btn_chat_appearance` | ❌ Not implemented | Low priority |

---

### 4. Recordings Screen (`RecordingsListActivity`)

| Feature | Old (`activity_recordings_list.xml` + `item_recording.xml`) | New (`RecordingsScreen.kt`) | Status |
|---------|----------------------------------------------------------|-----------------------------|--------|
| Toolbar | `toolbar` | ✅ `TopAppBar` | Done (placeholder) |
| Recording list | `recycler_recordings` with custom items | ❌ **Placeholder only** | **PRIORITY** |
| Recording item | `btn_play` (ImageButton), `tv_title`, `tv_meta`, `btn_transcribe`, `btn_view_transcription`, `progress_transcribe` | ❌ **Not implemented** | **PRIORITY** |
| Synced Media button | `btn_open_synced_media` → `SyncedMediaGalleryActivity` | ❌ **Not implemented** | Medium |
| Meeting recording banner | Included via `<include>` | ❌ **Not shown in Compose** | Medium |
| Empty state | `empty_state` TextView | ✅ Placeholder has empty state | Done |
| Bottom nav | ✅ Present | ✅ Present | Done |

---

### 5. Settings Screen (`SettingsActivity`)

| Feature | Old (`activity_settings.xml`) | New (`SettingsScreen.kt`) | Status |
|---------|-------------------------------|---------------------------|--------|
| TopAppBar | MaterialToolbar | ✅ TopAppBar | Done |
| Meeting banner | `meeting_recording_banner` include | ❌ Not shown | Low |
| **Pro Subscription** | `btn_configure_pro_subscription` card | ✅ `ProSubscriptionSection` | Done |
| **Theme** | Dark/Light toggle | ✅ `ThemeSection` | Done |
| **AI Provider** | `settings_section_ai_automation.xml` (3 radio buttons + config buttons) | ✅ `AiProviderSection` | Done |
| **Relay URL / Backend** | In AiProviderSection | ✅ `RelayUrlSection` | Done |
| **Model selection** | **In `ProSubscriptionSettingsActivity`, NOT here** | ✅ In `ProScreen` dashboard | Done — correct placement |
| **Local Agent section** | `settings_section_local_agent.xml` — 9 buttons + 5 switches + 2 inputs | ❌ **MISSING** | **PRIORITY** |
| **Memory & Privacy section** | Memory mode radio + vault controls | ❌ **MISSING** | **PRIORITY** |
| **Transcripts section** | `settings_section_transcripts.xml` | ❌ **MISSING** | **PRIORITY** |
| **Redaction section** | `settings_section_redaction.xml` | ❌ **MISSING** | **PRIORITY** |
| **Data section** | Export/Import/Clear buttons | ❌ **MISSING** | **PRIORITY** |
| Bottom nav | ✅ Present | ✅ Present | Done |

**Missing Settings sections to add:**

#### Local Agent Section (`settings_section_local_agent.xml`)
- `tv_local_agent_accessibility_status` — shows if accessibility service is enabled
- `btn_open_accessibility_settings` — opens system accessibility settings
- `btn_local_agent_blacklist_apps` → `AppBlacklistActivity`
- `btn_local_agent_view_screen_captures` → `ScreenCapturesActivity`
- `btn_local_agent_edit_daily_facts` → `DailyFactsActivity`
- `btn_local_agent_view_confirmed_daily_facts`
- `btn_local_agent_view_daily_summary` → `DailySummaryActivity`
- `btn_local_agent_edit_persona` — edit agent persona
- `btn_local_agent_edit_user_facts` — edit user facts
- `btn_local_agent_view_context_debug` → `TranscriptionDebugActivity`
- `switch_local_agent_require_confirmation`
- `switch_local_agent_auto_capture`
- `switch_local_agent_daily_facts_reminder`
- `switch_local_agent_auto_save_daily_facts`
- `switch_local_agent_extract_user_fact_candidates`
- `edit_local_agent_max_steps` (TextInputEditText)
- `edit_local_agent_capture_interval_min` (TextInputEditText)

#### Memory & Privacy Section
- Memory mode radio group: Private Local, Encrypted Sync, Fast Cloud, Confidential Cloud
- `tv_memory_mode_current`, `tv_memory_mode_hint`
- `tv_memory_vault_lock_state`
- `btn_memory_unlock`, `btn_memory_lock`, `btn_memory_set_passphrase`, `btn_memory_clear_passphrase`, `btn_memory_reset_vault`
- `tv_memory_sync_status`, `tv_memory_cloud_status`

#### Transcripts Section (`settings_section_transcripts.xml`)
- `switch_transcript_storage`
- `switch_include_full_transcription`
- `switch_auto_audio_capture`
- Debug text for auto capture

#### Redaction Section (`settings_section_redaction.xml`)
- `switch_redact_names`

#### Data Section
- `btn_export_local_data`
- `btn_import_local_data`
- `btn_clear_local_data`

---

### 6. Pro Subscription

| Feature | Old (`activity_pro_subscription_settings.xml`) | New (`ProScreen.kt` + `ProViewModel.kt`) | Status |
|---------|-------------------------------------------------|------------------------------------------|--------|
| Plan selection | RadioGroup (`rb_trial`, `rb_cheap`, `rb_standard`, `rb_max`) | ✅ `PLANS` list + `RadioButton` | Done |
| Subscribe button | `btn_subscribe` | ✅ Subscribe button with web checkout | Done |
| Free trial activation | Separate flow | ✅ `activateFreeTrial()` in ViewModel | Done |
| Status banner | `tv_status` | ✅ `StatusBanner` composable | Done |
| Plan details | `tv_plan_details_*` in cards | ✅ `PlanDetailsCard` | Done |
| Quota display | `tv_quota_status` | ✅ `StatusBanner` quota text | Done |
| Verify subscription | `btn_refresh_plan_status` | ✅ `verifySubscription()` | Done |
| Refresh quota | `btn_refresh_quota` | ✅ `refreshQuota()` | Done |
| **AI Model preferences** | `spinner_model_requests`, `spinner_model_questions`, `spinner_model_tasks` | ✅ `AiModelsCard` in ProScreen | Done |
| Beta cloud signup | `btn_join_beta_cloud` | ✅ `BetaCloudCard` | Done |
| Account info | `tv_account_email/token/subscription` | ✅ `AccountCard` | Done |
| Change plan | `btn_change_plan` | ✅ `changePlan()` | Done |
| Refresh account | `btn_refresh_account` | ✅ `loadAccount()` | Done |
| Refresh models | `btn_refresh_models` | ✅ `refreshModels()` | Done |
| Cloud sync toggle | `switch_cloud_sync` | ❌ Not in ProScreen | Low |
| Plugin rewards | `switch_plugin_rewards` | ❌ Not in ProScreen | Low |
| Priority support | `switch_priority_support` | ❌ Not in ProScreen | Low |
| Early access devices | `switch_early_access_devices` | ❌ Not in ProScreen | Low |
| Ecosystem section | Card with ecosystem options | ❌ Not in ProScreen | Low |
| Future features | Future section card | ❌ Not in ProScreen | Low |

---

### 7. Plugins Screen (`CommunityPluginsActivity`)

| Feature | Old (`activity_community_plugins.xml`) | New (`PluginsScreen.kt`) | Status |
|---------|--------------------------------------|-------------------------|--------|
| Toolbar | MaterialToolbar | ✅ TopAppBar | Done (placeholder) |
| Image automation card | `card_plugin_image_automation` with Gemini/ChatGPT toggle + enable/disable | ❌ **Not implemented** | **PRIORITY** |
| Trending plugins | `container_trending` with `item_community_plugin_card.xml` | ❌ **Not implemented** | Medium |
| Top voted plugins | `container_top_voted` | ❌ **Not implemented** | Medium |
| Top downloaded | `container_top_downloaded` | ❌ **Not implemented** | Medium |
| Period filter chips | ChipGroup with All/Weekly/Monthly | ❌ **Not implemented** | Medium |
| Publish FAB | `fab_publish_help` | ❌ **Not implemented** | Low |
| Reward notice | `tv_reward_notice` | ❌ **Not implemented** | Low |
| Bottom nav | ✅ Present | ✅ Present | Done |

---

### 8. Onboarding

| Feature | Old | New | Status |
|---------|-----|-----|--------|
| Welcome screen | `activity_welcome.xml` + `WelcomeActivity` | ✅ `WelcomeScreen` | Done |
| Battery optimization guide | `activity_battery_optimization_guide.xml` | ✅ `BatteryOptimizationScreen` | Done |
| App lock guide | `activity_app_lock_guide.xml` → `AppLockGuideActivity` | ❌ **Removed** (was already commented out in manifest) | OK |

---

### 9. Notes

| Feature | Old | New | Status |
|---------|-----|-----|--------|
| Notes list | `NotesListActivity` + `item_note.xml` | ⚠️ `NotesScreen` placeholder | **PRIORITY** |
| Note detail | `NoteDetailActivity` | ❌ **Not implemented** | **PRIORITY** |
| Create from transcript | FAB in old NotesListActivity | ❌ Not implemented | Medium |
| Copy/Share actions | In NoteDetailActivity | ❌ Not implemented | Medium |
| Meeting recording banner | Not in notes screens | ❌ Not shown | Low |

---

### 10. Local Models (`LocalModelsConfigureActivity`)

| Feature | Old (`activity_local_models_configure.xml`) | New (`LocalModelsScreen.kt`) | Status |
|---------|---------------------------------------------|------------------------------|--------|
| All UI elements | 9+ cards, spinners, text inputs, switches | ⚠️ **Placeholder only** | **PRIORITY** |
| Engine status card | Status + device snapshot | ❌ | **PRIORITY** |
| Installed models spinner | + load/remove/unload | ❌ | **PRIORITY** |
| Curated catalog | Download starter models | ❌ | Medium |
| Generation settings | Profile, temp, topP, topK, maxTokens, etc. | ❌ | Medium |
| Warmup probe | Benchmark results | ❌ | Medium |
| Download progress | `progress_download` indicator | ❌ | Medium |

---

### 11. Other Screens

| Screen | Old | New | Status |
|--------|-----|-----|--------|
| `DailyFactsActivity` | ✅ | ❌ `LegacyScreenPlaceholder` | **PRIORITY** |
| `DailySummaryActivity` | ✅ | ❌ `LegacyScreenPlaceholder` | **PRIORITY** |
| `AppBlacklistActivity` | ✅ | ❌ `LegacyScreenPlaceholder` | Medium |
| `ScreenCapturesActivity` | ✅ | ❌ `LegacyScreenPlaceholder` | Medium |
| `PendingActionsActivity` | ✅ | ❌ `LegacyScreenPlaceholder` | Medium |
| `SyncedMediaGalleryActivity` | ✅ | ❌ `LegacyScreenPlaceholder` | Medium |
| `TranscriptionDebugActivity` | ✅ | ❌ `LegacyScreenPlaceholder` | Low |
| `DeviceBindActivity` | ✅ | ❌ `LegacyScreenPlaceholder` | Low |

---

## Remaining Phases

### Phase 8 — Bottom Navigation Fix + Recordings (PRIORITY)
- [ ] Fix bottom NavigationBar to 5-tab structure: Glasses (home), Chats, Recordings, Settings, Plugins
- [ ] Build `GlassesScreen` placeholder (simplified dashboard — connection status, battery, quick actions)
- [ ] Build full `RecordingsScreen` (recording list with play/transcribe, synced media link)
- [ ] Move `HistoryScreen` out of bottom nav — accessible via top-bar icon in Chat screen
- [ ] Set Glasses tab as the app launch destination

### Phase 9 — Settings Completeness
- [ ] Add Local Agent section to `SettingsScreen` (accessibility status, 9 buttons, 5 switches, 2 inputs)
- [ ] Add Memory & Privacy section (mode selector, vault controls)
- [ ] Add Transcripts section (storage, full transcription, auto capture toggles)
- [ ] Add Redaction section (redact names switch)
- [ ] Add Data section (export/import/clear buttons)

### Phase 10 — Pro Settings & Notes
- [ ] Build full `ProSettingsScreen` (cloud sync, ecosystem options, future features)
- [ ] Build full `NotesScreen` + `NoteDetailScreen`

### Phase 11 — Plugins & Local Models
- [ ] Build full `PluginsScreen` (plugin cards, image automation toggle, period filters)
- [ ] Build full `LocalModelsScreen` (model management, generation settings, warmup)

### Phase 12 — Secondary Screens
- [ ] `DailyFactsScreen`, `DailySummaryScreen`, `AppBlacklistScreen`, `ScreenCapturesScreen`, `PendingActionsScreen`, `SyncedMediaGalleryScreen`

### Phase 13 — Glasses Dashboard (Full)
- [ ] Build comprehensive `GlassesScreen` or integrate legacy `MainActivity` as a tab destination
- [ ] Map all 25+ buttons to Compose actions

### Phase 14 — Final Cleanup
- [ ] Remove all XML layouts and old Activities replaced by Compose
- [ ] Remove old `MainActivity` if fully replaced
- [ ] Update migration plan with final status
- [ ] Force push to `main`

---

## Icon Availability Reference

**Available (Filled/Outlined base set — no extended icons):**

| Icon | Filled | Outlined |
|------|--------|----------|
| Home | ✅ `Icons.Filled.Home` | ✅ `Icons.Outlined.Home` |
| List | ✅ `Icons.Filled.List` | ✅ `Icons.Outlined.List` |
| Settings | ✅ `Icons.Filled.Settings` | ✅ `Icons.Outlined.Settings` |
| Star | ✅ `Icons.Filled.Star` | ✅ `Icons.Outlined.Star` |
| Add | ✅ `Icons.Filled.Add` | ✅ `Icons.Outlined.Add` |
| Arrow Forward | ✅ `Icons.Filled.ArrowForward` | ✅ `Icons.Outlined.ArrowForward` |
| Arrow Drop Down | ✅ `Icons.Filled.ArrowDropDown` | ✅ `Icons.Outlined.ArrowDropDown` |
| Search | ✅ `Icons.Filled.Search` | ✅ `Icons.Outlined.Search` |
| Delete | ✅ `Icons.Filled.Delete` | ✅ `Icons.Outlined.Delete` |
| Refresh | ✅ `Icons.Filled.Refresh` | ✅ `Icons.Outlined.Refresh` |
| Check | ✅ `Icons.Filled.Check` | ✅ `Icons.Outlined.Check` |
| Send | ✅ `Icons.AutoMirrored.Filled.Send` | ❌ Not available |

**Unavailable icons (NOT in base set — use alternatives):**
`Chat`, `ChatBubble`, `History`, `Mic`, `Camera`, `Video`, `Cloud`, `Download`, `Upload`, `StarBorder`, `Bolt`, `Slideshow` → Use `List` or `Star`

---

## Migration Constraints (unchanged)
- Keep work off `main` during development
- Build APK with Android Studio JBR (`/opt/android-studio/jbr`)
- Use direct IP `http://177.95.92.150:48787` for relay server
- **`force push`** to GitHub origin/main after final merge
- `_local_termux_server/` must be gitignored
- Kotlin **2.0.0** + Compose compiler plugin
- `androidx.activity:activity` forced to `1.8.0`
- Navigation `2.7.7`
- Compose BOM `2024.06.00`
