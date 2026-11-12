# CyanBridge Android Tasker / HeyCyan HIL

This directory contains the hardware-in-the-loop (HIL) test harness used by the
`Android Tasker HIL` GitHub Actions workflow. It is designed for the existing
self-hosted Linux runner; Jenkins is not required.

## Device roles

### Pro Gemini Live audio smoke test

The persistent emulator can use its normally verified Pro account across runs.
Install the current debug and androidTest APKs first. One-time setup uses
`ProAccountSetupHilTest#requestVerification` with instrumentation argument
`proEmail`, followed by `#verifyAccount` with `proCode` from the real email.
These call the production account APIs; they do not fabricate subscriptions.
Do not store the code or account token in source or CI artifacts.

After verification, run explicitly (consumes paid Live quota):

```bash
bash tools/hil/run_pro_live_audio.sh emulator-5554
# Explicit Private-path regression (product default is Economy):
bash tools/hil/run_pro_live_audio.sh emulator-5554 private
```

The test refreshes server entitlement and requires an active paid plan, selects
the requested mode (Economy by default; the prior preference is restored), waits
for Live setup, streams the existing `gemini_live_hello_5s.wav` in 40 ms PCM
packets plus a silence tail, and checks accumulated output transcription for
`I like red flowers`. Setup errors fail rather than skip. The runner also
rejects JUnit skips/failures even if ADB exits zero. This exercises the Live
client/network and PCM input path, not physical Bluetooth capture or the
foreground service's lock-screen lifecycle. Keep it opt-in on the persistent
runner; a fresh CI emulator must complete normal account verification first.

### CI Pro test account

A dedicated production account with unbilled Standard Pro exists for CI and HIL:
`akio+ci-pro@cyanbridgelabs.com`, marked `isTestAccount`, active until 2027-11-12.
Its credentials live outside git, on the lab machine, in
`~/.config/cyanbridge-ci/pro-test-account.env` (mode 600). The `README.md` next to it
explains how to extend, revoke and rotate the account. Never copy the token into
source, workflow files, logs or artifacts.

Link an emulator (for example Pixel_9a) in one non-interactive step. Install the debug
and androidTest APKs first:

```bash
(set -a; source ~/.config/cyanbridge-ci/pro-test-account.env; set +a
 bash tools/hil/run_instrumentation.sh emulator-5554 emulator \
   com.fersaiyan.cyanbridge.hil.audio.ProAccountSetupHilTest#linkApiToken)
```

When `CYANBRIDGE_HIL_PRO_API_TOKEN` is set, `run_instrumentation.sh` writes it with a shell
builtin to a mode-600 temp file, `adb push`es it to `/data/local/tmp/.cb-pro-api-token` (mode
600, readable only by the shell), deletes the host copy, and passes `-e proApiTokenFile`; the
test reads and deletes the device file through the shell, and the script deletes it again after
the run. The token never appears in a host or device command line. (`-e proApiToken` still works
by hand, but then it is visible in the process list.)
The test stores it in the app's encrypted prefs and fails unless `/api/pro/verify`
reports an active entitlement. Afterwards, run the Pro HIL without the token in the
environment, for example `CYANBRIDGE_HIL_PRO_PLANNER=true`, `run_pro_live_audio.sh` or
`ProQuestionsHilTest` with `-e pro_questions true`. The email route
(`#requestVerification` with `proEmail`, then `#verifyAccount` with `proCode`) also
works with this address; the code is delivered to the owner's inbox.

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
 - To explicitly exercise the cloud Pro planner in this test or the embedding-gated YouTube HIL, set `CYANBRIDGE_HIL_PRO_PLANNER=true` when invoking `run_instrumentation.sh`. This requires a linked account token and a fresh server-verified active subscription; the test fails rather than silently falling back to CPU inference. The YouTube test still uses the on-device Gemma embedding gate, but its LLM fallback routes through Pro. Restore the previous provider setting after each run.

