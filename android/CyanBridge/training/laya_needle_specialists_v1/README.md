# Laya / Needle specialist pilot and ablations

Campaign: **2026-10-03**, explicitly requested by the user. Offline training on
Luciano (`deepspectra`), two RTX A4000 16 GB GPUs. Preflight found both idle;
PhotoBioPC's RTX 5060 Ti was occupied. Each GPU lane has a separate environment
under `/tmp/opencode/cyanbridge-specialists-20261003/`; no package installation
into base conda and no local-GPU training.

The user's subsequent guidance changes the primary training contract to
[state-defined progressively narrowed choices](../../docs/BOUNDED_SPECIALIST_DECISIONS.md).
The first flat public/config results below are retained as diagnostics. The
new bounded-specialist comparison uses host-derived options, explicit
control choices and full self-routed episodes, starting again from base weights.
Its initial version-2 Laya fits give **85/120 self-routed hierarchical episodes** versus
**90/120 state-matched flat episodes**; the former's 301/352 oracle-turn choices
must not be substituted for full-path accuracy. See the linked contract for
the current results and reproduction. The recommended **version 3** adds
explicit Live running/availability, Tasker readiness and download-in-flight
facts; its fits start again from base with identical oracle/rollout rendering.
Completed version-3 Laya: **86/120 hierarchical / 91/120 flat** self-routed
episodes, with **305/352** hierarchical oracle turns. The explicit status fix
does not make narrowing outperform flat whole-path selection in this pilot.
Completed version-3 Needle: **9/120 hierarchical / 12/120 flat** self-routed
episodes, versus base **14/120 / 11/120**. Its hierarchical oracle score is
**68/352**; lower training loss and better flat output validity do not produce
useful goal selection. The complete [v3 summary](bounded-v3-summary.json) contains
all eight base/tuned model/view comparisons and verified report/model hashes.
The [v2 diagnostic summary](bounded-v2-summary.json) retains earlier results
and explicitly identifies the earliest Laya reports' missing hash metadata.
The [bounded order ablation](bounded-v3-order-summary.json) preserves host IDs
while reversing presentation: tuned Laya is **92/120 flat / 82/120 hierarchy**;
tuned Needle is **16/120 / 20/120**, still weak. A
[teacher-forcing diagnostic](bounded-v3-teacher-forcing-summary.json) exposes
the `<tool_call>` target versus `<think>` first-token tendency; matched
reasoning-target fitting is recorded in a separate
[four-report Needle ablation](bounded-v3-reasoning-summary.json): **18/120 flat /
7/120 hierarchical** episodes. It improves flat selection modestly but does
not resolve native choice/path accuracy. All variants remain shadow-only.
The tuned flat Laya checkpoint's [FP16/BF16/Q8 comparison](laya-bounded-v3-flat-precision-summary.json)
measured **645 MiB peak VRAM / 94/120 episodes** for BF16-resident, versus
**1,515 MiB / 93/120** for the FP32-weight/BF16-autocast reference; FP16-resident
scored 91/120 at the same peak footprint. Q8 scored **79/120**, changed 23/120
choices, and made 8/64 control cases substantive on the synthetic development
set; its CPU ONNX file was larger than the original FP16 checkpoint. None of
these measurements is an Android runtime result.

This is a **voice capability selector** plus **native configuration pilot**.
No Tasker UI checkpoint is fitted: the existing real phone pools still cannot
support independent training/calibration/final cohorts. Product examples are
generated text, not recorded voice or observed setting changes. Current
production routing/approvals are separate from this offline experiment.

## Data and model provenance

Remote campaign and earlier local artifact root:
`/tmp/opencode/cyanbridge-specialists-20261003/`. Newly retrieved bounded Needle
artifacts/full reports use `/mnt/seagate/ML/cyanbridge-specialists-20261003-artifacts/`
to avoid further NVMe checkpoint growth. A remote SHA-256 manifest verifies
50 copied bounded model/adapter/training/report files on Seagate.
Keep reference corpus bytes/checkpoints/full prediction reports external to Git.
The [source catalog](../laya_needle_dataset_sources_v1/catalog.json) gives licenses.

