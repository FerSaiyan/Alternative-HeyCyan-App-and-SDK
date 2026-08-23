# Tasker HIL / GitHub Actions Repair Worklog

Updated: 2026-08-23

## Goal

Repair the `security/tasker-agent-split` GitHub Actions workflows and make CyanBridge automatically exercise the real Tasker projects on the Pixel 9a emulator. Finish all HIL failures, validate the complete branch, commit, push, and verify both workflows.

## Status: COMPLETE — branch merged content pushed, both workflows GREEN

- Branch `security/tasker-agent-split` pushed through commit `09e5a12` (`d35c45f..09e5a12`).
- Both GitHub Actions runs on the pushed tip are **success**:
  - Android Tasker HIL — run 32620989475
  - Android self-hosted CI — run 32620989527
- All five Tasker HIL classes pass locally and in CI (`OK (5 tests)` via the workflow's exact comma-joined invocation).
- Unit tests, portability tests, both APK assemblies, static checks: green.
- Nothing further is pending for this effort. Sections below are kept as the investigative record.

## Repository And Worktrees

- Main repository: `/mnt/seagate/ML/HeyCyanSmartGlassesSDK` (stays on `main`; keep its unrelated `.idea/*` edits untouched; an identical copy of this worklog also lives there locally)
- This file (`/worklog.md`) is committed on both `main` and `security/tasker-agent-split` as the canonical handoff record.
- Remote: `git@github.com:FerSaiyan/Alternative-HeyCyan-App-and-SDK.git`
- Target branch: `security/tasker-agent-split`
- Authoritative editing worktree: `/tmp/opencode/HeyCyan-tasker-split`
- Clean build worktree: `/tmp/opencode/HeyCyan-tasker-verify` (Moonshine at `79b0217f...`, builds fine)

## Emulator And Installed Apps

- Serial `emulator-5554`, AVD `Pixel_9a`, API 37 (`sdk_gphone16k_x86_64`), headless `-no-window -no-snapshot-save`.
- Tasker 6.6.20, AutoInput 3.0.12, AutoApps installed (restored the user's existing AutoInput entitlement through official Play flow — no purchase/bypass).
- Accessibility services enabled (both required):
  - `com.joaomgcd.autoinput/com.joaomgcd.autoinput.service.ServiceAccessibilityV2`
  - `net.dinglisch.android.taskerm/net.dinglisch.android.taskerm.MyAccessibilityService`
- API 37 adbd drops connections frequently: prefix device ops with `adb -s emulator-5554 wait-for-device`. Full-suite single-shot runs die mid-run; per-class runs are reliable.
- Play proxy `10.0.2.2:8888` via `python3 -B /tmp/opencode/tcp_connect_proxy.py`.

## Root Causes Solved Today (after commit d89e472)

1. **AutoInput entitlement**: plugin actions silently failed until the user's existing license was restored by installing the official **AutoApps** app from Play ("You already bought AutoInput before").
2. **Tasker accessibility consent**: importing/running profiles that rely on `%WIN` requires Tasker's own "Accessibility Access" granted through **its official disclosure flow** (dialog OK → system screen → toggle → Allow). `settings put secure enabled_accessibility_services` gets reverted by Android and does NOT set Tasker's internal consent flag. Symptom was "Missing Permissions ... Accessibility Access" at import and at task play.
3. **AutoDiary foreground package**: AutoInput's `%aipackage` stays empty unless an AppPackage filter is set, so exclusion logic never matched. Fixed by deriving package from Tasker `%WIN` (requires item 2), falling back to `%aipackage`.
4. **JS `sendIntent` unreliable**: Tasker 6.6.20 JavaScriptlets finish without delivering package-targeted broadcasts. All CyanBridge deliveries (Local Agent responses earlier; now AutoDiary debug response, AutoDiary periodic capture, Visual Diary trigger) use **native Send Intent actions** (code 877, arg9=0 receiver target, arg7=package). Validator now forbids `sendIntent("com.fersaiyan.cyanbridge...` patterns.
5. **Transport escaping of JSON extras**: Tasker decodes one backslash-escape level inside Send Intent extra values, turning JSON `\n` into raw newlines and corrupting org.json parsing (`invalid_tasker_observation:JSONException`). Fix: `JSON.stringify(x).replace(/\\/g, "\\\\")` before assigning to the Tasker variable used in the extra.
6. **JavaScriptlet local-variable export**: only `var`-declared lowercase variables reach later Tasker actions; implicit assignments don't. All gate/result vars are declared explicitly (`var cb_enabled`, `var capture_payload`, etc.). Keep declarations as leading statements; bottom-of-script `var` declarations were observed flaky.
7. **UI Query bundle schema**: minimal bundles hang the plugin until its timeout. Both AutoDiary query actions got the full field set copied from the proven Local Agent export (AppPackage `<null>`, OnlyVisible true, subbundled, VARIABLE_REPLACE_KEYS, normalized code `1040876951`).
8. **Deterministic profile import**: the sync script previously exited early on transient focus changes, leaving stale half-imported projects. It now classifies each dialog (import → overwrite → enable-profiles) via fresh `uiautomator` dumps, requires import+enable confirmations plus two consecutive polls with the importer gone, and detects importer errors including the singular "Missing permission" form.
9. **16 KB compatibility dialog steals focus**: on API 35+ emulators, launching CyanBridge pops a system-owned "Android App Compatibility" overlay (Meta Wearables SDK libs are not 16 KB aligned). It holds window focus so `%WIN`=android and Compose taps get lost. `run_instrumentation.sh` now auto-taps "Don't Show Again" when present.

## Known-Failing, NOT CI-Gated (pre-existing, documented intentionally)

Six Compose UI screen tests fail on this emulator but are **run by neither GitHub Actions workflow** (self-hosted CI runs unit+portability+assemble only; HIL workflow runs exactly the five `hil.*` classes). They also predate this branch (last touched in `b8ec53a`/earlier) and were unrunnable before the Espresso upgrade because every instrumentation crashed on Espresso 3.6.1's removed-API call:

- `GlassesManagerUiGatingTest.genericAudioProfile_hidesExtrasPanel` — expects "Meeting capture" node displayed under GENERIC_AUDIO gating
- `ui.glasses.GlassesDashboardScreenTest`
- `ui.localmodels.LocalModelsConfigureScreenTest` — 'Catalog Model 9' not found in scroll range
- `ui.MetaPairingScreenTest`
- `ui.onboarding.WelcomeScreenTest.setupRequiresAnExplicitLanguageChoice` — Español click does not invoke callback (fails even after `pm clear`)
- `ui.settings.SettingsScreenTest` — TestTag 'settings_section_AI' absent / touch injection failure

The 16 KB dialog fix may improve some; they still fail as of this writing. Investigating them belongs to a separate non-security branch.

## Committed in `09e5a12` (AutoDiary/HIL-tooling fixes)

All now on the remote branch:

- `android/CyanBridge/tasker/CyanBridge_AutoDiary_Tasker.prj.xml` — %WIN-based package resolution with %aipackage fallback; explicit var declarations; complete UIQuery bundle schema on both query actions; native Send Intent for debug-response and periodic-capture; backslash transport-escaping of both payloads
- `android/CyanBridge/tasker/CyanBridge_VisualDiary_Tasker.prj.xml` — periodic trigger via conditioned native Send Intent (`%visual_capture_fire`)
- `tools/hil/sync_tasker_profiles.sh` — deterministic three-dialog confirmation; idempotent enabling of BOTH accessibility services before imports; error classifier covers "Missing permission"/"needed permissions"
- `tools/hil/preflight.sh` — requires Tasker accessibility service alongside AutoInput's
- `tools/hil/run_instrumentation.sh` — dismisses the 16 KB compatibility dialog before running
- `tools/hil/validate_tasker_profiles.py` — forbids JS `sendIntent("com.fersaiyan.cyanbridge` delivery pattern; requires `payload:%capture_payload`

## Validation Performed (all green)

- `python3 tools/hil/validate_tasker_profiles.py`; `bash -n` all `tools/hil/*.sh`; `git diff --check`
- `:app:testDebugUnitTest` + `:shared:portabilityTest` (clean verify worktree)
- `:app:assembleDebug` + `:app:assembleDebugAndroidTest`; both APKs reinstalled on emulator
- Preflight; deterministic 5-project profile sync (15 confirmations)
- Focused HIL classes individually AND workflow-style combined run: `OK (5 tests)` (14.7s)

## Final Steps

1. ~~Commit and push~~ **DONE**: commits `d89e472` + `09e5a12` pushed (`d35c45f..09e5a12`).
2. ~~Watch Actions~~ **DONE**: both workflows green (Tasker HIL 32620989475, self-hosted CI 32620989527).

## Constraints (unchanged)

- No subagents. No credential exposure. No billing bypass.
- Do not modify unrelated main-checkout files or use destructive Git commands.
