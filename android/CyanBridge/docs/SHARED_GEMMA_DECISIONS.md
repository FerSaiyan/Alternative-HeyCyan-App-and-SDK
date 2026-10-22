# Shared Gemma chat and bounded decisions

Updated: 2026-10-04. Implementation target: `jev-like-local-agent`.

## Direction

For phones with 4–6 GB RAM, prioritize evaluating **one already-loaded Gemma
GGUF for both prose and bounded decisions** before adding a resident Laya.
Our tuned Laya FP16/BF16 probe used about 626 MiB resident / 645 MiB peak VRAM,
but those Linux GPU numbers do not establish Android PSS. Shared-Gemma scoring
adds no second copy of model weights; it still requires prompt prefill, KV
memory and temporary buffers. Neither E2B nor E4B phone memory/decision accuracy
has been measured by this prototype.

This applies to a **GGUF loaded by llama.cpp**. A Gemma `.litertlm` already loaded
in LiteRT-LM cannot lend its weights or context to llama.cpp. Loading a GGUF
beside that instance would defeat the memory objective. The GGUF prototype
returns no native shadow result for LiteRT or the legacy Kotlin wrapper.
The separate [LiteRT shared-decision fork](LITERT_SHARED_DECISIONS.md) adds an
opt-in binding to existing native text scoring on the loaded `.litertlm` engine.

The existing `SingleTokenDecisionEngine` already supports reusing a LiteRT
chat model for a short decoded choice. The missing feature is **raw candidate
logits**, not bounded decisions generally. Reviewed v0.17.1 Kotlin
[`Session`](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.17.1/kotlin/java/com/google/ai/edge/litertlm/Session.kt)
and `Conversation` expose prefill/decode/generation, but no raw next-token score
method. Upgrading the package alone will not provide this JNI prototype's
scores. Keep the single-token fallback distinct from true logit probabilities.

## Verified upstream findings

