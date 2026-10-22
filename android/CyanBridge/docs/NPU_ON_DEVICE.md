# Running NPU models in CyanBridge: what is required

Updated: 2026-10-10. Target first: Qualcomm SM8650 (Snapdragon 8 Gen 3, Hexagon
V75; Galaxy Z Fold6 `RQCX700KSDF`, Android 15).

**Status:** with the opt-in NPU runtime build, one QAIRT 2.50 runtime runs all of these on
the Fold6's NPU at CPU-level accuracy:
- WeSpeaker;
- Walking Aid YOLO11n and YOLO-World;
- EmbeddingGemma 2 (270M and 740M);
- Gemma 4 E2B (including the decision LoRA, 118/120) and E4B.

Context lengths up to 32k load. 16k is the practical speed limit, and a 4k+16k
multi-bucket package keeps short chats fast. Long-context answers are not reliable yet:
the feasibility builds degenerate past ~4k with 4k calibration (confirmed on the host as a
calibration problem, not an HTP one), and long calibration restores coherence but not yet
CPU-level retrieval. See "Gemma 4 LLMs on the NPU".

**2026-10-10:**
- **Downloads:** the Gemma 4 E2B NPU entry can be downloaded and selected from Local Models
  settings, from a public Hugging Face repo (see "Per-SoC model downloads").
- **Image and audio:** the NPU packages so far were text-only. Image + audio packages for E2B
  and E4B are built and pass host checks, but have not run on a phone yet (see "Image and
  audio on the NPU").
- **Emulator:** image and audio pass on the Pixel 9a AVD through the app's own path, for
  llama.cpp (Gemma 4 GGUF + mmproj) and LiteRT (Gemma 4 E2B). Three app bugs were found and
  fixed along the way (see "Image and audio: emulator results").

## Device results (Galaxy Z Fold6, SM8650, QAIRT 2.50 runtime)

| Model | NPU | CPU (same phone) | Speedup | Accuracy NPU vs CPU |
|---|---:|---:|---:|---|
| WeSpeaker ResNet34-LM (`KaldiFbankNpu`), 2 s audio | **12.3 ms** | 226 ms (Interpreter, 4 threads) | **18×** | embeddings vs host cos ≥ 0.99993; target 0.518 vs other 0.283 (CPU 0.517 / 0.284) |
| Walking Aid YOLO11n, 640×640 frame | **10.4–11.4 ms** | 47–61 ms (XNNPACK, 4 threads) | **≈5×** | COCO val2017 200 images: mAP@.5:.95 **0.297** vs 0.290 |
| Walking Aid YOLO-World (int8, float I/O) | **74 ms** | 1,650 ms | **22×** | COCO 200 images: mAP@.5:.95 **0.508** vs 0.507 |
| EmbeddingGemma 2 text 270M (Google's SM8650 bundle, **QAIRT 2.49 context**) | **8 ms**/embedding; 123 ms per decision | 65–99 ms/embedding; 1,320 ms per decision | **≈11×** | bounded dev set 47/120 both; same top choice 107/120 |

More detail:
- **Load times:** WeSpeaker 295 ms, YOLO11n 218–329 ms, YOLO-World 423 ms, and an
  EmbeddingGemma 2 cold first embedding of 1.35–1.48 s (CPU 0.79–0.95 s).
- **Speedup basis:** latencies are medians of 20 runs after 3 warm-up runs (inference
  only). YOLO through the full `LiteRtVisionBackend` path: NPU 11–12 ms vs CPU
  46–64 ms of inference time per frame.
- **One runtime covers older contexts:** Google's 2.49-compiled EmbeddingGemma 2 runs
  on the 2.50 runtime. Its NPU embeddings differ in scale from the CPU bundle's
  (related/unrelated 0.80/0.31 vs 0.93/0.65; it is a separately quantized model), but
  decisions match.
- **The emulator is faithful:** the phone's NPU reproduces the host HTP emulation
  exactly. On YOLO11n, the 16-image detections are identical (recall and precision
  1.0) and the 200-image COCO metrics match to every reported digit. When no phone is
  attached, emulation stands in for the NPU.
- **Memory:** `Debug.getPss()` after the EmbeddingGemma 2 run was 365 MB on the NPU vs
  447 MB on CPU. That is indicative only (single snapshot); `dumpsys meminfo` runs
  are still to do.

### Tests executed on the Fold6 (opt-in NPU build, `install -r`, data kept)

| Test (`src/androidTestLitertNpu` unless noted) | What it checks | Result |
|---|---|---|
| `LiteRtNpuSpeakerTest` (CPU + NPU) | WeSpeaker CompiledModel CPU and AOT NPU vs host fixture-npu embeddings; target > other | 2/2 pass |
| `WalkingAidNpuDetectorTest` (CPU + NPU, 16-image fixture) | CompiledModel CPU bytes = Interpreter bytes; NPU detections vs host emulation | 2/2 pass; NPU = emulation (1.0/1.0) |
| `WalkingAidNpuDetectorTest#yolo_npu…` with `-e yolo_dump_dir` (200 images, YOLO11n and YOLO-World) | raw NPU outputs dumped for host COCO scoring (`yolo_htp_parity.py --npu-raws`) | pass; COCO table above |
| `WalkingAidNpuBackendTest` | `LiteRtVisionBackend` itself with `NPU_EXPERIMENTAL`: picks `…_Qualcomm_SM8650.tflite`, matches emulation; CPU backend matches host CPU | pass (NPU 1.0/1.0; CPU 0.97/0.98); temporary model files removed |
| `LiteRtEmbeddingRuntimeTest` (`src/androidTest`), EmbeddingGemma 2 NPU and CPU, with the bounded dev set | LiteRT-LM `Backend.NPU` on the 2.49 bundle; related > unrelated; dev-set decisions | pass both |
| `LiteRtEmbeddingRuntimeTest`, EmbeddingGemma 2 **740M** NPU and CPU | same, 740M bundle | pass; 47/120 both, 125 vs 1,144 ms per decision |
| `LiteRtBackendBenchmarkTest` (`src/androidTest`, new `NPU` option) | Gemma 4 E2B/E4B NPU packages (4k, 8k, 16k, 32k, 4k+16k multi-bucket) and stock E2B CPU/GPU | tables in "Gemma 4 LLMs on the NPU" |
| `LiteRtBoundedDecisionEvalTest` (`src/androidTest`), `NPU_EXPERIMENTAL`, annotated dev set | decision LoRA on the QAT E2B NPU package | **118/120**, 0 control false actions, p50 1.34 s |
| `LiteRtLongContextProbeTest` (new) | multi-turn chat across the 4k bucket; needle mode for long-calibrated builds | runs; 4k-calibrated long builds degenerate past ~4k (host-confirmed calibration cause) |
| `WalkingAidNpuDetectorTest` with `-e yolo_soak_seconds 90` (NPU and CPU) | sustained latency; host samples `dumpsys meminfo` and temperatures | NPU steady 11–14.5 ms; CPU 41 → 58 ms as it heats |

On the Pixel 9a AVD (x86_64, no NPU), with the same build:
- the CPU tests pass and the NPU tests skip cleanly ("SoC ranchu");
- `WalkingAidVisionStackIntegrationTest` (real models, bus photo) passes;
- `AudioSpeakerLiteRtProbeTest` (Interpreter 1.4.2) passes.

All 548 unit tests pass on LiteRT-LM 0.16 and on 0.18 with the NPU AAR.

### Testing policy: device first

With a supported Snapdragon phone attached, run NPU checks there:
- 200 YOLO images take 8 s (YOLO11n) or 65 s (YOLO-World) on the Fold6, vs about 12
  min and roughly 6–7 h in the x86 emulator;
- use `make_yolo_fixture.py --skip-htp`, then the device test with
  `-e yolo_dump_dir`, then `yolo_htp_parity.py --npu-raws`.

Use the host emulator only without a phone, or to bisect a graph stage by stage. The
AVD has no NPU: it only covers the CPU paths and the skip/fallback behaviour.

## Gemma 4 LLMs on the NPU (Fold6, 2026-10-08)

The other agent's recipe builds the packages (`/mnt/seagate/ML/gemma4-sm8650-port`,
work copies in `~/nvme_tmp/gemma4-sm8650-work/work_*`):
- QAT checkpoint, recalibrated INT16 activations;
- QAIRT 2.50 AOT with `qualcomm_enable_weight_sharing`;
- Google's LiteRT-LM chat template.

They ran through LiteRT-LM 0.18 `Backend.NPU` in the opt-in NPU build. CPU and GPU rows
use Google's stock `gemma-4-E2B-it.litertlm` on the same phone (`LiteRtBackendBenchmarkTest`:
prefill N tokens, then 128 decode tokens).

