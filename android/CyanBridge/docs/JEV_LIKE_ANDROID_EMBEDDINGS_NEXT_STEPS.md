# Jev-like Android embeddings: considerations and next steps

**Status:** handoff document for the `jev-like-local-agent` branch  
**Date:** 2026-09-19  
**Scope:** Android-first investigation; emulator CPU tests remain the release gate, with separate opt-in host-GPU and emulator-acceleration probes  
**Constraint:** do not make default CI or correctness conclusions depend on a GPU or downloaded model weights.

## Executive summary

The next experiment should be an **Android embedding probe**, not another generated-token classifier.

The intended sequence is:

```text
Android emulator / CPU
        ↓
EmbeddingGemma LiteRT probe
        ↓
finite, normalized vector
        ↓
cosine similarity against bounded intent/action prototypes
        ↓
top choice + margin + explicit abstention
```

Do not wire an embedding model into production routing until the probe has established its model I/O contract, tokenizer behavior, output dimension, repeatability, and latency.

## Android llama.cpp GPU status

Upstream llama.cpp can use an Android GPU, but GPU support is a **native build
capability**, not something that `n_gpu_layers` can add to a CPU-only binary.
The upstream build documentation enables Vulkan with `-DGGML_VULKAN=ON` and
uses a positive `--n-gpu-layers` value to offload model layers. Upstream also
documents an OpenCL backend aimed primarily at recent Qualcomm Adreno devices.

The currently bundled `io.github.ljcamargo:llamacpp-kotlin:0.4.0` AAR does not
contain either backend:

- its CMake file compiles `ggml-cpu` sources and defines `LM_GGML_USE_CPU`;
- it has no `ggml-vulkan` source directory and does not link `libvulkan`;
- `LlamaAndroid.startEngine()` returns `gpu=false` and
  `reasonNoGPU="Currently not supported"`;
- the packaged x86_64 native library contains CPU backend symbols but no Vulkan,
  OpenCL, CUDA, or Metal backend registration.

Therefore, setting CyanBridge to GPU with this AAR still executes on CPU.
`LlamaCppLocalInferenceEngine` now honors the runtime's explicit `gpu=false`
response and reports an `EngineLoadResult` CPU fallback instead of claiming
that the requested GPU backend became active. The real bounded probe confirmed:

```text
requested backend=GPU, n_gpu_layers=-1
active backend=CPU
fallback=Currently not supported Fell back to CPU.
cold decision=6,543 ms, warm decision=142 ms, answer=A
```

The `Pixel_9a` AVD exposes Vulkan 1.3 as
`Goldfish GFXStream (SwiftShader Device (Subzero))`. That is software/emulated
Vulkan, not representative of an Adreno or Mali phone GPU. A valid Android GPU
experiment requires a replacement AAR built from a pinned upstream llama.cpp
revision with Vulkan (or device-appropriate OpenCL) included, explicit backend
discovery, layer-offload logs, CPU fallback, output-parity tests, and physical
device measurements. Do not infer a phone speedup from this emulator.

Relevant upstream documentation:

- `https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md`
- `https://github.com/ggml-org/llama.cpp/blob/master/docs/build.md#vulkan`
- `https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/OPENCL.md`

## Laya real-checkpoint feasibility result

The Laya repository and actual public checkpoint were tested, rather than
relying only on its published benchmark. Source was inspected at commit
`1161ff639204388b1e576a7c5d56a0f6df470455`; checkpoint metadata reported
421,293,830 parameters and an 842,609,210-byte safetensors file. Weights and
generated artifacts stayed under `/tmp/opencode` and are not part of the app,
Git history, or CI.

Laya is a ModernBERT-large encoder (28 layers, hidden size 1,024) plus a custom
marker-position decision head. It performs one non-autoregressive forward pass
and generates no output tokens. On a four-choice version of the YouTube action
fixture, the real checkpoint selected `open_youtube`:

| Runtime | Result | Warm latency |
|---|---|---:|
| PyTorch CUDA, RTX 4060 Ti | `open_youtube`, probability 0.8781 | 50.5–52.8 ms; P50 51.8 ms |
| PyTorch CPU, workstation | `open_youtube`, probability 0.8855 | 264–357 ms; P50 268 ms |

The repository's entropy-derived confidence was 0.6369 on GPU and 0.6534 on
CPU, below a conservative 0.85 auto-action threshold. The top probability and
the reported confidence are different quantities; CyanBridge must not conflate
them.

Export findings:

- `torch.export` captured the complete encoder plus custom marker/scorer/action
  heads as a 1,606-node graph with exact output parity on the fixture.
- A fixed 128-token/8-option FP32 ONNX graph was 1,686,011,557 bytes, matched
  PyTorch option logits within `5.96e-6`, and ran at roughly 200–244 ms on host
  ONNX Runtime CPU.
- A mixed FP16 ONNX graph was 843,838,297 bytes. Option-logit maximum absolute
  difference was 0.0117, but the auxiliary action head did not preserve parity.
- Naive dynamic INT8 reduced the graph to 424,294,646 bytes and roughly 145–156
  ms, but destroyed decision parity: logits collapsed near uniform and the
  selected action changed. MatMul-only INT8 also failed parity.
- Global FP16 conversion initially failed because Laya's forward pass casts
  pooled features to FP32 while the converted action head expected FP16.
  Retaining the small action head in FP32 allowed export, but did not fix its
  output discrepancy.

