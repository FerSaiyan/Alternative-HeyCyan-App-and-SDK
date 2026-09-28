# Training Laya and Needle for Tasker-driven phone automation

Status: research/training recipe, **not a trained phone model** (2026-09-27).
The six captured YouTube screens are a diagnostic replay, not a training set.
The generated `local_agent_mobile_actions_v1.jsonl` corpus is a useful frozen
baseline, not evidence of generalization to live Tasker screens. See
[`TASKER_SMALL_MODEL_CONTEXT.md`](TASKER_SMALL_MODEL_CONTEXT.md) for the current
observation contract and shadow timings.
The opt-in synthetic pilot dataset, reproducible training commands and results
are in [`training/phone_ui_synthetic_v1/README.md`](../training/phone_ui_synthetic_v1/README.md).
The separate [real-emulator Artemis pool](../training/artemis_real_v1/README.md)
contains 29 verified navigation/tab/query-entry episodes, **not Tasker
observations or an independent phone-policy holdout**. Its three connected
screen families cannot supply a credible train/calibration/final-test split.
The separate [Tasker Settings episode](../training/tasker_real_v1/README.md)
verifies one reversible tap and demonstrates why a pre-action home-screen
check matters; one episode is diagnostic, not a trainable Tasker corpus.

## Transferable examples and what they establish