| Build | Prefill tok/s | Decode tok/s | Init | Peak PSS |
|---|---:|---:|---:|---:|
| E2B NPU, 4k cache | **2,250–2,900** | **32–35** | 6–8 s | 1.31 GB |
| E2B GPU (stock) | 540–1,050 | 14–16 | 13–16 s | – |
| E2B CPU (stock) | 110–205 | 11–19 | 1–1.4 s | – |
| E4B NPU, 4k cache | 585–622 | 11.6 | 25–31 s | 2.43 GB |

**Decisions** (bounded dev set, 120 turns, annotated prompts; LoRA retrained on the QAT
base, 205 INT8 rank-16 branches):

| Build | Correct | Control false actions | Decision p50 |
|---|---:|---:|---:|
| **E2B + decision LoRA, NPU** | **118/120** | **0.0** | **1.34 s** (p95 1.50 s) |
| Our earlier INT4 LoRA export, GPU | 110/120 | 0.0 | 4.6 s |
| Our earlier INT4 LoRA export, CPU | 109/120 | 0.0 | 15.2 s |

**Context length: 4k is a default, not a Qualcomm limit.** The KV cache is fixed at
export. 8k, 16k and 32k builds of E2B all compile fully and load (context 815 → 824 →
838 → 879 MB; KV is an I/O tensor). Speed falls with the compiled cache size, because every
step attends over the whole cache:

| E2B NPU cache | Prefill tok/s | Decode tok/s | Long prompt, time to first token | Init | Peak PSS |
|---|---:|---:|---|---:|---:|
| 4k | 2,250–2,900 | 33–35 | 3,072 tokens: 1.1–1.3 s | 6–8 s | 1.31 GB |
| 8k | 1,110–1,250 | 15–16 | 7,000 tokens: 6.1 s | 12–18 s | 1.64 GB |
| 16k | 560–660 | 11.5–12 | 14,000 tokens: 22 s | 21–27 s | 1.71 GB |
| 32k | 113–116 | 4.7–4.8 | 28,000 tokens: 246 s | 42–70 s | 1.60 GB |
| **4k + 16k multi-bucket** | 1,530–2,206 (≤ 4k) / 1,039 (7k prompt) | **30.3–30.5** (≤ 4k) / 11.6 (> 4k) | 7,000 tokens: 6.8 s | 27–36 s | 1.62 GB |

- **32k cliff:** 32k works but is slower than the CPU: about 0.55 GiB of I/O plus spill
  buffers per graph. 16k is the practical maximum for E2B on SM8650.
- **Multi-bucket package:** LiteRT-LM 0.18 multi-cache signatures
  (`prefill_128_cache_<N>`, `decode_cache_<N>`, per-bucket mask and cache_update
  aux), merged into one weight-sharing context (850 MB):
  - short chats run at near-4k speed;
  - when a conversation passes 4,096 tokens the runtime logs "Switched context size from
    4096 to 16384", and the KV copy (`CopyKVCache`) took 91 ms;
  - the cost is a longer init.
  - All buckets need the same KV calibration (the copy is raw), so long-context
    calibration ("_lc" builds) comes before shipping.
- **Long-range quality is not solved yet.** `LiteRtLongContextProbeTest` runs a greedy
  three-turn chat: plant a code word, send ~4.8k tokens of notes with a fact in the middle,
  then ask for both.
  - The 4k+16k multi-bucket build said "OK" to turn 1, then degenerated into repeated
    "Note: 1." until the cache filled.
  - The 16k-only build also produced no answer to turn 2 after minutes (stopped).
  - These feasibility builds reuse **4k calibration**: INT16 activation ranges fitted on
    prompts of at most 4k tokens clip beyond that.
  - The stock CPU reference run ended without logging turns (inconclusive).
  - **Host confirmation (recipe session, same 7.5k-token needle prompt):**
    - The FP32 16k export matches transformers' QAT output text for text at 3.5k, 7.5k
      and 15k tokens, and finds the 7.5k needle. The export is faithful.
    - Quantized A16 16k on the host CPU with 4k calibration degenerates (repeated
      "```"), reproducing the device failure. So the cause is calibration, not the HTP
      or the bucket switch.
    - The same build with 16k long calibration is coherent ("I do not have access…")
      but misses the needle FP32 finds: long calibration fixes coherence, not yet
      retrieval.
    - E2B itself misses 3 of 5 needles even in FP32. Score device needle runs by
      **agreement with the FP32 reference** (`work/needle_eval/host/hf_ref.log` in the
      recipe tree), not by raw hits.
  - Next phone session: `LiteRtLongContextProbeTest#needleEval` on `work_e2b_ctx8k_lc`, then
    `work_e2b_mb4k16k_lc/package/model.litertlm` (sha256 `98ca4d916aa27354…`; both buckets
    use the 16k long calibration), then the multi-turn probe on that package. Expect
    coherence past 4k but weaker retrieval than CPU. The 32k long build is cancelled
    (32k is slower than the CPU anyway).
