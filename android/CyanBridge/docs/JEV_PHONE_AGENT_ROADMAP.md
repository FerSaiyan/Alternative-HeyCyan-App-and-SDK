# Jev-like phone agent: evidence and next steps

Updated: **2026-10-03**. This is the current planning entry point; earlier
experiment notes remain useful as history. Measurements below are from
September 20–28, not new October device runs. Repository baseline reviewed:
`160299c`. No ADB device was attached during this planning review; interrupted
emulator processes after the server restart are not failed model evaluations.
The later user-requested [October 3 remote specialist pilot](../training/laya_needle_specialists_v1/README.md)
adds offline voice/configuration training and ablations; it is separate from
these historical device measurements and does not promote a phone checkpoint.

## Recommendation

**2026-10-04 runtime priority:** evaluate [shared Gemma chat + bounded logits](SHARED_GEMMA_DECISIONS.md)
before adding a second resident specialist on 4–6-GB phones. The Jev branch
now has an opt-in CPU GGUF prototype and Tasker shadow comparison. This does
not establish Gemma action accuracy, phone memory suitability or native GPU
support; the existing execution cascade remains the reference.

Prioritize a reliable **Tasker observation → fresh candidate binding → checked
outcome** loop and a new episode-disjoint dataset. Keep EmbeddingGemma's
margin ≥0.10 and the existing planner cascade. Keep Laya and Needle shadow-only
until their exact phone contracts pass separate accuracy and runtime gates.
The next fine-tune should depend on data readiness, not another epoch count.

Following the user's Laya/Needle specialist direction, prioritize the
[voice-router and native configuration-selector data contracts](LAYA_NEEDLE_SPECIALISTS_AND_DATASETS.md)
alongside the versioned Tasker episode collector. Modes/plugin settings have
typed app state, so their training readiness does not depend on a broad phone
UI split. Keep public voice/Intent data separate from Tasker-observed UI data;
the reviewed [source catalog](../training/laya_needle_dataset_sources_v1/README.md)
records conversion and negative-data gaps. Use the Tasker collector for recall,
stage timings and independently checked UI outcomes. An ARM Needle probe can
proceed independently once a suitable phone exists. Gmail remains a read-only
observation track.

## What Ship With Jev contributes

