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

## Single Activity (`cyan-refactor-single-activity`): re-run the capture

The capture above launched each of the 41 Activities by name. Those screens are now routes of MainActivity, so **re-run the parity capture before merging** (tools in `cyan-refactor-wt/parity-tools/`; they need the emulator, so this branch was not captured).

The capture script has to change:
- Open a screen with `adb shell am start -n <package>/.MainActivity -a com.fersaiyan.cyanbridge.nav.OPEN_<ROUTE> --es com.fersaiyan.cyanbridge.nav.ROUTE <ROUTE>`. Route names are in `ui/nav/AppRoute.kt`, for example `SETTINGS`, `COMMUNITY_PLUGINS`, `CHAT_THREAD`, `RECORDINGS` or `META_PAIRING`. Add extras as the old Activity took them.
- MainActivity is single-task: run `am force-stop` between screens, or each start pushes onto the running back stack.
- The old Activity names are gone, except the entry points that are still kept: `.ui.MetaPairingActivity`, `.agent.ProSubscriptionActivity`, `.agent.WebSubscriptionCallbackActivity`, the Gemini Live permission gate, MemoMind Web plugins, and the launcher alias `.ui.WelcomeActivity`.

Expected result: pixel-identical screens. Each tab still draws its own bottom bar, and only the glasses screen uses `CyanBridgeNavShell`'s bar, exactly as before.

Expected UI-tree differences:
- Every screen except the glasses screen sits in one extra full-screen node with a pane title (the old Activity label), so screen readers announce screen changes as the window change did.
- Window animations between screens, including the onboarding fade, are gone. A static capture does not see this.

Any other difference is a regression.

Not unified (owner decision): the per-tab bars use the secondary container for the selected item, while the glasses screen's shell bar uses the primary container with a semibold label. Letting the shell draw the bar for every tab would match iOS: MainActivity would draw `CyanBridgeNavShell` around the tab routes, and those screens would pass `showNavigationBar = false`. It changes those bars' colors, so it was left out.
