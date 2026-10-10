# State-defined, progressively narrowed specialist decisions

Contract: **`bounded-specialist-v3`**, 2026-10-03. Implements the user's requested
narrowing policy in offline training/evaluation tooling. The Python reference
is not yet wired to Android voice dispatch or Tasker execution.

## Each turn is a small decision

The host supplies:

1. The finalized request and any explicit user correction.
2. A fresh, versioned status: current scope, selected settings, usable installed
   models, eligible downloads, speaking/session state and supported capabilities.
3. Only the choices valid **at this scope and in this state**.

Laya scores those choices. Needle calls one declared, zero-argument choice
tool. Both see identical request/status text and choice descriptions, and both
use shuffled, bound `option_N` IDs. The model does not generate a setting ID,
model filename, enum value, UI target or executable argument. The host resolves
the selected ID to its already-bound next scope or leaf effect.

This ablation uses one **shared bounded-choice checkpoint per model/view** for
the root and configuration scopes. `status.phase` and `status.scope` identify
the decision role, and results are broken down by stage. Product voice-router
and configuration specialists can use different checkpoints behind the same
choice interface; this experiment does not establish that one shared fit is
the best specialist allocation or pass their separate deployment gates.

Version 3 explicitly includes `liveRunning`, `liveAvailable`, `taskerReady` and
`downloadInFlight`. A missing Start option alone cannot tell the model whether
a live session is already running or unavailable. Version 2's narrower status
is retained for diagnostic reproduction, not the final recommended input.

`clarify`, `no_change` and `defer` are explicit choices. An empty Needle call,
multiple calls, unknown ID or invented argument is invalid output. Native
validator-suppressed proposals still count in model-quality scoring; they
cannot manufacture a correct refusal.

## Local-model example

Request: “Use Gemma 4 E2B for my on-device replies.”

```text
voice_route
  Change persistent settings
    AI settings
      On-device local models
        Active local model
          Select installed usable model
            Use installed Gemma 4 E2B
```

The first turn offers **action families**, not every setting value. Other
eligible families may include an image question now, a voice question now,
starting Gemini Live, stopping a spoken reply, or phone-planner delegation.
The next turn offers supported settings categories; the next offers settings
in the selected category. This is internal deliberation: narrowing does not
require a new spoken user answer at every level.

If the requested model is missing:

```text
Active local model
  Offer compatible model download
    Offer download of Gemma 4 E2B
```

A download offer is a different leaf from model selection. It does not download
anything, mark the model installed, or silently switch the active model.
Actual download completion must refresh the model registry/device assessment,
then produce a new menu before model selection. An unavailable/incompatible
model leads to clarification/fallback; an already-selected usable model leads
to no-change. A bare “change my model” needs clarification about which model.

Only installed **usable**, non-current models appear as selection choices.
Download choices require the host's affirmative capability assessment, an
enabled non-coming-soon catalog entry with a source URL, no download already
in flight, and required token availability. Unknown assessments do not count
as supported. References:

- [`LocalModelCatalog.kt`](../shared/src/commonMain/kotlin/com/fersaiyan/cyanbridge/localmodels/catalog/LocalModelCatalog.kt)
- [`LocalModelsConfigureActivity.kt`](../app/src/main/java/com/fersaiyan/cyanbridge/agent/LocalModelsConfigureActivity.kt), `canDownload`
- [`LocalModelStorageRepository.kt`](../app/src/main/java/com/fersaiyan/cyanbridge/localmodels/storage/LocalModelStorageRepository.kt)
- [`LocalModelsPresentation.kt`](../shared/src/commonMain/kotlin/com/fersaiyan/cyanbridge/shared/localmodels/LocalModelsPresentation.kt)

## Host transition and verification

`tools/benchmarks/bounded_specialist_contract.py` implements menu building,
membership checking, reachable-scope validation and state-fingerprint checking.
Changing registry/status/revision invalidates the old choice binding. A forged
descendant scope cannot offer otherwise valid-looking model choices when its
parent category is unavailable.

