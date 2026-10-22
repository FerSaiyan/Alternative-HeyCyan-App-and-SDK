# Shared LiteRT-LM chat and decision scores

Updated: 2026-10-07. Prototype target: current Jev branch.

Ordinary `.litertlm` chat bundles (e.g. stock `gemma-4-E2B-it.litertlm`) can
score bounded classification menus with the **same loaded engine** used for
chat. No second model, no fine-tune and no model-specific export. Verified end
to end on the Pixel 9a AVD (x86_64) with the patched native library.

## Same loaded model

LiteRT-LM **0.16.0 already implements native `RunTextScoring`**. The stock Kotlin
and JNI APIs omit it; stock 0.17.1 and 0.18.0 still do (checked 2026-10-06). The
prototype adds `Engine.scoreDecision` and a JNI wrapper around that existing
scoring path, pinned to `924e79c91542761242244e4f1651851f822e4cbb` (v0.16.0).

```text
LiteRtLocalInferenceEngine: existing Engine / .litertlm / chosen backend
  ├── chat: normal Conversation
  └── score: model template, thinking disabled, assistant prefix, then "⊲"
       └── temporary decision session → prefill context (everything before ⊲)
            ├── single pass (default): RunCandidateScoring("⊲" + first label, all label ids)
            │     → one full-vocabulary log-softmax at the label position
            └── fallback if Unimplemented: clone → RunTextScoring("⊲" + label) per label
```

The decision path calls neither `Engine.initialize` nor any model loader. The
single pass uses only the temporary decision session, so no clones are made.

## Single-pass scorer (2026-10-07)

The patch extends the existing scoring task instead of adding a new task type:
`Tasks::Score` takes optional candidate token ids and, on the final target step,
computes one numerically stable full-vocabulary log-softmax and returns every
candidate in `Responses::GetCandidateLogProbs()` (upstream's per-step
`ComputeLogLikelihood` sorts the ~262k vocabulary every step). Both execution
managers carry the ids; `SessionAdvanced::RunCandidateScoring` exposes it, and the
`SessionInterface` default returns Unimplemented so the JNI falls back to the
per-label clone path. The target is `"⊲" + first label`: the final step's logits
predict the label position, and the runtime's own teacher-forced score for that
first label must equal the single-pass value (JNI self-check, 1e-3).

Full 120-turn development run on the AVD, E2B CPU, versus the per-label path:

| | Per-label clones | Single pass |
|---|---:|---:|
| Choices identical | — | **120/120** |
| Max probability difference | — | 6e-8 |
| Decision p50 / p95 | 18.7 / 22.4 s | **11.4 / 14.2 s** |
| Whole run | 38.7 min | 24.2 min |

The remaining cost is the two ~520-token prefills (normal and reversed menus).
The menus share everything up to `# Options`, so a shared-prefix prefill (one
prefill + clone per presentation) is the next optimization.

## Native first-token scoring bug and the delimiter fix

Pinned `RunTextScoring` scores the **first target token against the stale
distribution that predicted the last prefilled token**; every later target token
is conditioned correctly. The first prototype prefilled `prompt + "⊲"` and scored
a single label token, so it read exactly the broken position: on the AVD every
menu had label mass ≈1e-5 and near-uniform, position-driven choices.

Host diagnosis with stock `litert-lm` 0.16.0 Python (same native scorer) and
Gemma 4 E2B on one development turn whose answer is `H`:

| Prefix | Target | Per-token log p | Meaning |
|---|---|---|---|
| `P⊲` | `H⊳` | **-18.14**, -0.01, 0, 0 | label mis-scored, continuation correct |
| `P` | `⊲H⊳` | -0.65, 0, 0, **-0.02**, 0, 0, 0 | label correct once it is not first |
| `P` | `⊲A⊳` | -0.65, 0, 0, **-5.85**, … | wrong label correctly unlikely |
| greedy decode after `P⊲` | — | `H⊳` | model knows the answer |

