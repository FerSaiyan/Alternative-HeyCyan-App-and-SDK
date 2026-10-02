# Worklog — CyanBridge local agent

## 2026-10-02 — HeyCyan live preview hardware peer-matching failure

- Captured the original attempts and a fresh user-driven retest on Samsung
  SM-F956B (`RQCX700KSDF`, Android 15 / API 35). Evidence is under
  `/tmp/opencode/heycyan-live-20261002/`: `before-retest.log`, `retest.log`,
  `wifip2p-after-failure.txt`, and `connectivity-after-failure.txt`.
- The 16:04:18 retest sent `02 01 14 01` and discovered
  `AIMB-G3_C4E3BFC3A402` after 6.6 seconds. LivePreview rejected that peer:
  its BLE identity is `AIMB-G3_A402` / `C4:E3:BF:C3:A4:02`, while its distinct
  P2P address is `60:c2:2a:4c:5b:b5`. No connection request was sent, and
  BLE IP discovery timed out after 45 seconds. RTSP playback was never reached.
- The subsequent ordinary media sync in the same trace matched that peer,
  formed a P2P group, received glasses IP `192.168.49.40`, and downloaded two
  JPEGs successfully. This corroborates the live-specific matching bug.
- Updated LivePreview to reuse `HeyCyanP2pPolicy.matchesOfficialPeer`, the
  existing media-sync full-BLE-MAC matcher, preferring available matching peers.
  Added coverage for the observed AIMB identity, the report's M02S naming form,
  and rejection of an unrelated full MAC sharing the same short suffix.
- Reference: https://gist.github.com/vortex1024/2a9e0bada7692900a3bb4285362d8a2a
  confirms the branch's start/stop opcodes, `8554/ch0`, and LibVLC transport.
  End-to-end preview validation of this peer fix is pending the patched retest.

## 2026-10-02 — Live preview duplicate connect and local build tuning

- The peer-fix build passed all eight focused unit tests and was sideloaded to
  `RQCX700KSDF`. Its build took 27m38s. The project previously limited Gradle
  and Kotlin to 2 GB heaps, disabled parallel projects, and wrote generated
  outputs to the NTFS HDD while an unrelated tape-backup job read that disk.
- The patched 16:47:49 and 16:48:00 attempts successfully matched the full-MAC
  P2P peer. Samsung sent a disconnected broadcast immediately after accepting
  the first connect request, clearing the generic manager's connecting flag.
  A subsequent invited-peer broadcast triggered a duplicate connect, returning
  Android BUSY (`reason=2`); LivePreview treated it as terminal and tore down
  the negotiating group. Evidence: `patched-retest-focused.log` and `retest.log`
  under `/tmp/opencode/heycyan-live-20261002/`.
- Added preview-owned connection-request state to retain the pending request
  across startup disconnect / unformed-group broadcasts. Established-group
  disconnects remain terminal. Regression coverage replays this sequence.
- Enabled Gradle build/configuration caches and parallel projects with 24
  workers, 8 GB Gradle heap and 6 GB Kotlin heap. Added
  `tools/hil/build_debug_fast.sh` to place app/composite/native generated files
  and the project cache on the host SSD. `testAbi` now also filters native llama
  builds, avoiding an unused x86_64 build for ARM64 phone debugging.
- The fresh SSD/ARM64 build completed in 6m49s with configuration cache stored;
  all 46 selected media/P2P/OTA unit tests passed. Its APK contains only
  `arm64-v8a`, is 189.6 MiB, and was installed successfully on `RQCX700KSDF`.
  Build evidence: `fast-build.log`; profile:
  `~/.cache/cyanbridge/build-c42ce86fe7b6/CyanBridgeManagerApp/root/reports/profile/profile-2026-10-02-16-59-22.html`.
- Kept SSD native staging outside Gradle's build directory so `clean` preserves
  the expensive native compilation state; the staging-path update built in
  1m44s. The identical warm invocation then completed in 11s, reused the
  configuration cache, and reported 113 tasks up-to-date / three executed.
  Evidence: `fast-build-staging.log` and `fast-build-warm.log`.
- Documented that llama's local-source override is optional: normal CMake
  FetchContent downloads the pinned, SHA-256-verified upstream automatically.
  The no-override build passed in 2m46s (`no-llama-override-build.log`).
- The 17:27:00 and 17:27:33 hardware attempts confirmed both prior fixes:
  a single connect request formed the group in eight seconds, followed by BLE
  IP `192.168.49.40`. Android exposed `p2p-wlan0-0` with source `192.168.49.1`
  but rejected all process-bind attempts, so the app aborted before RTSP.
  Evidence: `second-patched-focused.log`.
- Added a direct-route probe for this platform behavior. Playback is allowed
  only after a TCP connection to the BLE-reported glasses endpoint uses a local
  address on the verified P2P Network. An ordinary Wi-Fi route or missing P2P
  interface cannot satisfy the gate. The no-override build passed in 7m7s with
  all 15 focused tests passing (`direct-route-build.log`); installed successfully
  on `RQCX700KSDF`, marked `HEYCYAN_DIRECT_P2P_ROUTE_FIX_INSTALLED` in logcat.
  End-to-end hardware playback validation is pending the next user retest.

## 2026-09-27 — Laya/Needle local training pilot

- Follow-up data audit after the synthetic transfer regressions: the available
  local logs contain only six structured `JEV_SHADOW_STATE` decision frames,
  all from one YouTube episode. Older Chrome HIL logs expose repeated
  candidate lists from failed runs but lack independently verified next-action
  and post-action labels. Do not retrain on those logs or on the now-inspected
  held-out cases; collect new episode-disjoint Tasker observations first.
- Created a deterministic **synthetic** English phone-UI decision dataset,
  `android/CyanBridge/training/phone_ui_synthetic_v1/` (576 train, 128
  validation, 128 test decisions per model). Inputs and answers are grouped by
  episode and split by disjoint invented names; explicit no-action/ambiguous
  cases are included. The 169-case generated calibration benchmark and six real
  Tasker states are *not* trained on. Tool IDs for duplicate operations are
  assigned after shuffle to avoid leaking the target's role in its ID.
- Trained a **frozen-encoder Laya choice head** (four epochs, CUDA) using
  `tools/benchmarks/train_laya_phone_ui.py`. Synthetic test top-1 56.3% base →
  86.7% trained. On the six *untouched* Tasker YouTube states, base Laya 5/6 →
  trained 4/6; both failed to defer at the playing-video state. The synthetic
  gain does not transfer to this real episode. Reversing option order dropped
  each model to 3/6 on the same six frames. The frozen 13-locale corpus
  remained unsuitable for auto action: raw held-out 42.5% base versus 45.0%
  trained, **zero** safe actions at its 98% precision gate. Artifacts under
  `/tmp/opencode/phone-ui-models/laya-head-v1/` and read-only reports under
  `/tmp/opencode/phone-ui-laya-*-real-shadow*.json`.
- Needle 3 base exact-call accuracy on the synthetic test: 59/128 (46.1%),
  0/32 for no-match or ambiguous inputs. On the real six-state trace under
  a separate first-four-visible-controls renderer, the 20-layer base chose
  only 1/6 reviewed next steps (host shadow, no execution). A local 20-layer
  LoRA in a dedicated non-base Python environment reached 80/128 validation
  and 83/128 synthetic test exact calls; missing-control no-call remained
  **0/16**. On the same real-state renderer it reached 2/6 and failed at the
  player DONE state. In a serial host-only rerun of 128 test rows, Needle
  p50 was 394 ms base versus 357 ms tuned, not an Android measurement.
  On the frozen 13-locale generated corpus, Needle's held-out raw top-1
  **regressed from 28/40 (70.0%) to 19/40 (47.5%)**; both had zero automatic
  actions at the 98% precision gate, and tuned confidence is unavailable.
  Weights at `/tmp/opencode/phone-ui-models/`; keep them
  shadow-only. See the dataset README for commands and provenance.