Conclusion: Laya is a genuine and promising zero-generation decision model,
and its full custom graph is exportable. It is **not production-ready for this
Android app** yet: the repository publishes no Android/LiteRT/ONNX artifact,
the valid FP32 graph is about 1.69 GB, the smaller FP16 graph has an action-head
parity issue, and generic INT8 quantization invalidates choices. The next useful
Laya work is calibration-aware quantization or distillation, representative
multi-fixture parity testing, then a physical Android CPU/GPU runtime probe.

## Needle 3 feasibility result

`Cactus-Compute/needle3` is a more immediately deployable alternative to Laya
for **bounded UI tool selection**, but the current release is not a replacement
for a contrastively trained embedding model. The public release was inspected
at Hugging Face revision `b009f8937124b2d0458f4ed040c10c41fd2a0dfc` and
GitHub revision `94df9999d58a67ff29f032a41f31307c05554bd6`. The downloaded
model and probes remain under `/tmp/opencode/needle3` and are not in Git or the
app.

### What it is

Needle 3 is a 121,021,910-parameter, 20-layer Laddered Simple Attention
Network. It uses GQA, a Monarch Hadamard MLP, n-gram engram memory, multi-lane
hyper-connections, int8 activations/KV cache, and Cactus CQ2/CQ4 weight
quantization. It is not a Laya-style zero-token scorer: it autoregressively
generates a short reasoning trace and schema-constrained tool-call JSON.

The release is Apache-2.0 and publishes:

- `needle3.cact`: **35,335,380 bytes** in the tested revision (larger than the
  29 MB currently stated in parts of the documentation);
- a 242,047,978-byte safetensors training checkpoint;
- an Android arm64 static library of 1,595,306 bytes and an arm64 CLI of
  1,159,824 bytes;
- Android ARMv7 and RISC-V artifacts, plus iOS, desktop, and WebAssembly
  artifacts;
- a five-function C ABI: `needle_load`, `needle_init`, `needle_complete`,
  `needle_embed`, and `needle_reset`.

The native state is process-global and explicitly non-thread-safe. CyanBridge
would need one serialized, lifecycle-owned engine, just as it does for the
current local decision model. The model has a 256-token trained KV window and
reports bounded session memory. It requires neither llama.cpp nor Vulkan.

The repository contains the JAX architecture, training, quantization, export,
Python bindings, and tests. The optimized C++ engine is distributed as platform
binaries/static libraries rather than as C++ source in that repository, so a
production adoption still needs a binary supply-chain and upgrade policy.

### Real host probe

The actual released CQ model and x86-64 engine were run directly, with no pip
installation. With four threads and correctly scoped sets of three or four
legal OpenAI-schema actions, the model selected all four expected steps of the
YouTube fixture:

| State | Expected call | Result | Confidence |
|---|---|---|---:|
| Android launcher | `open_youtube` | correct | 1.0000 |
| YouTube home | `tap_search` | correct | 1.0000 |
| Focused search field | `type_search_query("Linus Tech Tips")` | correct and argument grounded | 1.0000 |
| Search results | tap visible LTT result | correct when only the user goal was passed | 1.0000 |

The shared-library warm probe measured:

```text
model load + tool initialization: 343.9 ms
five reset warm decisions: 203.9–209.1 ms
prefill: 574–650 tokens/s
decode: 191–196 tokens/s
native CLI peak RAM: about 78.3 MB
```

This is close to Laya's host CPU P50 of 268 ms while using a roughly 24-times
smaller deployed model, and unlike Laya it already publishes an Android ARM
runtime. It is still generated-token inference (the warm fixture emitted about
32 tokens), not Laya's zero-generation option scoring. Host measurements are
not substitutes for physical Android ARM latency.

The release publishes no Android x86-64 engine. Both currently attached AVDs
are x86-64, so the official Android artifact cannot be honestly benchmarked in
the existing emulator gate. The next runtime probe must use an ARM phone or a
new supported ARM virtual target; do not treat the Linux host binary as an
Android result.

### Safety and calibration failures

The initial eight-tool test performed poorly: it produced duplicate and
irrelevant calls, matching the vendor guidance that the model is strongest with
five or fewer directly rendered tools. Even with only four well-formed tools,
the tested base model selected `tap_search` for an unrelated capital-of-France
question at confidence 1.0000, and selected a visible action for a vague “do
something useful” prompt at confidence 0.9758. A negated search was correctly
withheld in `suppressed_calls`, but its reported confidence was still 1.0000.

When a prompt repeated every A-D option and a single enum-classification tool
was used, the model copied several mentioned options into multiple calls. The
better contract is one tool per legal action, tool definitions as the candidate
descriptions, and only the goal/current facts in the user turn. The client must
reject zero or multiple calls rather than choosing the first silently.

Therefore, the vendor confidence field is an input to policy, not authorization
to act. CyanBridge would still require:

- deterministic request-intent and package safety checks before Needle;
- no more than four actionable tools plus an escalation tool;
- exact name/schema validation and an exactly-one-call requirement;
- argument grounding and the existing approval policy;
- a held-out CyanBridge UI fixture suite to calibrate act/confirm/abstain bands;
- fallback to the current detailed planner whenever any check fails.

Fine-tuning does not update Needle's confidence head; the package intentionally
returns `confidence=None` for tuned archives. Fine-tuned deployments therefore
need a separate calibrator. The local build path also exports 4-bit archives;
the shipped 2-bit post-training flow uses Cactus's platform service.