Hugging Face `transformers` bf16 (same prompt, `google/gemma-4-E2B-it`
`3e22461f`) gives log p(H | P⊲) ≈ 0 and -15…-21 for other labels, matching the
corrected ranking. `⊲` is three byte tokens in Gemma 4's tokenizer.

**Fix (in the patch):** the prompt must end with `⊲` (U+22B2). Native code
prefills everything before it, scores `"⊲" + label` per clone and keeps only the
label token's score, so the mis-scored position is a discarded delimiter byte.
It verifies the delimiter appends whole tokens without retokenizing the context,
that each label is one distinct token after the full prompt, and that each
clone returns exactly delimiter-tokens + 1 scores. After the fix, AVD label mass
is 0.97–0.997 on the first three development turns: two correct at ≥0.98
probability, the third spread (top 0.23, an abstention case).

## Score meaning and contract

- Each value is genuine **log p(label | context + ⊲)** at temperature 1 over the
  full vocabulary. The pinned `SessionInterface` comment says negative log
  probability, but the implementation sums `log(probability)`.
- Label mass is `sum(exp(log p(label)))` before normalizing over eligible labels;
  a strong non-label preference remains visible.
- Only ASCII position labels A–Z are accepted. Unsupported tokenizer boundaries
  reject the request instead of silently scoring a different continuation.
- The complete rendered prompt is retained; overflow is rejected before prefill.
- `SharedGemmaDecisionEngine` scores normal and reversed menus with fresh
  position labels, averages aligned probabilities and always abstains (shadow).
- `LiteRtLocalInferenceEngine` serializes chat, scoring, load and unload.
  `renderedDecisionPrompt()` exposes the exact scored text for HIL diagnostics.