## 2026-09-27 — Laya/Needle training research and Jev browser comparison

- Read the official Laya browser fine-tuning write-up and published pipeline,
  Needle 3 fine-tuning/tool-design/runtime/device guides, the open-source
  `browser-use/jev-ultrafast` loop, and Ship With Jev's independent catalog and
  engineering guide. Wrote a concrete phone-specific data, training and
  evaluation recipe in `android/CyanBridge/docs/LAYA_NEEDLE_PHONE_TRAINING.md`,
  linked from the model docs. Key findings: train Laya operation/compatible
  target heads using real post-action DONE and mid-task negatives; current v17s
  adds synthetic long-form episodes and harness fixes, and its model card
  discloses that earlier DAgger samples leaked evaluation goals. Train Needle
  on one-step `query`/`tools`/`answers` plus no-call examples; local Needle LoRA
  does **not** preserve calibrated confidence. Measure candidate recall before
  model accuracy and verified episode outcomes before giving either model any
  action authority. The six-state replay is too small to fine-tune reliably.

## 2026-09-27 — Tasker accessibility framing for Laya / Needle

- Read Laya's official browser-agent training example and Needle's official
  tool design, Python and confidence guides. The prior host replay duplicated a
  long multi-step goal in state and prompt, ranked options by whole-goal terms,
  exposed arbitrary first-three controls, and named Needle tools `action_a`.
  Laya's browser example specifically found that moving element details from
  state to option criteria improved accuracy; its base model needed
  task-specific training. Needle expects short current requests, narrowly named
  tools and evidence-grounded arguments, and may validly emit multiple calls for
  a multi-step request. Details and source links are in
  `android/CyanBridge/docs/TASKER_SMALL_MODEL_CONTEXT.md`.
- Added a **host-only, read-only** current-step renderer for the six captured
  Tasker YouTube states (`tools/benchmarks/phone_accessibility_frame.py`,
  `compare_laya_needle_live_shadow.py --frame compact`). With 1–2 grounded
  controls per step, both checkpoints selected the intended candidate on five
  steps and the player step delegated without inference; two visible suggestion
  options still yielded the exact suggestion when their order was reversed.
  **This is mostly code prefiltering, not a demonstrated 5/5 model accuracy:**
  four of five inferred steps had only one action tool. Workstation warm medians
  were ~40 ms Laya CUDA and ~0.21 s Needle CPU in the latest replay (host load
  varies); no Android runtime/agent
  completion measurement. Report:
  `/tmp/opencode/jev-laya-needle-compact-frame.json`. Keep the production Gemma
  state/gate unchanged until calibration on independent episodes and HIL.

## 2026-09-27 — Laya/Needle live-state shadow trial and Gmail gate

- The user's suspicion about an extra CyanBridge approval gate was checked against
  the real email trace: the ambiguous voice reply remained pending, an explicit
  affirmative reply moved the high-risk `SendEmail` to executed, and Tasker's
  `ACTION_SENDTO` opened the Gmail external composer. **There was no second
  CyanBridge confirmation gate after that approval.** A separate read-only HIL
  proved AutoInput/Tasker never returned a composer observation, so CyanBridge
  correctly did not guess a Send tap; no delivery is established.
- Reimported a profile with bounded per-node text and summary. A fresh opt-in
  `TaskerGmailComposerObserveHilTest` still timed out after 32 seconds on a
  harmless `example.invalid` SENDTO draft, with **no send operation**. Temporarily
  restricting AutoInput UI Query to Gmail and temporarily including invisible
  nodes each also timed out; both settings were restored and the original
  profile reimported. Traces: `/tmp/opencode/jev-gmail-bounded-observe.log`,
  `/tmp/opencode/jev-gmail-filtered-observe.log`, and
  `/tmp/opencode/jev-gmail-all-visible-observe.log`. Do not run another real-email
  send test until a Tasker-observed composer succeeds and the independent
  self-delivery check can run.
- Added a **debug-only, opt-in** `JevShadow` trace of bounded candidates and
  Tasker-observed YouTube state. Replayed six real consecutive decisions through
  the previously provisioned multilingual Laya checkpoint on host CUDA and
  Needle 3 host CPU. This replay is read-only: no model output can reach Tasker
  or bypass CyanBridge policy. Three actionable options plus planner fallback:
  Laya warm p50 ~41 ms/decision (first ~554 ms); Needle p50 ~1.24 s/decision,
  with invalid multiple calls at two of six steps. Laya chose “Navigate up” at
  the suggestion step and “notifications are off” at a video-player step;
  Needle and Laya diverged repeatedly. Two-action and seven-action Laya
  exploratory variants changed the choices, but also gave wrong navigation
  suggestions. Reports: `/tmp/opencode/jev-laya-needle-live-shadow.json`,
  `/tmp/opencode/jev-laya-needle-live-shadow-2actions.json`, and
  `/tmp/opencode/jev-laya-all-needle-3-live-shadow.json`. These are **host
  inference timings and hypothetical choices, not Android inference or either
  model finishing an end-to-end workflow**. The available Pixel is x86_64;
  Needle's released Android runtime is ARM64-only. A real ARM64 target and
  trustworthy calibration are needed before granting either model action
  authority.
- The Pro/Gemma-controlled YouTube HIL passed again in 22.9 seconds. Its
  independent Tasker oracle now accepts positive *advancing time* on the same
  requested video when YouTube hides the Pause overlay between polls, never a
  static progress indicator. Trace:
  `/tmp/opencode/jev-laya-needle-youtube-shadow-hil3.log`. The first shadow
  capture run reached service-observed playback but its older test oracle
  timed out; the improved oracle passed the later run.
- Paused the host USB-tether watchdog and GitHub Actions runner only during
  Android HIL, then restored both. The authenticated `Pixel_9a` remains bootable
  with its private checksum-verified offline backup intact.

## 2026-09-27 — Pro HIL, model comparisons, preserved Pixel

- **Preserved licensed Pixel:** stopped `Pixel_9a` and copied its entire AVD plus
  `.ini` to private `/home/fertroll10/.android/avd-backups/Pixel_9a-pro-20260927/`
  (14 GB, directories mode 700 and files mode 600). The offline userdata image
  SHA-256 matches the original before it was rebooted:
  `e16a5b3de0c171320f889d6af9c4bf1efb09d61a6d565e2c2abcdbddb358cf2e`.
  `/mnt/seagate` ignored mode restrictions, so its interim backup copy was removed
  after the private copy's checksum matched. Original rebooted with the same userdata,
  CyanBridge and licensed Tasker installed; no wipe or paid-app sign-in. Continue
  replacement installs only. The HIL uses `emulator-5554` (Android x86_64).
- **Cloud YouTube HIL passed:** Pro-planner/EmbeddingGemma-gated run reached
  independently observed requested playback in 44 seconds; trace
  `/tmp/opencode/jev-pixel-pro-youtube2.log`. Do not interpret this as a Laya or
  Needle run. The embedding gate can abstain and delegate to Pro.
- **Cloud email HIL remains incomplete:** after grounding the Chrome fixture,
  the run reached spoken readback, clarification after ambiguous reply, explicit
  approval, and executed `SendEmail` handoff. The original Tasker `composeEmail()`
  opened Android's generic share resolver, then returned to Chrome. The Tasker
  profile now requests `ACTION_SENDTO` with a `mailto:` URI; a harmless probe
  opened Gmail's composer. An approved-email continuation prevents re-drafting,
  re-approval, or repeated Send taps, and checks visible recipient/subject before
  any UI Send action. Targeted unit tests, Android-test compilation, Tasker
  profile validation and Tasker observer/executor smoke passed. The subsequent
  Pro email HIL reached `approved_action_executed`, then timed out: AutoInput
  observation of Gmail's external composer produces **no Tasker callback** (even
  with a 65-second read-only diagnostic request). The same observer works on the
  launcher and Gmail welcome screen. The composer was independently visible via
  `uiautomator`; **no Send tap or self-delivery was observed**. Traces:
  `/tmp/opencode/jev-pixel-pro-email3.log`, `jev-pixel-pro-email4.log`,
  `jev-pixel-pro-email-sendto.log`, `jev-gmail-composer-long-observe.log`.
  Do not launch another real-send HIL until Gmail composer observation works;
  Tasker/AutoInput must be the action/observation path, and old approved records
  or drafts must not be auto-rejected/re-sent.