| Input | Pin / byte hash |
|---|---|
| Mobile Actions JSONL | Revision `e920309bc2acbc2e99a5e3201cf37df2b9fd9151`; SHA-256 `91d251ee958cfd295af6c4504c236a3a1ad19517de240c3bc680bacfcbf7e7d9` |
| DroidCall structured train | Revision `42563ae614280d2891d57f1e7057c4bc50dd27bd`; SHA-256 `4cb2d5691c1b95b0908c59efcb361c8a9c9b12f0a4d182acfc2df0ccc92e6d3b` |
| Laya source | `4066d5d5fbf08b66c6757ddeedbd797bd7655bc0` |
| Laya multilingual base weights | SHA-256 `9d628fd971b700382ac6f65920a86f149777b2e748e0c955fb3b19695aa8f204` |
| Needle source | `94df9999d58a67ff29f032a41f31307c05554bd6` (existing provisioned trainer, rather than the later research-review pin) |
| Needle base safetensors | SHA-256 `c234c70dccc7a9115e7c41ac2e41d3655fea3b85c245dd898b46179fb90c6c0c` |
| Needle released 20L archive | SHA-256 `c9d915eca282ed42d1a09b143b592adb4cc6744ffe2d294adf5cfc5548170c38` |

Model files transferred through rsync and the remote base hashes verified.
Laya uses provisioned PyTorch 2.7.0+cu128 with transformers 4.53.2 in an isolated
overlay venv. Needle uses JAX 0.6.2 / Flax 0.10.7 / Optax 0.2.8 in its own venv.
All compiler/download caches are redirected to the writable `/tmp` campaign
directory; `/home/luciano/.triton/cache` was read-only during initial preflight.
The aborted preflight's logs remain under `aborted-preflight/` on the remote.

## Cohorts and counterfactuals

| View | Train | Development | Calibration | Sealed generated cohort |
|---|---:|---:|---:|---:|
| Voice positive-only | 1,200 | 480 | 480 | 480 |
| Voice + negatives | 3,600 | 480 | 480 | 480 |
| Config positive-only | 132 | 210 | 105 | 105 |
| Config + negatives | 420 | 210 | 105 | 105 |

- Public single-call source rows only. **961 internal Mobile Actions eval rows
  excluded**, multi-call rows excluded, undeclared answers and result-reference
  heuristics excluded. Source arguments are **not** learned as phone literals;
  only the requested capability is supervised through non-executing zero-argument
  route tools.
- Source query / delexicalized goal families are joined by exact matches and
  verified high-overlap lexical matches **before** split assignment and sampling.
  From 13,186 eligible originals, the builder finds 10,766 approximate groups;
  the bounded selected cohort spans 1,625 groups across all splits. This is not
  exhaustive semantic paraphrase detection or a speaker/session holdout.
- Each selected original produces one positive, one unavailable-menu case and
  one synthetic `Do not carry out this request` case, in the **same group**.
  Wider/equivalent sibling APIs are excluded as distractors to avoid falsely
  declaring the original capability unavailable. This manually defined
  compatibility map is a pilot assumption, not a complete Android ontology.
- The 1,200 voice positives comprise **934 DroidCall / 266 Mobile Actions**.
  Sampling balances source/operation buckets, so the two corpora are not equal
  halves. Generated menu/negation variants triple those counts.
- Public-source holdouts are disjoint from this fine-tune's rows, but upstream
  base-model exposure to public corpora is not independently established. They
  are not a new private deployment test or an uncontaminated foundation-model
  benchmark.
- Config covers AI wake-word voice/image routes, semantic Gemini Live refresh
  modes, enable versus start for four plugins, Visual Diary interval 1–240 min,
  and translator target language. These are **proposed non-executing tool
  schemas**, not a deployed voice manifest or service dispatcher.
  Lifecycle semantics must be reconciled with the actual dispatcher: the
  current Community Plugins toggle **also starts Walking Aid**, and
  `MainActivity.startNativePlugin` enables several plugins as part of starting
  them. An enable-only pilot label must not be blindly mapped to that UI toggle.
- Product cohorts group eight wrapper/phrasing families (four train, two dev,
  one calibration, one sealed). Core concepts, values and some negative clauses
  remain shared. This tiny generated pool cannot supply credible independent
  product calibration or a final real-voice test. The sealed cohort is a
  generated regression fixture; new real speech is still required.
- Development files are byte-identical across positive/negative ablations.
  Voice development has 160 positives / 320 negatives; config has 66 / 144.
  Always-defer therefore scores **66.7% / 68.6%**. Report positive accuracy,
  false actions and raw precision alongside aggregate accuracy.
- A separate **24 hand-written development challenge** set tests corrections,
  positive requests containing negative words, indirect instructions, ambiguous
  settings and unsupported units/plugins. It is excluded from fitting.