### Opt-in Laya / Needle live-state shadow comparison

`CYANBRIDGE_HIL_SHADOW=true` on the YouTube HIL enables a debug-only
`JevShadow` log of bounded choices built from real Tasker observations. The test
restores the previous trace preference. With provisioned, external checkpoints:

```bash
CYANBRIDGE_HIL_LOCAL_AI=true CYANBRIDGE_HIL_YOUTUBE=true \
  CYANBRIDGE_HIL_PRO_PLANNER=true CYANBRIDGE_HIL_SHADOW=true \
  bash tools/hil/run_instrumentation.sh emulator-5554 hardware \
  com.fersaiyan.cyanbridge.hil.LocalAiTaskerEmbeddingYouTubeHilTest
adb -s emulator-5554 logcat -d -v brief -s JevShadow:I '*:S' \
  > /tmp/opencode/jev-live-shadow-trace.log
NEEDLE_TELEMETRY=0 DO_NOT_TRACK=1 /tmp/opencode/laya-export-venv/bin/python \
  tools/benchmarks/compare_laya_needle_live_shadow.py \
  --trace /tmp/opencode/jev-live-shadow-trace.log \
  --output /tmp/opencode/jev-live-shadow-results.json
```

The host comparison cannot execute device actions; it reports hypothetical
model choices and host latency, not Laya/Needle end-to-end success or Android
latency. Needle's published Android runtime is ARM64-only, whereas the licensed
Pixel_9a AVD is x86_64. The current models made wrong or structurally invalid
choices on actual YouTube states, so do not grant them action authority.
For a **separate, read-only** test of a one-step frame with semantic tools and
screen noise removed, add `--frame compact` and use another output path. This
frame is a YouTube-specific research probe; most replayed steps have only one
actionable choice. Design, upstream documentation, measurements and limitations:
[`android/CyanBridge/docs/TASKER_SMALL_MODEL_CONTEXT.md`](../../android/CyanBridge/docs/TASKER_SMALL_MODEL_CONTEXT.md).
The separate, opt-in **synthetic fine-tuning pilot** for Laya and Needle is
documented under [`android/CyanBridge/training/phone_ui_synthetic_v1/README.md`](../../android/CyanBridge/training/phone_ui_synthetic_v1/README.md).
Its generated rows are not Tasker HIL evidence; compare the real-state shadow
reports before using a trained checkpoint for anything beyond research.

For the email bottleneck, `TaskerGmailComposerObserveHilTest` is an opt-in,
read-only check. Open a harmless `mailto:probe@example.invalid` Gmail draft
with subject `CB-HIL-READONLY-QUERY`, then invoke its instrumentation with
`-e hil_gmail_observe true`. It never taps Send. A timeout here blocks a
real-email HIL retry; an already-approved `SendEmail` composer handoff is not
proof that Gmail sent or delivered the message.

6. **Optional HeyCyan HIL**
   - Invokes the real Visual Diary periodic Tasker handler on the physical phone.
   - Requires `DeviceClass.HEY_CYAN` selected and BLE connected.
   - Pass condition is a new usable `AUTO_LOOP_THUMB_*.jpg` created by the real glasses thumbnail path.
   - Optionally waits for a new `Glasses scene ...` candidate fact when the phone has a compatible Gemma 4 visual model configured.

## Root and Frida on Pixel_9a (since 2026-11-12)