- **Laya and Cactus Needle rechecked on the frozen corpus (host, not phone):**
  multilingual Laya CUDA: 42.5% held-out raw top-1, 0 safe automatic actions,
  median 51.0 ms/decision (p95 64.6). Needle 3 Linux host CPU: 70.0% held-out
  raw top-1, 0 safe automatic actions, median 585 ms/decision including tool
  init (p95 1,151 ms). Both met the ≥98% calibrated auto-precision target only
  by abstaining entirely. Reports:
  `/tmp/opencode/laya-mobile-actions-recheck-20260927.json` and
  `/tmp/opencode/needle3/mobile-actions-recheck-20260927.json`. These are
  **not end-to-end run times**. Needle ships an ARM Android runtime but not an
  x86_64 one for this authenticated AVD; Laya's LiteRT Android host is external
  and task-specific safety calibration has failed. Neither is authorized to
  make live UI decisions on this Pixel. Do not claim either completed YouTube or
  email; an ARM test target and calibrated integration are still required.
- The USB-tether watchdog and user GitHub Actions runner were restored after
  HIL; watchdog PID 1817 is running and `github-actions-runner.service` is active.

## Current handoff: Jev-like local agent (2026-09-26)

This section supersedes the older `tasker-integration-polish` handoff below for
the current branch. The historical August instructions refer to a different
worktree/branch and should not be used as the current emulator or CI state.

- **Branch:** `jev-like-local-agent`; clean before this worklog update, HEAD
  `4551deb`, one commit ahead of `origin/jev-like-local-agent` and **not pushed**.
- **Architecture:** Tasker/AutoInput observes the phone and executes grounded
  actions. CyanBridge constructs bounded legal candidates, applies policy,
  plans, asks for approval, and verifies outcomes. Do not request CyanBridge's
  own accessibility service. Open-ended text goes to its local LLM; sending
  email requires explicit approval before Tasker executes Send.
- **Best current fast-path candidate:** EmbeddingGemma 300M Q8 with cosine
  top-two margin ≥0.10, cascading uncertain steps to the local-LLM planner.
  On the frozen 169-case/13-locale corpus it scored 61% raw top-1 on model
  calibration cases and 70% on held-out model cases; accepted actions were
  48/48 and 16/16 correct, respectively, at ~46% actionable coverage. This is
  small, structurally related benchmark evidence, **not** ≥98% real-world
  precision or end-to-end task success. Qwen embedding was slower and weaker;
  Needle and multilingual Laya had confident wrong choices and no safe
  standalone automatic-action threshold on that corpus. Needle may be tested
  as an optional Gemma agreement gate, not a sole decider.
- **Live mismatch:** actual YouTube/Tasker screen states gave Gemma margins
  ~0.001–0.013, so the production gate abstained rather than accelerating.
  A short production-description probe showed removing `(node N)` text did
  not fix this; long/noisy state is the stronger suspect. Shorten/ground state,
  collect production-like snapshots and legal candidate sets, then recalibrate
  on the exact live representation without weakening send-approval or
  independently observed completion checks. Review disputed corpus labels
  separately and score stage progress as well as safe auto-action precision.
- **End-to-end status:** Tasker observation/click/type HIL passed, but the
  embedding-backed YouTube playback run ended `max_steps_reached` after gate
  abstentions and an LLM Voice Search mistap. Email tests did not reach the
  approval/send success path due to slow planning, detours, or `Brain call
  timed out`. Neither workflow is validated end to end; rerun YouTube without
  concurrent emulator activity, then separately validate draft → spoken
  approval → Send → independently observed delivery. Do not automate paid-app
  authentication or wipe the provisioned Pixel_9a AVD.
- **Release path still needed:** choose/package the patched embedding runtime
  and matched model/tokenizer outside Git/default CI; provide user-facing local
  model acquisition and Tasker/AutoInput profile, permissions, and readiness
  setup. Embedding preferences are currently default-off and enabled only for
  tests. Keep model-free tests separate from opt-in Tasker HIL; external Artemis
  fixture smoke uses a different emulator and does not prove CyanBridge HIL.
- **Unmeasured:** ARM Needle latency on a physical ARM64 target; Laya Android
  accuracy/latency on a physical GPU device. Host GPU timings are not phone
  timings. Delay any claim that one backend can finish user tasks until both
  real workflows pass with grounded state checks.

**Next actions:** capture representative Tasker snapshots; fit the Gemma gate
on shortened live states; rerun undisturbed YouTube HIL; troubleshoot the email
planner and run approval/send HIL; document install/onboarding and provisioned
model delivery. Push `4551deb` only when the shared CI emulator is available.

### Update 2026-09-26 — Jev patterns applied, ADB root cause, HIL reruns (still no pass)

### Cloud-planner follow-up (2026-09-26)

- CPU inference on the Pixel is slow enough to hit both the decision and 60-second brain deadlines. The new classifier state is bounded to 600 chars of current controls, and explicit observed first-result/YouTube exact-suggestion taps skip an unnecessary model call. Chrome site-info popups are dismissed and re-observed rather than offering their warning text as clickable task options. Article email generation now receives a short evidence-only prompt (with recipient/subject code-checked) and 200 output tokens, still yielding a HIGH-risk SendEmail pending action; the service brain deadline is 90s. Unit regressions and HIL Kotlin compilation passed.
- `CYANBRIDGE_HIL_PRO_PLANNER=true` selects Pro for the existing YouTube/email HIL; a missing linked account or non-active server entitlement fails rather than silently falling back to CPU. Added `ProPlannerReadinessHilTest` (token presence and fresh server verification only, no quota use). Tests do not log tokens.
- Examined spare AVDs read-only. `Medium_Phone_API_36` has no Pro prefs or Tasker. `CyanBridge_Walking_Aid_CI` has the owner's verified email, Tasker/AutoInput, Gemma GGUF, and **passes the server Pro-readiness test**, but Tasker itself displays **Trial Over**. Its Tasker readiness probe failed despite branch profile import; do not automate paid-app activation. Both read-only alternate emulator instances were shut down. The licensed `Pixel_9a` had no Pro Subscription prefs before setup; link it through the existing `ProAccountSetupHilTest#requestVerification` / `#verifyAccount` with a real user-provided code, not by copying the alternate AVD's encrypted secrets.
- An interrupted Pixel email instrumentation remained active until its natural 10-minute timeout; it clicked Chrome's site-info warning instead of the first result and **did not request approval or send**. Avoid duplicate email HIL launches while any instrumentation remains active.

- **Ship-with-Jev patterns applied:** fresh bounded A–H actions rebuilt from each
  Tasker observation (never reuse saved coordinates); prose/summary left to the
  detailed local-LLM planner; stage-level `JEV_HIL_STAGE` logging added to both
  HIL tests so failures report `reached=[...]`; playback evidence tightened to
  word-boundary `pause`/`playing` (paused/progress alone no longer counts);
  `run_instrumentation.sh` now refuses auto-retry for local-AI/email classes so
  a lost ADB result cannot overlap two planners or double-send email.
