# Crash sweep: `cyan-refactor` 65fabc60

Run on 2026-10-27 on the read-only `CyanBridge_Walking_Aid_CI` emulator (Android 36, 1080×2400, port 5610). The [parity capture](SCREEN_PARITY.md) only took screenshots. This sweep opened every screen and tapped its controls, looking for crashes: FATAL EXCEPTION, ANR, StackOverflow and "has stopped" dialogs. The fixes are on `cyan-refactor-crash-sweep`.

## How it was exercised

The tools live outside the repo, in `cyan-refactor-wt/parity-tools/crash-sweep/`:
- an untracked instrumentation test, `CrashSweepTest.kt`;
- adb scripts for lifecycle and monkey runs.

The test opens a route (or a component). It can first click a list of labels, then taps every safe control of the top window, page by page:
- buttons, switches, chips, radio buttons and expanders;
- text fields, which get sample text.

How it handles what a tap opens:
- **Dialogs, sheets and menus:** it fills their fields, toggles their small checkable groups, then cancels them (Cancel / Not now / Close, or Back).
- **Screens:** it sweeps them one level deep, then returns with Back.
- **Other apps** (browser, Settings, file picker, Tasker): it returns with Back.

It skips controls that delete data, sign out, reset, buy or subscribe. Every run starts from `pm clear` with the runtime permissions and app-ops pre-granted. After each run the scripts save `logcat -b crash`, a FATAL/ANR filter and the action log.

Coverage, in 88 runs with about 1,100 control actions and 113 dialogs exercised:
- **Routes:** all 39 `AppRoute`s, opened alone. The bottom tabs are the CHAT_LIST, RECORDINGS, COMMUNITY_PLUGINS and SETTINGS routes. Sub-screens were swept as reached, for example:
  - Settings → Pro, Appearance, Local models, Tasker integrations, Voice profile;
  - Plugins → each plugin's settings;
  - Auto Diary → facts, summary and captures.
- **Kept activities:** the launcher alias, the Gemini Live permission gate, the web subscription callback and the MemoMind Web plugins screen.
- **Glasses dashboard per family:** HeyCyan, Eyevue, TuneBuds, MoYoung W620, Meta Ray-Ban, Mentra Live, Meizu MYVU, MemoMind and generic audio.
  - Each family's dashboard was swept with the install-reason question dismissed.
  - Each got a second pass with "Show advanced controls" open: Device info (Battery, Version, Sync time, Volume), AI image quality, developer tools and OTA.
  - Family screens:
    - Mentra: firmware update, broadcast and synced media.
    - MemoMind: dashboard card and Web plugins (with "MemoMind is not connected").
    - Meta: setup choice and diagnostics.
    - Eyevue: live preview card.
    - HeyCyan: sync-flow chooser, live preview, privileged ADB dialog.
- **Second-level dialogs:**
  - Notes tab;
  - chat bubble color pickers and appearance reset;
  - language switch to Portuguese, then a re-sweep of Settings;
  - vault passphrase.
- **Onboarding from a fresh install:**
  - the full path through all 5 steps, including voice enrollment: start recording, stop, Back while recording, Skip while recording;
  - "Skip all";
  - "Don't show this again" plus model choice;
  - a language change on Welcome;
  - Back on step 1.
- **Lifecycle:** each of these went through portrait → landscape → portrait, background → recents, then `am kill` in the background → recents, then Back:
  - Settings, Note editor, Chat thread, Local models, Gemini Live, Voice profile, Device bind, Mentra broadcast, Meta setup;
  - the glasses dashboard with HeyCyan, Mentra Live and MemoMind.

  Every screen came back as it was. Mentra keeps its connection foreground service, so `am kill` cannot kill it.
- **Monkey:** with 3-button navigation, `--throttle 150`, 3000 events per seed. The final build ran seeds 101 (fresh install), 404, 505 (MemoMind) and 606 (MYVU). The first build also ran 101, 202 (HeyCyan) and 303 (Mentra).

## Crashes found and fixed