### Embedding result: do not replace EmbeddingGemma/Qwen yet

The current `needle3.cact` manifest has a confidence head but **no contrastive
embedding head**. The vendor's porting guide states that `needle_embed` falls
back to the confidence probe pool and describes it only as a cheap similarity
signal. It returns a deterministic, unit-normalized 3,072-float vector.

The real probe was fast—roughly 11–20 ms per host embedding—but poorly
separated for cosine retrieval:

```text
open youtube ↔ launch the YouTube app: 0.9374
open youtube ↔ open Spotify:          0.9639
overall tested pair range:            about 0.90–0.96
```

On the actual action fixture, `Play the latest Linus Tech Tips video` was
slightly closer to `Tap the visible YouTube Search control` (0.9435) than to
`Open the YouTube app to find the requested video` (0.9429). That ordering and
the tiny margins are unsuitable for the current cosine-plus-margin decision
engine. Keep EmbeddingGemma/Qwen as the embedding candidates until Needle ships
a contrastive head and passes retrieval, multilingual, threshold, and
abstention calibration.

### Recommendation

Prototype Needle as an additional **tool-selection backend**, not as the
embedding backend and not yet as the sole safety decision-maker. It is the most
practical Laya alternative found so far because Android ARM artifacts already
exist and the deployed size is small. A safe trial path is:

```text
deterministic candidate builder
  -> top four legal actions + explicit escalation tool
  -> Needle complete()
  -> require exactly one known call + grounded arguments + calibrated score
  -> CyanBridge safety/approval policy
  -> execute, otherwise detailed planner
```

Before committing its binary/model to the app, run 100+ branch-specific
positive, negative, ambiguous, multilingual, multi-action, and adversarial UI
fixtures on the host, then the same frozen suite on a physical arm64 Android
device. The Python package enables anonymous event telemetry by default (not
prompts or outputs); set both `NEEDLE_TELEMETRY=0` and `DO_NOT_TRACK=1` for any
development tooling. Direct native C-ABI inference does not use that Python
telemetry module, but the binary should still be audited before release.

## What is already in this branch

The existing Jev-like implementation is in:

- `app/src/main/java/com/fersaiyan/cyanbridge/ai/decision/`
  - `LocalDecisionEngine.kt`
  - `SingleTokenDecisionEngine.kt`
  - `DecisionOutputParser.kt`
  - `DecisionPromptBuilder.kt`
  - `DecisionMath.kt`
- `app/src/main/java/com/fersaiyan/cyanbridge/ai/live/GeminiLiveControlRouter.kt`
- `app/src/main/java/com/fersaiyan/cyanbridge/localagent/UiActionCandidateBuilder.kt`
- `app/src/androidTest/java/com/fersaiyan/cyanbridge/hil/JevLikeDecisionEmulatorTest.kt`
- `.github/workflows/jev-like-local-agent.yml`
- `tools/hil/run_jev_like_decision_ci.sh`

The scoped emulator workflow already passes on the local homelab runner. It runs JVM tests, assembles x86_64 APKs, installs on the persistent emulator, and runs only `JevLikeDecisionEmulatorTest`.

There is **currently no production embedding router** in CyanBridge. `LocalEmbeddingService` is a 64-dimensional token-hash baseline, not a neural embedding model. The opt-in real-model probe below is separate from production routing.

The opt-in real GGUF probe is now implemented in:

- `app/src/main/java/com/fersaiyan/cyanbridge/localmodels/engine/TextEmbeddingEngine.kt`
- `app/src/main/java/com/fersaiyan/cyanbridge/localmodels/engine/LlamaCppTextEmbeddingEngine.kt`
- `app/src/main/java/com/fersaiyan/cyanbridge/ai/decision/EmbeddingDecisionEngine.kt`
- `app/src/test/java/com/fersaiyan/cyanbridge/ai/decision/EmbeddingDecisionEngineTest.kt`
- `app/src/androidTest/java/com/fersaiyan/cyanbridge/hil/RealEmbeddingModelEmulatorTest.kt`
- `tools/hil/run_real_embedding_models.sh`

It uses real CPU inference on the emulator, not the hash baseline. The real
decision fixture applies the documented model-family prompts: EmbeddingGemma's
`task: classification | query:` prompt on both query and prototypes, and
Qwen's English `Instruct: ... / Query:` prefix on queries with plain candidate
passages.

## Verified runtime findings

### llama.cpp Kotlin AAR

The cached `llamacpp-kotlin:0.4.0` AAR was inspected directly. Its public API includes:

```text
LlamaAndroid.embedding(contextId, text)
LlamaContext.getEmbedding(text)
```

The context initialization accepts an `embedding` boolean. This means a dedicated embedding adapter may be possible for GGUF models without immediately forking JNI.

The completion implementation also accepts `logit_bias`, but this only changes sampling. It does not expose the raw logits or token probabilities.

Still missing for true Jev zero-token scoring:

- `get_logits()` / `llama_get_logits`
- per-token probability output
- a public prefill-and-read-last-position API

The current `LlamaCppLocalInferenceEngine` uses generation, tokenization, and cancellation only. It does not call the embedding API.

`LlamaCppTextEmbeddingEngine` now provides that separate adapter and logs the
returned keys, vector length, norm, finite-value status, and latency. It does
not overload `LocalInferenceEngine.generate()`; the remaining production work
is dependency repair and opt-in wiring.