- Research summary (sources in the 2026-10-08 worklog):
  - Qualcomm said "4096 only" in late 2024; its 2026 FAQ supports `--context-length 8192`
    and calls 32k difficult.
  - Google's model card only says the NPU build was benchmarked at 4096.
  - LiteRT-LM added NPU multi-cache buckets and dynamic KV growth in Aug–Sep 2026.
  - Watch out for litert-torch#1184 (an HTP mask defect once an operand exceeds 1 MiB).

**Limits found on the phone:**
- **One NPU process domain per app.**
  - E4B's first context (estimate 4,052,082,176 B) failed with `Failed to find available
    PD ... err 1002`.
  - QNN then tried extra PDs, and fastrpc refused for an app ("createUnsignedPD ... not
    supported", `Permission denied`).
  - Everything an app maps on the NPU must fit one 32-bit DSP address space, about 3.5
    GiB in practice.
  - Weight sharing brought E4B to 2.26 GB and it loads.
- **Chat template:** HF's newer Gemma template uses `dict.get`, which LiteRT-LM 0.18's
  template engine rejects ("map has no method named get"). Packages must carry Google's
  LiteRT-LM template.
- **Per-layer embeddings:** the NPU's hardware PLE lookup accepts only INT4/INT8 tables.
  E4B's INT2 per-layer tables failed prefill with `INVALID_ARGUMENT`; widening them
  losslessly to INT4 fixed it.
- **EmbeddingGemma 2 740M** (QAIRT 2.49/2.50 contexts) on NPU: 8 ms per embedding and
  125 ms per decision vs CPU 60 ms and 1,144 ms; dev set 47/120 on both.

**Walking Aid soak** (YOLO11n, 90 s continuous, app PSS ~240–290 MB on both):
- **NPU:** p50 11 → 14.5 ms, steady (6,274 frames, about 70 fps); AP 40 → 39 °C, skin
  flat at 36.6 °C.
- **CPU (4 threads):** p50 41 → 58 ms, slowing 40% as it heats; AP 40 → 45 °C, skin
  36.8 → 39.1 °C.

## Per-SoC model downloads (Hugging Face)

Each model is compiled per Snapdragon SoC. The app reads `Build.SOC_MODEL`, downloads the
matching file from a public Hugging Face repo, and checks its sha256.

**Live (2026-10-10):** catalog entry `gemma4-e2b-it-npu`, "Gemma 4 E2B IT (Snapdragon NPU)".

| SoC | File | Source (pinned commit) | Input | sha256 |
|---|---|---|---|---|
| SM8650 (V75) | `gemma-4-E2B-it_Qualcomm_SM8650.litertlm` | [`Fertroll10/gemma-4-E2B-it-litert-lm-npu`](https://huggingface.co/Fertroll10/gemma-4-E2B-it-litert-lm-npu) @ `947dafae` | text | `70c0223b…` |
| SM8750 (V79) | `gemma-4-E2B-it_qualcomm_sm8750.litertlm` | Google's `litert-community/gemma-4-E2B-it-litert-lm` @ `b3ca0d2f` | text, image, audio | `41dd675f…` |

- **SM8650 build:** the validated 4k package (`work_e2b_general`, QAIRT 2.50; benchmarks in
  "Gemma 4 LLMs on the NPU"). The repo has a model card.
- **SM8750 build:** Google's own. Not yet run on an SM8750 phone. Google's QCS8275 (IoT)
  file is not listed.
- **Catalog:** `npuVariants: List<NpuVariantSet(repo, filePrefix, files: SoC -> NpuVariantFile(sha256,
  size), revision, lowercaseSoc)>`. The first set that publishes this SoC wins. `revision`
  pins a commit, so a later upload can't change what a checksum pins. `lowercaseSoc` covers
  Google's naming.
- **Resolution:**
  - `NpuVariantResolver.resolve(entry, soc)` (shared, unit-tested) returns the SoC's URL,
    file name, sha256 and size.
  - `NpuCatalogVariants.forDevice` also requires the NPU runtime build and a supported SoC.
    Otherwise it supplies the reason shown in place of the download button.
  - The settings page shows the resolved size and status. Unsupported phones see the
    entry with a disabled "Unavailable" button and the reason.
- **Selection:**
  - A downloaded NPU package is registered under the catalog id and selected.
  - Its default settings are LiteRT on `NPU_EXPERIMENTAL` (`defaultsFor` with `npuOnly`).
  - `LiteRtLocalInferenceEngine` forces the NPU backend for any `*_Qualcomm_<SoC>` file,
    including imports, and does not fall back to GPU/CPU for such packages, which can't run
    there. It reports the NPU error instead.
- **Runtime AAR:** now `litert-npu-qualcomm-2.3.0-qairt2.50.0.260828-v75-v79.aar` (sha256
  `524078fb…`, +4.5 MB) with the V75 and V79 stub/skel. Other Hexagon architectures
  (SM8450 v69, SM8550 v73, SM8850 v81) need `package_npu_aar.py --hexagon` plus a published file.
- **Upload new files** with the commit pinned in `LocalModelCatalog.kt` (`NPU_E2B_REVISION`)
  and their sha256/size, then update the model card's table.
- **Publish only 4k builds for now.** The model card (commit `66c2f494`) states that
  longer-context builds are not production quality. No ctx/multi-bucket package goes to the
  repo until long-context retrieval is fixed, as agreed with the recipe owner.

## Image and audio on the NPU

The first NPU packages held only the text model. A subagent added Google's vision and
audio sections. The packages are built and pass host checks, but haven't run on a phone.

- **Script:** `recipe/add_multimodal_sections.py` in `~/nvme_tmp/gemma4-sm8650-work`.
  - Takes our package plus Google's generic `.litertlm` encoders: vision encoder, vision
    adapter, audio encoder and adapter, end-of-vision and end-of-audio.
  - No MTP drafter.
  - Every output section is byte-identical to its source. The NPU main is unchanged; no
    recompile.
- **Metadata:** ours kept: template, stop strings, `max_num_tokens` 4096. Added from
  Google's: image/audio boundary tokens, patch size 16, `max_num_patches` 2520, and stop id
  50 (`<|tool_response>`).
- **Contract checks pass:**
  - adapter and end-of-media widths equal the main `embeddings` input (1536 E2B, 2560 E4B);
  - the tokenizer is identical to Google's across all 262,144 pieces;
  - the adapters hold our QAT checkpoints' projection weights byte for byte;
  - the image attention is causal in both configs, which our aux mask matches.
- **Packages** (`work_e2b_general_mm`, `work_e4b_general_mm`, `package_report.json` next to each):

| Package | Size | sha256 |
|---|---:|---|
| E2B 4k image + audio | 2.65 GB | `78e120e4…` |
| E4B 4k image + audio | 4.45 GB | `0fd6913e…` |

- **Host check** (`recipe/host_mm_check.py`: pre-AOT A16 main on CPU, LiteRT-LM 0.18's
  preprocessing, greedy):
  - E2B: COCO bear → "The image features a bear."; `gemini_live_hello_5s.wav` → "Hello, I
    like red flowers."; French clip → "Hello, how are you?"
  - E4B: stop sign described correctly; the hello clip transcribed in full.
- **App settings:** `Backend.NPU` main, vision GPU (or CPU), audio **CPU** (the audio
  encoder is tagged cpu). This is what `initializeNpuWithFallback` already does. The
  encoders stay out of the NPU process domain.
- **Before shipping:**
  1. Run `LocalMultimodalProviderTest` with the E2B package on the Fold6 NPU.
  2. Upload it over the SM8650 file in the HF repo.
  3. Bump `NPU_E2B_REVISION`, sha256 and size.

## Image and audio: emulator results (Pixel 9a AVD, x86_64, CPU, 2026-10-10)

`LocalMultimodalProviderTest` (`src/androidTest`) asks through `LocalModelsProvider.streamChat`,
the call the glasses image/voice flow makes.
- The model under test is the only installed model during the run.
- The model registry, projector and settings preferences are restored afterwards.
- Inputs: the Walking Aid `bus.jpg`; `gemini_live_hello_5s.wav` ("…I like red flowers").
- Greedy decoding, the app's default system prompt.

| Runtime / model | Image answer | Audio answer | Time to first token (image / audio) |
|---|---|---|---|
| llama.cpp, Gemma 4 E2B Q4_0 + mmproj Q8_0 (ggml-org) | "The vehicle in the picture is an electric bus." | The speaker says, "I like red flowers." | 76 s / 25 s |
| LiteRT-LM 0.18, Google `gemma-4-E2B-it.litertlm` | "…a public transit bus." | The speaker says, "I like red flowers." | 22–30 s / 7 s |

**Bugs found and fixed** (each reproduced on the emulator, then fixed and rerun):
1. **Gemma 4 GGUF prompts used ChatML.**
   - Imported models had no template, so they fell back to `generic_chatml`, and replies
     leaked `<|im_end|>`.
   - Added a `gemma4_it` template (Google's `<|turn>role … <turn|>` format) and file-name
     inference for imports (`PromptTemplateRegistry.inferForFileName`).
   - The old `gemma_it` (Gemma 2/3 `<start_of_turn>`) is unchanged.
2. **LiteRT media requests inlined the system prompt after the attachment.**
   - With the default prompt, Gemma 4 then answered "I need the recording…".
   - The system prompt now goes to LiteRT-LM's `systemInstruction`
     (`GenerationConfig.systemInstruction`, `buildMultimodalPrompt` returns system and user
     parts).
3. **Audio after an image failed in one LiteRT engine.**
   - Error: XNNPack "Node 2184 failed to prepare", `audio_litert_compiled_model_executor.cc:887`.
   - Audio before an image works, and keeps working afterwards.
   - `LiteRtLocalInferenceEngine` now runs the audio encoder once, on a 0.5 s silent clip,
     before an engine's first image request. That cost 7.6 s once on the emulator.
   - Not yet checked whether phones show the same failure.

Other notes:
- Upstream `llama-mtmd-cli` at the same pin transcribes the clip correctly, so the llama.cpp
  bridge and model are sound.
- With the app's terse default system prompt, E2B refuses a literal "Transcribe this audio
  exactly." (llama.cpp) but answers a natural question. The test asks "What does the speaker
  say in this recording? Quote it."
- `NpuCatalogSettingsTest` on the emulator: the entry is listed, Download is disabled, and the
  reason "No NPU build for this processor (ranchu) yet." is shown.
- Commands (push models to `/data/local/tmp/mm`):

```bash
adb shell am instrument -w -r -e class com.fersaiyan.cyanbridge.hil.LocalMultimodalProviderTest \
  -e mm_model /data/local/tmp/mm/gemma-4-E2B-it-Q4_0.gguf -e mm_projector /data/local/tmp/mm/mmproj-gemma-4-E2B-it-Q8_0.gguf \
  -e mm_image /data/local/tmp/mm/bus.jpg -e mm_audio /data/local/tmp/mm/gemini_live_hello_5s.wav \
  com.fersaiyan.cyanbridge.test/androidx.test.runner.AndroidJUnitRunner