`Pixel_9a` is rooted with Magisk v31.1 in the system image's ramdisk
(`system-images/android-37.2-beta3/google_apis_playstore_ps16k/x86_64/ramdisk.img`; the stock
ramdisk is kept next to it as `ramdisk.img.bak_20261112`). The image is `user`, so `adb root`
does not work; use `adb shell su -c ...`. Only `Shell` has a superuser grant (Magisk v31 has no
interactive prompt: grants are made in Magisk's Superuser tab). Read-only CI instances boot rooted
too, and CI's install path is unchanged.

Frida: `/data/local/tmp/frida-server-17.15.5` (matches the host's frida 17.15.5):

```bash
adb -s emulator-5614 shell "su -c '/data/local/tmp/frida-server-17.15.5 -D >/dev/null 2>&1 </dev/null'"
frida -D emulator-5614 -f <package> -l script.js     # or -p <pid> of a foreground app
```

- Redirect frida-server's output, or `adb shell` never returns.
- Attach only to a foreground app, or spawn with `-f`: background apps are frozen by the cached-app
  freezer and the attach times out.
- Java method hooks are not stable on this API 37 ART: a GC that walks a hooked frame crashes the app
  (SIGSEGV in `art::CodeInfo::DecodeGcMasksOnly`, MarkCompact). Native `Interceptor` hooks are fine.
  Attaching while the app is still in `bindApplication` also broke dex verification ("Bad checksum").
- The official HeyCyan app (`com.glasssutdio.wear`) cannot run here as a sideload: Google Play's
  install protection shows "Get this app from Play" and the app exits. `HeyCyan_Frida` (android-36
  `google_apis` `userdebug`, no Play) stays for HeyCyan-app Frida work and as the Wi-Fi Direct GO of
  `firmware_dump/sim/e2e_bt_p2p` (it needs `adb root` and toybox `su 1000`).

## Persistent automation emulator setup

Recommended state once you create the dedicated Google Play AVD:

- Give the AVD a stable name and set `CYANBRIDGE_HIL_EMULATOR_AVD` to that exact name.
- Sign into the Google account that owns Tasker/AutoInput (or a dedicated lab account with those purchases).
- Install Tasker, AutoInput, and AutoApps through the normal Play/AutoApps entitlement flow.
- Complete Tasker's own Accessibility Access disclosure flow and enable AutoInput accessibility.
- Install Chrome and Gmail; for the real-email HIL, Gmail must already be signed into the self-test account.
- Configure either an on-device CyanBridge local model or Pro Subscription if those AI HIL layers should run.
- Do not use `-wipe-data`, delete the AVD, or make CI recreate Google/account/app setup.

CI never boots or reuses that AVD writable. `start_emulator.sh` starts a `-read-only` instance of
`CYANBRIDGE_HIL_EMULATOR_AVD` on `CYANBRIDGE_HIL_EMULATOR_PORT` (default 5584, `-memory 3072`) and
reuses only that serial when it is a read-only instance of the same AVD. A writable instance (the
one you use by hand, on any other port) is never reused or stopped. Changes inside the read-only
instance are discarded on exit, so `install.sh` may uninstall a differently signed app there when
`CYANBRIDGE_HIL_UNINSTALL_ON_SIGNATURE_MISMATCH=true`; on a phone or writable emulator it fails
with a hint instead.

The emulator asks for 3072 MB, but this image raises it to 4096 MB, and a cold boot is still busy
after `sys.boot_completed`: for about a minute MemFree stays near 75 MB and lmkd kills a freshly
started test process ("Process crashed", CI run 43218835150). `settle_emulator` (common.sh) waits
for idle broadcast queues and, in the first 300 s of uptime, for MemFree >= 350 MB. It runs after
boot and before each class. After a "Process crashed", the runner says whether lmkd killed the target.

## Opt-in Artemis black-box fixture (separate AVD)

`jev-like-local-agent.yml` has an optional manual `artemis_serial` input. Use
only a dedicated emulator without Tasker or AutoInput; the preflight refuses
both packages before installing anything. The existing persistent Pixel_9a AVD
is for Tasker HIL and is not an Artemis target. The workflow builds CyanBridge's
debug APK, runs the model-free Jev tests, then installs the APK and uses the
external Artemis daemon to tap one fixture button and type one literal. Host
UI XML verifies `HIL_CLICK_COUNT=1` and the exact typed text. No Gmail/send
action or paid-app login is part of this test.

Provision an Artemis daemon separately with access to the dedicated serial and
its own LLM provider credentials. The workflow uses `CYANBRIDGE_ARTEMIS_BASE_URL`
(repository variable, defaults to loopback port 8000) and optional
`CYANBRIDGE_ARTEMIS_TOKEN` secret. Artemis defaults to installing its own
accessibility helper on the isolated AVD; set
`ARTEMIS_HIERARCHY_BACKEND=uiautomator` and
`ARTEMIS_HELPER_AUTO_INSTALL=false` on the daemon to opt out. No Artemis
dependency or model weight is added to default CI; its dependency-free client
is read from a pinned external checkout. This tests the *external UI/testing
surface*, not the local-agent's decisions or approval policy. Keep Tasker HIL
as the latter's end-to-end assertion.

An opt-in **real-emulator dataset** uses Artemis's device driver and
UIAutomatorClient on the fresh `CyanBridge_Artemis_Data` AVD. Its collection
commands, verified outcome contract and source limitations are in
[`android/CyanBridge/training/artemis_real_v1/README.md`](../../android/CyanBridge/training/artemis_real_v1/README.md).
This external test collection does not install Artemis on the authenticated
`Pixel_9a` or replace Tasker/AutoInput at runtime.

The separate opt-in Settings parity probe uses **Tasker/AutoInput** on the
authenticated HIL AVD. It opens Settings HOME, requests a real observation and
reports whether the visible `Network & internet` control survives CyanBridge's
eight-candidate filter. It never sends email or installs Artemis on that AVD:

```bash
CYANBRIDGE_HIL_ARTEMIS_PARITY=true bash tools/hil/run_instrumentation.sh \
  emulator-5562 hardware \
  com.fersaiyan.cyanbridge.hil.TaskerSettingsArtemisParityHilTest
```

For a distinct observed Tasker tap plus a fresh post-action observation, use
`TaskerSettingsEpisodeHilTest`. Its allowlisted diagnostic export and an
independent ADB hierarchy oracle are documented in
[`android/CyanBridge/training/tasker_real_v1/README.md`](../../android/CyanBridge/training/tasker_real_v1/README.md).
Pin the emulator by AVD name: in its unpinned mode,
`run_instrumentation.sh` can recover an offline serial by starting a different
configured AVD. With `CYANBRIDGE_HIL_ARTEMIS_PARITY=true`, it now defaults to
`CYANBRIDGE_HIL_EXPECT_AVD=Pixel_9a`, checks AVD identity before and after the
test, and fails closed if the pinned AVD goes offline.

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
  - Required by `start_emulator.sh` (there is no fallback AVD); currently `Pixel_9a`.
  - Exact AVD name whose `-read-only` instance CI boots on port 5584.

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

Walking Aid real YOLO11, YOLO-World, and Depth Anything inference, plus EyeVue/Meta
video-to-YOLO plumbing on the emulator:

```bash
serial="$(bash tools/hil/start_walking_aid_emulator.sh)"
bash tools/hil/install.sh "$serial"
bash tools/hil/run_walking_aid_model_ci.sh "$serial"
```

See `android/CyanBridge/docs/WALKING_AID_MODEL_TEST.md` for model/image provenance,
checksums, build/install prerequisites, and the 8 GiB AVD storage recommendation.
The runner also executes `eyevue-video` and `meta-video` in separate processes.
These use packaged two-scene H.264/HEVC fixtures: EyeVue retains the production LibVLC
headless decoder and camera rotation with a substituted connection URL; Meta uses
the actual DAT `MockDeviceKit`. Both require real YOLO detections from multiple
fresh frames and clean session teardown. A Meta DAT-enabled build is required.
For a focused video rerun, use `WALKING_AID_CI_PHASES=eyevue-video,meta-video`.

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