- [llama.cpp #29818](https://github.com/ggml-org/llama.cpp/pull/29818) merged on
  October 2 (`a4cb4c61fd9d9c2066c7c1747821d3d65b8943bd`). It supports dedicated
  Laya/Julia/Lev/OpenJev/Kev decision models; ordinary Gemma support is separate.
- [#29832](https://github.com/ggml-org/llama.cpp/pull/29832) is **an open draft**,
  checked at `f30811c80bafb6d257292592603ce2afc35ddcd5`. Its author demonstrates
  Gemma 4 E4B and Qwen3.5-4B using ordinary chat weights, a no-thinking chat
  template, `⊲label⊳` delimiters and next-token logits. It validates labels
  against the tokenizer and evaluates reversed choices. It lists calibration,
  always-thinking models and shared-prefix handling as unfinished. Those
  examples are not a CyanBridge benchmark, and do not establish E2B accuracy.
- [#29363](https://github.com/ggml-org/llama.cpp/pull/29363) is also open. Its
  standalone Laya GGUF implementation describes Q4/Q5/Q8 backbone quantization
  while protecting embeddings, norms and small heads. It is distinct from
  Laya's ONNX INT8 export; do not attribute our poor ONNX Q8 results to GGUF Q4.
- [PocketPal's dependencies](https://github.com/brentonbluehouse/pocketpal-ai/blob/main/package.json)
  include `llama.rn` 0.12.7. [llama.rn](https://github.com/mybigday/llama.rn)
  documents OpenCL for tested Adreno 700+ devices and experimental Hexagon HTP
  support. This is useful backend reference work, not proof that CyanBridge's
  current binary accelerates on a particular phone.
- [Gallery's model list](https://github.com/google-ai-edge/gallery/blob/main/model_allowlists/1_0_13.json)
  lists MobileActions-270M as a 288,964,608-byte Q8 `.litertlm`, CPU, minimum
  device memory 6 GB. The same list recommends **8 GB for its Gemma 4 E2B bundle
  and 12 GB for E4B**, with 32K context defaults. These are Gallery configurations,
  not measured hard minima for CyanBridge's lower-context setup. The 289-MB
  artifact size is not its runtime RAM footprint.
- [LiteRT-LM v0.17.1](https://github.com/google-ai-edge/LiteRT-LM/releases/tag/v0.17.1)
  exists; this branch currently uses 0.16.0. A version update and GPU backend
  validation are separate changes from generic logit scoring.

## Prototype implemented here

The Jev branch still uses `llamacpp-kotlin:0.4.0` by default. Its API lacks raw
logits. The opt-in `llama-runtime` prototype uses the Eyevue adapter's verified
upstream pin `0c1e57098bba43ac29e6e3b677cdceebdd22334f` plus `llama-common`'s
Jinja renderer. Ordinary next-token scoring needs neither the newer dedicated
decision graph nor a server. No localhost HTTP or server endpoint is used.

```text
LocalChatSessionManager
  └── SharedGemmaInferenceEngine: one GGUF / one llama_context
       ├── generate: normal text chat
       └── score: template(no thinking) → prefill → label logits

Tasker active choice → shared-model shadow comparison → retain active choice
```

- `NextTokenDecisionRuntime` borrows the chat model; it has no loading method.
- `SharedGemmaDecisionEngine` evaluates normal and reversed menus with new
  position labels, aligns probabilities to the original candidate indices,
  and reports genuine label probabilities and margin. This prototype averages
  the two normalized probability vectors; it is not a claim of exact PR parity.
- Native code requires each label to be one token and checks the **full prompt
  plus delimited label** for unchanged token boundaries. It also reports label
  probability mass over the full vocabulary, so a conditional label softmax
  cannot hide a strong preference for a non-label/thinking token.
- Full state/options are preserved; context overflow is rejected. No silent
  character clipping is introduced. Native inference and engine lifecycle are
  serialized; session reload/unload cannot interleave the two shadow variants.
- Every returned prototype decision is `abstained=true`. Tasker logs
  `SHARED_GEMMA_SHADOW` and keeps its original engine output. EmbeddingGemma's
  existing margin gate and planner fallback are preserved.
- The prototype is **text-only and CPU-only**. It does not enable Vulkan/OpenCL,
  multi-ISA dispatch, or multimodal Gemma input. The default build packages no
prototype native library. LiteRT native scoring uses its separate opt-in fork;
the default LiteRT-LM dependency remains stock 0.16.0.

Build with an existing pinned source checkout:

```bash
cd android/CyanBridge
JAVA_HOME=/opt/android-studio/jbr ./gradlew \
  -PsharedGemmaPrototype=true \
  -PllamaSourceDir=/tmp/opencode/llama-upstream-20261001 \
  -PtestAbi=arm64-v8a :app:assembleDebug
```

Without `llamaSourceDir`, CMake fetches the pinned archive and verifies its SHA256.
This flag selects the shared native engine for **GGUF chat**, not for LiteRT.
Do not use it for image/audio sessions yet. Opt-in on-device contract smoke:

```bash
adb -s "$SERIAL" shell am instrument -w \
  -e class com.fersaiyan.cyanbridge.hil.SharedGemmaRuntimeTest \
  -e sharedGemmaModelPath "$EXISTING_GGUF_ON_DEVICE" \
  -e sharedGemmaTemplate gemma_it \
  com.fersaiyan.cyanbridge.test/androidx.test.runner.AndroidJUnitRunner
```

This requires the prototype app and matching Android-test APK to be installed
on the chosen device. It uses an existing model and skips if unprovisioned.

## Evidence and next measurements

The host JNI contract smoke passed on the existing Qwen2.5 0.5B Q4_K_M GGUF:
chat/streaming, grammar, real logits, stable decisions after chat, duplicate
and multi-character label rejection, overflow rejection/recovery and unloaded
handle rejection. JVM tests cover reversed-menu binding, uncertainty, complete
state preservation and malformed logits. **48 selected JVM tests passed**;
the ARM64 native AAR, debug app and Android-test APK all built. The checked-JNI
host smoke also exercised concurrent chat/scoring on the same context. The
prototype `.so` in the opt-in ARM64 APK is 6,352,000 bytes; the default runtime
AAR contains no native library. These are plumbing checks, not Gemma
quality or phone latency evidence. No ADB device was attached during this work.

Host JNI smoke reproduction with an existing local chat GGUF:

```bash
cmake -S android/CyanBridge/llama-runtime/src/main/cpp \
  -B /tmp/opencode/shared-gemma-host-build \
  -DCYAN_LLAMA_SOURCE_DIR=/tmp/opencode/llama-upstream-20261001 \
  -DCMAKE_BUILD_TYPE=Release -DCYAN_SHARED_GEMMA=ON \
  -DCYAN_JNI_INCLUDE_DIR="$ANDROID_HOME/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include"
cmake --build /tmp/opencode/shared-gemma-host-build --target cyan_shared_gemma -j 4
"$JAVA_HOME/bin/javac" -d /tmp/opencode/shared-gemma-host-classes \
  android/CyanBridge/llama-runtime/src/testHost/java/com/fersaiyan/cyanbridge/llama/SharedGemmaBridge.java
"$JAVA_HOME/bin/java" -Xcheck:jni -cp /tmp/opencode/shared-gemma-host-classes \
  com.fersaiyan.cyanbridge.llama.SharedGemmaBridge \
  /tmp/opencode/shared-gemma-host-build/libcyan_shared_gemma.so "$EXISTING_CHAT_GGUF"
```

The smoke's chat fixture is ChatML (the run used Qwen2.5); it verifies the JNI
contract, not Gemma-specific prompt quality. The decision renderer itself uses
the GGUF's model template.

Next benchmark E2B/E4B on the same open development requests as the specialist
reference, including the complete family → category → setting → eligible-choice
paths. Compare original/reversed menus, DONE/DEFER and control false actions,
low label mass, stale state, missing candidates and real Tasker outcomes. Keep
calibration/sealed cohorts unopened during prototype development. Measure cold
load and peak/steady PSS, context allocation, prefill latency and chat→decision
switching on actual 4/6-GB phones before setting a supported-memory claim.
Fit new probability/margin/label-mass gates only on an appropriately reserved
calibration split after selecting the renderer and quantization.

The direct upstream adapter makes APIs and pins easier to maintain; it has no
measured speed advantage over the old wrapper here. Android multi-ISA flags
require dynamic-backend packaging and a suitable NDK/minSdk matrix; merely
copying the flags does not prove SME2 or GPU execution. GPU work should measure
the active backend, total shared memory and decision parity separately.