# LiteRT: -e mm_model <gemma-4-E2B-it.litertlm> [-e mm_backend CPU|GPU|NPU] [-e mm_catalog_id gemma4-e2b-it-npu]
# Options: -e mm_order image_first|audio_first|audio_image_audio, -e mm_system none|<text>, -e mm_audio_prompt <q>
adb shell am instrument -w -r -e class com.fersaiyan.cyanbridge.hil.NpuCatalogSettingsTest [-e npu_download true] \
  com.fersaiyan.cyanbridge.test/androidx.test.runner.AndroidJUnitRunner
```

## One runtime stack: LiteRT 2.3.0 + QAIRT 2.50.0.260828

| What | Runtime in the app | Artifact |
|---|---|---|
| LLM / embedding bundles (Gemma 4 E2B, EmbeddingGemma 2) | LiteRT-LM 0.18 `Engine` / `EmbeddingEngine` with `Backend.NPU(nativeLibraryDir)` | SoC-specific `.litertlm` (e.g. `embeddinggemma-2-*_Qualcomm_SM8650.litertlm`) |
| Plain models (WeSpeaker, ECAPA) | LiteRT 2.3.0 `CompiledModel` with `Accelerator.NPU` | AOT `.tflite` containing a `DISPATCH_OP` and a QNN context |

Both runtimes load the same `libLiteRtDispatch_Qualcomm.so` and QNN libraries from
`nativeLibraryDir`, so the app can ship exactly one QAIRT version. That version is
**QAIRT 2.50.0.260828**:

- LiteRT-LM 0.18.0 pins LiteRT `26895c9f` (2026-09-25), whose
  `third_party/qairt/workspace.bzl` is QAIRT 2.50.0.260828. LiteRT v2.3.0 is 16
  commits later with no Qualcomm or dispatch changes, and `ai-edge-litert-sdk-qualcomm`
  2.3.0 downloads the same QAIRT.
- The dispatch library refuses QNN libraries older than the QAIRT it was built with
  (`qnn_manager.cc`: core/backend API minor below the build headers is
  `kLiteRtStatusErrorDynamicLoading`; newer is a warning). QAIRT **2.47 runtime
  libraries cannot load under the 2.3.0 dispatch library or LiteRT-LM 0.18**. That is
  the LiteRT-LM #2226 failure (QNN System 1.7.0 < 1.8.0).
- Google's EmbeddingGemma 2 SM8650 bundles hold contexts from QAIRT 2.49.0.260730
  (text 270M) and 2.49/2.50 (740M). **Confirmed on the Fold6:** the 2.50 runtime runs
  the 2.49 text context (8 ms per embedding, same dev-set decisions as CPU). Our own
  models are compiled with 2.50.
- The previous recipe (`recipe_env`: ai-edge-litert 2.2.0, QAIRT 2.47) stated QAIRT
  2.47 as a minimum (A8W2 support), not a pin. The 2.50 environment is
  `~/nvme_tmp/npu-litert230/env` (ai-edge-litert 2.3.0 and sdk-qualcomm 2.3.0; the
  QAIRT zip is extracted manually, see below).

The app keeps `com.google.ai.edge.litert:litert:1.4.2` for walking-aid: LiteRT 2.3.0
uses the same Maven coordinates but drops `NnApiDelegate` and the classic
`GpuDelegate`. The NPU AAR therefore carries only the `com.google.ai.edge.litert`
classes (no `org.tensorflow.lite` classes, so nothing clashes with 1.4.2) and their
natives `liblitert_jni.so` and `libLiteRt.so`.

## Packaging: the NPU runtime AAR (opt-in)

[`tools/litert-npu/package_npu_aar.py`](../tools/litert-npu/package_npu_aar.py) builds
`litert-npu-qualcomm-<litert>-qairt<version>-<archs>.aar` from Maven artifacts
(SHA-1 checked against Google Maven) and the QAIRT SDK:

| File (`jni/arm64-v8a`) | Source | Why |
|---|---|---|
| `libLiteRtDispatch_Qualcomm.so` | `com.google.ai.edge.litert:litert-npu-runtime-qualcomm:2.3.0` | Runs `DISPATCH_OP` contexts. No local Bazel build needed. |
| `libQnnHtp.so`, `libQnnSystem.so` | QAIRT `lib/aarch64-android` | QNN HTP backend and system API |
| `libQnnHtpV75Stub.so` | QAIRT `lib/aarch64-android` | ARM side of FastRPC; needs vendor `libcdsprpc.so` |
| `libQnnHtpV75Skel.so` | QAIRT `lib/hexagon-v75/unsigned` | Hexagon DSP side (QDSP6 ELF) |
| `liblitert_jni.so`, `libLiteRt.so` (+ x86_64) | `litert-api` / `litert` 2.3.0 | `CompiledModel` |

- Add Hexagon architectures with `--hexagon` (SM8450 v69, SM8550 v73, SM8650 v75,
  SM8750 v79, SM8850 v81); each adds a stub and skel of about 12 MB.
- `--jit` adds `libLiteRtCompilerPlugin_Qualcomm.so` and `libQnnHtpPrepare.so`
  (86 MB) for on-device compilation. AOT models do not need them.
- The AAR manifest declares `<uses-native-library android:name="libcdsprpc.so"
  android:required="false"/>`. Apps targeting API 31+ cannot open the vendor FastRPC
  library without it, and neither LiteRT-LM's AAR nor our manifest declared it.
- All ARM libraries are checked for 16 KB-page `LOAD` alignment and platform-only
  dependencies. The QAIRT 2.47 and 2.50 Android libraries already meet both.
- Qualcomm binaries are not committed. Rebuild the AAR from the SDK.

Gradle: `-PlocalLiteRtNpuAarPath=<aar>` (requires `-PliteRtLmVersion=0.18.0`)
- adds the AAR,
- sets `BuildConfig.LITERT_NPU_RUNTIME`,
- switches `jniLibs.useLegacyPackaging = true`,
- keeps the Hexagon skel unstripped,
- adds the `src/androidTestLitertNpu` tests.

Without the property nothing changes. `LiteRtNpuSupport` checks the build, `Build.SOC_MODEL`
and the libraries in `nativeLibraryDir` before LiteRT-LM tries `Backend.NPU`. It also
rejects a `*_Qualcomm_<SoC>` model meant for another SoC. Either way the user gets a
fallback reason instead of a native error.

- The DSP loads the skel from a real file path, hence extracted (legacy) packaging.
  The dispatch library sets `ADSP_LIBRARY_PATH` to its own directory
  (`nativeLibraryDir`) when the app has not set it.
- ARM64 only. The x86_64 AVD has no NPU: the NPU test skips, and the CPU
  `CompiledModel` test still runs there.

## Contexts are SoC-specific: ship per-SoC artifacts and a fallback

- An AOT QNN context runs only on its SoC/DSP architecture (SM8650 = SoC 57 /
  V75). Gate on `Build.SOC_MODEL` (API 31+) and otherwise use CPU/GPU.
- After compiling, verify the context really targets SoC 57 / V75 (LiteRT #8037
  reports host AOT ignoring `--soc_model`).
- Deliver large per-SoC files on demand (download, Play Feature Delivery / AI
  packs), not inside every APK.
- Keep CPU/GPU fallbacks for every NPU model. Results must not depend on the
  backend, because enrollment templates are computed with whichever backend ran.

## Numerics: a clean compile is not proof

Qualcomm HTP computes float graphs in **fp16** (max 65,504), and some op shapes
lower differently from CPU. Check each NPU graph at two levels:

- **fp16 simulation**
  ([`audio_speaker_fp16_sim.py`](../../../tools/benchmarks/audio_speaker_fp16_sim.py)):
  the PyTorch graph in half precision. This catches overflow.
- **QNN HTP emulation**
  ([`tools/litert-npu/htp_emulator_parity.py`](../tools/litert-npu/htp_emulator_parity.py)):
  the AOT-compiled context executed by the QAIRT SDK's x86 `libQnnHtp.so`, compared
  with LiteRT CPU. This catches lowering bugs that fp16 simulation cannot see.

WeSpeaker ResNet34-LM, LibriSpeech test-clean (2 s, 5-phrase templates), fp32
reference EER 0.25 %:

| WeSpeaker graph | AOT offload (QAIRT 2.50) | fp16 sim EER | HTP emulation vs CPU |
|---|---|---:|---|
| Original (int16-scale Kaldi front end) | 88/88, 1 partition | **50 %** (all non-finite) | not run |
| Split: fp32 front end on CPU + ResNet head on NPU | head 71/71, 1 partition | 0.25 % | head cos ≥ 0.999996 |
| fp16-safe single graph (`KaldiFbankFp16Safe`) | 91/91, 1 partition | 0.25 % | **cos ≈ 0.06, then emulator crash** |
| **NPU single graph (`KaldiFbankNpu`)** | **94/94, 1 partition** | 0.25 % (same math) | **EER 0.25 %** (= CPU) on 1,200 crops; cos min 0.9993, mean 0.99997; 0 non-finite |

Bisecting the fp16-safe graph stage by stage
([`audio_speaker_frontend_stages.py`](../../../tools/benchmarks/audio_speaker_frontend_stages.py))
showed it already wrong at the first op: HTP computes the 400-tap, stride-160 DFT
`conv1d` incorrectly. The magnitudes are right, but the values don't match any CPU
frame. QAIRT 2.47 and 2.50 behave the same. `KaldiFbankNpu` builds frames from
160-sample blocks instead: frame t is blocks t, t+1, t+2, a stride-1, kernel-3 conv
over 160 channels, with the last 80 weights zero. It gives the same features as the
parent (max difference 6e-6 in PyTorch). Every stage then matches in emulation:

| Stage | 400-tap conv (cos vs CPU) | Block framing (cos vs CPU) |
|---|---:|---:|
| DFT spectrum | 0.04 | 0.9999995 |
| power / mel | 0.02 / 0.03 | 0.9999999 / 0.9999999 |
| log-mel | 0.98 (offset-dominated; max error 13) | 0.9999999 |
| features (mean-normalised) | emulator crash | 0.999995 |
| full model | emulator crash | 0.99995 |

Rules that follow:

- Before a device run, every NPU graph passes fp16 simulation of the **task
  metric** and HTP emulation parity. A full-offload compile alone proved nothing.
- Signal-processing front ends:
  - Scale the DFT basis so power and mel values stay below ~1e4.
  - Floor at fp16's smallest normal value; saturate below fp16's maximum.
  - Put scale constants after the log.
  - Avoid long-kernel strided convolutions: build frames from hop-sized blocks.
- NPU-variant embeddings differ from the CPU WeSpeaker export (mean cosine 0.97).
  Enrollment and verification must use the same variant; templates record the
  model and variant.
- Quantization constraints seen with QAIRT 2.47:
  - Weight-only int8 fails (per-channel `DEQUANTIZE` unsupported).
  - Fully static W8A16 left int16 `DEPTHWISE_CONV_2D` and `PAD` on CPU (6
    partitions).
  - Float (fp16) or carefully calibrated static quantization of the whole graph
    is the practical route for small models.
- ECAPA can follow the same pattern (fp16-safe gain, block framing, emulator check).
  Its SpeechBrain front end floors 80 dB below each utterance's peak. Not exported
  yet: it needs SpeechBrain in an isolated venv.
- Emulator limits: the x86 HTP backend cannot create Google's EmbeddingGemma 2
  contexts. Error `0x3e8` with QAIRT 2.49 *and* 2.50, while it reports 143 MB of
  shared weights across four graphs. On the Fold6 the same bundle runs correctly on
  the 2.50 runtime, so this is an emulator limit only.

## Walking Aid detectors on the NPU

| Detector (catalog file) | Format | AOT SM8650, QAIRT 2.50 |
|---|---|---|
| YOLO11n (`yolo11n_float16.tflite`, really a uint8 Qualcomm AI Hub export, 2.97 MB) | uint8 image → boxes, scores, class_idx (post-processing in-graph) | **369/369 ops, 1 partition**, 2 s; context 3.2 MB, SoC 57 / V75 |
| YOLO-World (`yolo_world.tflite`, int8 inside, float I/O, 73 MB) | float image in [0, 1] → anchor-distance boxes, scores [8400, 80] | **487/487 ops, 1 partition**, 9 s |

COCO val2017, first 200 annotated images, app preprocessing (stretch to 640) and
decoding (score ≥ 0.35, class-aware NMS 0.45). mAP over all candidates with score
≥ 0.01; precision and recall at the app's 0.35 threshold, IoU 0.5:

| Detector | Backend | mAP@.5:.95 | mAP@.5 | Precision @0.35 | Recall @0.35 |
|---|---|---:|---:|---:|---:|
| YOLO11n | CPU (host LiteRT) | 0.290 | 0.463 | 0.745 | 0.384 |
| YOLO11n | NPU (Fold6 = host emulation, identical) | **0.297** | 0.476 | 0.785 | 0.378 |
| YOLO-World | CPU (host LiteRT) | 0.507 | 0.674 | 0.635 | 0.682 |
| YOLO-World | NPU (Fold6) | **0.508** | 0.678 | 0.639 | 0.676 |

- **Agreement:** per-detection agreement between backends is not 1:1. NPU vs CPU
  recall is 0.76 for YOLO11n (matched IoU 0.89) and 0.91 for YOLO-World (IoU 0.96).
  Both are int8 approximations of a float model, rounded differently, so borderline
  objects flip. Ground truth is the yardstick: the NPU is as accurate as CPU.
- **YOLO-World is the more accurate detector** (mAP 0.51 vs 0.29, recall 0.68 vs
  0.38 at the app threshold), and on the NPU it runs at 74 ms per frame instead of
  1.65 s.
- **Two existing app bugs fixed** (both affected every backend):
  - **YOLO-World boxes:** the catalog export outputs undecoded left/top/right/bottom
    distances in stride units. `LiteRtVisionBackend` treated them as corners, so every
    box collapsed into the top-left corner. `decodeAnchorDistancesIfNeeded` now
    decodes them on the stride 8/16/32 grid. Without it, YOLO-World's boxes were
    unusable.
  - **YOLO11n labels:** `class_idx` is a uint8 tensor with quantization scale 0.
    `readTensorValues` multiplied by the scale, so every detection was labelled
    "person". Scale 0 now means raw values. The real-model integration test only
    checked for people in a bus photo, so it never caught this.
- **App path** (`LiteRtVisionBackend`, `LocalComputeBackend.NPU_EXPERIMENTAL`):
  - It first looks for `<detector file name>_Qualcomm_<SoC>.tflite` next to the plain
    model (e.g. `yolo11n_float16_Qualcomm_SM8650.tflite`).
  - It runs that through `LiteRtNpuModelFactory` (CompiledModel, NPU), with the plain
    model's Interpreter supplying output shapes and quantization, and staying loaded as
    the fallback. Outputs are paired by position: the AOT signature order equals the
    plain model's output order for both detectors, and byte sizes are checked.
  - Otherwise it uses the previous NNAPI → GPU → CPU chain. If the NPU fails mid-run,
    it closes it and continues on CPU.
  - The Kotlin `TensorBuffer` API has no uint8 type. Raw 8-bit bytes go through
    `writeInt8`/`readInt8`, which gives the Interpreter's exact bytes on the AVD and
    the phone.
- **Distribution is not decided.** The AOT files are not in `WalkingAidModelCatalog`
  (no hosted URL), and YOLO11n is AGPL-3.0 and YOLO-World GPL-3.0. Install them by hand
  for tests. Ship them per SoC as downloads or AI packs after a license review.
- **Depth Anything 3 Small** (`da3_small_gpu_fp16.tflite`, fp16) has not been compiled
  yet. It is a float graph, so it needs the fp16 checks above.

## Validation pipeline for every NPU model

1. Host parity: LiteRT fp32 vs PyTorch/ONNX reference.
2. fp16 simulation of the task metric (EER, accuracy) on held-out data.
3. AOT compile with full offload (one partition, all ops). Check the context with
   [`tools/litert-npu/inspect_aot_context.py`](../tools/litert-npu/inspect_aot_context.py)
   `--expect-soc 57 --expect-dsp-arch 75 --expect-build v2.50.0.260828`. LiteRT #8037
   reports host AOT ignoring `--soc_model`.
4. Task metric on NPU numerics. With a phone attached, run it on the phone: dump raw
   NPU outputs and score them on the host (YOLO: `make_yolo_fixture.py --skip-htp`,
   `WalkingAidNpuDetectorTest -e yolo_dump_dir`, `yolo_htp_parity.py --npu-raws`).
   Without a phone, use HTP emulation:
   [`htp_emulator_parity.py`](../tools/litert-npu/htp_emulator_parity.py),
   [`yolo_htp_parity.py`](../tools/litert-npu/yolo_htp_parity.py) and
   [`audio_speaker_htp_emulator_eer.py`](../../../tools/benchmarks/audio_speaker_htp_emulator_eer.py).
   The two agree exactly on the Fold6.
5. Device parity and latency: `LiteRtNpuSpeakerTest`, `WalkingAidNpuDetectorTest`,
   `WalkingAidNpuBackendTest`, `LiteRtEmbeddingRuntimeTest`
   (`-e embeddingBackend NPU_EXPERIMENTAL`), with fixtures of **the same model
   variant**.
6. Device measurements per backend: latency, steady and peak PSS (`dumpsys
   meminfo`, not one-off `Debug.getPss()` snapshots), thermal behaviour over
   minutes, power.
7. Fallback test: the NPU is missing or fails to load, and CPU/GPU gives the same
   decisions.

## Memory and coexistence on 4-GB phones

- Measure NPU context memory on the device. The HTP context for WeSpeaker is
  ~14 MB; Gemma-sized contexts are GB-scale (E2B main: 1.09 GB).
- The E2B LLM already uses ~2.1 GB PSS on CPU and ~3.0 GB on GPU (Fold6). Small
  NPU models are cheap, but loading an NPU LLM alongside a CPU/GPU LLM is not.
- One owner per NPU session; serialise model loads; release contexts when the
  feature is idle. HTP performance modes trade power for speed:
  `CompiledModel.QualcommOptions.htpPerformanceMode`. The LiteRT-LM NPU executor
  has a burst patch: 16 → 28 tok/s decode on QCS8275.
- APK cost of the NPU runtime (arm64, V75 only): 11.0 MB compressed in the APK,
  27.8 MB extracted on the phone (legacy packaging). Each extra Hexagon architecture
  adds a 13.2 MB stub+skel pair (4.7 MB compressed). Use per-SoC delivery once more
  than one architecture ships.

## Recipe update to QAIRT 2.50 (verified on the host)

- Environment: `~/nvme_tmp/npu-litert230/env` (Python 3.11, uv) with
  ai-edge-litert 2.3.0, ai-edge-litert-sdk-qualcomm 2.3.0, tflite 2.18.0 and
  litert-lm-builder 0.17.1.
- QAIRT 2.50 setup: the sdist's `setup.py` swallows download errors, so download
  `v2.50.0.260828.zip` yourself with `curl -C -` (2,601,473,189 bytes, SHA-256
  `a346ea0e…59a5`), run `unzip -t`, extract `qairt/2.50.0.260828/` to
  `~/nvme_tmp/npu-litert230/qairt/` and symlink it as the package's `data/`.
  QAIRT 2.49.0.260730 (x86 tools only) sits next to it for compatibility checks.
- WeSpeaker: `KaldiFbankNpu` graph, 94/94 ops, 1 partition, context
  `v2.50.0.260828221209` / SoC 57 / V75, 1.5 s compile.
- Gemma 4 E2B main graph (`gemma4-sm8650-work/work/final/main_quant_preaot.tflite`,
  the other agent's recipe, recompiled read-only into `npu-litert230/gemma-e2b-qairt250/`):
  - 897/897 and 1,694/1,694 ops, one partition each, as with 2.47.
  - 220 s; peak RSS 9.2 GB (12.5 GB with 2.47).
  - Context `v2.50.0.260828221209`, SoC 57 / V75, same two graphs, 1.0929 GB vs
    1.0925 GB.
  - Loads in the 2.50 HTP emulator. Numerics need the phone.
- Cross-version loading in emulation: our 2.47 WeSpeaker context runs on the 2.50
  runtime with the same outputs as on 2.47 (max difference 6e-5). Backward
  compatibility holds there. Google's 2.49 EmbeddingGemma 2 contexts cannot be
  checked on the host (see Numerics); on the Fold6 they run on 2.50 (Device results).

## Current artifacts

- NPU runtime AAR: `/mnt/seagate/ML/cyanbridge-litert-npu-20261008/`
  - `litert-npu-qualcomm-2.3.0-qairt2.50.0.260828-v75.aar` (+ manifest with every
    input and library SHA-256).
  - Google Maven inputs (`maven/`); arm64 test APKs (`apks-arm64/`).
- WeSpeaker NPU variant: `/mnt/seagate/ML/wespeaker-litert-20261007/npu-qairt250/`
  (SHA256SUMS)
  - `wespeaker_resnet34_lm_2s_npu.tflite` (CPU/GPU, and the AOT input).
  - `aot_sm8650_qairt250/…_npu_Qualcomm_SM8650_apply_plugin.tflite`.
  - `fixture-npu/`, `htp_emulator_eer_qairt250.json`, `stage-bisection/` reports.
  - `superseded-fp16safe-qairt250/`: the 2.50 compile of the broken fp16-safe graph, kept for reference.
- Superseded: `npu-aot-litert220/wespeaker_fp16safe_aot/` (QAIRT 2.47; compiles
  fully but computes wrong values on HTP). Do not ship.
- Walking Aid NPU: `/mnt/seagate/ML/cyanbridge-walking-aid-npu-20261008/` (SHA256SUMS)
  - `models/` (catalog downloads), `aot/` (SM8650, QAIRT 2.50).
  - `reports/` (emulation and Fold6 COCO evaluations, compile diagnostics).
  - `fixture-manifests/` (image lists and expected detections; regenerate the
    `.rgb` files with `make_yolo_fixture.py`).
- Recipe notes for the LLM: `~/nvme_tmp/gemma4-sm8650-work/README.md` (canonical
  project `/mnt/seagate/ML/gemma4-sm8650-port`).

## Fold6 runs: done and remaining

Run commands (install with `adb -s RQCX700KSDF install -r`, never uninstall; push
models and fixtures to `/data/local/tmp/npu/`):

```bash
adb -s RQCX700KSDF shell am instrument -w -r -e class com.fersaiyan.cyanbridge.hil.LiteRtNpuSpeakerTest \
  -e npu_speaker_fixture /data/local/tmp/npu/fixture-npu \
  -e npu_speaker_cpu_model /data/local/tmp/npu/wespeaker_resnet34_lm_2s_npu.tflite \
  -e npu_speaker_npu_model /data/local/tmp/npu/wespeaker_resnet34_lm_2s_npu_Qualcomm_SM8650_apply_plugin.tflite \
  com.fersaiyan.cyanbridge.test/androidx.test.runner.AndroidJUnitRunner
