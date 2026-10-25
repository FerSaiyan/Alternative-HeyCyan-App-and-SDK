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
- **Settings** (`b49ff72`, `fe6f866`, `27d2c62`):
  - The subtitle "Privacy-first defaults…" is gone.
  - "Custom AI provider" is now "AI provider".
  - The Tasker/Local switch row is gone.
  - Vault and data rows are reordered.
  - **Review these with the collaborator**; they change the Android settings screen.
- **Welcome and onboarding:** small icon changes.

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
