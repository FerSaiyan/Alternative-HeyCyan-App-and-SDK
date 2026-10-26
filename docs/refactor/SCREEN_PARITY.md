# Screen parity: baseline `98587c8` vs `cyan-refactor`

Captured on 2026-10-25 on the read-only `CyanBridge_Walking_Aid_CI` emulator (Android 36, 1080×2400):
- 41 activities, each with up to 4 scrolled pages;
- the Glasses screen plus the Chats, Media and Plugins tabs;
- the dashboard seeded for each device family.

The comparison covers screenshots (the status bar is ignored) and the accessibility UI tree. Tools live outside the repo in `cyan-refactor-wt/parity-tools/` (capture.sh, devices.sh, compare.py, plus an untracked instrumentation test).

Result: **no visual change comes from the refactor itself.** Of 95 targets, 51 are pixel-identical. Every other difference falls into one of these groups.

## From the merged iOS branch (`ios-accessible-mode`, shared Compose UI)

These came in with the collaborator's shared-UI commits, and Android inherits them:
- **Glasses dashboard:** icons on Scan and Reconnect; battery and storage icons in the status card; "Test image AI description" renamed "Test description" (`b49ff72`, `f5faee9`, `5d3fefc`).
- **Bottom bar:** the Glasses tab uses a glasses icon. This accounts for the 0.07% difference on every screen with the bottom bar.
- **Chat list:** the top-right Appearance button now shows a chat-bubble glyph, because `AppIcon.Appearance` was redrawn in `b49ff72`. **Confirm this is intended.**
- **Settings** (`b49ff72`):
  - The intro card "Privacy-first defaults, local controls, and automation settings." is removed.
  - "Custom AI provider" is renamed "AI provider" in every language.
  - FAQ is no longer its own card; it now sits inside the Support section, after "Send debug logs".
  - Every other row (including the Tasker/Local/Pro switch and the vault and data rows) is unchanged. They only sit higher on the page because the intro card is gone.
  - **Review these with the collaborator.** Side-by-side images are in `cyan-refactor-wt/friend-ui-changes/` (not in the repo).
- **Welcome and onboarding:** small icon changes.

## Owner decision (2026-10-25)

Keep the collaborator's UI changes except two, which were reverted:
- The test button is back to "Test image AI description" in all 9 languages.
- The Notes & Chats header button shows the palette (Appearance) icon again on the Chats tab. The Notes tab keeps its gear.

## Environment only
- Dates in the daily facts, summary and screen-capture titles.
- Device RAM and free space on the Local models screen.
- The launcher date behind screens that close at once without an account (Pro settings, web subscription callback, Gemini Live permission gate).

## Behavior difference found
- **Eyevue selected at startup:** about 5 s after launch, the baseline shows "Eyevue error" and the refactor shows "Connecting to Eyevue" or the real battery-read failure.
  - In the baseline's logcat, a first connect attempt is cancelled (`StandaloneCoroutine was cancelled`); the refactor makes one attempt.
  - Both then fail the same way against the fake MAC.
  - This looks like a duplicate connect being removed. It is listed in HARDWARE_TEST_CHECKLIST.md.

## Not covered
- Meizu MYVU crashes on the emulator in both builds, which is an existing issue.
- iOS screens: no Mac is available here.
- Flows beyond the first screen of each Activity (dialogs, long flows) are covered by HARDWARE_TEST_CHECKLIST.md.

## Single Activity: re-capture (2026-10-26)

Captured on 2026-10-26 from `cyan-refactor` 0e40f92a, then from `cyan-refactor-parity-fixes` with the fixes below. Same read-only `CyanBridge_Walking_Aid_CI` emulator. The baseline captures from 2026-10-23 were reused, since the baseline commit and AVD are unchanged. Port 5600 was taken by another emulator, so this run used 5610 (`SERIAL=emulator-5610`).

How the capture opens screens now:
- `ROUTES=1 capture.sh <variant>` opens each former Activity as its MainActivity route. `parity-tools/routes.txt` maps each old target to its route, and the capture test passes it as `-e route <ROUTE>`. The test starts MainActivity with `OPEN_<ROUTE>` plus the route extra, using `NEW_TASK | CLEAR_TASK`.
- The capture runs `force-stop` and `pm clear` between targets.
- MainActivity, the launcher alias `.ui.WelcomeActivity` and the kept Activities (web subscription callback, Gemini Live permission gate) still launch by component.
- Captures are filed under the old target names, so `compare.py baseline <variant>` lines up with the baseline.
- `capture.sh` and `devices.sh` also save the crash log buffer for each target.