- **ADB drops root-caused:** host `/usr/local/sbin/usb-tether-watchdog` treats
  `emulator-5554` as a tethered phone and repeatedly runs
  `svc usb setFunctions` / Wi-Fi resets on it. Each USB reset restarts emulator
  `adbd` (`adbd socket disconnected`, new `adbd` pid, ~1 s outage) and breaks
  `am instrument`. Host has no `usb0` and routes via Ethernet. Watchdog was
  `STOP`ped for HIL and `CONT`ed afterward; self-hosted runner was stopped and
  restarted. Do not run HIL while that watchdog targets the emulator.
- **YouTube (embedding-gated):** with CI paused, reached
  `tasker_observation_ready → youtube_foreground → requested_content_visible`
  (opened YouTube, tapped Search, typed “Linus Tech Tips”, tapped a suggestion).
  Then stalled on search-results navigation and ended `Brain call timed out`
  after a 60 s single-token generation timeout; last visible text was a
  YouTube Premium upsell. Typed-search candidates now exclude Voice Search.
- **Email (deterministic fixture):** three 10-min runs, ADB stable. Fix 1
  suppressed omnibox retyping on results pages (address-bar node excluded when
  results visible); run 12794 then reached the article and offered
  `Tap Cobalt Horizon first result` as A. Fix 2 suppresses all typing when the
  grounded-answer planner is ready, so the article now leads with
  `Use the detailed planner now…` as A (run 13968). That planner then exceeds
  the 60 s `BRAIN_TIMEOUT_MS` on the emulator CPU (Qwen2.5 0.5B, ~35 s per
  single-token step), so the run ends `Brain call timed out` before a draft or
  approval exists. No email was prepared, approved, or sent in any run.
- **Tests vs strictness:** keep send-approval, no-pre-send Gmail, and observed
  post-send completion strict. The useful relaxation is stage reporting plus
  valid-alternate-tap review, not weaker safety. Next levers are planner
  latency (prompt length, token budget, model speed, timeout policy) and
  result-first candidate ordering (the fallback LLM picks A almost every step),
  then a fresh undisturbed rerun of both workflows.

**Pointers:** `android/CyanBridge/docs/JEV_LIKE_ANDROID_EMBEDDINGS_NEXT_STEPS.md`,
`android/CyanBridge/calibration/README.md`,
`android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/localagent/`,
`tools/benchmarks/benchmark_laya_mobile_actions.py`,
`tools/hil/build_embedding_runtime_aar.sh`,
`tools/hil/run_artemis_fixture.py`. The frozen corpus is
`android/CyanBridge/calibration/local_agent_mobile_actions_v1.jsonl`.

---

## Historical handoff: Tasker-Integration-Polish AI/Gmail CI Tests

Updated: 2026-08-23 (session 2, context limit reached — CONTINUE FROM "Next Steps")

## Mission

Make the new CI tests on branch `tasker-integration-polish` actually green — not "green because skipped". Specifically:

- `LocalAiTaskerChromeHilTest` — CyanBridge local model plans, Tasker/AutoInput drives Chrome against a deterministic web fixture, answer must be grounded in observed page facts.
- `LocalAgentEmailApprovalHilTest` — full real-email flow: Chrome research → summary → SendEmail queued as HIGH-risk → voice prompt → "maybe" gets clarification → literal "yes" approves → Tasker executes Gmail compose+Send → unique `CB-HIL-<ts>` subject must appear in Gmail. Recipient is the user's own account: `fernandosaiyan10@gmail.com` (user explicitly authorized real sends).

Both tests are gated by repo variables that are currently UNSET, so the last branch CI run (32656567419, both workflows success) skipped them. Default gates are green; the two optional layers have never run.

## Current Status

- Branch/worktree: `/tmp/opencode/HeyCyan-tasker-polish`, branch `tasker-integration-polish`, tracking `origin/tasker-integration-polish`, HEAD `617f8e7` + local UNCOMMITTED fixes (see below).
- Core 5-class HIL suite passes on emulator (`OK` per isolated class): fixture smoke, LocalAgent, AutoDiary, AiTaskerProfile, VisualDiary.
- Gmail on emulator `emulator-5554`: SIGNED IN as fernandosaiyan10@gmail.com; first-run screens completed (welcome tour GOT IT → TAKE ME TO GMAIL → notifications Allow → Meet Got it).
- Local model: **currently NOT installed** (deleted to free storage during APK reinstall). Must re-download via catalog UI before running either AI test (exact steps below).
- Web fixture: running on host port **18765** (8765 is occupied on this host by an LTFS server, pid 3381957 — that is also a real CI hazard, fixed in workflow, see below). Process: `nohup python3 tools/hil/serve_web_fixture.py --port 18765` (log `/tmp/opencode/tasker-polish-web-fixture-18765.log`). `adb reverse tcp:18765 tcp:18765` was set; re-run after any emulator restart. Chrome was prelaunched at `http://127.0.0.1:18765/` and marker `CYANBRIDGE_HIL_WEB_SEARCH_72941` verified visible, then HOME pressed.
- Build: `:app:assembleDebug :app:assembleDebugAndroidTest` green (needs both submodules initialized, see below).

## Uncommitted Fixes In The Worktree (all verified locally, need commit+push)

1. `.github/workflows/android-tasker-hil.yml` — fixture port collision fix:
   - Old code hard-coded port 8765; on this self-hosted runner `127.0.0.1:8765` is already used by `ltfs_web.py`, so `serve_web_fixture.py` died with `Address already in use` and the health check would pass against the WRONG server (it only checked HTTP 200).
   - New behavior: allocate an ephemeral host port, `echo port=... >> $GITHUB_OUTPUT` (step id `web_fixture`), `export CYANBRIDGE_HIL_FIXTURE_PORT`, health check requires body marker `CYANBRIDGE_HIL_WEB_SEARCH_72941`, `adb reverse tcp:$port tcp:$port`, Chrome opened at `http://127.0.0.1:$port/`, "Reset Chrome fixture" and "Stop fixture" steps use `steps.web_fixture.outputs.port`.
2. `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/localmodels/device/DeviceCapabilityService.kt` — unit fix: replaced `GIB = 1024^3` divisor with `GIGABYTE = 1_000_000_000.0` (catalog `minRamGb`/`minStorageGb`/`sizeBytes` are decimal GB). Before this, a nominal 4 GB AVD reported 3.82 "GB" and every 4 GB-tier model was rejected ("RAM unsuitable: device has 3.8 GB, model needs at least 4.0 GB").
3. `android/CyanBridge/shared/src/commonMain/kotlin/com/fersaiyan/cyanbridge/localmodels/catalog/LocalModelCatalog.kt` — qwen2.5-0.5b entry: `minStorageGb` 1.0 → 0.75 → **0.5** (with comment). Rationale: 0.5B model is ~0.47 GB; the 6 GB emulator data partition hovers around 0.5–0.8 GB free with Chrome/Gmail/Tasker resident, so a 1.0 GB post-install floor made the supported starter model permanently unloadable. Download headroom check (size+0.35 GB) is separate and still enforced.
4. `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/localmodels/download/LocalModelDownloadManager.kt` — downloader hardening: OkHttp client now `.protocols(listOf(Protocol.HTTP_1_1))` (Hugging Face large-file redirects intermittently reset HTTP/2 streams on Android: `StreamResetException: stream was reset: CANCEL`), and the retry loop catches `java.io.IOException` (covers StreamReset/Socket/DNS) instead of only SocketException+UnknownHostException. Verified: catalog download then completed ("Download complete", Status: ready).

Static checks pass: `python3 tools/hil/validate_tasker_profiles.py`, `bash -n tools/hil/*.sh`, `git diff --check`.

## Submodule Note (fresh worktrees)

This branch adds a second submodule. Both must be initialized or the build fails:
- `third_party/moonshine` @ 79b0217 (missing → `cmake.path ... doesn't exist`)
- `android/CyanBridge/app/src/main/myvu-upstream` @ 66ec6f6 (missing → dozens of `Unresolved reference 'myvu'`)