#### Verified wrapper defect and temporary fix

The first real run crashed before the JUnit assertion with:

```text
JNI DETECTED ERROR IN APPLICATION: attempt to return an instance of
java.util.ArrayList from java.util.Map LlamaContext.embedding(...)
```

The native implementation returns an `ArrayList`, but the published Kotlin declaration says `Map`. A temporary patched AAR was built from the upstream source with the native return type changed to `List` and the public method wrapping it as `{ "embedding" to values }`:

```text
/tmp/opencode/kotlinllamacpp-fixed/llamaCpp/build/outputs/aar/llamaCpp-release.aar
```

`LlamaCppTextEmbeddingEngine` now checks the reflected JNI return type and fails clearly if the unpatched AAR is used, instead of allowing ART to abort the app process. The custom AAR is intentionally not committed; a permanent dependency strategy is still required before production use.

### LiteRT and LiteRT-LM

The current `LiteRtLocalInferenceEngine` wraps:

```text
com.google.ai.edge.litertlm.Engine
com.google.ai.edge.litertlm.Conversation
conversation.sendMessageAsync(...)
```

That is a generative/chat API. It is not the right abstraction for EmbeddingGemma.

Google’s current EmbeddingGemma Android path uses the standalone LiteRT `CompiledModel` API and provides an EmbeddingGemma semantic-similarity sample. The app already has a LiteRT dependency for other functionality, but the exact `CompiledModel` classes and version compatibility must be verified before implementation.

Use a sibling `TextEmbeddingEngine` backed by `CompiledModel`, rather than modifying `LiteRtLocalInferenceEngine`’s chat path.

The official LiteRT Community model card also says the EmbeddingGemma model requires the SentencePiece tokenizer. The tokenizer and model must be treated as a matched pair.

## Model candidates

### EmbeddingGemma 300M — first Android target

Official Google model characteristics:

- 308M parameters
- trained for 100+ languages
- 2K-token context
- 768-dimensional output
- Matryoshka truncation down to 128 dimensions
- LiteRT/TFLite deployment artifacts
- quantized mobile variants

The LiteRT Community card reports Android measurements on a Samsung S25 Ultra, not an emulator. Treat those numbers as hardware reference only, not CI acceptance criteria.

Sources:

- <https://ai.google.dev/gemma/docs/embeddinggemma>
- <https://ai.google.dev/gemma/docs/embeddinggemma/inference-embeddinggemma-with-sentence-transformers>
- <https://huggingface.co/litert-community/embeddinggemma-300m>
- <https://developers.google.com/edge/litert/inference>

### Official Qwen3 Embedding 0.6B — second target / comparison

The official Qwen series provides 0.6B, 4B, and 8B embedding models. The 0.6B model is the relevant size for local testing and is documented as multilingual, instruction-aware, MRL-capable, and available in GGUF variants.

Qwen’s recommended query format uses an English task instruction followed by the query. Candidate documents do not receive the query instruction. The implementation must reproduce the model card’s pooling and normalization rules exactly.

Source:

- <https://github.com/QwenLM/Qwen3-Embedding>

### Qwen3.5-Embedding-0.8B — PC/later experiment

The currently identified Qwen3.5 embedding model is `Rebine/Qwen3.5-Embedding-0.8B`, a community fine-tune rather than an official Qwen embedding-series release.

Important limitations:

- Chinese and English support is claimed; do not assume broad multilingual behavior.
- 2K-token context.
- Last-token pooling and L2 normalization are required.
- It supports 128/256/512/768/1024 MRL dimensions.
- Its published comparison is model-card-specific and should be independently reproduced.

It is useful for a later memory-retrieval comparison, but it should not be the first CyanBridge multilingual front-door model.

Source:

- <https://huggingface.co/Rebine/Qwen3.5-Embedding-0.8B>

## Emulator-first test plan

### Phase 1: pure Android math and routing tests; no model file

Add a small, model-independent embedding layer with tests for:

1. L2 normalization.
2. Cosine similarity.
3. Prototype/candidate ranking.
4. Top-1/top-2 margin calculation.
5. Explicit abstention when the maximum score or margin is too low.
6. Deterministic tie handling.
7. Dimension mismatch rejection.
8. NaN/infinite vector rejection.

Use synthetic vectors in `androidTest` so these tests run on the existing emulator without GPU, network, or model downloads.

The pure ranking layer is now implemented in `EmbeddingDecisionMath` and the
JVM suite covers normalization, cosine scoring, deterministic ties, margin
abstention, dimension mismatch, and non-finite vectors. `EmbeddingDecisionEngine`
adapts that ranker to the existing bounded `LocalDecisionEngine` contract and
propagates an explicit `abstained` flag to the assistant, Live, and UI safety
gates.

### Phase 2: fake embedder end-to-end emulator test

Introduce a small interface, separate from generation:

```kotlin
interface TextEmbeddingEngine {
    suspend fun embed(text: String): EmbeddingResult
}

data class EmbeddingResult(
    val vector: FloatArray,
    val dimension: Int,
    val elapsedMs: Long,
    val backend: String,
)
```

The emulator HIL test should inject a deterministic fake engine and verify the complete path:

```text
transcript / goal
    → embedding router
    → candidate scores
    → margin/abstention policy
    → AssistantIntent / LiveControlAction
```