The screens and states are the same as before: the 39 former Activities still present, the glasses screen with the Chats, Media, Plugins and Settings tabs, and 7 device families. That is 45 targets and 107 screenshots, plus UI trees. Mentra pairing and transcription debug are no longer captured: other branches removed those screens (ca06bc3a, 6b6e26cb).

### Regressions found and fixed (`cyan-refactor-parity-fixes`)
- **a1c7fc25: install-reason question over every screen.** The "What brought you to CyanBridge?" dialog was tied to MainActivity starting, so it opened over Welcome, onboarding and every route opened by an intent. It now follows the glasses screen's start, as before.
- **e1135d62: voice profile screen crashed on open.** `NavScreen.viewModels` defaulted to `NewInstanceFactory`, which cannot build an `AndroidViewModel`. It now defaults to `AndroidViewModelFactory`, as the Activity's default did. `AppNavigatorTest` covers this. The adb smoke test found it.
- **a21ecb04: app crashed at launch with Mentra Live selected.** This is not a single-Activity regression. A refresh loop through refreshAfterSelection → renderConnectionStatus → updateDeviceClassText → gating overflowed the stack. It came from 860fb791 (Mentra Live as its own glasses device), after the 2026-10-25 capture.

### Result after the fixes
The final run is compared with the pre-single-Activity capture of `cyan-refactor` (2026-10-25). Every route screen is pixel-identical except for changes from other merges and the environment. Insets and edge-to-edge, status and navigation bar colors, top bars and back arrows, titles, the theme, fonts and soft-input layouts are unchanged. The bottom bars are pixel-identical to before.

Targets compared: 45 (107 screenshots). Against the baseline, 21 targets are pixel-identical on every page and 24 differ. Against the 2026-10-25 capture, 26 are identical and 19 differ. Every UI tree differs at least by the pane-title node. Each difference is one of these:
- **Expected from the single Activity:**
  - The UI tree has one extra full-screen node per route (the pane title).
  - Pro subscription settings closes at once without an account. Opened alone, it now shows the glasses screen under it instead of the launcher. In the app it still returns to the screen that opened it.
  - The capture's tap on "Settings" from the Plugins tab now reaches the bottom-bar Settings tab. In the baseline it hit a stale tree node and did nothing. This is a harness timing effect.
- **Collaborator UI changes and owner reverts:** already listed above (icons, Settings intro/FAQ, "AI provider", Test image AI description, palette icon).
- **Other merges since 2026-10-25:**
  - Device bind has no "Pair Mentra Live" button (ca06bc3a).
  - Mentra Live reconnects on launch and gains advanced controls (Mentra merges).
  - Onboarding has 5 steps (voice profile step), and Settings gains "Question routing", "Voice profile" and "Accessibility" (c8fc4efb).
  - The Meta setup screen offers the setup choice (4c9894c7).
  - Local Agent: Telegram is removed and the screenshot switches became a Tasker-variable note.
- **Environment:**
  - Dates and the launcher behind screens that close at once.
  - Emulator RAM and free space on Local models.
  - Local Agent shows "Accessibility service: Enabled" because AutoInput's accessibility service is now enabled in the AVD image.
  - Eyevue's status timing, as described above.

### Navigation smoke test (adb only)
- **Back:** from each of the 40 routes, opened over the glasses screen, one Back returns to the glasses screen with no crash. Two exceptions:
  - Pro settings closes at once without an account.
  - Meta setup takes 3 Backs (two dialogs first), as the baseline Activity does.
- **Rotation:** Settings, Note editor, Chat thread and Local models keep their screen and back stack through portrait → landscape → portrait. Text typed in the note editor is kept.
- **Launcher (`.ui.WelcomeActivity` alias):** the same path as the baseline:
  - Welcome → battery guide → onboarding steps.
  - Back on a step leaves the app, as before.
  - "Skip all" opens the glasses screen with the install-reason question.
  - After onboarding, the launcher opens the glasses screen directly.

### Still open
- **Bottom-bar colors (owner decision, not unified).** The per-tab bars use the secondary container for the selected item. The glasses screen's shell bar uses the primary container with a semibold label. Letting the shell draw the bar for every tab would match iOS: MainActivity would draw `CyanBridgeNavShell` around the tab routes, and those screens would pass `showNavigationBar = false`. That changes those bars' colors, so it was left out. The capture confirms both bars are unchanged from the baseline.
- **Not seen by a static capture:** window animations between screens are gone, including the onboarding fade.