```bash
git submodule update --init --recursive
git -C third_party/moonshine lfs pull --include="core/speaker-embedding-model-data.cpp,core/third-party/onnxruntime/lib/android/arm64/libonnxruntime.so"
```

CI is fine (workflow checks out submodules recursively + LFS pull); this is only for local worktrees.

## Reproduction Trail (what was observed, in order)

1. `LocalAgentEmailApprovalHilTest` with `CYANBRIDGE_HIL_EMAIL_SEND=true` → fails safely at prereq: "No CyanBridge local model is installed/selected for the email automation HIL" (no email sent).
2. Imported the 0.5B GGUF via app UI (Import model file → Downloads → pick file). Import path has NO capability gate, but load-time gate then crashed the test app: `RAM unsuitable ... 3.8 GB` → fixed by decimal-GB change (#2).
3. After #2, load gate: `Not enough free storage. Need about 1.00 GB` (custom imports fall back to hardcoded min 4.0 RAM/1.0 storage in `LocalChatSessionManager.ensureModelLoaded` capabilityEntry) → decided to use the CATALOG download instead (catalog floor now 0.5).
4. Catalog download failed twice with `stream was reset: CANCEL` (HTTP/2) → fixed by #4; then downloaded successfully via the app UI.
5. `LocalAiTaskerChromeHilTest` then reached model load and failed only on the storage floor (0.75) → lowered to 0.5 (#3). APK replacement then hit `INSTALL_FAILED_INSUFFICIENT_STORAGE`; recovered by deleting `files/local_models` via `run-as` + `cmd package trim-caches 2G` + reinstall (Success).
6. **Stopped here**: about to re-download the model via catalog UI and rerun the local-AI test.

## Next Steps (in order)

1. **Re-install + re-download model** (emulator `emulator-5554`):
   - APKs are already installed (patched build). If needed: `bash tools/hil/install.sh emulator-5554` from the polish worktree.
   - Free storage first if low: `adb shell cmd package trim-caches 2G`; check `adb shell df -k /data` (need ≥ ~1.2 GB free for download headroom 0.4+0.35 GB; after install ~0.5 GB is enough to LOAD).
   - UI recipe (coordinates are for this 1080x2424 AVD, verified working):
     1. `adb shell monkey -p com.fersaiyan.cyanbridge 1` (launch; dismiss any permission dialog with Allow at ~(540,1325))
     2. Tap Settings tab: (980,2190)
     3. Swipe up: `input swipe 540 1900 540 700 500`
     4. Tap "Configure local models" row parent (text bounds ~[147,1140..1282]; tap ~(300,1193) — re-dump `uiautomator` and compute from `text="Configure local models"` if unsure)
     5. In Local models screen: tap "Curated catalog" header to expand (~(500,1157) or wherever the header is; verify `Collapse Curated catalog` appears)
     6. Swipe down: `input swipe 540 2200 540 700 600` until `text="Qwen2.5 0.5B Instruct (Q4_K_M)"` visible with `Device suitable`
     7. Tap its **Download** button — it is the LEFT button of the pair, e.g. bounds [84,1335][529,1461] → tap (300,1387). (The right button is "Info".)
     8. Wait ~90 s; verify `Status: ready` and "Download complete". If "stream was reset" appears, the HTTP/1.1 fix isn't installed — rebuild/reinstall first.
   - Fallback (if catalog download keeps failing): `adb push /tmp/opencode/qwen2.5-0.5b-instruct-q4_k_m.gguf /sdcard/Download/` then app → Local models → "Import model file" → Downloads → tap the file card (left card, e.g. (300,1000)). Caveat: imported-custom models use hardcoded floors (4.0 RAM/1.0 storage) in `LocalChatSessionManager` — with the decimal-GB fix RAM passes (4.1 GB), but storage needs ≥1.0 GB free; trim caches or free space first. Catalog download is preferred.
2. **Run local-AI test** (fixture must be up):
   ```bash
   nohup python3 tools/hil/serve_web_fixture.py --port 18765 >/tmp/opencode/tasker-polish-web-fixture-18765.log 2>&1 &
   adb -s emulator-5554 reverse tcp:18765 tcp:18765
   adb -s emulator-5554 shell am force-stop com.android.chrome
   adb -s emulator-5554 shell am start -W -a android.intent.action.VIEW -d 'http://127.0.0.1:18765/' -p com.android.chrome
   sleep 3; adb -s emulator-5554 shell input keyevent KEYCODE_HOME
   CYANBRIDGE_HIL_LOCAL_AI=true bash tools/hil/run_instrumentation.sh emulator-5554 hardware com.fersaiyan.cyanbridge.hil.LocalAiTaskerChromeHilTest
   ```
   Test must answer with "37", "amber", "cyanbridge", "tasker" (Borealis fixture facts) and end observed on Chrome article marker `CYANBRIDGE_HIL_WEB_ARTICLE_72941`. Debug with `adb logcat -s TaskerLocalAgent LocalChatSession LocalModelDownload` and `run-as com.fersaiyan.cyanbridge cat shared_prefs/local_agent_prefs.xml` (status/last_error). Planner is Qwen 0.5B on CPU — steps are slow; BRAIN_TIMEOUT_MS=60 s per step, test budget 6 min.
3. **Run the real-email test** (user authorized; sends exactly one self-email per run):
   ```bash
   adb -s emulator-5554 shell am force-stop com.android.chrome
   adb -s emulator-5554 shell am start -W -a android.intent.action.VIEW -d 'http://127.0.0.1:18765/' -p com.android.chrome
   sleep 2; adb -s emulator-5554 shell input keyevent KEYCODE_HOME
   CYANBRIDGE_HIL_EMAIL_SEND=true bash tools/hil/run_instrumentation.sh emulator-5554 hardware com.fersaiyan.cyanbridge.hil.LocalAgentEmailApprovalHilTest
   ```
   Preconditions inside the test: NO pre-existing pending actions (assert fails otherwise — resolve via app Pending Actions UI if needed), provider becomes LOCAL_AGENT (or already PRO_SUBSCRIPTION), `RemoteOpenAiPrefs.isActive` false, model installed. Replies "maybe"/"yes" are injected textually via `LocalAgentController.replyToApproval` (no real mic needed), but the voice session still speaks via TTS and listens (silence tolerated; external reply wins).
4. **Fix whatever the tests expose** (product bugs, keep changes minimal), then:
   - `python3 tools/hil/validate_tasker_profiles.py && bash -n tools/hil/*.sh && git diff --check`
   - `./gradlew --no-daemon :app:testDebugUnitTest :shared:portabilityTest`
   - Core suite (5 classes) still green.
5. **Commit** the four files listed above (+ any new fixes) on `tasker-integration-polish` and push. Suggested message themes: fixture port collision + marker health check; decimal-GB capability units; 0.5B storage floor; HTTP/1.1 + IOException retry for model downloads.
6. **Enable CI layers** (requires repo admin; `gh variable list` currently shows NO CYANBRIDGE_* vars):
   ```bash
   gh variable set CYANBRIDGE_HIL_ENABLE_EMAIL_SEND --body true -R FerSaiyan/Alternative-HeyCyan-App-and-SDK
   gh variable set CYANBRIDGE_HIL_ENABLE_LOCAL_AI  --body true -R FerSaiyan/Alternative-HeyCyan-App-and-SDK   # optional but recommended
   ```
   If you cannot set vars, ask the user to add them in GitHub → Settings → Secrets and variables → Actions → Variables.
7. **Verify CI**: push → watch `Android Tasker HIL` run; the previously "skipped" steps (Prepare persistent emulator for AI automation HIL, Prepare deterministic Chrome fixture, Run CyanBridge local AI through Tasker and Chrome, Reset Chrome fixture, Run approved Gmail self-send HIL) must now RUN and PASS. Note the runner's persistent AVD is presumably this same machine's AVD (self-hosted homelab runner) — local emulator state (Gmail sign-in, installed model, Tasker entitlements) IS the CI state; keep it healthy, never wipe.
8. Update this worklog with final results.

## Operational Notes

- ADB on this API 37 emulator drops constantly (`error: device offline/not found`). Pattern that works: `adb kill-server; adb start-server; adb -s emulator-5554 wait-for-device` before each batch; retry failed taps once.
- `run_instrumentation.sh` already: dismisses the 16 KB compat dialog, splits comma-lists into one process per class, and recovers offline emulators via `tools/hil/start_emulator.sh` (cold boot, userdata preserved).
- Storage is the recurring constraint (6 GB /data, ~80–92% used). Recovery recipe that worked: `adb shell am force-stop com.fersaiyan.cyanbridge; adb exec-out run-as com.fersaiyan.cyanbridge sh -c 'rm -rf files/local_models'; adb shell cmd package trim-caches 2G; adb install -r -d <apk>`.
- Gmail/Tasker/AutoInput state, Google account, and accessibility grants must never be wiped (`pm clear`, `-wipe-data` are forbidden).
- The email test's Tasker side executes the approved SendEmail through the Local Agent Tasker project (`android/CyanBridge/tasker/CyanBridge_LocalAgent_Tasker.prj.xml`); if execution fails, check `TaskerExecutionBackend.execute` results in the pending-action record and the Tasker run log. The planner prompt requires a second observation proving compose is gone before finishing.
- Repo variables/secret context: `META_GITHUB_TOKEN` secret exists; `CYANBRIDGE_HIL_*` vars do not (as of this writing).

## Constraints (unchanged)

- No subagents. No credential exposure. No billing/entitlement bypass (Tasker/AutoInput entitlements were legitimately restored earlier via the user's own AutoApps purchases).
- Real email ONLY to `fernandosaiyan10@gmail.com`, one per test run, unique `CB-HIL-<ts>` subject, body labeled as deterministic fixture data.
- Do not `pm clear` CyanBridge/Gmail/Tasker; do not wipe the AVD; do not force-push without approval.

## Real UI collection (2026-09-27, Artemis isolated AVD)

- Created `CyanBridge_Artemis_Data` (Android 36, `emulator-5560`) separate from authenticated `Pixel_9a`. The collector verifies AVD name, boot, absence of Tasker/AutoInput and absence of Android accounts before actions. Artemis checkout revision `371aa6df56880643da57b30da936e9812fb0ec66` supplied `AndroidAdbDriver` + UIAutomator2; no Artemis agent, LLM credentials or accessibility helper were used.
- Collected 14 verified Settings navigation episodes, 5 Clock selected-tab episodes, and 10 YouTube **query-field-only** episodes. Every labeled action has pre/post screenshot and hierarchy hashes, a currently observed binding, and a separate checked postcondition. Unverified Settings transitions into other packages and YouTube's failed network search were excluded. Raw screenshots/XML remain in `/tmp/opencode/artemis-phone-captures/`, not Git.
- Assembled `android/CyanBridge/training/artemis_real_v1/pool.jsonl` and manifest: 29 episodes, 82 decisions (43 TAP, 10 TYPE_LITERAL, 29 DONE), 40 screen signatures, just **three connected screen families**. `readyForIndependentSplit=false` and `readyForTaskerTraining=false`. These rows were **Artemis-observed**, not Tasker-observed; no Laya/Needle retraining was performed on them.
- Python collector/audit suite: 9 tests passing. Android `:app:compileDebugAndroidTestKotlin` passed with `ANDROID_HOME` set. Added an opt-in Tasker Settings parity HIL test to measure whether a target seen by Artemis is also present and retained in the real Tasker candidate set; Tasker execution result must be measured separately.

## Tasker Settings source parity and verified episode (2026-09-28)

- On `Pixel_9a`, Tasker/AutoInput observed 20 Settings nodes and CyanBridge retained `Network & internet` among seven candidates. The opt-in read-only parity HIL passed.
- A first Settings-navigation trial had **already resumed the Network page before the tap**. Its post markers looked good, but the audited pre-action state exposed a false positive; the export was discarded. The final test explicitly starts Settings HOME and refuses an already-open destination, missing Tasker markers, unchanged hashes or stale timestamps.
- The HIL emulator-recovery helper reused `emulator-5554` for `CyanBridge_Walking_Aid_CI` hours later. An AVD-identity check prevented an invalid Pixel export. `CYANBRIDGE_HIL_ARTEMIS_PARITY=true` now pins `Pixel_9a` and fails closed on offline/wrong AVD before and after instrumentation. Pinned HIL passed on `emulator-5562` without clearing app data.
- The final allowlisted Tasker diagnostic is `android/CyanBridge/training/tasker_real_v1/settings_network.json` (episode `tasker_settings_network_1790601149125`). Tasker pre-state: Settings home. Tasker action: tap `Network & internet`. Tasker post-state: `Internet` and `SIMs`, changed hash; separate ADB UIAutomator dump confirmed page title and `Airplane mode`. Pre-observation to verified post-observation: **1,821 ms**; action start to verification: **1,734 ms**. This is one diagnostic episode, not a trainable split or a planner comparison. No email was sent.

## 2026-09-29/30 — AudioRecord replaces Google STT for glasses questions (in progress, hunks parked)

> Historical implementation note. The 2026-10-01 review below corrects the
> diagnosis, stash reference, existing-VAD inventory, and readiness assessment.

### Goal
Remove the system Google `SpeechRecognizer` from the glasses image-question and
voice-question flows. Field logs showed its server-side speech-onset deadline
killing the Ask window ~0.6-0.9 s after the cue (`NO_SPEECH_DETECTED` -> error 7),
while the app's own 3.3 s window never fired. Replacement: `AudioRecord`
capture with on-device energy endpointing, feeding audio + image (+ text
instruction) straight into the multimodal LiteRT turn. No transcription
fallback (rejected: latency, VRAM/RAM contention, offline requirement) and no
relay transcription (privacy: relay only routes to a Pro user's chosen cloud
model, never transcribes). 99% of users are offline local-model users.

### What failed (no code fault on our side)
- Mid-implementation, a concurrent agent working Eyevue/build issues on
  `cyanbridge-relay-readiness` stashed the shared tree (`git stash push -u`,
  "parked to unblock eyevue build"), sweeping up the in-progress files. Current
  branch `heycyan-eyevue-tunebuds-protocol-fixes` is back at HEAD for those
  paths. Nothing is lost: the work sits in `stash@{0}` (see `git stash show`
  output below), but it mixes both agents' files across two branches, so it
  was NOT popped. The other agent is now separating my MainActivity hunks from
  their WIP to preserve them.
- Stash@{0} contents relevant to this task: `MainActivity.kt` (all rewires),
  new `ai/image/AudioQuestionRecorder.kt`, new test
  `ai/image/AudioQuestionRecorderTest.kt`.

### What was planned and why (approved before implementation)
- New `AudioQuestionRecorder`: `VOICE_COMMUNICATION` AudioRecord @16 kHz mono
  (follows the glasses SCO mic like today), pure `VoiceEndpoint` state machine
  (onset timeout reuses the 3.3 s constant, ~1.8 s silence hangover so long
  questions are never cut, ~20 s cap, ~400 ms min-speech to reject cue echo),
  pure RMS frame classifier + WAV writer (both JVM-testable), WAV to
  `cacheDir/audio-questions/`. Rationale: endpointing fully on-device, window
  stays open while the user speaks, zero network dependency.
- Image flow: `CapturedImageQuestion` gains `audioPath`; threaded through the
  parallel/sequential capture, `offerFollowUp`, `triggerAssistantImageQuery`,
  `triggerChosenProviderImageQuery`. LOCAL route sends image + audio + short
  instruction text (not the default description) in one LiteRT turn
  (`audioPath` already supported end to end via `GenerationConfig` ->
  `Content.AudioFile`). PRO/TASKER/Live proceed with text only + log line
  (interim: PRO needs the planned combined image+audio relay endpoint; TASKER
  external-UI fill fundamentally needs text = open product decision).
- Voice flow: same recorder replaces its `SpeechRecognizer`; LOCAL answers
  direct from audio; PRO answers via existing `/audio-query` (no website change);
  TASKER voice gets a clear toast (needs text bridge). Text intent routing
  (`AssistantRequestRouter` ANALYZE_IMAGE/EXECUTE_UI_TASK/CLARIFY from the voice
  button) is dropped with the text it depended on — documented regression.
- Verified beforehand (read-only): LiteRT-LM 0.16.0 already builds
  `Content.AudioFile` (wav/mp3/flac); llamacpp-kotlin 0.4.0 binding is
  text-only (upstream libmtmd has audio, binding does not expose it); website
  has no standalone VAD (only Live-API `silenceDurationMs`); relay
  `/audio-query` takes audio+prompt, `/image-query` image+prompt, no combined
  route yet; chat-screen typed questions stay text-only via existing defaults.
- Website plan (no edits): extend `/image-query` with optional `audioBase64`
  appended as an `input_audio` part (mirrors `/audio-query`), backward
  compatible.

### Hunk recovery plan
1. Let the concurrent agent finish isolating my MainActivity hunks from their
   Eyevue WIP (in progress on their side).
2. Re-apply on `heycyan-eyevue-tunebuds-protocol-fixes`: my 3 paths only
   (`MainActivity.kt` hunks, `AudioQuestionRecorder.kt`,
   `AudioQuestionRecorderTest.kt`) — via their separated patch/branch, or
   `git checkout stash@{0} -- <paths>` + manual verification that no Eyevue
   hunks leak in, or rewrite from context if the stash becomes unusable.
3. Do NOT `git stash pop` (would reintroduce their files across branches).

### Next steps (after recovery)
1. Re-apply edits, then `git diff --stat` review of exactly the 3 paths.
2. Run `:app:testDebugUnitTest` (new endpointing/classifier/WAV tests + existing
   vision/transcription/router tests), then `:app:compileDebugKotlin`.
3. On-device test with logcat (`ImageQuestion`, `ImageQuestionAudio`,
   `AudioQuestionRecorder`): confirm onset/hangover behavior and that LOCAL
   answers audio+image directly.
4. Follow-ups (separate): combined image+audio relay endpoint (website),
   PRO image audio enablement (one-line once endpoint lands), TASKER spoken
   questions product decision, voice-button intent redirection (lost with text).

## 2026-10-01 — Audio/image question diagnosis and parked-patch review

- Full review: `android/CyanBridge/docs/AUDIO_IMAGE_QUESTION_REVIEW.md`.
- Corrected the earlier certainty: logs prove early recognizer closure and no
  usable question, not a proven Google server-side onset deadline. Google starts
  online recognition **and** offline SODA; SODA fails to obtain the `en` language
  pack although an `en-US` pack is listed. Bluetooth route-open/packet activity
  alone does not prove intelligible speech reached recognition. An earbuds route
  and an 11-second empty-result attempt are additional confounders.
- The app already has local energy-based speech detection: AmbientSpeechDetector
  (actively used), SpeechActivityDetector, SilenceCompactor, and
  GeminiLiveSpeechActivityDetector. They are not currently the single-shot
  question endpoint. Reuse suitable primitives rather than claiming none exist.
- LiteRT already combines image/audio/text and preserves configured/runtime
  system instructions. Found a separate real defect: failed media generation
  silently retries text-only, and absent files can be silently omitted.
- Original WIP is now `stash@{1}`, stable commit
  `75c25289d6f2ae7755a364f088798ed834888de9`. Exported only the original audio
  MainActivity delta plus recorder/test files and hash manifest into
  `/tmp/opencode/audio-image-review-20261001/` without applying them.
- The WIP is incomplete: orphaned recognizer code, undefined voice cue/timeout,
  missing imports. Its unconditional 20-second cap contradicts the long-question
  requirement. Cancellation ownership, file cleanup, ignored non-local image
  audio, and removed voice intent/Live routing also require revision.
- Concurrent EyeVue protocol, WAV-import, and RTSP changes are broadly sensible
  for transport/live preview. The auto-audio ACK-timeout permit-release change
  still needs vendor late-reply isolation verification; coordinator tests alone
  establish only permit bookkeeping.
- Current selected unit tests passed: **33 tests across five classes**; log at
  `/tmp/opencode/audio-image-review-tests-20261001.log`. This does not validate
  the stashed recorder. No ADB device attached. Located test WAV fixtures, but
  no raw microphone recording associated with the failing question attempts in
  searched paths.
- Next: rebuild the LOCAL capture-to-multimodal handoff from the reviewed hunks,
  preserve system/extra text, stop silent media loss or implicit cloud fallback,
  retain route behavior deliberately, and verify offline on physical hardware.

## 2026-10-01 — Offline-first question flow + upstream llama.cpp (uncommitted)

### Goal (user-approved)
- Keep Google recognition strictly on-device where usable; fix the `en` vs
  installed-pack failure mode instead of relying on network STT.
- If offline transcription fails, send recorded audio + image + required text
  prompts to LiteRT.
- Bring llama.cpp up to an upstream revision with audio-capable libmtmd, since
  `llamacpp-kotlin:0.4.0` exposes no audio path.

### Implemented (working tree, not committed)
- Branch: `heycyan-eyevue-tunebuds-protocol-fixes` @ `05463fb`; changes uncommitted.
- Capture: new `ai/image/AudioQuestionRecorder.kt` (16 kHz mono WAV, shared RMS
  primitive from `SpeechActivityDetector`) + new `ai/image/QuestionEndpoint.kt`
  (`NO_SPEECH` on onset timeout, `COMPLETE` on sustained silence, 2-min resource
  limit reported as `LIMIT_REACHED`, not silent truncation).
- STT: new `ai/transcription/OfflineQuestionTranscriber.kt` (API 33+ only,
  `createOnDeviceSpeechRecognizer`, `checkRecognitionSupport` installed-locale
  selection, recorded WAV injected via `EXTRA_AUDIO_SOURCE` + segmented session;
  any failure returns null so the original audio is retained for the model).
- Wiring: `MainActivity.kt` image/voice capture now records once, tries on-device
  transcript, keeps existing text routing on success, otherwise forwards the WAV
  to local multimodal inference. Added `audioPath` plumbing and WAV cleanup.
- LiteRT: removed silent text-only retry/fallback in
  `LiteRtLocalInferenceEngine.kt`; missing/empty attachments now fail loudly.
- llama.cpp: new `:llama-runtime` module pinned to upstream
  `0c1e57098bba43ac29e6e3b677cdceebdd22334f`
  (`SHA256=6a85ec94...0407f`) with `libmtmd` image+audio bridge (`bridge.cpp`,
  `UpstreamLlamaBridge.kt`); replaced `LlamaCppLocalInferenceEngine` and
   embedding engine with the upstream JNI path; `EngineLoadConfig.projectorPath`
  added; new `LocalModelProjectorStore.kt` for per-model mmproj GGUF imports.
- Routing: `MediaInferenceRoutingPolicy` no longer falls back LOCAL media to
  Pro/Tasker relay; `LocalModelsProvider` can pick an installed LiteRT or
  projector-backed GGUF media model for the request. Tests: new
  `QuestionEndpointTest`, `OfflineQuestionTranscriberTest`, `Utf8TokenDecoderTest`;
  updated `MediaInferenceRoutingPolicyTest`.

### Verification
- `:app:compileDebugKotlin`: PASS (`/tmp/opencode/offline-question-compile.log`).
- `:llama-runtime:assembleDebug`: PASS
  (`/tmp/opencode/llama-upstream-android-build.log`).
- `:app:testDebugUnitTest`: 576 tests, 1 failure —
  `EyevueReleaseSafetyTest.eyevueMediaSyncUsesVerifiedWifiActivationCommand`
  (`/tmp/opencode/offline-question-tests.log`). Unrelated EyeVue Wi-Fi
  expectation; needs triage before commit.
- Host JNI smoke (`test_upstream_llama_jni.sh`): blocked at CMake
  `Could NOT find JNI` under both JBR and Java 11
  (`/tmp/opencode/upstream-llama-host-smoke.log`); Android AAR build itself
  succeeded, host smoke still open.
- No device attached; no on-phone offline STT or LiteRT audio+image run yet.

### Next
1. Triage the EyeVue safety-test failure; rerun full unit tests green.
2. Fix host JNI smoke environment or document it as device-only.
3. On-device test with network disabled: on-device transcript path, audio+image
  LiteRT path, long-question endpointing, and projector-backed GGUF media caps.
4. Commit only after 1–3; review `android/CyanBridge/tools/` untracked dir before
   committing.

## 2026-10-01 — Meta DAT log review, guided setup, and actual SDK mock CI

- Reviewed report `log_mukhte4r_6fb6f47a` (Samsung SM-F976U, Android SDK 37,
  app 2.3.0). Its September 28 00:00–00:12 UTC events predate `a13fffc`
  (14:39 UTC), whose initial-STOPPED stream/display fix is already in this branch.
  Registration succeeded; discovery raced camera permission checks, and recovered
  `Granted` results left the old error latched. Review:
  `android/CyanBridge/docs/META_DAT_LOG_REVIEW_2026-10-01.md`.
- DAT readiness now follows the selected device's `LinkState.CONNECTED`, with
  link/auth diagnostics, bounded retries for typed connection errors, and recovery
  of old camera failures. Setup checks are deduplicated per ready-device state and
  resume. Permission-request errors preserve their SDK description. The manager
  also observes an already initialized process-wide SDK (`ALREADY_INITIALIZED`).
- Added next-step popups for Android permissions, app settings after permanent
  denial, Bluetooth, registration, reconnecting, and separate Meta camera consent.
  Capture failures open Meta setup. Shared `MetaDatPermissions` covers Location on
  Android 10–11 and Nearby Devices on Android 12+. Connection errors route to Meta
  AI rather than misleading permission/re-registration instructions.
- Debug mock is guarded to debug builds, handles camera checks consistently,
  preserves a running preview during one-shot capture, and releases its lease on
  disable. Removed unreliable reflective activation of a second mock backend.
- Added `MetaDatSdkMockTest` to self-hosted CI: actual Meta `MockDeviceKit` through
  the production manager, authorization denial/grant, stream, decodable photo,
  cleanup, and power-off readiness. A 3.6 KB synthetic camera video is included.
- Actual SDK mock exposed a native packaging crash: LibVLC's old bundled STL won
  `pickFirst`, leaving `libfbjni.so` unable to load a required stringstream symbol.
  Added a cached AAR transform removing only LibVLC's STL so the modern shared
  runtime is packaged. The SDK test loads LibVLC and DAT in the same process.
- Verification: all **21 Meta instrumentation tests pass** on a fresh API 36
  `CyanBridge_Meta_DAT_CI` emulator (4 lifecycle, 1 actual SDK mock, 7 UI mock,
  9 setup popup tests), via the same isolated-class HIL runner used by CI.
  Targeted unit tests: 26/26; shared portability tests: 80/80.
- Full unit run initially found 583 tests / one pre-existing EyeVue source-check
  failure. Removed its obsolete `stopLiveBlocking()` assertion: committed sync
  cleanup now uses `finishMediaSync(completed, total)`. The Wi-Fi activation checks
  remain. Final verification is green: **583/583 app unit tests**, **80/80 shared
  portability tests**, debug and instrumentation APK builds, plus the 21/21 Meta
  emulator tests. The normal debug APK's ARM64 and x86_64 shared runtimes both
  contain the required fbjni symbol and match the modern fbjni dependency.
  Build/unit logs and SDK logcat: `/tmp/opencode/meta-dat-review/`; isolated
  instrumentation results: `build/hil/results/instrumentation-emulator-*-Meta*.txt`.
- No physical Meta glasses attached. Hardware validation on the reported Samsung
  / Android 17 build remains the next device check; emulator capture verifies the
  app/SDK sequence and does not establish that hardware result. Changes uncommitted.

## 2026-10-01 — Walking Aid continuous EyeVue and Meta video

- Added a persisted Walking Aid camera source with periodic photos as the default,
  and device-gated EyeVue/Meta continuous-video controls in settings. The photo
  interval and photo benchmarks apply only to the photo source. Source changes
  are disabled while active. Permission popups guide Bluetooth, EyeVue Wi-Fi,
  permanent-denial settings, and Meta registration/camera authorization.
- Live sources feed the existing local YOLO/tracking/warning worker through the
  shared latest-frame queue. Queue overflow and shutdown recycle unused bitmaps;
  local depth owns an independent copy and runs as a cancellable child so it does
  not block the next YOLO frame. Video history saves only analyzed frames and
  bounds retained JPEGs. Stream stalls/errors stop the plugin with a visible error.
- EyeVue preview's BLE/Wi-Fi/HTTP/RTSP connection was extracted into
  `EyevueLiveConnection`, shared by the visible preview and headless LibVLC output.
  An ImageReader surface receives RV32/RGBX and applies the same -90° rotation as
  preview. The real video test caught/fixed an initial RGBA/RGBX format mismatch.
- Meta video uses the production manager and actual DAT session/stream callbacks.
  Both sources respect exclusive transport leases and release on cancellation.
  Continuous video uses `connectedDevice` foreground-service type.
- Added 12-second two-scene H.264/HEVC fixtures derived from the existing pinned bus
  and Zidane JPEGs. CI runs actual YOLO11 over EyeVue's production decoder (only
  the connection URL substituted) and actual Meta `MockDeviceKit` video delivery.
- Stronger cross-scene assertions found an existing detector bug: the YOLO11
  export's unquantized UINT8 `class_idx` has scale=0. Multiplying IDs by zero made
  every object a person. Preserve raw IDs for zero-scale integer tensors, and
  require person/bus and person/tie in real-image tests as well as stream tests.
- Actual Meta video testing found that the mock negotiates an HEVC decoder: an
  H.264 feed entered STREAMING but produced no decoded frames. The Meta fixture
  now uses HEVC, and the manager logs first-frame format metadata for diagnosis.
  The passing mock delivered 640×480 I420 (460,800 bytes), detected bus/person and
  then person/tie, and completed `STOPPING -> STOPPED` with no stream/lease leak.
- Final verification is green on `CyanBridge_Walking_Aid_CI` / emulator-5580:
  **586/586 app unit tests**, **80/80 portability tests**, **5/5 real-model/video
  phases** (YOLO11, YOLO-World, depth, EyeVue video, actual Meta DAT video), and
  **28/28 control/lifecycle/DAT regression instrumentation tests**. Both debug
  APK builds pass, including the normal ARM64+x86_64 APK for hardware retesting.
  Evidence: `/tmp/opencode/walking-video-*.log`,
  `build/hil/results/walking-aid-model-ci/`, and
  `build/hil/results/instrumentation-emulator-emulator-5580-*.txt`.
- CI runner also fixes the optional `stop_other_emulators` function returning
  failure under `set -e` when the option is disabled. Documentation:
  `android/CyanBridge/docs/WalkingAid_mvp.md`, `tools/hil/README.md`, and video asset
  provenance under `app/src/androidTest/assets/walkingaid/README.md`.
- No physical EyeVue or Meta glasses are attached. Changes remain uncommitted.