The user's closer reference is the
[Robotics & devices category](https://www.shipwithjev.com/categories/robotics-and-devices).
The follow-up [voice/Tasker findings](JEV_VOICE_TASKER_TRAINING_FINDINGS.md)
prioritize Evoke's sentence-to-recipe/input selection, smart-home command vs
conversation routing, Fusion 360's command detector, and RoboJEV's
intent/action/outcome separation. They also add Laya's reconstructible Spanish
voice-turn training and Needle's media/wearable/home tool fixtures. Collect
linked **voice-routing** and **screen-action** labels; a UI-only dataset does
not validate interpretation of spoken commands or ASR errors. These device
examples complement the browser node-grounding references below.

Pages reviewed on October 3, 2026:

| Reference | Mechanism to adopt | Application here |
|---|---|---|
| [Jev engineering](https://www.shipwithjev.com/guides/jev-engineering) | Operational questions, one judgment per question, explicit unclear, versioned calibration | Ask whether the current subgoal has observable evidence, then choose an operation and a compatible observed target. Version the renderer and rubric together. |
| [Browser Use flight search](https://www.shipwithjev.com/builds/browser-use-flights) | Fresh action space per step, separate text generation | Rebuild candidates from each Tasker snapshot; exact user literals can be copied, open-ended text remains with the planner. The reported ~7 s browser run is not a phone latency target. |
| [Hunch](https://www.shipwithjev.com/builds/hunch-browser-agent) | Accessibility snapshot, code-verified outcomes, escalation | Verify the destination or changed value after execution; escalate incomplete observations. Its author-reported 24/24 and 153 ms median do not establish CyanBridge precision. |
| [Task-done checker](https://www.shipwithjev.com/builds/is-the-task-done-checker) | Small completion judgment | A model may propose DONE; CyanBridge checks task-specific evidence. Include the same filled field with both finished and unfinished goals. |
| [Jev Layer](https://www.shipwithjev.com/builds/jev-layer), filed October 1 | Host-approved capability set, host execution, outcome replay | Preserve CyanBridge's policy/approval ownership and attach executed results to decision IDs. No new library is needed to apply this design. |
| [Playwright + Jev](https://www.shipwithjev.com/builds/playwright-jev), filed October 1 | Semantic selection with a separate executor | Borrow the selection/execution separation; Playwright DOM roles are not evidence of Tasker actionability. |

Ship With Jev identifies itself as an independent catalog with author-reported
numbers. These references support architecture choices. They supply neither
phone labels nor calibrated local-model thresholds.

## Evidence ledger

### Decisions and task outcomes

| Experiment | Recorded result | Consequence |
|---|---|---|
| Frozen generated 169-case, 13-locale corpus; 40 held-out model cases | EmbeddingGemma raw 28/40; at margin ≥0.10, 48/48 calibration and 16/16 test accepted choices correct, ~46% actionable coverage | Best measured conservative fast-path candidate. Small related templates do not prove ≥98% deployment precision. Keep the threshold; recalibrate a new renderer on new data. |
| Same corpus | Qwen3 embedding raw 15/40, ~9% coverage at its conservative point; ~2.2 s cold-decision p50 | Deprioritize for the fast path. |
| Same corpus, host inference | Laya multilingual 17/40; Needle base 28/40; neither has a useful standalone ≥98%-precision confidence gate | Do not authorize actions from their raw probabilities/confidence. Needle + Gemma agreement was a small-corpus diagnostic, not a deployment calibrator. |
| Synthetic head/LoRA pilot, inspected test | Laya 56.3% → 86.7%; Needle 46.1% → 64.8% | Training machinery works on its templates. |
| Six actual Tasker YouTube states, inspected replay | Laya 5/6 → 4/6; reversed options 3/6 for both. Needle first-four-control projection 1/6 → 2/6 | Template gains did not establish phone transfer. These renderers differ; the fractions are not a fair cross-model leaderboard. |
| Tuned checkpoints on frozen generated corpus | Laya 18/40, no useful automatic coverage; Needle 19/40, down from 28/40. Tuned Needle missed all 16 synthetic missing-control cases | Missing-control abstention and distribution shift are blockers. |
| Compact semantic replay | Intended choice on five inferred steps, already-playing step deferred in code; four inferred steps offered only one actionable tool | Good input-design clue; mostly prefiltering, not general model selection evidence. |
| Pro planner + Gemma cascade YouTube HIL | Playback verified twice; latest successful run 22.9 s, earlier successful run 44 s | Working end-to-end reference path. Re-establish it after restart; no Laya/Needle completion claim. |
| Tasker Settings diagnostic | Home → Network verified by Tasker and separate ADB UI; seven candidates; 1,821 ms pre-observation to post-observation, 1,734 ms action-start to verification | The observation/execution loop costs substantially more than a tiny host forward pass. One manually scripted episode is not planner performance. |
| Approved Gmail workflow | Approved handoff opened composer; Tasker UI Query did not return composer observation; Send/delivery unobserved | Keep send continuation blocked at observation. Model training cannot repair the missing callback. |

Source detail: [embedding/runtime experiments](JEV_LIKE_ANDROID_EMBEDDINGS_NEXT_STEPS.md),
[Tasker input experiments](TASKER_SMALL_MODEL_CONTEXT.md),
[synthetic pilot results](../training/phone_ui_synthetic_v1/README.md).

### Dataset inventory and allowed uses

| Pool | Size/provenance | Next use |
|---|---|---|
| Frozen mobile-action calibration corpus | 169 generated cases, translated templates; repeatedly inspected | Fixed regression suite. Never tune against it and then call it unseen. |
| `phone_ui_synthetic_v1` | Per model: 576 train / 128 validation / 128 test decisions; episode/name-disjoint, shared templates; tests inspected | Trainer/renderer smoke and regression diagnostics. Not real phone evidence. |
| Six-frame YouTube trace | One actual Tasker episode, already inspected | Diagnostic replay only; exclude from new training and final holdout. |
| `artemis_real_v1` | 29 actual emulator episodes / 82 decisions / 40 signatures; three connected screen families; Android 36 UIAutomator2 via Artemis driver | Develop collection/audit tooling; optional auxiliary training only with explicit source tags and source ablations after splits exist. It is not Tasker data. |
| `tasker_real_v1` | One verified Android 37 Settings tap; allowlisted export, not a complete model-facing candidate record | Collector seed and outcome-oracle regression. Not an independent training/evaluation pool. |

The Artemis pool has 43 taps, 10 exact-literal entries and 29 DONE rows, but no
missing-control or ambiguous-action supervision. Its YouTube tasks stop at
query entry; network submission reached RETRY, so result selection/playback
must be collected separately. Do not shuffle these 82 rows into three splits.
The audit's six-component minimum is a collection heuristic, not sufficient
statistical evidence or proof of semantic family independence.

### Runtime selection

| Backend | Established contract | Remaining gate / priority |
|---|---|---|
| EmbeddingGemma GGUF | Android x86_64 CPU tested, 768-d; query-only p50 ~67 ms with cached prototypes; ~334 MB model / ~518 MB measured PSS | Retain as incumbent gate. Verify pinned patched AAR/model hashes and token budget; measure live coverage with short state. |
| Qwen3 embedding GGUF | Fixed runtime yields 1024-d; ~639 MB model / ~1,083 MB measured PSS; weaker frozen-corpus behavior | Low priority; do not add another packaging track now. |
| Needle 3 | ~35 MB deployed `.cact`; ~78 MB host CLI peak RAM in one probe; serialized process-global native engine; Android ARM artifacts, no published Android x86_64 engine in the inspected release | First new runtime feasibility probe on ARM64. Host timings vary by format (~0.2–0.6 s in selected probes); obtain matched phone CPU results. Base confidence overstates correctness; local LoRA confidence is unset. |
| Laya multilingual | Zero-generated-token scorer; host CUDA warm ~40–53 ms. Community LiteRT Android artifact set ~679 MB, 256-token graph; upstream phone parity claims | Second runtime track. Verify tokenizer, option/logit parity and all required heads on our phone; no CyanBridge Android latency established. Naive INT8 export previously changed choices. |
| Existing local LLM / Pro planner | Qwen2.5 0.5B bounded CPU probe ~6.5 s cold / 142 ms warm; Pro cascade completed YouTube | Keep as fallback/reference; task timing includes observation, network planning, execution and verification. Pro HIL requires fresh server-verified planning, with no silent local fallback. |

The bundled llama.cpp Kotlin AAR is CPU-only. GPU preferences do not create a
Vulkan backend. The embedding-runtime build script and pinned patches now
exist in `tools/hil/embedding-runtime/`; production dependency/distribution
still needs a reproducible artifact and supported acquisition path. Do not
repeat the already-fixed JNI return-type or per-model dimension investigations.
The native long-prefill crash remains a reason to enforce **token** budgets;
the current character cap is not proof that every tokenizer input is safe.

## Phone-UI work packages

These packages describe the external phone-UI lane. The
[voice/configuration specialist sequence](LAYA_NEEDLE_SPECIALISTS_AND_DATASETS.md#specialist-training-and-evaluation-sequence)
can proceed alongside it; voice/configuration fitting requires its own grouped
data and baseline gates, rather than a ready Tasker UI corpus.

### 1. Restore and pin the measurement environment

- Inventory local checkpoints, reports and runtime artifacts after the server
  restart; verify their manifests/hashes before relying on `/tmp/opencode`.
- Boot the existing `Pixel_9a` without wiping/re-authenticating. Select an unused
  port and verify AVD identity before installs and after HIL. Explicitly set
  `CYANBRIDGE_HIL_EXPECT_AVD=Pixel_9a` for every Pixel experiment, including
  YouTube; the parity flag pins it automatically. Coordinate with shared CI.
- Verify Tasker/AutoInput/profile/readiness and run Settings home parity,
  reversible navigation and observer/executor smoke. Then run a freshly
  Pro-verified YouTube baseline with opt-in structured trace and stage timing.
- Keep Artemis on its isolated AVD. On disconnect, inspect task/instrumentation
  state before rerunning; never count a substituted AVD or partial run.

**Exit:** reproducible setup record, verified reversible smoke, and a current
planner baseline. The Settings test must reject an already-open destination.

### 2. Freeze one phone decision contract and expand Tasker collection

Include the [voice-layer collection and parser specification](JEV_VOICE_TASKER_TRAINING_FINDINGS.md):
actual ASR text and literal spans, supported capability menus, negation/correction
pairs, route/subgoal provenance and speaker/session holdouts. Preserve Tasker
array alignment and mark unreported flags, invalid coordinate points and
upstream clipping separately. The proposed compact evidence schema is not yet
implemented in the wire contract.

Current code already preserves selected node indices, focuses the bound node
before literal entry, and has a short `UiActionDecisionState` (600-character
cap). Validate and version these improvements rather than assuming the older
first-node bug is still open. The experimental Python phase renderer is not
the same production contract. Production offers up to eight candidates and
four node choices; Needle's small tool budget needs an explicit projection.

- Assign episode/decision IDs, observation provider, Android/app/locale,
  renderer/policy/oracle versions, node binding, observation hash/timestamp,
  complete eligible candidates, gold operation/target, outcome and exclusions.
  Preserve raw evidence privately; export redacted model-facing records.
- Record all observed candidates **before** prefiltering, then the final bounded
  list and gold-target recall. Unknown Tasker clickable/editable/focus flags
  remain unknown. A dropped or unavailable target must yield defer/escalation,
  not a label chosen from the remaining distractors.
- Start with reversible Settings, Clock and YouTube entry/navigation; add other
  accessible app/page families after smoke checks. Investigate Chrome's known
  Pixel renderer failure before relying on it. A debug fixture is useful for
  plumbing but must be labeled fixture data, not natural-app transfer.
- Include no-match, duplicate/ambiguous labels, negation, interruptions,
  unavailable observations, stale/replaced nodes, unfinished filled fields,
  misleading page instructions, and independently verified DONE. Missing
  observations are backend failures; do not invent a UI snapshot for training.
- Proposed first collection budget: **100–150 fresh episodes, roughly 500–1,000
  decisions across at least six independent connected screen families**, with
  about 25–35% defer/blocked/unfinished cases. Counts are planning targets;
  audit diversity and per-class coverage before declaring readiness. Seek
  additional families if shared roots join whole apps into one component.

**Exit:** candidate-recall report, reviewed pre/post labels, minority-class
coverage and a reproducible grouped split. Allocate families and task goals
to training, development/calibration and sealed final-test cohorts before
augmentation or teacher corrections. Keep permutations/translations together;
all already-inspected pools are development/regression evidence. Six families
permits only a minimal two-per-cohort design; it does not establish broad
cross-app generalization. Expand until the final cohort has enough accepted
decisions and negatives to assess the proposed deployment scope.

### 3. Compare the deterministic filter, Gemma and planner before training

- On development episodes compare deterministic selection, the existing Gemma
  gate and planner, compact current-step Gemma, base Laya and base Needle.
  Give models the same observed action universe; report any model-specific
  projection and tool recall. A forced single-candidate step measures filtering.
- Ablate short subgoal vs full goal, selected visible facts vs repeated dumps,
  option order, one vs several plausible controls, and history/no-history DONE.
  Log actual tokens and truncation. No threshold relaxation to force coverage.
- Report raw operation accuracy, compatible-target accuracy, accepted-action
  precision and coverage, early/missed DONE, missing-control false actions,
  zero/multiple/unknown Needle calls, and exact argument grounding.
- Decompose wall time into observation, candidate/rendering, cold load,
  inference, policy/binding checks, execution and post-verification. Report
  p50/p95, failures/timeouts and per-episode totals. The saved Settings time
  is not a model speedup; Laya host GPU and Needle host CPU are separate columns.

**Exit:** identify whether lost targets, missing observations, rendering,
abstention or inference dominates. Prefer a proven contract/filter correction
to training if it recovers the target without confident errors.

### 4. Retrain only after the data and baseline gates

- Laya: start with the frozen-encoder head baseline; supervise operation and
  matching target separately. Compare to the same base checkpoint and renderer.
  Escalate to encoder updates only if clean development errors justify them.
- Needle: use exact current-step `query/tools/answers`, explicit `[]` negatives,
  and grounded literals; avoid a random row validation split. Compare base and
  tuned **20-layer** models first, then compare any smaller depths at equal
  depth. Fix missing-control regressions before optimizing size.
- Calibrate on a separate cohort using the exact runtime/renderer. Laya's
  probability/entropy and saturated act head are not permission to act;
  Needle's base confidence and tuned `None` cannot be reused as an action gate.
  Gemma-agreement is an optional ablation with its extra latency/coverage cost.
- Evaluate Tasker-only training first; test Artemis auxiliary data as a
  provenance-tagged ablation. AndroidControl projected a11y training is another
  separate auxiliary ablation with its own target-recall audit. Validate on
  Tasker, never on a pooled source mix.

**Exit:** meaningful development improvement over base and deterministic
baselines, with no missing-control/DONE regression. Lock model, question text,
projection and thresholds before opening a new final test. Once inspected,
that final set becomes regression data for the next iteration.

### 5. Probe real mobile runtimes, then attempt a narrow promotion

Needle ARM feasibility can run alongside packages 2–3 without granting action
authority: serialized JNI/C-ABI adapter, load/reset/unload, tokenizer/tool
budget, host/phone output parity, warm/cold CPU latency and peak memory. Base
first, tuned model later. Keep x86_64 emulator tests model-free for Needle.
Probe Laya LiteRT after a clean development candidate exists; verify required
heads/tokenizer and order parity, then CPU/GPU availability, memory and thermal
behavior on the actual device. Model downloads stay opt-in and outside Git.

For promotion, predeclare a narrow reversible-action scope and a useful
coverage floor. Require ≥98% accepted-action precision on fresh episodes,
no observed policy/approval violations, correct target recall in supported
gate cases, and verified task completion at least comparable to the incumbent
planner on matched goals/devices. Report uncertainty: 16/16 is not evidence
that true precision exceeds 98%; even 149/149 *independent* successes are
needed for a one-sided 95% binomial lower bound above 98%. Correlated actions
within episodes weaken that interpretation; report episode-grouped intervals
and per-class errors, not just pooled accuracy.

Then run shadow HIL, followed by opt-in execution for only that supported
reversible slice. Confirm end-to-end p50/p95 improvement without reduced task
success; otherwise keep the model shadow-only. All completion checks remain
outside the model. Draft writing stays with the prose planner and approved
Send stays behind its separate fresh-observation/delivery boundary.

## Gmail debugging track

Reproduce the harmless composer with the existing read-only observation HIL.
Compare working screens and composer for AutoInput plugin result/error,
callback envelope size and timing, foreground package/window and enabled
services. Bound diagnostics and restore profile changes after each experiment.
An ADB hierarchy can establish that the composer exists; it cannot replace
Tasker recipient/subject/body verification. Resume approved-send HIL only after
Tasker reliably observes the checked composer; retain explicit approval and
independent delivery verification. This is not a prerequisite for collecting
reversible non-email episodes.

## First implementation batch

1. Define versioned product capability/setting/plugin manifests and collect
   grouped voice/config examples, including finalized ASR and no-action cases.
2. Prepare source converters and paired Laya/Needle exports using reviewed
   public training subsets; reserve separate product development/calibration/final cohorts.
3. Restore/pin Pixel and run reversible smoke plus Pro baseline; extend the
   Tasker collector with complete redacted candidates, negatives and stage timers.
4. Audit AndroidControl projected training and separate Tasker families before
   a phone-UI fine-tune; benchmark deterministic/current routing as references.
5. Train/evaluate specialist variants when each data contract is ready;
   provision ARM64 for Needle and measure variant switching/residency costs.

References and detailed training formats:
[Laya/Needle training contract](LAYA_NEEDLE_PHONE_TRAINING.md),
[Artemis pool](../training/artemis_real_v1/README.md),
[Tasker diagnostic](../training/tasker_real_v1/README.md), and `worklog.md`.