Intermediate choices update only the deliberation scope. Leaf choices produce
a **proposal**. Android must validate the live state again, dispatch the typed
operation, and independently read back the result before declaring success.
For a local model, that includes the selected registry entry and usable file;
changing the AI provider to local is a separate preference operation. For
Gemini Live, mode `every_turn` maps to wire value `2`, not a two-second timer.

Plugin branches need the same treatment: offer currently supported plugins,
then their actual valid lifecycle operations/settings. Current enable/start
side effects vary by plugin; for example, the Community Plugins toggle starts
Walking Aid as well as enabling it. This pilot implements a local-model,
AI-wake-route and Gemini-Live-refresh slice, not the complete plugin manifest.

For phone UI, the equivalent turn is the current subgoal, fresh observation,
last independently checked result, and only host-eligible bound actions on that
screen. `phone_choices` is a reference adapter requiring explicit eligibility,
fresh observation revision and verified binding. The existing Tasker placeholder
flags and `(0,0)` coordinates cannot establish those facts on their own. A
complete Tasker evidence export/collector is still needed. Wait/defer and DONE
proposals must also be host-owned: selecting DONE cannot verify a task outcome.

## Training and evaluation audit

`make_bounded_specialist_training.py` builds **generated** written requests and
typed hypothetical states. Phrasing families are assigned to cohorts before
state variants and decision steps. Related goals/steps never split across
cohorts. This remains a synthetic language/contract pilot, not real finalized
ASR, independent speaker data, device compatibility evidence or actual Tasker
supervision.

Two views share byte-identical episode files:

| View | Train episodes / turns | Development episodes / turns | Largest menu |
|---|---:|---:|---:|
| Hierarchical | 300 / 880 | 120 / 352 | 9 choices |
| State-matched flat | 300 / 300 | 120 / 120 | 12 choices |

The flat control exposes every currently eligible leaf in one menu; it uses
the same snapshots, requests, outcomes and binding IDs. The hierarchy reveals
only children at each scope. Both retain explicit control choices. This avoids
confounding narrowing with different examples or arbitrary public API menus.

Audits enforce:

- Every offered choice has positive training supervision, including all root
  action families and control choices.
- Each gold choice is actually available in the supplied state, with matching
  Laya/Needle input and host binding. If a requested branch is unsupported,
  clarification is supervised instead; it is not an invented valid action.
- Hashes, grouped cohorts, complete gold paths and model-view parity.
- Entire request/status, every option and every supervised target fit. Laya
  uses a 512-token sequence / 256-token head limit; Needle uses 768 tokens.
  Its 512-token cap would clip some of these structured inputs, so that cap
  is explicitly increased and the loss-mask length checked before fitting.
  Version-3 audit maxima across all cohorts: **393 / 632 tokens** for Laya /
  Needle hierarchy, **442 / 727** for the flat control. This does not establish
  compatibility with a 256-token mobile export; rendering/export parity and
  device memory/latency remain runtime gates.
- No calibration/sealed inference, evaluation-row fitting or random example
  validation split.

Laya starts from the original multilingual base and fits encoder+head for
**180 optimizer updates, effective batch 16**, selecting among three open
development checkpoints. Needle starts from the original base and fits rank-16
quant-aware LoRA for **180 updates, batch 8**, retaining the final adapter.
`train_needle_bounded.py` reuses the pinned upstream LoRA/quantization/loss/export
contract with a fixed update budget. Larger batch 32 exceeded the A4000's VRAM;
that failed run is retained as a resource diagnostic. Both views use the same
budget within each model. Epoch/episode exposures differ because the hierarchy
contains multiple decisions per episode; cross-model FLOPs are not matched.

There are two distinct evaluation questions:

1. **Oracle-turn choice accuracy:** a correct preceding scope is supplied.
2. **Self-routed episode accuracy:** the next scope comes solely from the
   model's own prior choice. A wrong ancestor is never replaced with gold scope.

Episode accuracy compares exact proposed terminal effects or control outcomes.
An early correct clarification/no-change can reach the right outcome without
following every annotated scope. It still does not establish a verified app
mutation. Report whole-path accuracy and number of inference turns alongside
per-stage choice accuracy and control false actions; more calls can offset
the benefit of smaller menus.