| Source | Useful mechanism | Limit for CyanBridge |
|---|---|---|
| [Laya browser fine-tune](https://nandhakishorm.github.io/laya/finetune_browser_agent/), [current v17s card](https://huggingface.co/cklxx/laya-browser) and [pipeline artifacts](https://huggingface.co/cklxx/laya-browser/tree/main/code/finetune) | Early recipe: 421 real pages, 5,244 reverse-generated goals, 700 **executed** DONE states, 659 mid-task negatives, 7,296 Mind2Web steps. Newer v17s also uses NNetNav, scripted trajectories, ~70k synthetic webgym steps, plus harness fixes; puts element role/label/current value in option criteria, not duplicated state. | The older write-up's 62% browser live completion is **superseded**. The current card reports 54/54 on 18 unseen-site short tasks, 41/48 on the older suite, but only 2/10 flights and 3/10 hotels on long synthetic forms. Earlier DAgger samples included **evaluation-suite goals**; the v15s clean retrain removed them. None are Android measurements. |
| [Jev Ultrafast](https://github.com/browser-use/jev-ultrafast) (also [cataloged on Ship With Jev](https://www.shipwithjev.com/builds/jev-ultrafast)) | One fresh indexed action space per observation; operation and *compatible* targets decided together; separate small LLM writes field text; recheck target freshness and independently verify DONE. | Its DOM knows roles/field values/visibility and can keep node references atomically; our compact AutoInput contract does **not** expose the same metadata. Its [Flights timing](https://www.shipwithjev.com/builds/browser-use-flights) is three repeats of one browser task, not phone latency or reliability. |
| [Ship With Jev engineering guide](https://www.shipwithjev.com/guides/jev-engineering) and [Hunch catalog entry](https://www.shipwithjev.com/builds/hunch-browser-agent) | Closed operational questions, an honest unclear/blocked answer, versioned criteria, code-checked outcomes and escalation for uncertain/irreversible steps. | Ship With Jev is an **independent, author-reported catalog**, not a source of model-training weights or our own benchmark. |
| [Needle 3 fine-tuning](https://cactuscompute.com/blog/finetuning-needle), [tool design](https://cactuscompute.com/blog/designing-tools-for-needle) and [runtime contract](https://cactuscompute.com/blog/needle-python-docs) | Local LoRA on `query`/`tools`/`answers` JSONL, short argument-derivation `reasoning`, negative `answers: []` cases; build a tuned `.cact` and evaluate different layer depths. Hosted platform instead trains the full model and confidence head. | Needle is a *tool-call generator*, not Laya's parallel option scorer. Local tuned weights return `confidence: None`; base confidence/thresholds cannot be reused. Hosted training uploads data; avoid uploading raw personal Tasker traces. |

## Collect labels that distinguish training from prefiltering

An offline row is **one observed decision**, before the action, with: an
episode ID; foreground app/version/locale; goal and current subgoal; snapshot
timestamp and a stable snapshot hash; short recent actions/results; the entire
observed eligible action set; a human-reviewed *operation + observed target*
(or `DEFER`, `WAIT`, `BLOCKED`, `DONE`); and a separate *post-action* observation
and independent outcome label. Preserve original candidate bindings privately
for review, but strip account names, email addresses, message bodies, tokens,
coordinates, and raw personal screen text from exported training rows. Version
the renderer, allowed-action policy, labeling rubric and model checkpoint.

Collect episodes by running **Tasker/AutoInput**, not by reconstructing a UI
from Android fixture data. Mix YouTube launch/search/query-entered/suggestion/
result/channel/player, Gmail **read-only compose preparation** and inaccessible
composer, permission/chooser, interruption/timeout, and unrelated apps. Include
duplicate labels, misleading recommendation controls, changed focus, stale
observations, long localized labels, screen text instructing an agent to do
something else, and cases with multiple *plausible* targets. Never put actual
Send, discard-draft, or other external side effects into the trainable tool
catalogue: policy handles them before inference, and approved send remains
behind a fresh observation and independent delivery verification.

The browser recipe's crucial distinction: create DONE only from a **real
post-action screen** with all task requirements independently verified. Also
capture that same sort of post-action screen with a *new unfinished subgoal*,
so the model cannot learn “has history ⇒ DONE”. Capture filled search fields
that still need submit/choose-suggestion and results that still need opening.
On-policy correction means run a candidate model in **shadow**, review the
exact observed mistake and label the correct next action. Do not trust the
existing planner or the model as an unreviewed teacher; a reviewer can mark
the true answer `unknown` and exclude it from supervised loss. The current
[Laya v17s card](https://huggingface.co/cklxx/laya-browser) discloses that
earlier DAgger rows leaked suite-A goals into training (152/331 cases); its
clean retrain removed that set. **Never** sample corrected cases from a held-out
task suite and then report that suite as unseen.

Reverse goal generation can expand **non-sensitive** observations: choose an
observed candidate, ask a local teacher to phrase a plausible user subgoal,
then human-check that it really needs that candidate. Split by **episode and
app/page family before augmentation**; keep all paraphrases, screenshots and
translated near-duplicates in the same split. Keep training, temperature-fit,
and final held-out sets disjoint. Record enough examples per minority class
(search submitted, suggestion selected, scroll, DONE, defer) for their own
metrics; simply over-sampling a tiny original set is not new evidence.

## Laya: learn an operation and a compatible node

Match [Jev Ultrafast's operation/target shape](https://github.com/browser-use/jev-ultrafast/blob/main/jev_ultrafast/model.py):
one typed `choice` head for `CLICK`, `TYPE_LITERAL`, `SCROLL`, `WAIT`,
`DONE`, `DEFER`; a `click_target` head for observed click candidates and a
`type_target` head only for fields actually addressable through Tasker. Only
the target head corresponding to the chosen operation can execute. The model
state contains the current subgoal, app, short visible page facts, field value
if observed, and recent actions (enough to avoid loops); criteria contain
`[index] role-if-known label current-value-if-known`. An absent accessibility
flag is **unknown**, never inferred as proof of editability/clickability.
The newer browser model also added `PRESS_ENTER` after a filled focused field,
filtered covered controls, retried navigation observations and suppressed
repeated failed choices; these are observation/execution lessons to test on
Tasker, not extra facts the model can infer from missing UI attributes.
`DONE` is a suggestion checked by application evidence; `DEFER` is a legitimate
label on missing or ambiguous controls. Keep choices within the target/head
token budget; measure **gold target recall before inference** rather than
claiming accuracy when code has filtered away every distractor.
Supervise/evaluate a target head **only for its matching gold operation**;
speculative target predictions for an unchosen operation are not real actions.

An **evaluation** row in [Laya's documented JSONL format](https://nandhakishorm.github.io/laya/evals/)
looks like this (illustrative, not a training item or a claimed phone label):

```json
{"state":{"subgoal":"choose the Linus Tech Tips search suggestion","app":"YouTube","recent_actions":["entered Linus Tech Tips"]},"questions":{"operation":{"type":"choice","instructions":"Choose one current-screen operation; defer if no observed control advances this step.","criteria":{"CLICK":"Tap a current observed control","DONE":"All requirements visibly met","DEFER":"No safe observed target"}},"click_target":{"type":"choice","instructions":"Which current observed control matches the subgoal?","criteria":{"n8":"[8] search suggestion · Linus Tech Tips","n9":"[9] edit suggestion · Linus Tech Tips","n10":"[10] navigation · Navigate up"}}},"expected":{"operation":"CLICK","click_target":"n8"},"tags":["youtube","suggestion","three-targets"],"language":"en"}
```

Use the [browser fine-tune pipeline](https://huggingface.co/cklxx/laya-browser/tree/main/code/finetune)
or [official notebook](https://github.com/NandhaKishorM/laya/blob/main/notebooks/laya_finetune_typed_decisions_2xT4_kaggle.ipynb)
as a template for formatting and training, **not** as a drop-in phone trainer:
their data loaders and head budgets are for their browser/typed datasets.
Freeze one phone renderer and write its supervised encoder/head builder and
holdout evaluation first. Train baseline, then task-specific fine-tune; compare
held-out operation accuracy, target top-1, DONE/defer false positives and
episode success before changing head size. The browser run used `head_max_len`
768 and post-hoc temperature fitting (v17s trained more epochs and data than
the older v10s write-up); this is a starting experiment,
not a phone setting. Its notebook fits on training-derived samples: keep a
**separate** phone calibration set. Remove inherited
`temperature_by_options` when exporting a new per-type fit or it can override
that fit. The current Android LiteRT prototype has a *different* 256-token
window; retraining on a 768-token head does not make that deployment usable
without an explicit compatible export and on-device parity test.

## Needle: train the exact current-step tool contract

Needle training rows have `query`, `tools`, `answers`, optional `reasoning` and
`system`. Each current-step query should be short but contain **evidence for
every required argument**; the system turn holds only facts (app/locale). Use
one bounded tool per currently eligible action, plain names/descriptions, no
triggers, no arbitrary executable callbacks. For repeated labels, the row must
include an unambiguous distinguishing label or defer. Do not let >5 tools
silently trigger Needle's retrieval to drop the gold tool; score tool recall
before inference, or explicitly use a two-pass selector.

The following **synthetic** row illustrates the format; different screens need
different tool lists. In the agent, `tap_search_suggestion` is a binding to the
fresh observed node, not a general API to click a label string.

```json
{"query":"Choose the visible Linus Tech Tips search suggestion","system":"device: phone; app: YouTube; locale: en-US","tools":[{"name":"tap_search_suggestion","description":"Tap the visible Linus Tech Tips search suggestion","parameters":{"type":"object","properties":{},"required":[]}},{"name":"edit_search_suggestion","description":"Edit the visible Linus Tech Tips search suggestion","parameters":{"type":"object","properties":{},"required":[]}}],"answers":[{"name":"tap_search_suggestion","arguments":{}}],"reasoning":"'Choose' and 'visible Linus Tech Tips search suggestion' -> tap_search_suggestion"}
```

Also train the *same type of toolset* with `"answers": []` for missing
controls, no relevant tool, negated commands, mismatched app and unresolved
ambiguity. In a single-step UI loop two calls are generally a **failure** even
though Needle legitimately supports multiple calls for a multi-action user
request; use one current subgoal per turn and enforce exactly one allowed call
or `[]`. For a typed literal, only use exact user-provided text and include the
literal in the query; all other text generation stays with the planner.

In a dedicated non-`base` environment, after collecting and auditing real
training rows (no model dependencies installed into the base conda env):

```bash
# Official CLI; do not run on the six-screen diagnostic trace.
needle finetune train.jsonl --epochs 10 --out adapter.safetensors
needle build --lora adapter.safetensors --layers 8 --out tuned_8l.cact
# Platform deployment build, once a compatible target is available:
needle build --lora adapter.safetensors --platform android-arm64 --layers 8 --out ./android-build
```

The [official local guide](https://cactuscompute.com/blog/finetuning-needle)
trains rank-16 4-bit-aware LoRA at 20 layers, then exports smaller depths; its
default validation split is 0.1, which is **not** episode-aware. Prepare
separate train/validation/test episode groups beforehand, and verify the CLI's
exact split/eval behavior before fitting (validation loss alone is not task
accuracy). Start with hundreds of clean examples for tool selection and expand
to thousands of varied grounded-value examples if argument errors dominate.
Local fine-tuning leaves `confidence` **unset**; separately calibrate an
application-level abstention rule on untouched phone cases, or compare the
hosted full-model option if uploading suitably redacted data is acceptable.
The current official [device list](https://cactuscompute.com/blog/needle-supported-devices)
includes Android ARM64 and ARMv7, **not Android x86_64**; evaluate a real
compatible device before attributing CPU/latency results to this Pixel AVD.
Verify actual native input-window/prefix budget for the pinned engine and
checkpoint; a JSONL line fitting the trainer's default 1024-token limit does
not prove it fits the previously probed 256-token native context.

## Promotion experiment (ordered)

1. Freeze label/renderer/action-policy versions; establish a zero-shot and
   deterministic-filter baseline. Report how often the correct current action
   **survives filtering**, how many candidates are offered, and how often none
   or only one remains. A five-step single-tool replay is not model accuracy.
2. Fit both models on the *same observed training episodes* in their respective
   documented formats. Compare unseen episodes/apps/locales with swapped option
   ordering, unseen labels, injected page text, suggestions after typing,
   redundant taps, and inaccessible screens. Inspect qualitative failures.
3. Calibrate abstention on its own split; hold the final test set closed.
   Report end-to-end phone task success, unsafe-action rate, candidate recall,
   decision precision at actionable coverage, early/missed DONE, multi-call and
   ungrounded-call rates, and **observation + candidate build + inference +
   execution + verification** p50/p95 (cold and warm separately).
4. Keep the existing approved-email and package policy in CyanBridge. Shadow
   first, then test only a narrow reversible-action slice on a compatible phone.
   Gmail composer observation must work *before* any approved-send continuation;
   neither a fine-tune nor a browser success claim fixes that timeout.