This validates Android wiring before adding a 180–220 MB model artifact.

### Phase 3: real EmbeddingGemma model probe

Do not commit model weights or make ordinary CI download them.

Use an opt-in instrumentation test with a model path supplied through an environment variable or `adb push` step. The test should log with the `JEV_EMBED` tag:

- model path basename and SHA-256
- tokenizer path basename and SHA-256
- model input names and shapes
- model output names and shapes
- requested text length/token count
- output dimension
- vector norm
- minimum/maximum element
- finite-value result
- cold initialization time
- warm inference time
- cosine scores and top-2 margin for synthetic test pairs

The test must fail clearly when the model path is absent; it must not silently report a pass using the hash baseline.

Minimum semantic fixtures:

```text
same:       "Open Spotify" / "Abra o Spotify"
related:    "Play my liked songs" / "Start my saved music"
different:  "Open Spotify" / "What is the weather today?"
```

The initial model probe should test output correctness and ordering, not claim production classification accuracy.

### Phase 4: Android router bakeoff

Once a real embedder works, create a synthetic multilingual fixture with these heads:

```text
Assistant: ANSWER_QUESTION, ANALYZE_IMAGE, EXECUTE_UI_TASK, CLARIFY
Live:      CONTINUE_LIVE, END_LIVE, LOCAL_AGENT
UI:        bounded candidate actions A-H
```

For each sample record:

- expected label
- selected label
- score for every candidate
- top-2 margin
- accepted/abstained
- elapsed time
- backend

Do not convert cosine scores directly into calibrated probabilities. Fit thresholds later using a held-out CyanBridge dataset.

The opt-in real probe now exercises the assistant and Live heads with three
fixtures each. It asserts the expected bounded label and rejects a result when
the top-two margin is below `0.01`; the production threshold remains unset
until a held-out dataset is available.

## Emulator limitations

The emulator can establish:

- model packaging
- tokenizer/model compatibility
- CPU correctness
- output shapes and normalization
- deterministic routing behavior
- regression-safe logs

The emulator cannot establish:

- Samsung/Pixel NPU performance
- Vulkan/Metal accelerator performance
- realistic battery or thermal behavior
- production RAM pressure
- physical-device audio/transcription timing

Do not block the first Android validation on the RTX 4060 Ti or on a physical NPU. Record hardware performance as a later phase.

## CI guidance

Keep the real-model embedding probe separate from the default Jev-like CI unless the runner has a provisioned model artifact.

Default CI should remain:

```bash
JAVA_HOME=/opt/android-studio/jbr \
ANDROID_HOME="$HOME/Android/Sdk" \
./gradlew --no-daemon \
  :app:testDebugUnitTest

bash tools/hil/run_instrumentation.sh \
  <emulator-serial> emulator \
  com.fersaiyan.cyanbridge.hil.JevLikeDecisionEmulatorTest
```

An opt-in model workflow can add:

```text
JEV_EMBEDDING_MODEL_PATH
JEV_EMBEDDING_TOKENIZER_PATH
JEV_EMBEDDING_MODEL_SHA256
```

The opt-in workflow must push the files to the emulator, verify checksums, run only the embedding probe class, and upload the `JEV_EMBED` log.

### Local real-model command

After obtaining the two GGUFs outside the repository:

```bash
JEV_EMBEDDING_LLAMA_RUNTIME_AAR=/tmp/opencode/kotlinllamacpp-fixed/llamaCpp/build/outputs/aar/llamaCpp-release.aar \
  JAVA_HOME=/opt/android-studio/jbr \
  ANDROID_HOME="$HOME/Android/Sdk" \
  bash tools/hil/run_real_embedding_models.sh emulator-5580
```

The script defaults to:

```text
/tmp/opencode/jev-embedding-models/embeddinggemma-300M-Q8_0.gguf
/tmp/opencode/jev-embedding-models/Qwen3-Embedding-0.6B-Q8_0.gguf
```

It pushes the models to `/data/local/tmp/jev-embedding`, builds x86_64 APKs, installs them, and invokes only `RealEmbeddingModelEmulatorTest`.

### First real emulator result

The latest probe passed **2/2 tests** on `emulator-5580` using CPU inference.
The host and device SHA-256 values were checked before instrumentation:

```text
EmbeddingGemma-300M-Q8_0.gguf  b5ce9d77a3fc4b3b39ccb5643c36777911cc4eb46a66962eadfa3f5f60490d63
Qwen3-Embedding-0.6B-Q8_0.gguf 06507c7b42688469c4e7298b0a1e16deff06caf291cf0a5b278c308249c3e439
```

Vector-level results:

| Model | Dimension observed | Self cosine | English↔Portuguese | Unrelated | Warm inference samples |
|---|---:|---:|---:|---:|---:|
| EmbeddingGemma 300M Q8 | 768 | 1.000 | 0.756 | 0.163 | 45–155 ms |
| Qwen3-Embedding 0.6B Q8 | 768 observed by current runtime | 1.000 | 0.877 | 0.392 | 173–414 ms |

The Qwen model card advertises up to 1024 dimensions, but this current mobile llama.cpp build returned 768. Treat that as a runtime/model-contract discrepancy to investigate; do not silently assume 768 is the intended production dimension.

Real bounded-decision results (three assistant + three Live fixtures per model):