adb -s RQCX700KSDF shell am instrument -w -r -e class com.fersaiyan.cyanbridge.hil.WalkingAidNpuDetectorTest \
  -e yolo_fixture /data/local/tmp/npu/yolo/fixture200-yolo_world -e yolo_dump_dir npu-dump-yolo_world \
  -e yolo_plain_model /data/local/tmp/npu/yolo/yolo_world.tflite \
  -e yolo_npu_model /data/local/tmp/npu/yolo/yolo_world_Qualcomm_SM8650_apply_plugin.tflite \
  com.fersaiyan.cyanbridge.test/androidx.test.runner.AndroidJUnitRunner
adb -s RQCX700KSDF pull /sdcard/Android/data/com.fersaiyan.cyanbridge/files/npu-dump-yolo_world
python tools/litert-npu/yolo_htp_parity.py --plain yolo_world.tflite --aot x --qairt-root x \
  --images coco200 --npu-raws npu-dump-yolo_world --coco-annotations instances_val2017.json --out report.json
adb -s RQCX700KSDF shell am instrument -w -r -e class com.fersaiyan.cyanbridge.hil.LiteRtEmbeddingRuntimeTest \
  -e embeddingModelPath <dir>/embeddinggemma-2-text-270m_Qualcomm_SM8650.litertlm -e embeddingBackend NPU_EXPERIMENTAL \
  com.fersaiyan.cyanbridge.test/androidx.test.runner.AndroidJUnitRunner
