# Jev-like Android embeddings: considerations and next steps

**Status:** handoff document for the `jev-like-local-agent` branch  
**Date:** 2026-09-18  
**Scope:** Android-first investigation; emulator/CPU tests only  
**Constraint:** the workstation RTX 4060 Ti is currently occupied, so do not make the first validation depend on Python/GPU inference.

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

There is **currently no real embedding implementation** in CyanBridge. `LocalEmbeddingService` is a 64-dimensional token-hash baseline, not a neural embedding model.

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

**Potential follow-up:** create a separate `LlamaEmbeddingEngine`; do not overload `LocalInferenceEngine.generate()` with embedding semantics. Start with an isolated probe that logs the returned map keys, vector length, norm, and finite-value status.

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

Do not download Hugging Face models from CI by default. EmbeddingGemma requires accepting Google’s model license, and large model downloads make the persistent runner flaky.

## Known Jev branch follow-ups unrelated to embeddings

Before production routing is enabled, address these existing issues:

1. `AssistantRequestRouter` does not yet have a production `decisionEngineProvider` wired by default; the new decision path is currently dormant unless injected.
2. `LocalAgentDecisionBridge.engineProvider` is not yet wired to a real production embedder or single-token engine.
3. `LocalAgentBrain.mapCandidateKeyToAction()` currently resolves every `click_text` candidate to the first clickable node. Candidate metadata must retain the selected node index.
4. Pseudo-softmax values in `DecisionMath` are not calibrated confidence.
5. Keep embeddings and generation behind separate interfaces and feature flags.

## Recommended next-agent order

1. Add `TextEmbeddingEngine`, `EmbeddingResult`, `EmbeddingRouter`, and pure math tests.
2. Add synthetic emulator HIL coverage using a fake embedder.
3. Add model-path/checksum plumbing for an opt-in Android probe.
4. Integrate EmbeddingGemma through standalone LiteRT `CompiledModel` plus SentencePiece.
5. Run CPU-only emulator correctness tests and capture `JEV_EMBED` logs.
6. Add Qwen3 GGUF embedding probing through `LlamaAndroid.getEmbedding()`/`embedding()` if a compatible artifact is available.
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
