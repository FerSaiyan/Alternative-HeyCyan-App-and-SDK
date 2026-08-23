# CyanBridge Android Tasker / HeyCyan HIL

This directory contains the hardware-in-the-loop (HIL) test harness used by the
`Android Tasker HIL` GitHub Actions workflow. It is designed for the existing
self-hosted Linux runner; Jenkins is not required.

## Device roles

The workflow intentionally treats the lab as two different kinds of instruments:

- **Persistent licensed automation emulator**: Tasker, AutoInput/AutoApps entitlement, Google account, Chrome, Gmail, and optionally a CyanBridge local model or Pro Subscription configuration. AI/browser/email automation HIL is pinned here even after a physical phone is connected.
- **Physical glasses phone**: real HeyCyan/Meta BLE, camera/media, battery/background behavior, and Visual Diary hardware validation.

The emulator is cold-booted with `-no-snapshot` when CI must start it, but its userdata is never wiped. Paid-app installs, Google login, Tasker profiles, accessibility consent, and app settings remain persistent.

## Test layers

1. **Static contract checks** (`validate_tasker_profiles.py`)
   - Tasker XML parses and contains the expected actions/task names.
   - Gemini v3 / ChatGPT v1 profile handshake versions match CyanBridge.
   - Local Agent / AutoDiary / Visual Diary Tasker contracts are present.
   - HIL controller calls the real production periodic Tasker tasks.
   - Play-sensitive broad permissions and the CyanBridge AccessibilityService stay absent.

2. **Emulator smoke**
   - Uses the already-configured Android Studio AVD when available.
   - Installs the debug app and androidTest APK with replacement installs.
   - Verifies the deterministic `HilFixtureActivity` and instrumentation plumbing.

3. **Core Tasker HIL (emulator or physical phone)**
   - Prefers a configured physical target for hardware coverage and falls back to the running emulator.
   - Requires Tasker, AutoInput, and the required accessibility services; missing prerequisites fail the workflow instead of silently skipping integration coverage.
   - Synchronizes the exact Tasker XML files from the checked-out commit.
   - Verifies Local Agent observe/click/type and `%CB_LocalAgentBlocked`.
   - Invokes the real AutoDiary periodic Tasker handler and verifies both Memory Vault ingestion and `%CB_AutoDiaryExcluded`.
   - Verifies Gemini and ChatGPT profile handshakes independently.
   - If the emulator drops off ADB after Tasker import, CI cold-boots the same persistent AVD and re-installs only CyanBridge/test APKs; userdata is preserved.

4. **Optional CyanBridge local-AI -> Tasker -> Chrome HIL**
   - Runs specifically on the persistent automation emulator.
   - Uses a deterministic web fixture exposed to Chrome with `adb reverse`.
   - The production CyanBridge local model chooses every action while Tasker/AutoInput performs Android UI execution.
   - The final answer must contain facts that exist only on the observed browser page.
   - Unsupported Tasker primitives must be recovered from by the planner instead of being hidden by ADB test-side input.

5. **Optional approved real-email HIL**
   - Runs specifically on the persistent automation emulator and is disabled by default because it creates a real external side effect.
   - CyanBridge researches the deterministic smartglasses-news fixture in Chrome, summarizes it, and prepares a uniquely tagged email to `fernandosaiyan10@gmail.com`.
   - The unique article facts are deliberately absent from the task prompt, so the email cannot pass by copying instructions; the planner has to observe the Chrome page.
   - `SendEmail` is a HIGH-risk action and must remain queued in CyanBridge while Gmail is still unopened.
   - An ambiguous textual reply such as `maybe` must not authorize anything.
   - A literal production reply `yes` is routed through `LocalAgentController` -> `TaskerLocalAgentService` -> `LocalAgentApprovalCoordinator` before the queued action reaches Tasker.
   - After approval, CyanBridge re-observes Gmail; Tasker executes the visible Send interaction, and the planner may not claim completion until the compose state is gone / send state is observed.
   - Because sender and recipient are the same lab account, the test waits for its unique `CB-HIL-<timestamp>` subject to become visible in Gmail.
   - If Pro Subscription is selected in CyanBridge, the Pro planner is allowed; otherwise the test requires the on-device local-model path.

6. **Optional HeyCyan HIL**
   - Invokes the real Visual Diary periodic Tasker handler on the physical phone.
   - Requires `DeviceClass.HEY_CYAN` selected and BLE connected.
   - Pass condition is a new usable `AUTO_LOOP_THUMB_*.jpg` created by the real glasses thumbnail path.
   - Optionally waits for a new `Glasses scene ...` candidate fact when the phone has a compatible Gemma 4 visual model configured.

## Persistent automation emulator setup

Recommended state once you create the dedicated Google Play AVD:

- Give the AVD a stable name and set `CYANBRIDGE_HIL_EMULATOR_AVD` to that exact name.
- Sign into the Google account that owns Tasker/AutoInput (or a dedicated lab account with those purchases).
- Install Tasker, AutoInput, and AutoApps through the normal Play/AutoApps entitlement flow.
- Complete Tasker's own Accessibility Access disclosure flow and enable AutoInput accessibility.
- Install Chrome and Gmail; for the real-email HIL, Gmail must already be signed into the self-test account.
- Configure either an on-device CyanBridge local model or Pro Subscription if those AI HIL layers should run.
- Do not use `-wipe-data`, delete the AVD, or make CI recreate Google/account/app setup.

## Physical phone setup

- USB debugging enabled and permanently authorized for the lab PC.
- No PIN/password on the dedicated test phone.
- Developer option **Stay awake while charging** enabled.
- Normally connected to power and USB.
- Tasker/AutoInput installed and configured if core Tasker HIL will also run there.
- CyanBridge configured once with the HeyCyan glasses paired and selected.