Integrity checks audit every cohort's hashes/group boundaries/model-view
alignment/schema validity. Sealed metadata is audited but no sealed/calibration
inference occurs. Needle full rendered lengths are ≤297 tokens for voice and
≤260 for config; training cap is **512**, with zero clipped examples. Laya
train/dev rendered inputs are ≤211/155 tokens and every option fits its 48-token
per-choice cap. This is host context feasibility, not Android runtime parity.

## Ablation matrix

| Model | Experiment | Budget / selection |
|---|---|---|
| Laya | Base → positive-only head → negatives head, per specialist | Voice: 300 optimizer updates each. Config: 120 each. Effective batch 16 / microbatch 4; head LR 1.2e-4. Three development checkpoints, best exact-call score selected. |
| Laya | Frozen encoder vs encoder+head on voice/config + negatives | Same 300 voice / 120 config updates, effective batch 16; encoder LR 1e-5; gradient checkpointing; same base, schema, data and development cohort. Config encoder fitting added after the frozen-head coverage failure was inspected. |
| Needle | Base → positive-only LoRA → negatives LoRA, per specialist | Rank 16; batch 16; LR 1e-4; `--val-split 0`; seed 20261003. Voice 6 positive epochs vs 2 mixed epochs = **450 updates each**. Config 9 vs 3 = **81 updates each**. Fixed training budget, no random validation split. |
| Needle | 20L vs 8L vs 4L, base and negatives adapter | Adapter trained at 20L, exported at smaller depths; same-depth base comparisons. This measures down-export transfer, not independently trained small-depth adapters. |
| Both | Reversed candidate/tool order | Same development cases/bindings, changed presentation order. |
| Both | Opaque bound IDs instead of semantic tool/choice names | Same descriptions, schemas and requests; fresh IDs per case. Unknown Needle names remain errors rather than being rebound to a gold call. |
| Deterministic | Lexical overlap + explicit-negation rule | Fixed baseline; same host value binder for configuration; no model/GPU required. |

Update matching is **within each model's data ablation**, not equal FLOPs across
Laya and Needle. Negatives change positive-example exposures, and Needle's
epoch-based schedule changes the number of reshuffles. Those are disclosed
limits of this first ablation.

Laya config predicts the operation and uses an independent lexical host binder
for its arguments. Needle config generates typed arguments. Exact-call metrics
therefore compare two pipelines, not two equivalent text-generation models.
Both preserve spelling, argument types and call order; invalid output is an
error, never silently counted as a successful empty call. Raw Laya classifier
false actions are reported separately from argument-binder rejection.
Needle's native envelope can put attempted calls in `suppressed_calls` and
return `function_calls: []`. Primary proposal metrics include both lists;
engine-delivered metrics are reported separately. Native errors/truncated
responses are invalid, never successful no-calls. The original base config
score of 80/210 falls to **67/210 reported-proposal exact calls** after removing
two false successes on truncated responses and eleven validator-suppressed
false actions. Saved envelopes support this mechanical rescoring without new
inference. The interface does not expose a pre-validation decoder token trace;
these are **C-ABI reported proposals**, not a claim to recover that trace.

## Reproduction

Commands below assume the pinned sources/raw bytes/checkpoints and isolated
environments described above already exist:

```bash
ROOT=/tmp/opencode/cyanbridge-specialists-20261003
python3 tools/benchmarks/test_specialist_training.py
python3 tools/benchmarks/make_specialist_training.py \
  --raw "$ROOT/raw" --out "$ROOT/data" --train-limit 1200
PYTHONPATH=/tmp/opencode/needle-source /tmp/opencode/needle-train-venv/bin/python \
  tools/benchmarks/audit_specialist_training.py --data "$ROOT/data" \
  --needle-source /tmp/opencode/needle-source --out "$ROOT/reports/data-audit.json"
python3 tools/benchmarks/make_specialist_stress.py --out "$ROOT/config-stress"

# Stage sources/data/scripts and environments locally (not the shared Luciano host), then one lane per GPU:
python3 "$ROOT/scripts/run_specialist_campaign.py" --root "$ROOT" --lane laya
python3 "$ROOT/scripts/run_specialist_campaign.py" --root "$ROOT" --lane needle

# After retrieving full reports/models into the local artifact directory:
python3 tools/benchmarks/summarize_specialist_campaign.py \
  --root "$ROOT" --out "$ROOT/reports/summary.json"
```

Evaluation is open **development evidence**. No native setting, phone UI action,
email send or delivery is executed by these commands. No automatic-action
threshold is calibrated from these results. See the
[specialist plan](../../docs/LAYA_NEEDLE_SPECIALISTS_AND_DATASETS.md) for real
voice collection and per-contract promotion requirements.