| Model | Assistant labels | Live labels | Representative top-2 margins |
|---|---|---|---|
| EmbeddingGemma 300M Q8 | A, B, C — all expected | A, B, C — all expected | 0.216–0.554 assistant; 0.301–0.445 Live |
| Qwen3-Embedding 0.6B Q8 | A, B, C — all expected | A, B, C — all expected | 0.162–0.251 assistant; 0.176–0.284 Live |

The earlier raw-prototype run intentionally failed two semantic cases. That was
corrected by using the models' documented classification/instruction prompts;
the letter-only decision interface itself did not change.

## Validation boundary: Tasker and real UI tasks

The real embedding validation **did not connect to Tasker and did not execute a
real UI task**. This is intentional: the scoped command used for this work is
`tools/hil/run_jev_like_decision_ci.sh`, and that runner explicitly excludes the
Tasker, AutoInput, network, email, and full local-agent HIL layers.

### Tests actually performed

| Layer | Result | What it covered |
|---|---|---|
| Scoped JVM suite | **70 passed, 0 failed** | Decision math, embedding ranking, tie handling, dimension/NaN rejection, abstention safety, parsers, routers, and bounded UI candidates |
| APK assembly | **Passed** | Debug app and instrumentation APKs, x86_64 emulator ABI |
| `JevLikeDecisionEmulatorTest` | **5/5 passed** | On-device fake single-letter decisions, Portuguese routing, multilingual Live heuristics, reasoning-token parsing, bounded UI candidates; no model, Tasker, or network |
| `RealEmbeddingModelEmulatorTest` | **2/2 passed** | Real CPU GGUF inference for EmbeddingGemma and Qwen3; vector validity plus three assistant and three Live ranking fixtures per model |
| `LocalAiTaskerYouTubeHilTest` | **Skipped** | Invoked on `emulator-5580`; correctly reported `AssumptionViolatedException: Tasker is not installed on the HIL device` |
| Tasker profile connection | **Not validated** | No Tasker profile import, readiness probe, AutoInput observation, or Tasker action execution occurred |
| Chrome/YouTube task | **Not run in this session** | No Chrome launch, YouTube search, Linus Tech Tips result selection, or video playback was performed |

The repository contains separate Tasker HIL tests. `TaskerLocalAgentHilTest`
checks fixture observation/click/type execution, and
`LocalAiTaskerChromeHilTest` runs a local model through Tasker/AutoInput against
a deterministic Chrome HIL page. A new gated
`LocalAiTaskerYouTubeHilTest` plus
`tools/hil/run_tasker_youtube_hil.sh` now cover the requested YouTube smoke
path. They were not run here because the current emulator has YouTube/Chrome
but **does not have Tasker or AutoInput installed**; its invocation skipped
before any task execution. The new test is a Tasker plumbing test using the
current local-agent brain; it is not evidence that the embedding decision
engine is production-wired.

### What is still needed for embedding-backed production decisions

1. **Permanent llama.cpp dependency repair.** The tested path uses the temporary
   patched AAR documented above. Update 2026-09-20: the `static n_embd`
   truncation defect is fixed in source and both ABIs were rebuilt from
   `/tmp/opencode/kotlinllamacpp-fixed` (x86_64 + arm64-v8a) and repackaged;
   on-device validation shows Gemma 768 → Qwen 1024 dims in one process.
   Still needed: a reproducible build script pinned to an upstream revision
   before this AAR can be a real dependency (currently a local artifact).
2. **Production engine wiring.** Partially done 2026-09-20 for the Tasker
   service path: opt-in `LocalAgentPrefs` embedding keys (default off),
   lifecycle-owned `LlamaCppTextEmbeddingEngine` in `TaskerLocalAgentService`,
   and a `FallbackDecisionEngine` cascade (embedding → single-token LLM →
   detailed JSON planner). Still dormant: `AssistantRequestRouter`
   and `LocalAgentDecisionBridge` providers.
3. **Calibrated safety policy.** Cosine scores and the compatibility softmax are
   not probabilities. Fit top-score/margin thresholds on a held-out multilingual
   CyanBridge command set; abstention must clarify or fall back rather than
   starting phone control. Corpus result: Gemma margin≥0.10. Live YouTube UI
   shows near-tie abstentions (margin ~0.001–0.013) that correctly cascade;
   production thresholds need a re-fit on production candidate descriptions.
4. **Correct UI candidate identity.** Preserve the selected node index in each
   `click_text` candidate. The current production mapping can resolve every
   selected click to the first clickable node.
5. **Embedding model contract.** Resolved 2026-09-20: the "Qwen returns 768"
   observation was the runtime `static n_embd` defect, not the model. Qwen
   reports its documented 1024 dims with the fixed runtime.
6. **Model packaging decision.** Either finish the standalone EmbeddingGemma
   LiteRT `CompiledModel` + SentencePiece path, or explicitly standardize on
   the repaired GGUF backend. Do not silently mix tokenizer/model contracts.
   New constraint 2026-09-20: gate queries must stay short. A 687-token state
   string aborts the native embedding prefill (SIGABRT; probe
   `abortprobe.{0,1,2}`); the service caps gate input at 1000 chars.