| Crash | Root cause | Fix | Confirmed |
|---|---|---|---|
| **Meizu MYVU selected: app crashes at launch.** `NullPointerException: StateFlow.getValue()` in `MeizuMyvuManager$MyvuDisplayAdapter.<init>` from `AutoPairManager.tryConnectOnce` (DefaultDispatcher). Listed as a pre-existing "crashes in both builds" issue in SCREEN_PARITY.md. | Kotlin initialization order: the `adapter` property was declared above `_state`/`state`, and the adapter's `init` reads `manager.state`, which was still null. It fails every time the manager is created, on hardware too. | `c50e7c27` declares the adapter after the state flows. `f227179d` adds a Robolectric test that creates the manager; it fails without the fix. | Yes. The MYVU dashboard, its advanced pass, the lifecycle runs and monkey seed 606 ran with no crash. |
| **Test voice with the "Pro (Free Gemini Live)" provider crashes the app** (monkey seed 101 found it; reproduced by hand). `ForegroundServiceDidNotStartInTimeException` for `AiQuestionForegroundService`; system_server logs "Bringing down service while still waiting for start foreground". | The question starts the service with `startForegroundService()`, then hands over to Gemini Live about 10 ms later and calls `AiQuestionForegroundService.stop()` → `stopService()` before `onStartCommand` has run. Android kills an app whose foreground-started service is stopped before `startForeground()`. The same `stop()` is in the pre-refactor code (98587c8). Any question that ends right after it begins can hit it. | `0a08b7a5`: a stop that arrives while the start is pending is held until `onStartCommand`, which promotes the service and then stops it. `28fd5369` adds a Robolectric test that fails without the fix. | Yes. The same path ran twice (logcat shows the Gemini Live hand-over, the service going foreground, and a normal stop), and monkey seed 101 ran clean on the fixed build. |

No other FATAL EXCEPTION, ANR, StackOverflow or "has stopped" dialog appeared in any run on the fixed build.

## Not exercised
- **Flows that need a connected device:** Mentra gallery sync, live preview and the media controls only appear on the dashboard while connected. MoYoung and generic audio show only the connection card when disconnected. Meta DAT registration, MemoMind panel actions and OTA uploads also need hardware. These remain in HARDWARE_TEST_CHECKLIST.md.
- **Local model inference** (no model is installed on the AVD), so chat answers and the model performance test only reach their "no model" states.
- **Destructive controls** were skipped by design: delete, remove, reset, sign out, purchase. Their confirmation dialogs were opened and cancelled where they have one.
- **External apps**, such as the file picker, browser, Tasker and the system Settings pages, were opened and left with Back.

## Harness notes
- **Accessibility windows:** while a modal dialog is open, only the dialog is listed. The harness compares the top window with the screen's own window id rather than looking for a second window. It also calls `UiAutomation.clearCache()` before each read, because routes swap content inside one window.
- **Gesture navigation and monkey:** a monkey edge swipe can move the task to the back. Monkey's controller then refuses to resume the launcher, and the app gets blamed with an "Application does not have a focused window" ANR (main thread idle). The monkey runs therefore use 3-button navigation.
- **Play Store updates fill the overlay:** on this read-only AVD, Play Store updates GMS and other apps in the background and fills the overlay (ENOSPC on install). The sweep disables `com.android.vending` for the session and rolls back the updates with `pm uninstall-system-updates`.

## 2026-11-11: screens and features added since ff329c7f (`cyan-refactor-pro-questions` b695e3ca)

Run on the read-only `CyanBridge_Walking_Aid_CI` emulator (Android 36, port 5610, `-memory 3072`,
3-button navigation, Play Store disabled for the session). The APK was built from b695e3ca. The
tools are the same (`parity-tools/crash-sweep`), plus three helpers and new jobs (`job-new.sh`,
`job-fam2.sh`, `job-extra.sh`):
- `fresh_routed`: a fresh install now opens "What brought you to CyanBridge?" before any route, so the
  job taps Skip once, then opens the route.
- `seed_pro`: Pro settings opens only with an active plan, so the job writes a locally active Standard
  plan (`pro_subscription_prefs`). The app has no server Pro account, so the customization card shows
  its upgrade state.
- `bt_on`: monkey seed 808 switched Bluetooth off from the quick settings. Every later dashboard then
  opened the system "turn on Bluetooth" prompt first. The jobs now switch it back on, and the
  affected family runs were repeated.

