# Voice commands and Tasker UI: closer examples and training contract

Reviewed **2026-10-03**. This extends the
[phone roadmap](JEV_PHONE_AGENT_ROADMAP.md) and
[Laya/Needle training recipe](LAYA_NEEDLE_PHONE_TRAINING.md) with the voice
interface and the actual Tasker observation limits. Proposed schemas below
are design examples, not collected phone data or implemented runtime behavior.

## Main finding

For the user's specialist-variant direction and a reviewed dataset shortlist,
see [`LAYA_NEEDLE_SPECIALISTS_AND_DATASETS.md`](LAYA_NEEDLE_SPECIALISTS_AND_DATASETS.md).
It separates voice routing, native configuration/plugin selection and external
phone UI selection, and supplies measured Mobile Actions/DroidCall inventory
plus MASSIVE/SLURP/CLINC and AndroidControl conversion requirements.

The [Robotics & devices category](https://www.shipwithjev.com/categories/robotics-and-devices)
is a closer reference for **utterance → bounded action** than flight-search
demos. Browser training remains useful for **current UI → operation/target**.
Our dataset needs both decisions, with different labels and evaluation:

```text
voice → ASR transcript → supported request / clarification / conversation
                         ↓
              grounded task + literal spans
                         ↓
Tasker observation → evidence-preserving parser → eligible bound candidates
                         ↓
              one current-step model decision
                         ↓
CyanBridge checks binding/policy → Tasker executes → fresh verified outcome
```

Laya and Needle take text/tool schemas; neither supplies speech recognition or
missing accessibility evidence. A known simple utterance can select a bounded
capability; a multi-screen task needs a current subgoal from the existing
planner or a verified recipe. Sending the entire voice request to the UI
selector at every screen conflates those jobs. Keep Laya/Needle shadow-only,
the Gemma margin ≥0.10, Pro requirements and current approval boundaries.

## Device examples, ranked for this project

| Reference | Useful part for CyanBridge | Evidence boundary |
|---|---|---|
| **[Evoke](https://www.shipwithjev.com/builds/evoke-natural-language-reflexes)**; [source at reviewed revision](https://github.com/evoke-build/evoke/tree/0c61ee5aa08dd283cf7db48fa65c4722dec3dc95) | Installed recipes define inputs/effects; Jev selects recipes and closed values; code grounds spans/result dependencies and handles missing values. Best concrete sentence-to-capability reference. | Reviewed README and adapter entry. Single-machine CLI/SDK and test transcripts are implemented; distributed examples are future illustrations. Its 0.8/0.9 gates are its settings, not our calibration. No Laya/Needle training dataset is supplied. |
| **[Smart-home assistant fast path](https://www.shipwithjev.com/builds/smart-home-assistant-fast-path)** | Decide device-control vs conversation, then device and action; uncertain/non-device requests fall through to the LLM. Closest voice-routing architecture. | Catalog links a Reddit report. No repository/training artifacts or independently reproduced accuracy verified in this review. |
| **[Fusion 360 voice](https://www.shipwithjev.com/builds/fusion-360-voice)** | A microphone utterance is first classified as command or chatter. Train quoted, hypothetical, negated and incomplete commands as distinct cases. | The reported Jev role is command detection only; do not attribute feature selection, slot extraction or UI grounding to it. |
| **[RoboJEV](https://www.shipwithjev.com/builds/robojev)**; [reviewed source](https://github.com/lykycy123/RoboJEV/tree/2c920c29e5e76cc57908b3c05907d7c5d91565c1) | State → immediate intent → compatible action; malformed/inconsistent output does not execute; independent success oracle and matched rule baseline. Analogous to subgoal → operation → observed node. | MuJoCo privileged state, not phone UI or learned perception. Published gate task: Jev 5/10 vs rules 8/10. More geometry did not improve its later paired comparison. |
| [Dual-arm middle layer](https://www.shipwithjev.com/builds/dual-arm-robot), [EmbodiedJev](https://www.shipwithjev.com/builds/embodied-jev) | Keep decision selection apart from actuator details. EmbodiedJev's [integration review](https://github.com/FBddcz/embodied-jev/blob/f08de2e4e20d6cd69fea9c57ac1062c3ef510f1e/docs/JEV_INTEGRATION.md) distinguishes measured state from visual estimates and candidate-generation contributions. | Simulation/author reports and incomplete comparison tracks; no phone training recipe. Do not import simulator geometry or predicted-success fields as observed Tasker facts. |
| [Jev drone](https://www.shipwithjev.com/builds/jev-drone), [Driver](https://www.shipwithjev.com/builds/jev-driver), [traffic light](https://www.shipwithjev.com/builds/jev-controlled-traffic-light), [fault diagnosis](https://www.shipwithjev.com/builds/jev-machine-fault-diagnosis) | Compact symbolic evidence, explicit hold/re-observe, code-owned fast execution; fault diagnosis separates anomaly detection from classification. Useful for separating observer failures, semantic ambiguity and action failures. | Drone/driver/traffic-light examples are simulated; diagnosis is read-only telemetry. None demonstrates fine-tuned phone control. |

The category has ten entries at review time. The most useful adaptation is
**Evoke + voice routing + RoboJEV's outcome protocol**, alongside the browser
node-selection recipe. These are architecture references, not a benchmark
that establishes our reliability.

## Better training references

### Laya: Spanish voice turns, then browser grounding

The upstream [Spanish phone-turn recipe](https://github.com/NandhaKishorM/laya/blob/fa9a2a7070b1789912a49ae24603bbfb1a78b001/research/benchmarks/es_phone_turns/README.md)
is substantially closer to our voice interface than general typed-workflow
training. It routes a caller's sentence to one of a current menu of actions,
or keeps talking. Here “phone” means **telephone call routing**, not Android
screen control.

- Rebuildable **4,740 training cases**, 13 trades / 49 destinations absent from
  test menus; randomized menu composition/order and alternate fallback names.
- Direct requests, indirect needs, refusals, refusal-plus-new-request,
  greetings/frustration, human requests and hang-up refusal. Negation points
  both ways: “don't open X” differs from “X doesn't work, open Y.”
- Thirty percent ASR-like rewriting: missing punctuation/case/accents, fillers
  and repeated words. This is synthetic text corruption, not recorded ASR.
- Author-reported pooled **0.396 → 0.912** over 217 written fixtures; tuned
  model still made **12 wrong actions**. At threshold 0.80 it accepted 129
  turns with seven wrong actions. Accuracy improvement does not yield our
  98% automatic-action precision gate.
- Later recipe variants were designed after inspecting evaluation errors;
  sets are small and one person labelled them. Treat the example as a
  documented development cycle, not an unopened phone holdout.
- INT8 changed 24/221 decisions including borderline cases; FP32 matched
  221/221. Mobile export must be tested on the actual tuned checkpoint.

Concrete code to adapt: [menu/data builder](https://github.com/NandhaKishorM/laya/blob/fa9a2a7070b1789912a49ae24603bbfb1a78b001/research/benchmarks/es_phone_turns/train/build_train.py)
and [single-device trainer](https://github.com/NandhaKishorM/laya/blob/fa9a2a7070b1789912a49ae24603bbfb1a78b001/research/scripts/finetune_single_device.py).
The trainer consumes `state/questions/gold`, updates encoder and head with
proper-reward + soft cross-entropy, and removes inherited option-temperature
overrides. **Adapt before running on our data:** its calibration split is
randomized over preprocessed questions, not episodes or menu families; it
hardcodes 1024 state/256 head limits. Group-split externally and add an explicit
calibration input before augmenting episodes. Synthetic 0.94/0.98 targets in
the builder are training smoothing choices, not measured confidence labels.

Use this recipe for the voice router; use
[Laya browser training](https://nandhakishorm.github.io/laya/finetune_browser_agent/)
for current-screen operation/target and genuine DONE/mid-task negatives. They
solve complementary problems. A tuned routing head is not already a UI head,
and a `choice`-only fine-tune does not establish `score`/`noul` quality.

### Needle: product toolsets, grounded phone calls, and explicit no-call rows

The current official [fine-tuning guide](https://cactuscompute.com/blog/finetuning-needle)
demonstrates `dim the kitchen to 10`, derived arguments and `answers: []`;
it also shows a fine-tuned MicroDuck/MuJoCo demo. The robotics caption is
relevant, but a reconstructible MicroDuck dataset/evaluation was **not verified**
in this review. More concrete public starting points are the current
repository's product environments at revision
`9571a58b0ad3d0e4500afa6f4e00cffb8d6d1818`:

- [Media player](https://github.com/cactus-compute/needle/blob/9571a58b0ad3d0e4500afa6f4e00cffb8d6d1818/needle/environments/media_player.py): named-content play vs zero-argument pause/resume/skip, bounded volume;
  missing values, negation, invalid values and unsupported requests.
- [Wearable](https://github.com/cactus-compute/needle/blob/9571a58b0ad3d0e4500afa6f4e00cffb8d6d1818/needle/environments/wearable.py): closed sender/workout sets, verbatim message text, refusal when sender/text/type is absent.
  Its reply tool is a schema example; CyanBridge retains its own send approval.
- [Smart home](https://github.com/cactus-compute/needle/blob/9571a58b0ad3d0e4500afa6f4e00cffb8d6d1818/needle/environments/smart_home.py): bounded room/action values, no invented defaults. The source notes
  an `office`/`off` enum collision: test names and partial-word matches.

These are schema/acceptance fixtures with dummy `ok` callbacks, **not trained
Tasker trajectories**. Their [harness](https://github.com/cactus-compute/needle/blob/9571a58b0ad3d0e4500afa6f4e00cffb8d6d1818/needle/environments/_harness.py)
case-folds strings, sorts calls and maps validation failures to `[]` even in
the default run. Our evaluation must separately retain raw/suppressed calls,
exact literal spelling and UI action order; it must not credit that masking
as learned abstention. Their instruction-style `SYSTEM` examples also differ
from the official facts-only system guidance; freeze and evaluate our own
facts-only renderer rather than assuming interchangeability.

[DroidCall](https://github.com/UbiquitousLearning/DroidCall) provides schema-first
Android **Intent** data generation and conversion scripts;
[Google Mobile Actions](https://huggingface.co/datasets/google/mobile-actions)
provides text-to-Android-system-tools traces with `train`/`eval` metadata.
These are closer to voice capability/argument parsing than browser benchmarks,
but contain no current Tasker node binding. Use their schema/slot methods for
a separate capability-routing dataset; an Intent benchmark score does not
verify a UI tap. Preserve official splits and exclude their eval rows from
training. Their generation/random-split defaults do not satisfy our grouped
voice/UI holdout requirement. Do not bulk-import already-inspected benchmarks.

Cactus's DroidCall tuning plots use **forced calls** for base and tuned models
per its guide. They do not measure refusal on our missing-control cases.
Our `[]` evaluation must run without forced calls or triggers.

The inspected [Needle trainer](https://github.com/cactus-compute/needle/blob/9571a58b0ad3d0e4500afa6f4e00cffb8d6d1818/needle/model/finetune.py)
now includes held-out exact-call scoring under export-like W4 numerics as well
as validation loss; its default split remains random over examples. For an
already group-split training file, the inspected CLI supports `--val-split 0`.
Evaluate the separate development/calibration cohorts with our harness and
select epochs there. Verify flags against the installed pinned version; this
source review is not a rerun of our earlier pilot.

## What Tasker really supplies, and what parsing must preserve

Source reviewed: `tasker/CyanBridge_LocalAgent_Tasker.prj.xml`,
`TaskerAgentContract.kt`, `UiActionCandidateBuilder.kt`, `UiActionDecisionState.kt`.

| Layer | Current behavior | Proposed model-data rule |
|---|---|---|
| AutoInput arrays | Aligns text, view-ID and coordinate arrays by index; uses max length | Record array lengths/alignment diagnostics. Do not independently sort/filter arrays and re-pair their values. A missing entry stays missing. |
| Export caps | First 160 nodes; 240 characters/text, 160/ID; summary ≤6000 characters | Record upstream clipping separately from model clipping. Audit targets omitted at either stage. A clipped suffix or absent row does not prove absence from the actual screen. |
| Geometry | Parses coordinate pair; absent/unparsed becomes `(0,0)`; exports point as zero-area `bounds` | It is a tap point, not measured control rectangle. Preserve coordinate-parse validity in a future sidecar/contract. Reject invalid/out-of-display bindings before candidate eligibility. `(0,0)` cannot establish validity by itself. |
| Flags/roles | Export writes clickable/editable/scrollable/password/checked/focused `false`; class/hint/description empty, depth 0 | These flags are **unreported**, not measured negative facts. Existing Kotlin Boolean parsing erases that distinction. Add field-presence/provider provenance before rendering `unknown`; do not claim the current wire format already does this. |
| Candidate construction | Goal relevance/ID/control heuristics; four node candidates, ≤8 total; labels clipped to 40 characters | Keep full redacted evidence privately; retain semantic distinctions in model labels. Describe ID-derived field/phase as a heuristic, not reported focus/editability. A label alone is not proof of actionability. |
| State construction | Keyword-ranked visible facts, eight labels, 600-character total cap | Avoid losing negation/correction/literal endings. Measure tokenizer limits on the actual checkpoint; character caps are not token budgets. |

Normalize whitespace/case only for matching; retain original user literal and
display text. Duplicate text is not a duplicate target: merge only provably
identical bindings, and keep same-label nodes separate when IDs/points differ.
When labels are indistinguishable under the exported evidence, defer/clarify.
Do not add “selected,” “focused,” “playing,” “enabled,” or “search suggestion”
as measured facts unless the source supports them. Record app-specific phase
inference separately and test its errors.

Keep `request_id`, snapshot hash/time, node index, ID, valid tap point and a
task/cancellation generation in the **host binding table**, outside the model
prompt. Re-observe after each action and invalidate stale replies/changed
bindings. Type actions need a verified addressable field and literal span;
exact text appearing elsewhere on the page is not evidence that the intended
field was filled. The current builder's page-wide `targetAlreadyVisible`
heuristic is an explicit ablation target.

Proposed compact input: utterance/current subgoal, app, essential observed
facts, last checked result, plus 2–4 eligible choices; Needle ≤5 directly
rendered tools. Selection cannot restore a gold target removed by filtering:
log full observed pool → eligible pool → budgeted pool, reasons, token usage
and target recall at each boundary. A callback timeout means re-observe or
backend failure; it does not generate an imaginary missing-control screen.

## Paired training examples for our contract

These examples are **synthetic illustrations** using declared eligible
bindings. They must be replaced by verified Tasker episodes before training.
Laya's training `gold` differs from the existing evaluation `expected` field.

### One voice request becomes several fresh decisions

Example utterance: “Open YouTube and search for Linus Tech Tips. Don't use
voice search.” Keep both the exact search span and the voice-search prohibition
when the planner/recipe derives current steps. A normalized subgoal must not
erase the original restriction. An illustrative sequence is:

| Current observed condition | Current decision | Evidence required before advancing |
|---|---|---|
| Target app installed; another app foreground | Select bounded `open_youtube` capability | Fresh foreground observation identifies YouTube |
| YouTube exposes distinct Search and microphone labels | Select bound Search, excluding voice search under the request constraint | Fresh observation exposes an addressable search-entry target |
| Search-entry target verified; several eligible actions remain | Select literal entry into that bound target | Independent checked field content equals the requested literal; merely seeing the phrase elsewhere is insufficient |
| Query filled; original goal requires submitted search | Select an observed submission control or separately verified enter primitive | Search results for the exact query are observed; a RETRY page is failure, not DONE |
| Results checked against the task's completion predicate | Propose DONE | CyanBridge verifies submitted-search goal; a later “play a video” goal would remain unfinished |

If any required control/evidence is unavailable, record the boundary and defer
or re-observe. This is not an assertion that the current Tasker profile exposes
field values or submission state on every YouTube screen. Do not fabricate
those intermediate facts from this example.

### Model-format examples

Voice routing example: current installed capability set, literal utterance,
explicit unsupported/clarify/conversation choices. Keep approval replies in
the existing context-bound approval state machine, outside generic routing.

```json
{"state":"Utterance: open YouTube\nAvailable app: YouTube","questions":{"route":{"type":"choice","instructions":"Choose the supported request, or clarify/other if the utterance does not specify one.","criteria":{"open_youtube":"Open the installed YouTube app","clarify":"Target or request is ambiguous","other":"Conversation or unsupported request"}}},"gold":{"route":{"label":"open_youtube","probabilities":{"open_youtube":1.0,"clarify":0.0,"other":0.0}}}}
```

One-hot `gold` here represents a reviewed class, not a calibration target or
confidence claim. If soft supervision is used, record how it was obtained;
do not turn guessed teacher percentages into verified action probabilities.

UI selector example: two same-title targets, distinct observed labels. The
host maps choice keys/tool names to this snapshot's bound node; target IDs
must not encode gold rank. No callable function is executed by this JSONL.

```json
{"state":"Current step: tap Linus Tech Tips, not Edit suggestion\nApp: YouTube","questions":{"action":{"type":"choice","instructions":"Choose one eligible action for the current step; defer if the evidence cannot distinguish a target.","criteria":{"tap_suggestion":"Tap visible label: Linus Tech Tips","tap_edit_suggestion":"Tap visible label: Edit suggestion Linus Tech Tips","defer":"No unambiguous eligible action"}}},"gold":{"action":{"label":"tap_suggestion","probabilities":{"tap_suggestion":1.0,"tap_edit_suggestion":0.0,"defer":0.0}}}}
{"query":"Tap Linus Tech Tips, not Edit suggestion","system":"device: phone; locale: en-US","tools":[{"name":"tap_suggestion","description":"Tap the visible Linus Tech Tips label","parameters":{"type":"object","properties":{},"required":[]}},{"name":"tap_edit_suggestion","description":"Tap the visible Edit suggestion Linus Tech Tips label","parameters":{"type":"object","properties":{},"required":[]}}],"answers":[{"name":"tap_suggestion","arguments":{}}]}
```

Train the same renderer across these contrast sets, keeping all variants of
an episode in its assigned split:

| Voice or state change | Label/host handling |
|---|---|
| “don't tap Linus Tech Tips yet” | Laya defer; Needle `[]`; no UI action |
| “not Linus Tech Tips, choose MKBHD” with both eligible | Choose the requested alternative, not blanket refusal on “not” |
| “search isn't working, open Settings” | A real positive request despite a negative word |
| “tap that one” with unresolved reference or indistinguishable duplicates | Clarification/defer; Needle `[]` |
| Requested control absent from fresh observation; only distractors offered | Defer/`[]`; retain reason `unobserved_target`, not a fabricated node |
| Exact query entered; goal is only query entry vs goal is submitted search | DONE only for entry goal with a checked field-value outcome; unfinished for submitted-search goal |
| Stale observation, missing callback, changed task/cancel | Host rejects or re-observes before inference/execution; pipeline-failure label |

For literal entry, a Needle tool can accept one `text` argument copied from
the current-step query, or use a zero-argument binding with the host literal
fixed beforehand. Compare both; keep the exact span in the input and verify
it in code. Never ask either model to invent text/coordinates or derive a
recipient from a partial ASR name. Needle `[]` alone cannot distinguish DONE,
WAIT, clarification and refusal; those statuses need host labels/oracles.

Illustrative literal row, only after the host established the eligible field:

```json
{"query":"Enter Linus Tech Tips in the search field","system":"device: phone; locale: en-US","tools":[{"name":"enter_search_text","description":"Enter text into the current eligible search field","parameters":{"type":"object","properties":{"text":{"type":"string","description":"The search text from the request"}},"required":["text"]}}],"answers":[{"name":"enter_search_text","arguments":{"text":"Linus Tech Tips"}}],"reasoning":"'Linus Tech Tips' -> text"}
```

This single-tool row tests span copying, not competing-tool selection. Evaluate
both separately and compare the returned string to the host's approved literal.

## Collection and training changes to make next

1. **Collect voice routing and UI selection as linked, separate records.**
   Save utterance/session/speaker-group IDs, actual ASR output/version and
   exact literal spans, supported capability menu, route/subgoal provenance,
   Tasker snapshot/candidates and checked outcomes. Link transcript corrections
   and all screen decisions to the same episode; keep private audio optional.
2. **Gather real ASR as well as clean text.** Include short/long requests,
   fillers, corrections, indirect needs, chatter, negation, code-switching,
   unavailable apps and unresolved references. Synthetic text noise is a
   labelled augmentation. If ASR deletes “not” or changes a recipient/name,
   mark it as lost intent evidence; the downstream model cannot recover it
   confidently from a keyword match. Measure clean→ASR action disagreements.
3. **Freeze parsing and rendering before fitting.** Emit an evidence ledger
   and candidate-drop report; reserve bytes/tokens for the full current
   instruction and literals. Overflow/indistinguishable truncation defers.
   Do not use post-action facts in the pre-action input.
4. **Split original goals/episodes/menu and screen families before augmentation.**
   Also reserve speakers/sessions for the actual voice test and keep parallel
   translations together. Use independent train, development, calibration
   and sealed final cohorts, sized for the declared supported scope. Adapt
   upstream random validation/calibration splits rather than copying them.
5. **Laya:** retain frozen-head baseline, then compare the voice recipe's
   encoder+head update on sufficient new data. Start with bounded action-choice
   supervision; add compatible operation/target heads when the observation
   supports them. Permute choices and use unseen capability/target labels.
   Calibrate the exact deployed renderer/runtime on a separate cohort.
6. **Needle:** train exact eligible `tools` and one call or `[]`, with substantive
   no-target/negated/ambiguous cases. Local guide starts with hundreds of clean
   selection examples, thousands for argument grounding; label diversity
   matters more than duplicating templates. Compare 20L base/tuned first,
   then smaller matched depths. Local LoRA has no usable confidence head;
   hosted confidence also needs independent phone validation.
7. **Report each failure stage.** Voice-route wrong actions/clarifications,
   exact-span accuracy, candidate recall, UI accepted precision/coverage,
   no-call false actions, premature DONE, stale-response rejection, observed
   execution/outcome and full voice-to-verified-task latency. Separate raw
   model quality from filters/gates and compare to deterministic + Pro baselines.

No verified public end-to-end recipe covering **recorded voice + Tasker UI
observations + these two models** was found in this review. The combination
above is an adaptation grounded in inspectable voice/menu training, device
tool schemas and UI/outcome collection; its reliability must be established
on our own new episodes.
