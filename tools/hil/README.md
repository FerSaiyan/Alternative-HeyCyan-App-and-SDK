# CyanBridge Android Tasker / HeyCyan HIL

This directory contains the hardware-in-the-loop (HIL) test harness used by the
`Android Tasker HIL` GitHub Actions workflow. It is designed for the existing
self-hosted Linux runner; Jenkins is not required.

## Test layers

1. **Static contract checks** (`validate_tasker_profiles.py`)
   - Tasker XML parses and contains the expected actions/task names.
   - Gemini v3 / ChatGPT v1 profile handshake versions match CyanBridge.
   - Local Agent / AutoDiary / Visual Diary Tasker contracts are present.
   - HIL controller calls the real production periodic Tasker tasks.
   - Play-sensitive broad permissions and the CyanBridge AccessibilityService stay absent.

2. **Emulator smoke**
   - Uses an already-configured Android Studio AVD when available.
   - Installs the debug app and androidTest APK.
   - Verifies the deterministic `HilFixtureActivity` and instrumentation plumbing.
   - Does not pretend to validate Tasker, AutoInput, or glasses unless those apps/hardware exist.

3. **Tasker HIL (emulator or physical phone)**
   - Prefers a configured/physical device and falls back to the running emulator.
   - Requires Tasker, AutoInput, and the AutoInput Accessibility service; missing prerequisites fail the workflow instead of silently skipping integration coverage.
   - Installs the branch APK with `adb install -r` so the target keeps its settings.
   - Synchronizes the exact Tasker XML files from the checked-out commit.
   - Verifies Local Agent observe/click/type and `%CB_LocalAgentBlocked`.
   - Invokes the real AutoDiary periodic Tasker handler and verifies both Memory Vault ingestion and `%CB_AutoDiaryExcluded`.
   - Verifies Gemini and ChatGPT profile handshakes independently.

4. **Optional HeyCyan HIL**
   - Invokes the real Visual Diary periodic Tasker handler.
   - Requires the dedicated phone to have `DeviceClass.HEY_CYAN` selected and BLE connected.
   - Pass condition is a new usable `AUTO_LOOP_THUMB_*.jpg` created by the real glasses thumbnail path.
   - Optionally waits for a new `Glasses scene ...` candidate fact when the lab phone has a compatible Gemma 4 visual model configured.

## Tasker HIL target setup

Recommended emulator or phone state:

- USB debugging enabled and permanently authorized for the lab PC.
- No PIN/password on this dedicated test device.
- Developer option **Stay awake while charging** enabled.
- Phone normally connected to power and USB.
- Tasker installed.
- AutoInput installed.
- AutoInput Accessibility service enabled.
- CyanBridge Accessibility service must not exist/be enabled.
- Tasker and AutoInput excluded from aggressive battery optimization if the OEM requires it.
- For optional physical-glasses coverage, CyanBridge must be configured once with the HeyCyan glasses paired and selected.

An OLED-protection app such as Extinguisher is compatible as long as Android remains logically
interactive and AutoInput still sees the underlying foreground app. If Tasker profile import
fails because the protection overlay becomes the foreground/accessibility window, configure an
exception for Tasker/CyanBridge during HIL runs or temporarily disable the overlay for profile sync.

## GitHub repository variables

All variables are optional. Defaults keep the suite safe before the phone is connected.

- `CYANBRIDGE_HIL_SERIAL`
  - Recommended for either a dedicated emulator or phone.
  - Set to the exact `adb devices` serial so another target cannot be selected accidentally.
  - The legacy `CYANBRIDGE_HIL_PHONE_SERIAL` variable remains a fallback.

- `CYANBRIDGE_HIL_ENABLE_GLASSES`
  - Default: `false`.
  - Set to `true` only after the dedicated phone reliably reconnects to the paired HeyCyan glasses.
  - When true, `VisualDiaryHeyCyanHilTest` becomes a real required hardware assertion.

- `CYANBRIDGE_HIL_EXPECT_VISUAL_FACT`
  - Default: `false`.
  - Set to `true` only when the phone has the intended Gemma 4 visual model selected and usable.
  - Adds the stronger assertion that the captured image produces a new candidate `Glasses scene ...` fact.

- `CYANBRIDGE_HIL_UPLOAD_VISUAL_DIAGNOSTICS`
  - Default: `false`.
  - When true, failure artifacts may include screenshots/UI XML and can expose account/UI content.
  - Keep false for normal runs, especially on a public repository.

The workflow also expects the existing `META_GITHUB_TOKEN` secret. It is exported to Gradle as
`GITHUB_TOKEN`, which is the name currently consumed by the Meta DAT repository/build logic.

## Branch-exact Tasker profile synchronization

`sync_tasker_profiles.sh` imports these exact files from the checked-out commit:

- `Tasker_AI.xml`
- `CyanBridge_LocalAgent_Tasker.XML`
- `CyanBridge_AutoDiary_Tasker.XML`
- `CyanBridge_VisualDiary_Tasker.XML`
- `CyanBridge_HIL_Tasker.XML` (lab-only; not a user-facing plugin)

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

When the Tasker HIL target is connected:

```bash
bash tools/hil/preflight.sh <adb-serial>
bash tools/hil/install.sh <adb-serial>
bash tools/hil/sync_tasker_profiles.sh <adb-serial>
```

Run the full physical instrumentation suite in the same form used by CI:

```bash
CYANBRIDGE_HIL_GLASSES=false \
  bash tools/hil/run_instrumentation.sh \
  <adb-serial> hardware \
  com.fersaiyan.cyanbridge.hil.HilFixtureSmokeTest,com.fersaiyan.cyanbridge.hil.TaskerLocalAgentHilTest,com.fersaiyan.cyanbridge.hil.AutoDiaryTaskerHilTest,com.fersaiyan.cyanbridge.hil.AiTaskerProfileHilTest,com.fersaiyan.cyanbridge.hil.VisualDiaryHeyCyanHilTest
```

For the real glasses layer:

```bash
CYANBRIDGE_HIL_GLASSES=true \
CYANBRIDGE_HIL_EXPECT_VISUAL_FACT=false \
  bash tools/hil/run_instrumentation.sh \
  <adb-serial> hardware \
  com.fersaiyan.cyanbridge.hil.VisualDiaryHeyCyanHilTest
```

## Diagnostics

Safe failure diagnostics are written under `build/hil/diagnostics-safe/` and include filtered
CyanBridge/Tasker/AutoDiary/VisualDiary/glasses logs and device state. Full screenshots/UI dumps
are opt-in and use `build/hil/diagnostics-private/` with shorter artifact retention.

The Tasker HIL tests intentionally restore HIL-only blacklist/exclusion state after each run
and restore AutoDiary/Visual Diary enabled state when the test enabled a feature that had previously
been disabled.