Cost: one prefill plus one short scoring pass per menu, twice for the reversed
presentation (see [Single-pass scorer](#single-pass-scorer-2026-10-07)); the AVD
takes ~11–14 s for a 10–12 option, ~520-token turn.

## Source patch and build

Patch and packaging: [`../tools/litert-decisions/`](../tools/litert-decisions/).
`build_aar.sh` takes `ABIS` (`arm64-v8a`, `x86_64`, or both) and `JOBS`, builds the
JNI library and the `:litertlm-android_kt` Kotlin classes, then `package_aar.py`
verifies patched Kotlin/JNI symbols, per-ABI ELF machine, 16-KB LOAD alignment
and native dependencies, and writes a SHA-256 manifest.

The open-source Bazel build links `libGemmaModelConstraintProvider.so`
dynamically (Google's stock AAR does not). The packager bundles that upstream
prebuilt from `prebuilt/android_<abi>/` (Git LFS; verify the checkout fetched
real binaries, not 133-byte pointers) after the same ELF/dependency/alignment
checks. It needs only platform libraries.

**Build locally, not on Luciano** (policy 2026-10-07): Luciano's NVMe is shared
with other students. The October 6–7 AARs were built there at 40 jobs; that
speed came from parallelism, not the host. The original local build ran
`--jobs=4`. The workstation has 14 cores / 28 threads and 62 GB RAM, so
`build_aar.sh` now defaults to `JOBS=20` with Bazel memory capped at 60% of RAM
(`RAM_FRACTION`). Put Bazel's ~15 GB output base on the NVMe
(`BAZEL_OUTPUT_ROOT`; the Seagate HDD is much slower for it) and remove it with
`bazel clean --expunge` when done. Expect a cold build of roughly 30–45 min at
20 jobs (estimate) and a patch-only rebuild in minutes. Run bulk copies to
Seagate under `ionice -c3`. Sources, NDK r28b and Bazel 7.6.1 for 0.16 already
exist locally in `/mnt/seagate/ML/cyanbridge-litert-decision-20261004/`; for
0.18, add a `v0.18.0` worktree and fetch its Git LFS prebuilts, checking each
against its pointer SHA-256.

```bash
L=/mnt/seagate/ML/cyanbridge-litert-decision-20261004
export ANDROID_HOME=$HOME/Android/Sdk ANDROID_NDK_HOME=$L/android-ndk-r28b
LITERTLM_VERSION=0.16.0 ABIS="x86_64 arm64-v8a" JOBS=20 \
  BAZEL_OUTPUT_ROOT=$HOME/.cache/bazel-litertlm-016 \
  bash android/CyanBridge/tools/litert-decisions/build_aar.sh \
  $L/LiteRT-LM $L/bazel-7.6.1 "$STOCK_0_16_AAR" /mnt/seagate/ML/cyanbridge-litert-decision-build-<date>

cd android/CyanBridge
./gradlew -PliteRtDecisionPrototype=true \
  "-PlocalLiteRtDecisionAarPath=/mnt/seagate/ML/cyanbridge-litert-decision-build-20261007/litertlm-0.16.0-shared-decisions-x86_64+arm64-v8a.aar" \
  -PtestAbi=x86_64 :app:assembleDebug :app:assembleDebugAndroidTest
```

Current AAR (both ABIs, single pass): SHA-256
`21cf41b8a8f6ace09914f44d9e14ca86637a334d1896c349cf31eaf993fdb606`, manifest beside
it. Superseded AARs are kept under `superseded-first-token-scoring/`,
`superseded-per-label-scoring/` and `superseded-single-pass-offbyone/` (the first
single-pass build read the step that predicts the delimiter's last byte; label
mass ≈1e-8, caught on the AVD before use).
`liteRtDecisionPrototype=true` enables shadow calls; an AAR override without the
flag is rejected; default builds still use stock 0.16.0.

## Verification

- JVM: 41 decision/engine tests pass in both default and prototype builds.
- `LiteRtSharedDecisionRuntimeTest` passes on the Pixel 9a AVD with E2B CPU:
  load 7.8 s, scoring → chat → scoring with state reset, duplicate-label,
  overflow and unloaded-engine rejection.
- `LiteRtBoundedDecisionEvalTest` runs the shipped decision path over pushed
  canonical turns (no expected labels on device); results are joined on the host
  by `tools/benchmarks/score_litert_bounded_eval.py`.

### Gemma 4 E2B, zero-shot, bounded-flat-v3 development (AVD, CPU)

Stock `gemma-4-E2B-it.litertlm` (2,588,147,712 bytes), no tuning, same 120 turns
and contract metrics as Laya/EmbeddingGemma
([comparison](BOUNDED_SPECIALIST_DECISIONS.md#embeddinggemma-2-bi-encoder-versus-tuned-laya-2026-10-06)).
All 120 turns completed in 39 min; label mass min 0.88, median 0.994.

| Metric | E2B zero-shot | Laya base | Laya tuned | EG2 tuned |
|---|---:|---:|---:|---:|
| Correct / 120 | **80** | 39 | 91 | 110 |
| Substantive (56) | 0.786 | 0.518 | 0.804 | 0.964 |
| Control false action (64) | 34.4% | 64.1% | 0% | 0% |

| Minimum margin | Accepted | Precision |
|---:|---:|---:|
| 0 | 120 | 0.667 |
| 0.5 | 75 | 0.827 |
| 0.75 | 61 | 0.869 |
| 0.9 | 47 | **0.979** (46/47) |

- Zero-shot E2B is the strongest untuned option measured (Laya base 39,
  EmbeddingGemma zero-shot ≤56) and needs no extra model on the phone.
- Errors concentrate on controls: `no_change` turns answered with an action
  (15 of 36 `no_change` turns, mostly route/model/voice-question) and clarify↔action
  confusions. Substantive misses: `ai_voice_question` → clarify (6), phone task →
  clarify/defer (4), stop speaking → no change (2).
- High margins are informative (≥0.9: 46/47 correct), but this is development
  data, not a calibrated threshold.
- AVD timing: decision p50 18.7 s / p95 22.4 s for both presentations; warm-cache
  load 0.66 s (7.8 s cold in the smoke test); PSS 541 MiB after load, 2,323 MiB
  after the run (temporary decision sessions included). Emulator figures only.

Report: `/mnt/seagate/ML/cyanbridge-specialists-20261003-artifacts/reports/litert-e2b/`
(device JSONL, scored report, label-free case file).

```bash
T=/sdcard/Android/data/com.fersaiyan.cyanbridge/files/litert-eval
adb push development.decision.jsonl gemma-4-E2B-it.litertlm $T/
adb shell am instrument -w -e class com.fersaiyan.cyanbridge.hil.LiteRtBoundedDecisionEvalTest \
  -e liteRtDecisionModelPath $T/gemma-4-E2B-it.litertlm \
  -e boundedDecisionCasesPath $T/development.decision.jsonl \
  -e boundedDecisionOutputPath $T/e2b-cpu-dev120.jsonl \
  com.fersaiyan.cyanbridge.test/androidx.test.runner.AndroidJUnitRunner
python3 tools/benchmarks/score_litert_bounded_eval.py --canonical development.canonical.jsonl \
  --device-results e2b-cpu-dev120.jsonl --report e2b-cpu-dev120-report.json
```

`development.decision.jsonl` holds `caseId`, `turn` and `decisionState` (contract
instructions + status/request, the same information Laya receives), not the
expected choice.

## Fixing control false actions: prompts vs LoRA (2026-10-07)

Zero-shot E2B answers "set X to the value it already has" with an action:
the menu only offers the *other* value and E2B matches on topic, not value.

**Prompting (host replica, train split 300, selection only):**
[`hf_bounded_decision.py`](../../../tools/benchmarks/hf_bounded_decision.py)
mirrors this engine exactly (template, `⊲`, normal + reversed menus) on HF bf16;
it agrees with the AVD on 91/120 dev choices (same total, 80), so it is a
directional proxy only. Explicit value rules and few-shot examples
([`gemma_decision_prompts.py`](../../../tools/benchmarks/gemma_decision_prompts.py))
moved train accuracy 190 → 175–188; host-rendered current values on setting
options ("… to voice (current: image)", `annotated`) → 196. Single-token
answers leave E2B no room to compare, so prompting has little headroom.

**LoRA** ([`train_gemma_decision_lora.py`](../../../tools/benchmarks/train_gemma_decision_lora.py)):
r=16 on language-model attention/MLP, the exact decision prompt, full-vocabulary
CE on the gold position label under freshly shuffled option order, the
Laya/EmbeddingGemma budget (180 updates × 16, development snapshot every 60).
Host bf16 development: current prompt **114/120**, annotated options **118/120**,
both 0% control false actions (adapters SHA-256 `b6e37a01…` / `6fcf1863…`).

**Deployment:** stock `.litertlm` bundles have no LoRA input slots, so adapters
are merged ([`merge_gemma_decision_lora.py`](../../../tools/benchmarks/merge_gemma_decision_lora.py))
and re-exported with the pinned litert-torch 0.9.1 environment
([`export_gemma_litertlm.py`](../tools/litert-decisions/export_gemma_litertlm.py)).
Gotchas, all handled: transformers 5.19 saves a config schema and drops Gemma 4
KV-shared layers' unused k/v tensors that the exporter's transformers 5.5 needs
(merge keeps base `config.json` and restores the 60 tensors verbatim); the
default INT8 recipe makes a 5.07 GB bundle, so blockwise-32 INT4 is passed as a
JSON recipe (2.84 GB; ai-edge-quantizer 0.7 needs an empty `recipes/` dir
before any JSON recipe loads); export `--prefill-lengths 128 1024` or ~520-token
prompts take five 128-token prefill chunks. Exports are text-only: the app now
omits audio/vision backends for bundles without encoders (LiteRT-LM 0.18
`ModelInfo.inputModalities()`; on 0.16 a one-time conversation probe), which
previously failed every conversation with `TF_LITE_AUDIO_ENCODER_HW not found`.

| Bundle (120 dev turns) | Device | Correct | Substantive | Control false action | Decision p50 |
|---|---|---:|---:|---:|---:|
| Stock E2B | AVD CPU | 80 | 0.786 | 34.4% | 11.4 s |
| Stock E2B | Galaxy Z Fold6 CPU | 78 | 0.786 | 37.5% | 7.6 s |
| Our export of unmodified E2B, INT4 (control) | AVD CPU | 81 | 0.732 | 14.1% | — |
| LoRA annotated, INT4, prefill 128 | AVD CPU | 109 | 0.857 | **0%** | 22.1 s |
| LoRA annotated, INT4, prefill 128 | Fold6 CPU | 109 | 0.875 | **0%** | 15.2 s |
| LoRA annotated, INT4, prefill 128+1024 | Fold6 GPU | **110** | 0.893 | **0%** | 4.6 s (≈2 s cool) |

Host bf16 gave 118; INT4 costs ~9 substantive turns, never a control. On the
Fold6 the annotated LoRA accepts 110 turns at margin ≥0.1 with 94.5% precision.
Reports: `/mnt/seagate/ML/cyanbridge-specialists-20261003-artifacts/reports/litert-e2b/`;
bundles: `/mnt/seagate/ML/cyanbridge-litert-decision-build-20261007/gemma-decision-bundles/`.
Development-set snapshot selection, so these are development results.

## LiteRT-LM 0.18, GPU and EmbeddingGemma 2 (2026-10-07)

The patch is rebased onto 0.18.0 (`b2f686e2`,
[`litertlm-0.18.0-shared-decision.patch`](../tools/litert-decisions/litertlm-0.18.0-shared-decision.patch);
only a JNI include list conflicted). `LITERTLM_VERSION=0.18.0 build_aar.sh`
selects pin/patch; 0.18's `kt_android_library` is a native rule
(`liblitertlm-android.jar`) and its `.bazelrc` forces `CC=clang`, so the script
passes `HOST_CC`. Current dual-ABI AAR SHA-256 `1e8be424…` (manifest beside it).
The app builds against either line with `-PliteRtLmVersion=0.16.0|0.18.0`;
version-specific calls live in `src/litertlm0160|0180/java` (`LiteRtModelProbe`,
`LiteRtEmbeddingFactory`). 0.16 remains the default until 0.18 chat is reviewed.

Stock AARs ship no GPU accelerator, so "GPU" had silently been CPU. The AAR now
bundles the verified upstream OpenCL accelerator and top-k sampler (ARM64).
On the Fold6, E2B decision scoring runs on GPU: smoke decision 0.93 s vs 3.7 s
CPU, same choice, label mass 0.998.

`LiteRtEmbeddingEngine` (0.18 `EmbeddingEngine`, text + `InputData.Audio`) runs
`litert-community/embeddinggemma-2-text-270m-litert-lm` (`9be6e8b9`, 165 MB):
Fold6 CPU 768-d, 1.7 s cold, **96 ms p50 per embedding**, 449 MB PSS; zero-shot
request-only bounded parity 47/120 vs host 50/120. The Tasker embedding gate
uses it when the configured embedding model is a `.litertlm` (0.18 builds;
GGUF embedders keep llama.cpp); its margin threshold was fit on EmbeddingGemma 1
and must be refit. EmbeddingGemma 2 on the OpenCL
accelerator SIGSEGVs during delegate application, so the factory keeps GPU
requests on CPU.

**NPU (2026-10-08).** With the opt-in NPU runtime build (`-PlocalLiteRtNpuAarPath`,
QAIRT 2.50; [`NPU_ON_DEVICE.md`](NPU_ON_DEVICE.md)), Google's SM8650 bundle runs on the
Fold6 NPU through `Backend.NPU`, although its context is QAIRT 2.49:

| EmbeddingGemma 2 text 270M, Fold6 | Per embedding (p50) | Per bounded decision (p50) | Cold first | Dev set (request-only) |
|---|---:|---:|---:|---|
| NPU, `…_Qualcomm_SM8650.litertlm` | **8 ms** | **123 ms** | 1.35–1.48 s | 47/120 |
| CPU, `embeddinggemma-2-text-270m.litertlm` | 65–99 ms | 1,320 ms | 0.79–0.95 s | 47/120 |

The two pick the same option on 107/120 turns.

**Gemma 4 E2B on the Fold6 NPU (2026-10-08).** The recipe session built a QAT E2B with
the decision LoRA retrained on the QAT base (INT8 rank-16 branches), QAIRT 2.50 and weight
sharing. Annotated dev set on the NPU: **118/120, 0 control false actions, p50 1.34 s per
decision** (p95 1.50 s). Our INT4 LoRA exports scored 109/120 at 15.2 s (CPU) and 110/120
at 4.6 s (GPU). Plain E2B NPU throughput: prefill 2,250–2,900 tok/s and decode 32–35 tok/s,
vs stock GPU 540–1,050 / 14–16 and CPU 110–205 / 11–19. Details, E4B and context-length
results: [`NPU_ON_DEVICE.md`](NPU_ON_DEVICE.md). The NPU bundle's similarity scale
differs (related/unrelated 0.80/0.31 vs 0.93/0.65), so the gate's margin threshold
must be fit per backend. The fine-tuned EmbeddingGemma 2 (110/120) has no LiteRT export
path yet (litert-torch 0.9.4 has none); a weight swap into the official bundle is
the candidate route.

**Matched CPU vs GPU decision latency, Fold6** (same bundle per row, first 10
development turns, mean excluding the first decision; each decision prefills two
~520-token menus; results in `reports/litert-e2b/fold6-bench10-20261007/`):

| Bundle | CPU | GPU | Speed-up | Load CPU / GPU (cold) | PSS end CPU / GPU |
|---|---:|---:|---:|---:|---:|
| Stock E2B | 8.2 s | **1.58 s** | 5.2× | 2.6 / 19.3 s | 2.09 / 2.30 GB |
| Fine-tuned (our INT4 export) | 15.6 s | 3.9 s | 4.0× | 3.2 / 27.7 s | 1.90 / 3.07 GB |

End to end, the stock model reaches ~650 prompt tok/s on GPU vs ~125 on CPU;
session setup, prompt rendering and scoring keep this below raw prefill throughput
(user-measured ~1,500 GPU / ~50 CPU tok/s). Our export is ~2× slower than
Google's stock bundle on both backends, so matching the stock quantization recipe
is the next lever for the fine-tuned model. `LiteRtBackendBenchmarkTest` (raw
prefill/decode tok/s via LiteRT-LM `benchmark()`) is written but has not run yet.

**Memory, Fold6, fine-tuned E2B, 8 decisions, `dumpsys meminfo` every 3 s:**

| Backend | Steady PSS | Native heap | Model file mmap (reclaimable) | GPU buffers (`GL mtrack`) |
|---|---:|---:|---:|---:|
| CPU | 2.15 GB (peak 2.36) | 0.79 GB | 1.42 GB | 0 |
| GPU | 3.02 GB | 0.12 GB | 0.12 GB | 2.21 GB |

Single `Debug.getPss()` snapshots taken right after load (0.7 GB CPU vs 3.1 GB
GPU) were misleading: CPU faults weights in lazily while GPU copies them into
non-reclaimable buffers at load. CPU is the path for 4-GB phones.

## Still open

E4B; a shared-prefix prefill for the normal/reversed menus; fine-tuned
EmbeddingGemma 2 on LiteRT; EmbeddingGemma 2 GPU crash; E2B on the NPU (2.50 package not yet run on the phone);
quantization-aware or INT8 attention to recover the ~9 INT4 turns; review of
0.18 chat before making it the default. The emulator is software x86_64 with
host-KVM CPU; its timings are not phone timings. Calibration and sealed cohorts
remain unopened.

Related: [GGUF shared-model prototype](SHARED_GEMMA_DECISIONS.md),
[specialist evidence](BOUNDED_SPECIALIST_DECISIONS.md).