```

Done (2026-10-08): WeSpeaker, YOLO11n (detector, backend, 200-image COCO), YOLO-World
(200-image COCO) and EmbeddingGemma 2 text (2.49 context on the 2.50 runtime). See
"Device results".

Done later the same day: Gemma 4 E2B/E4B NPU packages, decision LoRA, 8k–32k contexts and
the multi-bucket package, EmbeddingGemma 2 740M, and a 90 s YOLO soak with memory and
temperature sampling. See "Gemma 4 LLMs on the NPU".

Next Fold6 session (2026-10-10 build in
`/mnt/seagate/ML/cyanbridge-litert-npu-20261008/apks-arm64-mm-20261010/`, V75+V79 runtime):
1. `NpuCatalogSettingsTest -e npu_download true`: download, register and select the SM8650
   file from Hugging Face.
2. `LocalMultimodalProviderTest`:
   - with `-e mm_backend NPU` on the downloaded text package (expect image/audio to be
     reported unsupported);
   - then on `work_e2b_general_mm` (image + audio);
   - then on `work_e4b_general_mm`.
3. The same test on Google's stock E2B with GPU, including image→audio, to see whether
   phones need the audio-priming workaround.

Remaining:
1. Long-calibrated ("_lc") 8k and 4k+16k multi-bucket builds: needle checks scored by
   agreement with the FP32 reference (`LiteRtLongContextProbeTest#needleEval`, capped at 16
   answer chunks). The 4k-calibrated builds are not coherent past ~4k (calibration, confirmed
   on the host); 16k long calibration is coherent on the host but misses a needle FP32 finds,
   so retrieval past 4k still needs recipe work. No 32k long build.
2. EmbeddingGemma 2 740M audio embeddings on the NPU (only text was tested).
3. Depth Anything 3 Small on the NPU (fp16 checks first).
4. Longer soaks (10+ min) for the LLM on the NPU.
5. Distribution of the per-SoC AOT models (hosting, license review) and Hexagon
   architectures beyond V75.
6. If a future 2.49 bundle fails on 2.50, recompile it with 2.50 or ship that model's
   NPU path later. Never ship two QNN runtimes.