Coverage: 106 runs, about 1,400 control actions and 123 dialogs, 4 monkey seeds × 3,000 events, and
16 instrumentation tests.
- **Pro settings**, with HeyCyan, TuneBuds, EyeVue and MYVU selected: the glasses customization card
  (expand, upgrade path), the Photo & voice questions model picker with the Live Economy/Private modes,
  the Chats and Background models, Refresh, Change plan, Cancel (kept), and "Open Gemini Live preview".
- **Device picker** (`DEVICE_BIND`): scan, rescan, Meta pairing. No Bluetooth devices exist on the
  emulator, so the Classic-inquiry and bonded TuneBuds rows were not drawn.
- **Meta:** the setup choice (Developer Mode / request access), diagnostics and Send logs.
  `MetaPairingMockFlowTest` (7/7) and `MetaPairingScreenTest` (9/9) cover NEEDS_SETUP and Register again
  on MockDeviceKit.
- **Mentra SDK display glasses** (Even G1, G2, Mentra Display, NIMO, Xingyi AR99): dashboards and
  advanced passes. Disconnected, they show only the connection card.
- **Mentra miniapps:** the route with Mentra Live and Even G1 selected, URL install field, store browser
  and search, and the Enable switch on the Plugins page.
- **Plugins page** with HeyCyan, MemoMind, Mentra Live, Even G2 and TuneBuds selected.
- **Question routing** (Settings → AI provider): Classic, Shadow and Smart selected by hand.
- **MemoMind:** dashboard and Web plugins.
- **Onboarding from a cleared app** through the launcher alias: full path, Skip all, Don't show again,
  language change, Back.
- **All 39 routes** (90 s each). The family dashboards and advanced passes were repeated with
  Bluetooth on.
- **Monkey:** seeds 101 (fresh), 404, 707 (Even G1) and 808 (TuneBuds).

Result: no FATAL EXCEPTION, ANR, StackOverflow or "has stopped" dialog in any run. Nothing to fix.

Not exercised: TuneBuds Classic rows and connected display glasses (need hardware); customization
upload and install (needs a Standard/Max server plan and glasses); miniapp install from the store;
Pro model answers (no test Pro account, see worklog 2026-11-11).

Side effect: each `pm clear` launch registers an anonymous relay account in production, as in earlier
sweeps.

## 2026-11-12: functional sweep of the changes since v2.3.0 (`cyan-refactor-v230-sweep`, from 420e07b3)

Earlier sweeps only looked for crashes. This one checked that the new and changed screens work.
The feature list and per-item results are in [CHANGES_SINCE_v2.3.0.md](CHANGES_SINCE_v2.3.0.md);
evidence (screenshots `shots/NN-*.png`, logcat, instrumentation output) is in
`cyan-refactor-wt/v230-sweep-evidence-20261112/`.

Setup: read-only Pixel_9a (API 37, rooted, Play, Tasker) on port 5610 through `tools/hil/start_emulator.sh`,
`-memory 3072`, 3-button navigation, Play Store disabled, CI Pro account linked with
`ProAccountSetupHilTest#linkApiToken`. The debug build is release-key signed (keystore.properties), so it
replaced the persistent app keeping its data. Emulator host audio was enabled through an `EMULATOR_BIN`
wrapper and a PulseAudio null sink, but emulator 37.1.11 (headless) logs "Could not init `pa` audio driver":
no microphone input reaches the AVD, so voice enrollment could not complete.

Covered: onboarding from a cleared install (full path, Skip all, TalkBack vision offer both ways, not shown
again), Settings (media sync flow, question routing, voice profile status, vision switch, Pro settings,
customization card, Local models download/backend/test), Smart and Classic question routing on the Pro relay
(`GlassesQuestionFlowTest -e gq_provider pro`), Plugins per family, Mentra miniapps, MemoMind Web plugins,
Oral Lesion Bench up to the model download, Device Bind empty state, chats and notes, deep link, back stack,
launcher re-entry, rotation and process death.

Bugs fixed on the branch: launcher re-entry dropping the back stack (722b083e), a new chat lost after process
death (845ae035), the Settings loop after a twice-denied Bluetooth permission (28fed707), the customization
card's raw "HTTP 404" (b8c99e6b), backslashes before apostrophes in Compose strings (d7170a34).
Unit tests after the fixes: app 743/743, shared portability 413/413.

No FATAL EXCEPTION or ANR in the session logcat or the crash buffer.