### Initial version-2 Laya development comparison

| View / checkpoint | Oracle-turn choices | Self-routed episodes | Mean decisions per episode |
|---|---:|---:|---:|
| State-matched flat base | 31/120 (25.8%) | 31/120 (25.8%) | 1.00 |
| State-matched flat tuned | 90/120 (75.0%) | 90/120 (75.0%) | 1.00 |
| Hierarchical base | 167/352 (47.4%) | 37/120 (30.8%) | 1.58 |
| Hierarchical tuned | 301/352 (85.5%) | 85/120 (70.8%) | 2.75 |

Narrowing helps the tuned model's per-turn decisions in this written pilot,
but does **not** outperform the state-matched flat control on complete episodes.
The final installed-model and download-offer turns are each **6/6** under gold
scope; the root router is **92/120**, and the active-model branch **23/30**.
Wrong ancestor choices compound. Tuned flat gets **24/24** model-request
episodes versus hierarchical **21/24**; those counts share the same written
model names/templates and hypothetical device states, so they are not evidence
of independent real-user generalization. Oracle control false-action rate is
**3.1% flat / 9.4% hierarchical**; no invalid choice IDs occur for Laya.

Keep the state-defined contract, broaden actual request/status coverage, and
judge any further narrowing by whole-path correctness and inference cost. The
pilot does not establish automatic-action precision or a deployment threshold.
This comparison used version 2, before the explicit running/readiness facts
were added. Version 3 is trained again from base and evaluated with matching
training/oracle/rollout status rendering.

### Version-3 comparison with explicit session/readiness facts

| Model / view / checkpoint | Oracle-turn choices | Self-routed episodes | Mean decisions per episode |
|---|---:|---:|---:|
| Laya / state-matched flat base | 39/120 (32.5%) | 39/120 (32.5%) | 1.00 |
| Laya / state-matched flat tuned | 91/120 (75.8%) | 91/120 (75.8%) | 1.00 |
| Laya / hierarchical base | 176/352 (50.0%) | 36/120 (30.0%) | 1.65 |
| Laya / hierarchical tuned | 305/352 (86.6%) | 86/120 (71.7%) | 2.77 |
| Needle / state-matched flat base | 11/120 (9.2%) | 11/120 (9.2%) | 1.00 |
| Needle / state-matched flat tuned | 12/120 (10.0%) | 12/120 (10.0%) | 1.00 |
| Needle / hierarchical base | 61/352 (17.3%) | 14/120 (11.7%) | 1.15 |
| Needle / hierarchical tuned | 68/352 (19.3%) | 9/120 (7.5%) | 1.31 |

The additional facts fix the input ambiguity, but the complete-path outcome
still favors the flat control by five episodes in this small written pool.
Hierarchical tuned root choices are **95/120** and its active-model branch
**21/30**; final installed/download gold-scope turns remain **6/6 each**.
Oracle control false-action rate is **15.6% hierarchical / 0% flat**. The flat
model's errors include all eight unsupported-hardware requests, where it
chooses the wrong control outcome rather than a substantive action. Aggregate
choice accuracy, false actions and complete-path accuracy answer different
questions and should all be retained.

Needle's 180-update training losses fell to **0.202 hierarchical / 0.267 flat**,
but exported native selection remains weak. Hierarchical root choice accuracy
is **16/120** after fitting and complete-path accuracy decreases by five
episodes. Flat invalid choices fall from **26 to 2**, while its oracle control
false-action rate increases from **53.1% to 79.7%**. Hierarchical invalid choices
are **40 base / 41 tuned**, with control false-action rates **43.8% / 35.9%**.
Better output validity or lower training loss cannot substitute for correct
choices, refusal behavior or complete episodes.

For comparison, bounded version-2 Needle gives **62/352 → 87/352** oracle turns,
**12/120 → 12/120** hierarchical episodes and **10/120 → 11/120** flat episodes.
These older inputs omit the v3 readiness facts. The
[v2 summary](../training/laya_needle_specialists_v1/bounded-v2-summary.json)
records missing legacy metadata explicitly: the earliest two Laya hierarchical
reports lack prediction-time model hashes. Their current artifact bytes are
hashed, but that cannot retroactively establish prediction/model binding.