7. **End-to-end Tasker HIL.** In progress 2026-09-20: `TaskerLocalAgentHilTest`
   passes on the provisioned Pixel_9a target (observation/click/type);
   `LocalAiTaskerEmbeddingYouTubeHilTest` runs the Gemma gate against real
   YouTube UI (gate engages, abstains on ties, cascades). First full playback
   run 2026-09-20 FAILED with `max_steps_reached` (20 steps, no crash): the
   gate abstained on every real-UI decision (production candidate descriptions
   differ from the calibration corpus, margins collapse to ~0.01), the
   single-token LLM tapped Voice Search instead of the search field, and the
   run never recovered. The gate never mis-acted; it currently contributes no
   accepted decision on production descriptions. Next: align candidate
   descriptions with the calibration protocol (or re-fit the threshold on
   production candidates) and re-run; the single-token mistap/recovery is a
   pre-existing planner issue independent of the gate.

### Exact Chrome/YouTube validation still required

Run the new test only on a provisioned Tasker HIL target. It should:

1. verify Tasker and AutoInput packages, enabled accessibility services,
   imported/current profiles, CyanBridge local-agent readiness, and a selected
   local model;
2. start the production `TaskerLocalAgentService` with the goal to open Chrome
   or YouTube, search for **Linus Tech Tips**, and select a known result;
3. assert each boundary using `TaskerExecutionBackend.observe()` rather than
   trusting only an app status string: foreground package, visible search text,
   selected result, and player state;
4. capture the Tasker/CyanBridge log and stop/restore the service and preferences
   in `finally`;
5. keep this outside default CI because YouTube content, ads, login state,
   network availability, and playback UI are nondeterministic. A deterministic
   local HIL web fixture is preferable for the first embedding-backed action
   test; YouTube can be a later physical/emulator smoke test.

Provisioned-target command:

```bash
bash tools/hil/sync_tasker_profiles.sh <tasker-target-serial>
JAVA_HOME=/opt/android-studio/jbr \
  ANDROID_HOME="$HOME/Android/Sdk" \
  bash tools/hil/run_tasker_youtube_hil.sh <tasker-target-serial>
```

The runner requires Tasker, AutoInput, YouTube, an imported/current Tasker
profile set, a selected local model, and the accessibility services enabled.
It intentionally runs in `hardware` mode so missing prerequisites fail instead
of being silently skipped.

Do not download Hugging Face models from CI by default. EmbeddingGemma requires accepting Google’s model license, and large model downloads make the persistent runner flaky.

## Mobile-action calibration benchmark (goal-only, 2026-09-20)