## Completed Laya development results

| Variant | Exact calls | Positive exact | Negative false action | Raw emitted-call precision |
|---|---:|---:|---:|---:|
| Voice base | 253/480 (52.7%) | 86.2% | 64.1% | 39.2% |
| Voice positive-only, frozen encoder | 222/480 (46.2%) | 87.5% | 74.4% | 36.1% |
| Voice + negatives, frozen encoder | 336/480 (70.0%) | 31.9% | 10.9% | 57.3% |
| **Voice + negatives, encoder+head** | **469/480 (97.7%)** | **95.0%** | **0.9%** | **96.2%** |
| Config base + host binder | 111/210 (52.9%) | 90.9% | 49.3% | 44.8% |
| Config positive-only, frozen encoder | 105/210 (50.0%) | 90.9% | 52.1% | 43.5% |
| Config + negatives, frozen encoder | 115/210 (54.8%) | 37.9% | 27.1% | 37.3% |
| **Config + negatives, encoder+head** | **191/210 (91.0%)** | **77.3%** | **2.8%** | **91.1%** |

Full encoder updates are materially more useful than the frozen-head fits in
this pilot. Positive-only tuning increases false actions; the mixed frozen-head
fits trade away excessive positive coverage. The best voice result still has
**three false actions on 160 unavailable-menu rows**, five false deferrals and
three wrong operations on the 160 positives. All 160 **templated** negations are
correct; this does not establish correction/general-negation understanding.

Presentation/binding checks:

- Reversed choices: voice encoder+head **465/480 (96.9%)**; config
  encoder+head **193/210 (91.9%)**.
- Opaque per-case choice IDs, same descriptions: voice **454/480 (94.6%)**,
  raw call precision **89.3%**; config **157/210 (74.8%)**, raw precision
  **72.7%**. Matched single-example semantic-ID references are 469/480 and
  191/210. Single-example base voice is 254/480 versus batched 253/480;
  the one-case numerical difference is recorded, not a new example.
- Hand-written configuration challenges: base **10/24**, positive-head
  **10/24**, mixed head **8/24**, encoder+head **11/24**. Encoder+head gets only
  **5/15 positive exact calls**. The aggregate template gain does not transfer
  reliably to corrections, word-number/unit changes or conversational wording.
  The host value binder is also limited: multiple plugin/language mentions,
  competing interval values and word numbers are not reliably resolved.

These checkpoints remain **shadow/development artifacts**. Neither the raw
96.2% voice call precision nor generated config results meet the existing real
deployment precision/data gate. Improving context/schema rendering and actual
voice/config collection is more important than another fit on these same
templates.

## Completed Needle first-pilot development results

These are C-ABI **reported proposal** scores, including suppressed calls and
excluding false successes on runtime errors. No upstream validation masking,
case folding, sorted calls or argument repair contributes a success.

| Variant | Exact calls | Positive exact | Negative false action | Reported-call precision |
|---|---:|---:|---:|---:|
| Voice 20L base | 218/480 (45.4%) | 91.2% | 77.5% | 36.0% |
| Voice positive-only LoRA | 198/480 (41.2%) | 93.8% | 85.0% | 34.7% |
| Voice + negatives LoRA | 216/480 (45.0%) | 95.0% | 80.0% | 36.6% |
| Config 20L base | 67/210 (31.9%) | 92.4% | 94.4% | 30.2% |
| Config positive-only LoRA | 66/210 (31.4%) | 89.4% | 92.4% | 29.6% |
| Config + negatives LoRA | 66/210 (31.4%) | 89.4% | 91.7% | 29.8% |

Down-exporting the mixed 20L adapter to 8L/4L yields voice **136/480 / 140/480**
and config **55/210 / 35/210**. Smaller-depth config exports have **61 / 59
invalid rows**, so apparent lower false-action rates are partly decode errors.
Reversed 20L menus yield voice **214/480** and config **68/210**. These results
do not establish a useful abstaining specialist.

The voice mixed training loss fell to ~0.05 without corresponding native
refusal gains. A bounded twelve-row input-format diagnostic gives identical
4/12 reported-proposal exact calls for wrapped/flat tool schemas and null/empty
system strings, with identical prefix lengths. SentencePiece versus archive
tokenizer checks find **zero vocabulary ID mismatches (8,192 pieces)** and zero
encoding mismatches on the rendered diagnostic prompts/targets. These checks
do not prove complete training/native decoding parity or identify the cause.
The later hierarchical experiment adds explicit status/control-choice tools
and uses the same system instructions at training and inference.