The complete [v3 summary](../training/laya_needle_specialists_v1/bounded-v3-summary.json)
verifies all eight required report variants, recomputes oracle and episode
metrics from saved predictions/effects, verifies every referenced model's
SHA-256 and records each report's SHA-256. Incomplete campaigns require explicit
partial output; mixed views/contracts, missing artifacts, modified weights,
false success flags and evaluation of another cohort are rejected.

### Presentation-order and target-format diagnostics

Reversing option/tool presentation preserves every bound ID, description,
request and status, and uses the same checkpoints. The complete
[order summary](../training/laya_needle_specialists_v1/bounded-v3-order-summary.json)
reports the following tuned self-routed outcomes:

| Model / view | Original order | Reversed order |
|---|---:|---:|
| Laya / flat | 91/120 | 92/120 |
| Laya / hierarchical | 86/120 | 82/120 |
| Needle / flat | 12/120 | 16/120 |
| Needle / hierarchical | 9/120 | 20/120 |

Laya's flat advantage persists. Needle changes materially with presentation
while remaining weak in both orders; neither ordering establishes useful
automatic-action precision. These are additional development diagnostics,
not a selection on a new final test.

A [CUDA teacher-forcing diagnostic](../training/laya_needle_specialists_v1/bounded-v3-teacher-forcing-summary.json)
uses the first twelve open development turns per view. The no-reasoning SFT
targets start with `<tool_call>`, while the base predicts `<think>` first on
all 24 rows. Tuned flat still predicts `<think>` on all twelve; tuned hierarchy
predicts `<tool_call>` on six. Teacher-forced correct-token counts improve
**124/158 → 142/158 hierarchy / 128/163 → 140/163 flat**, but every variant has
**0/12 entirely correct teacher-forced targets**. Shared syntax tokens dominate
those token scores; this is not native choice or episode accuracy.

A matched Needle reasoning-target ablation is complete. It changes only
training targets by adding a short host-label-derived `<think>` block before
the same bound call. Requests, status, menus, answers, cohort assignments and
all development/calibration/sealed files remain unchanged. Both views retain
180 updates, batch 8 and a 768-token cap; both audits passed before fitting.
Maximum training lengths are **652 hierarchy / 753 flat**, with no clipping.
This tests a target-format hypothesis rather than asserting the cause of the
native failure. Derived explanations are synthetic supervision, not observed
model reasoning or independent phone evidence.

The [reasoning-target summary](../training/laya_needle_specialists_v1/bounded-v3-reasoning-summary.json)
requires all four Needle base/tuned reports for this deliberately single-model
ablation and verifies the same model/report/metric invariants:

| Needle target variant | Flat episodes | Hierarchical episodes | Hierarchical oracle choices |
|---|---:|---:|---:|
| Base | 11/120 | 14/120 | 61/352 |
| Tuned, direct call target | 12/120 | 9/120 | 68/352 |
| Tuned, reasoning + same call | 18/120 | 7/120 | 76/352 |

Reasoning targets improve flat outcomes by six episodes versus direct targets,
but worsen hierarchical episodes by two. Hierarchical invalid oracle choices
fall **41 → 3**, while root accuracy falls **16/120 → 12/120** and oracle control
false actions increase **35.9% → 51.6%**. Flat invalid choices increase **2 → 18**,
and control false actions fall **79.7% → 60.9%**, still above the base's 53.1%.
This is a mixed diagnostic result, not a usable specialist or a demonstrated
fix for training/native parity. Training losses (**0.680 hierarchy / 0.654 flat**)
use longer/different targets and are not directly comparable to direct-target
losses. Further deployment fitting needs actual request/status evidence and
native format/export parity checks rather than selecting a favorable pilot
metric.

### Laya Python/PyTorch memory probe (Linux A4000, not Android)