An OLED-protection app such as Extinguisher is compatible as long as Android remains logically
interactive and AutoInput still sees the underlying foreground app. If Tasker profile import
fails because the protection overlay becomes the foreground/accessibility window, configure an
exception for Tasker/CyanBridge during HIL profile sync.

## GitHub repository variables

All variables are optional. Defaults avoid destructive or hardware-dependent checks until the lab is ready.

- `CYANBRIDGE_HIL_EMULATOR_AVD`
  - Recommended once the licensed automation AVD exists.
  - Exact AVD name used when the emulator must be cold-booted/recovered.

- `CYANBRIDGE_HIL_SERIAL`
  - Optional exact serial for the core Tasker/physical target.
  - The legacy `CYANBRIDGE_HIL_PHONE_SERIAL` remains a fallback.

- `CYANBRIDGE_HIL_ENABLE_LOCAL_AI`
  - Default: `false`.
  - Set to `true` after the persistent emulator has an on-device CyanBridge model selected.
  - Runs `LocalAiTaskerChromeHilTest` on that emulator and rejects a remote OpenAI-compatible local-model endpoint for this specific local-model assertion.

- `CYANBRIDGE_HIL_ENABLE_EMAIL_SEND`
  - Default: `false`.
  - Set to `true` only after Gmail is installed/signed in on the persistent automation emulator and the planner configuration is ready.
  - Sends one real self-addressed email on each HIL workflow run that reaches the stage.
  - The email has a unique `CB-HIL-<timestamp>` subject and explicitly labels its content as deterministic fixture data, not live smartglasses news.

- `CYANBRIDGE_HIL_ENABLE_GLASSES`
  - Default: `false`.
  - Set to `true` only after the dedicated physical phone reliably reconnects to paired HeyCyan glasses.

- `CYANBRIDGE_HIL_EXPECT_VISUAL_FACT`
  - Default: `false`.
  - Adds the stronger assertion that a real glasses capture produces a new candidate `Glasses scene ...` fact.

- `CYANBRIDGE_HIL_UPLOAD_VISUAL_DIAGNOSTICS`
  - Default: `false`.
  - When true, failure artifacts may include screenshots/UI XML and can expose account/UI content.

The workflow also expects the existing `META_GITHUB_TOKEN` secret. It is exported to Gradle as
`GITHUB_TOKEN`, which is the name currently consumed by the Meta DAT repository/build logic.

## Branch-exact Tasker profile synchronization

`sync_tasker_profiles.sh` imports these exact files from the checked-out commit:

- `Tasker_AI.prj.xml`
- `CyanBridge_LocalAgent_Tasker.prj.xml`
- `CyanBridge_AutoDiary_Tasker.prj.xml`
- `CyanBridge_VisualDiary_Tasker.prj.xml`
- `CyanBridge_HIL_Tasker.prj.xml` (lab-only; not a user-facing plugin)

The script stages the files inside the debuggable CyanBridge app with `adb run-as`, exposes them
through CyanBridge's FileProvider, opens Tasker's normal import UI, and accepts import/replace
confirmation using the Android UI hierarchy. It does not edit Tasker's private database and does
not require root.

Production profiles are imported first. The HIL controller is imported last because it invokes
the real task names from the production AutoDiary and Visual Diary profiles.

## Useful local commands

From the repository root:

```bash
python3 tools/hil/validate_tasker_profiles.py
```

When a Tasker HIL target is connected:

```bash
bash tools/hil/preflight.sh <adb-serial>
bash tools/hil/install.sh <adb-serial>
bash tools/hil/sync_tasker_profiles.sh <adb-serial>
```

Core Tasker suite:

```bash
CYANBRIDGE_HIL_GLASSES=false \
  bash tools/hil/run_instrumentation.sh \
  <adb-serial> hardware \
  com.fersaiyan.cyanbridge.hil.HilFixtureSmokeTest,com.fersaiyan.cyanbridge.hil.TaskerLocalAgentHilTest,com.fersaiyan.cyanbridge.hil.AutoDiaryTaskerHilTest,com.fersaiyan.cyanbridge.hil.AiTaskerProfileHilTest,com.fersaiyan.cyanbridge.hil.VisualDiaryHeyCyanHilTest
```

Local on-device AI browser layer (after starting the deterministic fixture and `adb reverse`):

```bash
CYANBRIDGE_HIL_LOCAL_AI=true \
  bash tools/hil/run_instrumentation.sh \
  <automation-emulator-serial> hardware \
  com.fersaiyan.cyanbridge.hil.LocalAiTaskerChromeHilTest
```

Real approved Gmail self-send layer (also requires the deterministic fixture and `adb reverse`):

```bash
CYANBRIDGE_HIL_EMAIL_SEND=true \
  bash tools/hil/run_instrumentation.sh \
  <automation-emulator-serial> hardware \
  com.fersaiyan.cyanbridge.hil.LocalAgentEmailApprovalHilTest
```

Real glasses layer:

```bash
CYANBRIDGE_HIL_GLASSES=true \
CYANBRIDGE_HIL_EXPECT_VISUAL_FACT=false \
  bash tools/hil/run_instrumentation.sh \
  <physical-phone-serial> hardware \
  com.fersaiyan.cyanbridge.hil.VisualDiaryHeyCyanHilTest
```

## Diagnostics

Safe failure diagnostics are written under `build/hil/diagnostics-safe/` and include filtered
CyanBridge/Tasker/AutoDiary/VisualDiary/glasses logs and device state. Full screenshots/UI dumps
are opt-in and use `build/hil/diagnostics-private/` with shorter artifact retention.

HIL tests restore HIL-only blacklist/exclusion state and automation preferences they temporarily change.