Corpus: `android/CyanBridge/calibration/local_agent_mobile_actions_v1.jsonl`
(169 cases: 125 calibration / 44 held-out test; 13 locales; generated by
`tools/benchmarks/generate_local_agent_calibration.py`, see
`android/CyanBridge/calibration/README.md`). The corpus models the production
boundary: Tasker/AutoInput supplies visible nodes, CyanBridge builds bounded
candidates, the fast backend picks one; open-ended prose selects
`DELEGATE_LOCAL_LLM` (CyanBridge's local LLM writes it) and sending selects an
approval request. Blocked packages carry no candidates (policy boundary).

Method corrections applied during this run:

- Retrieval query is the goal text only. An earlier ablation that appended
  `Current app` / `Tasker screen` biased both models toward screen-echo
  candidates (e.g. "YouTube Home" spuriously favored Tap Home) and is retained
  only as a negative result.
- One model per instrumentation process. The patched runtime's native
  `embedding()` caches `n_embd` in a function-local `static`
  (`rn-completion.cpp`), so whichever model embeds first fixes the output
  dimension process-wide: a Gemma-then-Qwen run silently truncated Qwen's
  1024-dim vectors to 768. This also resolves the old "Qwen returns 768 vs
  documented 1024" mystery: it was the runtime, not the model. A permanent fix
  must remove the `static` and rebuild the AAR; until then never benchmark two
  embedding models in one process (Gemma-first also risks OOB reads in the
  reverse order).
- Needle evaluated with `--max-tokens 256` (128 truncated 8 envelopes).

Results (emulator x86_64 CPU; Needle on host CPU for reference only):

| Backend | Cal raw top-1 | Test raw top-1 | 98%-target operating point | Cold decision p50 / p95 | Query-only p50 | Size |
|---|---|---|---|---|---:|---:|
| EmbeddingGemma-300M-Q8_0 (768-d, goal-only) | 61% (71/116) | 70% (28/40) | margin≥0.10: 100% cal (48/48), 100% test (16/16), ~46% actionable coverage | 255 ms / 467 ms | 67 ms | 334 MB, ~518 MB PSS |
| Qwen3-Embedding-0.6B-Q8_0 (1024-d, goal-only) | 38% | 38% (15/40) | margin≥0.23: 100% but only ~9% coverage | 2193 ms / 3375 ms | 1380 ms | 639 MB, ~1083 MB PSS |
| Needle 3 `needle3.cact` (host, 256 tok) | 60% | 70% | none: wrong actions routinely report confidence ≈1.0, so no threshold reaches 98% with nonzero coverage | host total 397 ms / 792 ms (init 164 + complete 227) | n/a | 35 MB |

Notes:

- Gemma's auto-fitted threshold (margin≥0.0665) sat exactly on a calibration
  error and missed one held-out vague case (`nl.ambiguous` → `scroll_down`);
  margin≥0.10 is clean on both splits. Remaining ~54% falls back to the
  detailed local-LLM planner — that fallback is the design, not a failure.
- Qwen failed systematically (13/13 locales): `tap_search`→`tap_home`,
  `type_search_query`→`press_back`, `tap_compose`→`open_first_email`,
  recipient/body cases→`discard_draft`/`request_send_approval`. Prompt wording
  may contribute, but 2.2 s p50 cold latency and 639 MB already disqualify it
  for the fast path; no prompt ablation was pursued.
- Needle raw accuracy matches Gemma at 1/10th the size, but its confidence
  cannot gate auto-actions, and there is still no Android x86_64 artifact
  (ARM-only), so its numbers remain host-only.
- Multilingual raw accuracy was flat, not collapsed: Gemma 50–75% per locale
  (es/zh-CN lowest at 50%), Needle 42–83%, Qwen 25–50%. No backend shows a
  single broken language, but per-locale n=12 is too small for release claims.
- Production-description probe (`RealProductionDescriptionProbe`, 3 live-format
  YouTube cases, 2026-09-21): sanitizing `(node N)` suffixes/quotes does NOT
  change rankings (identical picks, margins ±0.02). 2/3 accept correctly at
  margin≥0.10 (type-query 0.19, first-result 0.17); home-search abstains (0.06,
  would have picked a channel distractor). Live-run margins (~0.001–0.013)
  collapse from long noisy screen dumps in the state, not candidate
  formatting — the lever is state brevity and threshold, not text cleanup.
- Baseline for context: the shipped local-LLM bounded path (Qwen2.5 0.5B)
  measured 6.5 s cold / 142 ms warm. Gemma wins cold-start and determinism
  (67 ms query-only with cached prototypes); it does not beat a warm LLM
  single-token call on raw speed.
- Needle separate-calibrator result: no function of Needle's own outputs
  (confidence, call count, latency, reasoning length) separates right from
  wrong — wrong-answer confidence median is 0.98 vs 1.0 for correct. The only
  gate that held 98%+ was cross-model: Needle agrees with Gemma AND Gemma
  margin≥0.10 → 100% on both splits (36/36 cal, 11/11 test), coverage ~38%/31%.
  All agreement misses were abstention-worthy vague/empty cases both models
  confidently over-acted on. Fine-tuned Needle returns confidence=None by
  design, so budget a separate calibrator (this agreement gate is the current
  candidate) before any auto-action role.

HIL caveats from this run: an unrelated host-side package install killed the
app mid-run (`installPackageLI`, 98/169 Qwen cases kept — verified prefix and
resumed 98..168 in a fresh process); a stale test-APK install caused one
`ClassNotFoundException`, fixed by reinstall. Avoid any `adb install` while a
calibration run is in flight. HIL-specific: the service loop and a 1 Hz test
observer poll contend inside serialized Tasker execution and starve the
service past its 8 s observation timeout — the YouTube HIL test now warms up
once, then polls status at 1 Hz and observes at most every 20 s, failing fast
only on non-retryable errors. Tasker's monitor goes dormant after an emulator
reboot; launch the Tasker UI once (dismissing its Tip) before HIL. Pixel_9a
Chrome currently SIGILL-crashes its renderer on loopback pages, so YouTube —
not the deterministic Chrome fixture — is the working HIL vehicle there.

## Known Jev branch follow-ups unrelated to embeddings

Before production routing is enabled, address these existing issues:

1. `AssistantRequestRouter` does not yet have a production `decisionEngineProvider` wired by default; the new decision path is currently dormant unless injected.
2. `LocalAgentDecisionBridge.engineProvider` is not yet wired to a real production embedder or single-token engine.
3. `LocalAgentBrain.mapCandidateKeyToAction()` currently resolves every `click_text` candidate to the first clickable node. Candidate metadata must retain the selected node index.
4. Pseudo-softmax values in `DecisionMath` are not calibrated confidence.
5. Keep embeddings and generation behind separate interfaces and feature flags.

## Recommended next-agent order

1. ~~Add `TextEmbeddingEngine`, `EmbeddingResult`, `EmbeddingRouter`, and pure math tests.~~ Done as `TextEmbeddingEngine`, `TextEmbeddingResult`, `EmbeddingDecisionEngine`, and `EmbeddingDecisionMath`.
2. Add synthetic emulator HIL coverage using a fake embedder.
3. ~~Add model-path/checksum plumbing for an opt-in Android probe.~~ Done for the GGUF probe; tokenizer/checksum plumbing remains for a future LiteRT path.
4. Integrate EmbeddingGemma through standalone LiteRT `CompiledModel` plus SentencePiece.
5. ~~Run CPU-only emulator correctness tests and capture `JEV_EMBED` logs.~~ Done for both GGUFs.
6. ~~Add Qwen3 GGUF embedding probing through `LlamaAndroid.getEmbedding()`/`embedding()` if a compatible artifact is available.~~ Done with the patched wrapper AAR; permanent dependency repair remains.
7. Compare real model routing margins on a held-out multilingual fixture.
8. Only then wire the best model behind an opt-in front-door router.
9. Test Qwen3.5-Embedding-0.8B later, preferably on PC or after a verified mobile conversion; do not assume it is multilingual.

## Do not do

- Do not commit model weights, tokenizer secrets, or Hugging Face tokens.
- Do not install packages into the base Conda environment.
- Do not silently fall back from a missing real embedder to the 64-d hash baseline in a model-validation test.
- Do not treat cosine similarity as a probability.
- Do not auto-end Live or start phone control without the existing safety and approval policies.
- Do not use the emulator’s latency as a claim about Vulkan/NPU production performance.