Measured the bounded-v3 hierarchical checkpoint using the isolated campaign
PyTorch inference environment on Luciano's RTX A4000 16 GB. One checkpoint is
**614.01 MiB on disk**: 321,908,998 FP16 parameters. The evaluation model
construction loads those tensors into the normal FP32 PyTorch module, then uses
BF16 autocast for forward operations.

- Resident model after `.cuda()`: **1,247 MiB allocated / 1,272 MiB reserved**.
- Peak for one **438-token, 12-choice** development prompt: **1,515 MiB allocated /
  1,538 MiB reserved**. This is a single batch-1 forward, not a measured maximum
  across arbitrary app state or concurrent workloads.
- Whole Python process RSS was **about 1.78 GiB after moving the model to CUDA**
  and **2.08 GiB after inference**, including Python, PyTorch, Transformers and
  tokenizer/runtime overhead. Process high-water RSS briefly reached **about
  3.10 GiB during checkpoint loading**; it is not the steady model-only RAM.

These numbers characterize this development Python/GPU path only. They do not
predict Android LiteRT/CPU memory: a mobile export may change weight dtype,
quantization, operators, tokenizer copies and runtime buffers. We have not
measured a phone build. If separate voice/config checkpoints each duplicate the
full model, their resident costs will add unless the runtime shares an encoder;
that sharing/export layout is also unmeasured. Measure the actual Android
artifact's PSS/RSS, native allocation and peak during max-context inference
before choosing device memory requirements.

### Upstream mobile/quantization check (2026-10-04)

Checked upstream `main` at `8a6e1328cce2460a0e5aa348ad465bb1b5821cd2`; the
campaign's pinned Laya clone is older (`4066d5d5fbf08b66c6757ddeedbd797bd7655bc0`).
Current main has a **dynamic INT8 (Q8), weight-only ONNX export**, opt-in via
`scripts/export_onnx.py --quantize`. It quantizes MatMul weights, defaults to
per-tensor, and targets **CPU**. Upstream describes 1.4–2.8× smaller exports
and faster CPU inference than its eager/fp32-ONNX paths, but those size ratios
are relative to an FP32 ONNX graph; they do not guarantee a file smaller than
this campaign's 614-MiB FP16 safetensors. Per-channel dynamic quantization is
documented as severely damaging parity. Even default per-tensor INT8 agrees
with the eager model on only **about 83%** of its multilingual decisions in the
upstream parity sample. That is output agreement, not measured task accuracy,
and would need a same-checkpoint/task evaluation and fresh calibration before
any use where confidence matters. No Q4 exporter or prequantized Q4/Q8
multilingual Hub checkpoint is published in the inspected model snapshot.

The new `laya-java` source module runs ONNX Runtime on a JVM, but its own status
explicitly says **Android is not implemented** and the artifact is not published
to Maven Central. It is a possible implementation base, not a drop-in supported
Android integration. The current Python exporter/ONNX API likewise does not
provide an Android app runtime. References: [current upstream exporter](https://github.com/NandhaKishorM/laya/blob/8a6e1328cce2460a0e5aa348ad465bb1b5821cd2/scripts/export_onnx.py),
[`laya-java` status](https://github.com/NandhaKishorM/laya/blob/8a6e1328cce2460a0e5aa348ad465bb1b5821cd2/laya-java/README.md),
and the [multilingual Hub checkpoint](https://huggingface.co/convaiinnovations/laya-multilingual),
whose current safetensors metadata lists FP16 weights.

### Tuned flat-checkpoint FP16 / BF16 / Q8 comparison

Compared the **tuned bounded-v3 flat checkpoint** on the local RTX 4060 Ti
against the same 120 open development episodes. The FP32-weight/BF16-autocast
row reproduces the current PyTorch evaluation path (FP16 checkpoint values
widened to FP32) locally. FP16/BF16 rows keep resident model parameters in that
dtype; Q8 is upstream-style dynamic per-tensor INT8 MatMul quantization on ONNX
Runtime CPU with four threads.

| Runtime | Oracle choices | Correct proposed outcomes | Agreement with local reference | Resident/peak VRAM or process RSS | p50 forward |
|---|---:|---:|---:|---:|---:|
| FP32 weights + BF16 autocast (reference) | 93/120 | 93/120 | 100% | 1,247 / 1,515 MiB VRAM | 35.39 ms GPU |
| FP16-resident | 91/120 | 91/120 | 98.3% (2 changed choices) | 626 / 645 MiB VRAM | 34.47 ms GPU |
| BF16-resident | **94/120** | **94/120** | 99.2% (1 changed choice) | 626 / 645 MiB VRAM | **33.49 ms GPU** |
| Q8 dynamic MatMul, CPU | 79/120 | 79/120 | 80.8% (23 changed choices) | 1,968 MiB RSS after run; 6,649 MiB process high-water | 597.19 ms CPU |

The static-shape Q8 ONNX file is **872.5 MiB**; its FP32 ONNX source graph is
**1,230.4 MiB** (1.41× smaller). Q8 is still **1.42× larger than the original
614.0-MiB FP16 safetensors**. Of the 23 Q8/reference choice changes, Q8 fixes
3 reference errors, breaks 17 reference-correct choices, and changes 3 choices
that both models get wrong. Q8 makes **8/64 control cases substantive** (12.5%
false-action rate); all three GPU modes make 0/64 such proposals. This does not
support using this Q8 export for the bounded selector.

The pinned dynamic-axis exporter failed on varying context length (a traced
attention reshape expected the dummy length 16). For this comparison, a
fixed-shape **1×512-token / 12-option** ONNX graph was exported; every precision
received the same padded inputs and attention/option masks. The local reference
produced the same 93/120 choices with and without padding. This is a bounded
export diagnostic, not an Android artifact. Q8 CPU latency/RSS are not directly
comparable to GPU VRAM/latency; measured forward timings exclude tokenizer and
sequence construction. The local 4060 Ti reference differs from the prior A4000
run on two choices, so precision deltas above use the local reference. The
[compact summary](../training/laya_needle_specialists_v1/laya-bounded-v3-flat-precision-summary.json)
records hashes, variant outputs, memory, timing and changed turn IDs; the full
report remains outside Git. These are still generated development examples,
not real ASR, calibration or verified setting outcomes. No calibration/sealed
data was opened, and no model is promoted by this result.

To reproduce with a fresh ONNX/report path (the runner refuses to overwrite
artifacts):

```bash
ROOT=/tmp/opencode/cyanbridge-specialists-20261003
OUT="$ROOT/reports/bounded-v3-laya-flat-precision-repro.json"
/tmp/opencode/laya-export-venv/bin/python \
  tools/benchmarks/compare_laya_precision.py \
  --model "$ROOT/models/laya-bounded-flat-v3" \
  --data "$ROOT/bounded-flat-v3" \
  --source /tmp/opencode/laya-current \
  --fp32-onnx "$ROOT/precision-v3-repro/laya-flat-fp32-fixed512-12.onnx" \
  --q8-onnx "$ROOT/precision-v3-repro/laya-flat-q8-fixed512-12.onnx" \
  --out "$OUT"
```

The script verifies the development-file hashes, exports a fixed 512-token / 12
option graph, applies upstream-default per-tensor Q8 quantization, then launches
isolated reference/FP16/BF16/Q8 workers. The Q8 worker uses CPU; all modes use
identical padded inputs. See
[`compare_laya_precision.py`](../../../tools/benchmarks/compare_laya_precision.py)
and [`export_laya_fixed_onnx.py`](../../../tools/benchmarks/export_laya_fixed_onnx.py).

### EmbeddingGemma 2 bi-encoder versus tuned Laya (2026-10-06)

Google released [EmbeddingGemma 2](https://huggingface.co/google/embeddinggemma-2)
on 2026-10-06 (revision `914f7f89142e33e77833254d9c9b90c3cef7303b`, Apache 2.0):
740M parameters total, but a **270M text-only** configuration (vision/audio
towers dropped via `config_kwargs`), 768-d output with Matryoshka 512/256/128,
8K context, bf16/fp32 only (fp16 overflows). LiteRT Community publishes
`.litertlm` bundles, including `embeddinggemma-2-text-270m-litert-lm`; Google
reports ~191 MB active RAM for the quantized text part on a Pixel 11 Pro.

Same `bounded-flat-v3` development set (120 turns, 64 controls) and contract
metrics as Laya. The bi-encoder scores `cosine(query, option label)` per offered
option (prompts `task: search result | query:` / `title: none | text:`), so
presentation order cannot change a choice. "Full" embeds the same status+request
JSON Laya receives; "request" embeds only the request (state-dependent controls
are then unknowable). The fine-tune matches Laya's v3 budget: 180 updates, batch
16, best development snapshot at 60/120/180, in-menu softmax cross-entropy over
cosine/0.05, full text tower, lr 2e-5. Luciano RTX A4000, bf16 inference.

| Model (dimension) | Correct / 120 | Substantive | Control false action | Query encode p50 |
|---|---:|---:|---:|---:|
| Laya multilingual base | 39 | 0.518 | 64.1% | — |
| **Laya tuned v3 flat** (reference) | **91** | 0.804 | 0% | 33–35 ms (4060 Ti forward) |
| EG2 zero-shot, full (768) | 20 | 0.357 | 100% | 34.7 ms |
| EG2 zero-shot, request (768) | 50 | 0.750 | 76.6% | 34.6 ms |
| EmbeddingGemma 1 zero-shot, request (256) | 56 | 0.821 | 79.7% | 38.6 ms |
| **EG2 tuned, full (768)** | **110** | **0.964** | **0%** | 34.8 ms GPU / 223 ms CPU-4T |
| **EG2 tuned, full (256)** | **110** | 0.964 | 0% | 35.0 ms |
| EG2 tuned, full (128) | 100 | 0.857 | 0% | 32.5 ms |
| EG2 tuned, request (768) | 97 | 0.839 | 0% | 36.0 ms |
| EmbeddingGemma 1 tuned, full (768) | 103 | 0.911 | 0% | 39.6 ms |
| Gemma 4 E2B LiteRT zero-shot, AVD CPU ([details](LITERT_SHARED_DECISIONS.md)) | 80 | 0.786 | 34.4% | 18.7 s decision |

Findings:

- **Tuned EG2 beats tuned Laya on this set (110 vs 91)** with zero control false
  actions, at a ~2.3× smaller text model (270M vs 421M) and an encoder that has
  an official LiteRT path. 256-d loses nothing here; 128-d costs 10 turns.
- Zero-shot embeddings are not usable for this contract: the status JSON
  dominates the query (20/120), and request-only cannot see state, so it
  confidently chooses actions on 64–80% of control turns.
- EG2 zero-shot is not better than EmbeddingGemma 1 on this task (50 vs 53 at
  768, 56 for EG1 at 256); after identical fine-tuning EG2 leads 110 vs 103.
- Option vectors are host constants, so a phone needs one query encode per
  decision (plus cached label vectors), versus one Laya forward per menu.

Caveats: 120 generated development turns, and both Laya and EG2 select their
best snapshot on this same set (EG2 best update 120; Laya per its campaign), so
this is a development comparison, not held-out evidence. Calibration and sealed
cohorts were not opened. Cosine similarities are not calibrated probabilities.
No Android/LiteRT EG2 export, phone latency or memory was measured. Laya's
two runs reproduce the published 91/39 exactly on the A4000.

Artifacts: reports `/mnt/seagate/ML/cyanbridge-specialists-20261003-artifacts/reports/eg2/`
(25 JSON files), tuned checkpoint `.../models/emb-bounded-flat-v3-embeddinggemma-2-full/`
(`model.safetensors` SHA-256 `41122ba958a8eb9999714892ae4cdf04de9bf78bedf6b8cee75fdeaf671b3360`,
remote/local verified), campaign log `.../logs/eg2-campaign.log`. Isolated venv
`eg2-venv` on Luciano: torch 2.7.0+cu128 (driver 12.4), transformers 5.19.0,
sentence-transformers[image] 6.1.0.

```bash
ROOT=/tmp/opencode/cyanbridge-specialists-20261003
cd tools/benchmarks  # staged as $ROOT/scripts-eg2
$ROOT/eg2-venv/bin/python eval_bounded_specialist.py --backend embedding \
  --model $ROOT/sources/embeddinggemma-2 --data $ROOT/bounded-flat-v3 --flat \
  --truncate-dim 768 [--request-only] [--device cpu] --report $ROOT/reports/eg2/NAME.json
$ROOT/eg2-venv/bin/python train_embedding_bounded.py --base $ROOT/sources/embeddinggemma-2 \
  --data $ROOT/bounded-flat-v3 --out $ROOT/models/emb-bounded-flat-v3-embeddinggemma-2-full
```

On the Fold6 NPU (2026-10-08), Google's SM8650 EmbeddingGemma 2 text bundle gives the same
zero-shot request-only result as CPU (47/120; same option on 107/120 turns) at 123 ms vs
1,320 ms per decision. See `LITERT_SHARED_DECISIONS.md` and `NPU_ON_DEVICE.md`.

## Tools and reproduction

**Policy (2026-10-07):** future runs use the local workstation (RTX 4060 Ti 16 GB,
28 threads), not the shared Luciano host; see the root `AGENTS.md`. The October
campaigns below ran on Luciano (GPU 0 for Laya, GPU 1 for Needle). Source/model
pins and environments are recorded in the [campaign README](../training/laya_needle_specialists_v1/README.md).
The campaign directory `/tmp/opencode/cyanbridge-specialists-20261003/` was backed
up (models, reports, datasets, logs, scripts) to
`/mnt/seagate/ML/luciano-cyanbridge-backup-20261007/` before cleanup. Earlier local artifacts use the same path; newly retrieved bounded
Needle models/adapters/reports are stored on Seagate under
`/mnt/seagate/ML/cyanbridge-specialists-20261003-artifacts/`. All 50 copied
model/adapter/training/report files, including the order and reasoning ablations,
were SHA-256 checked against the remote bytes.

```bash
ROOT=/tmp/opencode/cyanbridge-specialists-20261003
python3 tools/benchmarks/test_bounded_specialist_contract.py
python3 tools/benchmarks/test_summarize_bounded_campaign.py
python3 -B -m unittest discover -s tools/benchmarks -p 'test_*specialist*.py'
python3 tools/benchmarks/make_bounded_specialist_training.py \
  --out "$ROOT/bounded-hierarchy-v3"
python3 tools/benchmarks/make_bounded_specialist_flat.py \
  --out "$ROOT/bounded-flat-v3"
# Both generators refuse to overwrite an existing data directory.

/tmp/opencode/laya-export-venv/bin/python tools/benchmarks/audit_bounded_specialist.py \
  --data "$ROOT/bounded-hierarchy-v3" --needle-source /tmp/opencode/needle-source \
  --laya-source /tmp/opencode/laya-current --laya-base /tmp/opencode/laya-multilingual \
  --out "$ROOT/reports/bounded-hierarchy-v3-audit.json"
# Repeat the audit with the flat view, then stage audited bytes/scripts remotely.
python3 "$ROOT/scripts-v3/run_bounded_campaign.py" --root "$ROOT" --lane laya --version v3
python3 "$ROOT/scripts-v3/run_bounded_campaign.py" --root "$ROOT" --lane needle --version v3
python3 tools/benchmarks/summarize_bounded_campaign.py \
  --root "$ROOT" --version v3 --out "$ROOT/reports/bounded-v3-summary.json"
# Check candidate-order sensitivity without changing weights or host bindings:
python3 "$ROOT/scripts-v3-order-complete/run_bounded_order_ablation.py" \
  --root "$ROOT" --lane laya
# Repeat with --lane needle; run once into fresh report paths.
python3 tools/benchmarks/summarize_bounded_campaign.py \
  --root "$ROOT" --version v3 --reports "$ROOT/reports/order-reversed" \
  --reverse-options --out "$ROOT/reports/bounded-v3-order-summary.json"
```

Scripts do not install into base conda, execute phone actions or open the sealed
cohort. Models remain development/shadow artifacts until actual voice and
Tasker data plus runtime/calibration gates support deployment.
