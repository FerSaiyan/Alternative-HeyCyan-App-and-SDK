# Worklog — CyanBridge local agent

## 2026-10-08 (afternoon) — Gemma 4 on the Fold6 NPU, context length study, per-SoC downloads

- **Gemma 4 LLMs on the NPU** (packages from the recipe session: QAT, QAIRT 2.50, weight sharing; LiteRT-LM 0.18 `Backend.NPU`; stock E2B for CPU/GPU on the same phone):
  - **E2B:** prefill 2,250–2,900 tok/s, decode 32–35 tok/s, init 6–8 s, PSS 1.31 GB. GPU: 540–1,050 / 14–16 (init 13–16 s). CPU: 110–205 / 11–19.
  - **E4B:** 585–622 / 11.6 tok/s, init 25–31 s, PSS 2.43 GB.
  - **Decision LoRA** (retrained on the QAT base) with annotated prompts: **118/120**, 0 control false actions, **1.34 s p50**. Our earlier INT4 LoRA exports scored 109 (15.2 s, CPU) and 110 (4.6 s, GPU).
- **Three blockers found on the phone, all fixed by the recipe session:**
  - **Chat template:** LiteRT-LM 0.18 rejects HF's newer Gemma template ("map has no method named get"); packages now carry Google's template.
  - **One NPU process domain per app:** E4B's 3.77 GiB estimate failed with `Failed to find available PD`, and extra PDs are `Permission denied` for apps. Weight sharing brought E4B to 2.26 GB.
  - **INT2 per-layer embedding tables:** the NPU's hardware lookup only accepts INT4/INT8 (`INVALID_ARGUMENT` in prefill); widened losslessly to INT4.
- **Context length** (the user asked whether 4k is a Qualcomm limit): it isn't. Research agent: Qualcomm's 2026 FAQ supports 8192, and LiteRT-LM added NPU multi-cache buckets in Aug–Sep 2026. E2B 8k/16k/32k all compile and load, but speed falls with the compiled cache:

  | Cache | Prefill tok/s | Decode tok/s | Init |
  |---|---:|---:|---:|
  | 8k | 1,110–1,250 | 15–16 | 12–18 s |
  | 16k | 560–660 | 11.5–12 | 21–27 s |
  | 32k | 113–116 | 4.7–4.8 | 42–70 s |

  - 32k is slower than CPU; 16k is the practical limit.
  - The 4k+16k **multi-bucket** package keeps short chats at 30 tok/s decode and switches buckets past 4,096 tokens (`CopyKVCache` 87–91 ms); init is 27–36 s.
- **Limitation:** the long-context feasibility builds use 4k calibration and **degenerate past ~4k** in the new `LiteRtLongContextProbeTest` (multi-turn, greedy). Long-calibrated builds (`_lc`, 8k ready) and a held-out needle set (3.5k–30k) are waiting for the next phone session (`#needleEval`).
- **Host follow-up (recipe session):** the cause is calibration, not the HTP. The FP32 16k export matches transformers' QAT text at 3.5k/7.5k/15k tokens; quantized A16 16k with 4k calibration degenerates on the host CPU exactly like the device; 16k long calibration is coherent but misses a needle FP32 finds. E2B misses 3/5 needles even in FP32, so device needle runs are scored by agreement with `work/needle_eval/host/hf_ref.log`. Next phone package: `work_e2b_mb4k16k_lc` (sha256 98ca4d916aa27354…, both buckets on 16k long calibration). The 32k long build is cancelled.
- **Other device results:**
  - EmbeddingGemma 2 740M on NPU: 8 ms per embedding, 125 ms per decision (CPU 60 / 1,144 ms), 47/120 on both.
  - YOLO11n 90 s soak: NPU steady at 11–14.5 ms with a flat skin temperature; CPU slows from 41 to 58 ms as it heats.
- **Per-SoC downloads (user plan: compile for all recent Snapdragons and host on a public Hugging Face repo):**
  - Shared `NpuVariantSet` / `NpuVariantResolver` resolve `<repo>/resolve/main/<prefix>_Qualcomm_<SoC>.<format>` (unit-tested).
  - The app's `NpuCatalogVariants` gates downloads on the NPU build and the phone's SoC, with a reason shown otherwise.
  - The NPU "coming soon" entry stays disabled until the repo exists.
- **Tests:** `LiteRtBackendBenchmarkTest` (new NPU option), `LiteRtBoundedDecisionEvalTest` (NPU), `LiteRtEmbeddingRuntimeTest` (740M), `LiteRtLongContextProbeTest` (new), and `WalkingAidNpuDetectorTest` soak mode (new). Shared `NpuVariantResolverTest` 3/3. The app compiles with the decision AAR plus the NPU AAR.
- **Coordination:** the Gemma recipe belongs to another session (`/mnt/seagate/ML/gemma4-sm8650-port`); its packages were used read-only. Long host jobs ran under `nice` at the request of a third session sharing the CPU.
- **Phone state:** the CyanBridge debug build with the decision and NPU runtime AARs is installed (`install -r`).
  - Test packages are in the app's external `files/litert-eval/` (multi-GB): E2B 4k/8k/16k/mb4k16k, LoRA, E4B ple4, EmbeddingGemma 2 270M/740M.
  - Walking-aid and speaker fixtures are in `/data/local/tmp/npu`.

## 2026-10-08 — First NPU runs on the Fold6: speaker, Walking Aid YOLO, EmbeddingGemma 2

- **Opt-in NPU build on the Galaxy Z Fold6 (SM8650, Android 15, `install -r`, data kept).** Results:

  | Model | NPU | CPU | Speedup | Accuracy |
  |---|---:|---:|---:|---|
  | WeSpeaker | 12.3 ms | 226 ms | 18× | parity cos ≥ 0.99993 |
  | YOLO11n | 10.4–11.4 ms | 47–61 ms | ≈5× | COCO-200 mAP 0.297 vs CPU 0.290 |
  | YOLO-World | 74 ms | 1,650 ms | 22× | mAP 0.508 vs 0.507 |
  | EmbeddingGemma 2 text | 8 ms/embedding, 123 ms/decision | 1,320 ms/decision | ≈11× | dev 47/120 both, same choice 107/120 |

  Google's EmbeddingGemma 2 bundle is a **QAIRT 2.49 context running on our 2.50 runtime**, so one runtime covers everything.
- **Walking Aid on the NPU:**
  - Both detectors compile fully for SM8650 (YOLO11n 369/369 ops, YOLO-World 487/487, one partition each).
  - `LiteRtVisionBackend` now runs `<detector>_Qualcomm_<SoC>.tflite` via `LiteRtNpuModelFactory` (CompiledModel) when the NPU backend is selected, keeping the Interpreter as metadata and fallback.
  - `WalkingAidNpuBackendTest` passes end to end on the Fold6 (NPU = host emulation 1.0/1.0; 11 ms vs 46 ms per frame).
- **Two existing Walking Aid bugs fixed (all backends):**
  - YOLO-World's catalog export outputs undecoded anchor distances, so its boxes collapsed to the top-left corner. Now decoded; CPU mAP 0.51.
  - YOLO11n's unquantized uint8 `class_idx` was multiplied by scale 0, so every label was "person".
- **Testing policy (user):** NPU tests run on the Fold6 when attached. The device dumps raw outputs (`-e yolo_dump_dir`) and `yolo_htp_parity.py --npu-raws` scores them. 200 frames take 8 s / 65 s on the phone vs about 12 min / 6–7 h in the x86 emulator. The phone matched the emulator exactly (YOLO11n 16-image detections and 200-image COCO metrics), so the emulator is the no-phone fallback.
- **Tests run:**
  - Fold6: `LiteRtNpuSpeakerTest` 2/2, `WalkingAidNpuDetectorTest` 2/2 plus two 200-image dump runs, `WalkingAidNpuBackendTest` 1/1, `LiteRtEmbeddingRuntimeTest` NPU and CPU with the dev set.
  - AVD: CPU parts, `WalkingAidVisionStackIntegrationTest`, `AudioSpeakerLiteRtProbeTest`.
  - Unit: 548/548 on 0.16 and 0.18+NPU.
- **New tools:** `tools/litert-npu/{yolo_htp_parity.py, make_yolo_fixture.py}`. Artifacts: `/mnt/seagate/ML/cyanbridge-walking-aid-npu-20261008/` and final APKs in `cyanbridge-litert-npu-20261008/apks-arm64/` (SHA256SUMS verified).
- **Not done yet:** `dumpsys meminfo`/thermals per backend, Gemma 4 E2B NPU package on the phone, EmbeddingGemma 2 740M audio NPU, Depth Anything on the NPU, distribution and licensing of per-SoC models.

## 2026-10-08 — One NPU runtime (QAIRT 2.50) in the app; WeSpeaker NPU graph fixed via HTP emulation

- **Single QNN runtime:** QAIRT **2.50.0.260828**.
  - LiteRT-LM 0.18 pins LiteRT `26895c9f`, which is QAIRT 2.50. LiteRT v2.3.0 is 16 commits later with no Qualcomm or dispatch changes.
  - The 2.3.0 dispatch library (`qnn_manager.cc`) rejects QNN libraries older than its headers, so the 2.47 runtime cannot work with LiteRT-LM 0.18.
  - The recipe's 2.47 was a minimum, not a pin.
- **App (opt-in, `-PlocalLiteRtNpuAarPath`, needs `-PliteRtLmVersion=0.18.0`):** `tools/litert-npu/package_npu_aar.py` builds one AAR with:
  - LiteRT 2.3.0 `CompiledModel` classes and natives (the Interpreter classes are left out, so `litert:1.4.2`'s NNAPI/GPU delegates for walking-aid stay);
  - Google Maven `litert-npu-runtime-qualcomm:2.3.0` (`libLiteRtDispatch_Qualcomm.so`, so no Bazel build);
  - QAIRT 2.50 `libQnnHtp`/`libQnnSystem`/V75 stub and skel;
  - a `uses-native-library libcdsprpc.so` declaration, which neither the app nor LiteRT-LM had.

  Gradle switches to extracted native libraries and keeps the Hexagon skel unstripped. `LiteRtNpuSupport` checks the runtime, `Build.SOC_MODEL` and the libraries before `Backend.NPU`, and rejects a `_Qualcomm_<SoC>` model meant for another SoC. Cost: 11.0 MB compressed / 27.8 MB extracted (V75 only).
- **Verified:**
  - Pixel 9a AVD (16 KB pages): `LiteRtNpuSpeakerTest` CPU `CompiledModel` parity 0.99999999 at 227 ms per 2 s; the NPU test skips cleanly ("SoC ranchu"). `AudioSpeakerLiteRtProbeTest` (Interpreter 1.4.2) passes in the same APK.
  - All 546 unit tests pass on 0.16 and 0.18+NPU.
  - arm64 APK has every library 16 KB-aligned.
- **The earlier "fp16-safe whole model on NPU" was wrong.** The QAIRT x86 HTP emulator (`htp_emulator_parity.py`) gives cosine ≈ 0.06 vs CPU on 2.47 and 2.50. The emulator itself is sound: it runs the split head at 0.999997. Stage bisection: HTP mis-computes the 400-tap stride-160 DFT conv1d.
  - `KaldiFbankNpu` (160-sample blocks, stride-1 kernel-3 conv; identical features) compiles 94/94 ops in one partition.
  - Emulated-HTP EER 0.25% = CPU over 1,200 crops; context SoC 57 / V75 / v2.50.0.260828221209.
  - Export `wespeaker_resnet34_lm_2s_npu.tflite` + `fixture-npu`, in `wespeaker-litert-20261007/npu-qairt250/`.
- **Recipe on 2.50 (`~/nvme_tmp/npu-litert230/env`, QAIRT zip downloaded by hand):** Gemma 4 E2B main graph recompiled read-only from the other agent's `main_quant_preaot.tflite`.
  - 897/897 and 1,694/1,694 ops, as with 2.47; 220 s, 9.2 GB peak RSS (2.47: 12.5 GB).
  - Context SoC 57 / V75, same two graphs, loads in the emulator.
  - In emulation the 2.50 runtime runs our 2.47 context with identical outputs.
  - Google's 2.49 EmbeddingGemma 2 contexts can't be created by the x86 emulator with 2.49 *or* 2.50 (shared weights across four graphs), so 2.49-on-2.50 compatibility is a phone check.
- **Pending (needs the Fold6):** NPU latency/parity of WeSpeaker, EmbeddingGemma 2 via `Backend.NPU` on the 2.50 runtime, the 2.50 E2B package. Artifacts: `/mnt/seagate/ML/cyanbridge-litert-npu-20261008/` (AAR, Maven inputs, arm64 APKs, SHA256SUMS). Doc: `android/CyanBridge/docs/NPU_ON_DEVICE.md`.

## 2026-10-08 — Whole-model NPU compile, app NPU requirements, Luciano cleaned

- **fp16-safe WeSpeaker front end** (`KaldiFbankFp16Safe`: DFT gain 0.5, fp16-normal mel floor, saturation, post-log offset). The single graph AOT-compiles fully for SM8650 (91/91, 1 partition); fp16 simulation EER 0.25% = fp32, 0 non-finite (the original graph: 50%, all non-finite). Embeddings differ from the CPU model (mean cos 0.97), so use one variant for enrol and verify. Artifacts in `wespeaker-litert-20261007/npu-aot-litert220/`.
- New `android/CyanBridge/docs/NPU_ON_DEVICE.md`: two runtimes (LiteRT-LM NPU vs LiteRT 2.x `CompiledModel`); missing dispatch/QNN libraries (the app's NPU option always falls back today); QAIRT version matching (ours 2.47, EmbeddingGemma 2 bundles 2.49/2.50); extracted native packaging and `ADSP_LIBRARY_PATH`; per-SoC artifacts and fallbacks; fp16 numerics rules; validation pipeline; memory; first-device-run checklist.
- **Luciano:** deleted the four backed-up project dirs (121 GB; root disk 91% → 37% used, partly from others' cleanup). The verified backup is in `/mnt/seagate/ML/luciano-cyanbridge-backup-20261007/`.

## 2026-10-08 — Speaker models on the SM8650 NPU (host-side), Luciano backup verified

- AOT-compiled for SM8650 with the NVMe `recipe_env` (LiteRT 2.2.0, QAIRT 2.47). WeSpeaker and ECAPA fp32 single graphs offload fully (88/88, 467/467), but HTP computes in fp16 and the in-graph front end overflows: GPU fp16 simulation gives 100% non-finite WeSpeaker embeddings (EER 50%). Weight-only int8 fails to compile (per-channel DEQUANTIZE); fully static W8A16 offloads 87/92 ops in 6 partitions.
- **Working layout:** fp32 front end on CPU plus the AOT ResNet head on the NPU (71/71 ops, 1 partition, 13.5 MB). fp16 simulation keeps EER at 0.25% (= fp32); the composed host graphs match at cosine 0.9999998. Artifacts are in `wespeaker-litert-20261007/npu-aot-litert220/` (SHA256SUMS). Device run pending: it needs LiteRT 2.2.0 `CompiledModel`, `libLiteRtDispatch_Qualcomm.so` (not in the pip SDK) and the QAIRT 2.47 Android libraries. EmbeddingGemma 2's SM8650 bundles use QAIRT 2.49/2.50.
- **Luciano backup:** the first pass missed files in nested folders (`--files-from` does not recurse with `-a`); re-run with `-r`. Now `VERIFY_OK` for all 885 main files and `AUDIO_VERIFY_OK` for all 3,252 audio files (23 GB) in `/mnt/seagate/ML/luciano-cyanbridge-backup-20261007/`. The Luciano directories listed for cleanup are safe to delete.

## 2026-10-07 (evening) — Fold6 CPU/GPU bench, WeSpeaker on LiteRT, compute policy

- **Fold6 matched decision latency** (same bundle, 10 turns): stock E2B CPU 8.2 s vs GPU 1.58 s (5.2×). Fine-tuned export: 15.6 s vs 3.9 s. Our INT4 export is ~2× slower than Google's stock bundle.
- **WeSpeaker ResNet34-LM converted locally with LiteRT Torch.** Kaldi fbank in-graph; host parity: fbank 6e-4, ONNX 0.999994, LiteRT 0.99999999999. Dynamic int8 is broken (0.015); ResNet-only weight-only int8 works (0.994, 11.6 MB).
- **Pixel 9a AVD, `AudioSpeakerLiteRtProbeTest`:** WeSpeaker fp32 241 ms, int8 227 ms, ECAPA 238 ms per 2 s window; device/host cosine ≈1. Recommendation updated: WeSpeaker int8 as default (7× smaller than ECAPA, more accurate), ECAPA as fallback.
- **Policy:** no more runtime patch builds or Gemma fine-tunes/exports on Luciano (shared NVMe). Local defaults: `JOBS=20`, Bazel RAM cap, `BAZEL_OUTPUT_ROOT` on NVMe; documented in `AGENTS.md`, `LITERT_SHARED_DECISIONS.md`, `BOUNDED_SPECIALIST_DECISIONS.md` and the campaign README.
- **Luciano backup:** non-regenerable work (14 GB: Laya/Needle/EG checkpoints, reports, datasets, logs, scripts, the baseline LoRA bundle, the latest 0.16 AARs) is being copied to `/mnt/seagate/ML/luciano-cyanbridge-backup-20261007/` with SHA-256 verification before any cleanup.
- **Disk findings:** the Seagate scheduler is `mq-deadline`, so `ionice` has no effect (BFQ needed). Gradle on the HDD is I/O-bound: a 92-min x86_64 build at about 50% system I/O pressure. The NVMe build-dir init script helps only the outputs.

## 2026-10-07 — Single-pass scorer, fine-tuned E2B on device, LiteRT 0.18 + EmbeddingGemma 2

- **Single pass:** `Tasks::Score` optionally returns a final-step full-vocabulary log-softmax for candidate ids (`RunCandidateScoring`); the JNI scores `⊲`+first label once, self-checks it against teacher forcing and falls back to per-label clones on Unimplemented. A first build read the step predicting the delimiter's last byte (mass ≈1e-8, caught on the AVD). AVD full run: 120/120 identical choices (max Δp 6e-8), p50 18.7 → 11.4 s.
- **Control false actions:** failures are "set X to its current value" with only the other value offered. Prompt rules/examples barely help (train 190 → ≤196 with host-annotated current values). LoRA on train (same budget as Laya/EG2): host dev 114/120 (current prompt), 118/120 (annotated options), 0% control false actions. Merged and exported to `.litertlm` (blockwise INT4 JSON recipe, 2.84 GB) after fixing config/tensor skew and a quantizer recipes-dir bug. On device the annotated LoRA scores 109/120 (AVD CPU and Fold6 CPU) and 110/120 (Fold6 GPU), all 0% control false actions, versus stock E2B 80/78.
- **App fixes:** text-only bundles no longer get audio/vision backends (failed every conversation before). LiteRT-LM version is a Gradle property (`-PliteRtLmVersion`), with per-version source sets; 0.16 stays default.
- **LiteRT-LM 0.18 patch** builds (dual ABI, OpenCL accelerator bundled). Fold6 GPU E2B scoring works (0.93 s vs 3.7 s smoke). `LiteRtEmbeddingEngine` runs EmbeddingGemma 2 text on CPU at 96 ms/embedding; GPU SIGSEGVs in the OpenCL delegate (factory keeps CPU); NPU bundle needs QNN libs. Fine-tuned EG2 has no LiteRT export path yet.
- **Memory (Fold6, measured properly with `dumpsys meminfo`):** CPU 2.15 GB steady PSS (1.42 GB reclaimable model mmap), GPU 3.02 GB (2.21 GB non-reclaimable GPU buffers). Earlier single `Debug.getPss()` snapshots were misleading.
- The Samsung's CyanBridge 2.3.0 debug build was replaced by this branch's debug build for testing (`install -r`, data kept). Details: `android/CyanBridge/docs/LITERT_SHARED_DECISIONS.md`.

## 2026-10-07 — Audio speaker filter (user-voice enrollment), parallel agent

- Benchmarked EmbeddingGemma 2 audio, the Gemma 4 E2B audio encoder, SpeechBrain ECAPA-TDNN and WeSpeaker ResNet34-LM on matched LibriSpeech test-clean trials (40 speakers, 3/5 enrollment phrases, 1/2/3.5 s; MUSAN noise/music negatives; wearer mixed with noise/babble at 0/5/10 dB). Gemma models are unusable for speaker filtering: EmbeddingGemma 2 final 38.7% EER, Gemma 4 soft tokens 35.7% (2 s, 5 phrases). Their audio towers are byte-identical (751/752 tensors), and the best internal layer reaches 9.4%. ECAPA 0.4%, WeSpeaker 0.2%. ECAPA is exported to LiteRT (84 MB, raw 2 s PCM → 192-d, host parity 0.9999998); device probe written, not run. Speaker templates are biometric data and stay on device. Doc: `android/CyanBridge/docs/AUDIO_SPEAKER_FILTER.md`.
- Shared-encoder follow-up (frozen Gemma audio encoder, LibriSpeech train-clean-100/360, 1,172 speakers disjoint from test): a 0.92M-param head (learned weights over conv + layers 0–3, attentive statistics pooling, 0.95 MB int8) reaches 0.8% EER on the matched trials (ECAPA 0.4%, WeSpeaker 0.2%; previous best mean-pooled 9.4%) and 1.3% on out-of-domain VCTK (ECAPA 0.5%), but accepts only 47% of the wearer in 0 dB noise (ECAPA 95%). Later layers, the encoder output (4.6–5.8%) and a LoRA branch (1.2%) do worse; a log-mel-only head reaches 1.5%. The E2B bundle's audio encoder section (94 MB INT2/INT4) runs standalone in a LiteRT Interpreter but only exposes final states; the useful layers 0–3 would need a ≈31 MB truncated copy, no RAM saving over int8 ECAPA. Decision unchanged: dedicated speaker model (ECAPA int8; WeSpeaker upgrade). No public Gemma/USM speaker head exists as of 2026-10-07. Artifacts: `/mnt/seagate/ML/cyanbridge-audio-speaker-20261007/head/` (SHA256SUMS verified).

## 2026-10-07 — LiteRT E2B classification working on the Pixel 9a AVD

- Moved the patched LiteRT-LM build to Luciano (48 cores; ~15× faster than local 4-job Bazel, which was stopped with its cache kept). NDK r28b downloaded there and SHA-1 checked against Google's repository manifest; Bazel binary SHA-256 matched; minimal SDK subset copied. `build_aar.sh` now takes `ABIS`/`JOBS`, builds `:litertlm-android_kt` (the old `:litertlm-android` target only yields an empty wrapper jar) and uses NDK `llvm-nm`/`llvm-readelf`; `package_aar.py` packages several ABIs with per-ABI ELF checks.
- First compile of the patched JNI failed: `Engine::GetTokenizer()` is const but `TextToTokenIds` is not. Applied the same `const_cast` upstream uses in `c/engine.cc`. Link then failed on Git LFS pointer files; copied real `prebuilt/android_{x86_64,arm64}` binaries after matching each LFS SHA-256. The open-source build links `libGemmaModelConstraintProvider.so` dynamically, so the AAR now bundles that verified upstream prebuilt (platform deps only, 16-KB aligned).
- AVD smoke (`LiteRtSharedDecisionRuntimeTest`, E2B CPU) passed but showed label mass ≈1e-5. Host diagnosis with stock `litert-lm` 0.16.0 Python and an HF `transformers` reference proved native `RunTextScoring` scores the first target token with the stale pre-last-prefill distribution; later tokens are correct (greedy decode picked the right answer). Patch now prefills the context before `⊲`, scores `⊲`+label and keeps the label token. Label mass is now 0.97–0.997. Rebuilt both ABIs (AAR SHA-256 `099c3dd1…`); the superseded AARs are kept separately.
- Added `LiteRtBoundedDecisionEvalTest` (device never sees expected labels), `renderedDecisionPrompt()` diagnostics and `score_litert_bounded_eval.py`. Full bounded-flat-v3 development run, stock E2B zero-shot on the AVD CPU: **80/120**, substantive 0.786, control false actions 34.4%; margin ≥0.9 accepts 47 at 46/47 correct. Decision p50 18.7 s (emulator). Best untuned option measured; tuned EG2 (110) and Laya (91) remain ahead on controls.
- 41 JVM decision/engine tests pass in default and prototype builds; 31 Python contract/new tests pass. Details: `android/CyanBridge/docs/LITERT_SHARED_DECISIONS.md`.

## 2026-10-06 — EmbeddingGemma 2 versus tuned Laya

- EmbeddingGemma 2 released today (`google/embeddinggemma-2` `914f7f89`, Apache 2.0; 270M text-only of 740M). Added an `embedding` backend to `eval_bounded_specialist.py` and a matched-budget `train_embedding_bounded.py` (180 updates, batch 16, best-of-60/120/180 development snapshot, in-menu softmax over cosine). Isolated `eg2-venv` on Luciano; EmbeddingGemma 1 fetched there with the token piped over stdin, never stored.
- `bounded-flat-v3` development, 120 turns: tuned EG2 (status+request, 768 or 256-d) **110/120, 0% control false actions**, versus tuned Laya 91/120 (rerun on the same A4000, identical to the published result) and tuned EmbeddingGemma 1 103/120. Zero-shot is unusable: 20/120 with status JSON, 50/120 request-only with 77% control false actions. EG2 query encode p50 35 ms A4000 / 223 ms CPU-4T. Development-set snapshot selection for both models; calibration/sealed untouched.
- Reports, tuned checkpoint (SHA-256 verified) and log on Seagate under `cyanbridge-specialists-20261003-artifacts/`. Details: `android/CyanBridge/docs/BOUNDED_SPECIALIST_DECISIONS.md`.

## 2026-10-04 — Same-engine LiteRT-LM decision scoring fork

- Found existing native `RunTextScoring` in pinned LiteRT-LM v0.16.0 (`924e79c91542761242244e4f1651851f822e4cbb`). It computes full-vocabulary **log probabilities**, despite the interface comment describing negative log probabilities. Added experimental Kotlin `Engine.scoreDecision` and JNI wrapping the existing scoring implementation: full-prefix single-token checks, configured-context overflow rejection, prefill once, isolated cloned context per candidate, finite score/mass validation. All contexts use the existing engine; no model loader is invoked.
- Added opt-in `-PliteRtDecisionPrototype=true` shadow calls and `-PlocalLiteRtDecisionAarPath=...` dependency override. The app renders the loaded model's template with thinking disabled, feeds the delimited assistant prefix and serializes LiteRT chat/scoring/load/unload. Existing normal/reversed-menu alignment and incumbent Tasker output are retained. Stock 0.16 remains the default; flag-only stock builds report that the patched API is missing if scoring is attempted.
- Preserved the pinned source patch and matching Kotlin/JNI ARM64 AAR build/packaging helpers under `android/CyanBridge/tools/litert-decisions/`. Source and native build/download cache are under `/mnt/seagate/ML/cyanbridge-litert-decision-20261004/`. No libraries were installed in base conda.
- **9 selected JVM tests passed** (5 fork-binding + 4 shared-menu tests); opt-in debug app and instrumentation APK built against stock 0.16.0. The default build also passes **13 selected JVM tests**, adding the four existing LiteRT streaming checks. This verifies app integration, not actual fork-native inference. Added provisioned `LiteRtSharedDecisionRuntimeTest` for scoring→chat→scoring reset, invalid-label/overflow/unloaded rejection and smoke timing/PSS. ADB reports no attached device; E2B/E4B/GPU inference and phone memory remain pending. Native fork build status is recorded in `docs/LITERT_SHARED_DECISIONS.md`.
- First scoring implementation requires one prefill and N clone/decode operations per menu, repeated for reversed presentation. It adds temporary context memory and synchronous scoring can delay cancellation. A single native logit pass for all labels is an optimization target after correctness/backend parity. Evidence and reproduction: `android/CyanBridge/docs/LITERT_SHARED_DECISIONS.md`.
- Recovered after server restart: retained completed app/test results and the pinned source patch, resumed the interrupted NDK download, verified its size and upstream SHA-1, and extracted NDK r28b on Seagate. Resumed matching Kotlin/JNI ARM64 build using the saved Bazel cache; log `build-arm64-resumed.log`. Corrected the packager's hashing helper to work with the restarted server's Python 3.10 and added ARM64, JNI-symbol, native-dependency and 16-KB-load-segment checks.
- The resumed source build first failed during toolchain discovery because Python ZIP extraction had materialized NDK symlinks as text. Restored all 35 symlinks from the verified archive, confirmed Clang 19 launches and resumed the cached build (`build-arm64-ndk-links.log`). Added compiler-launch preflight to the build helper. This failure occurred before native source compilation.

## 2026-10-04 — Shared chat-model System-One prototype (Jev branch)

- Verified the supplied external discussion against current upstream sources: dedicated `/v1/systemone` PR #29818 merged October 2; ordinary-chat-model PR #29832 remains an open draft with E4B/Qwen3.5 examples and no E2B/phone-action accuracy evidence. PocketPal depends on llama.rn; its backend documents Adreno OpenCL and experimental Hexagon, not universal phone acceleration. Gallery's referenced bundles recommend 8 GB for E2B / 12 GB for E4B; MobileActions has a 289-MB artifact and 6-GB recommendation. These are bundle configurations, not measured CyanBridge RAM requirements.
- User selected **current Jev branch** as the implementation target. Added an opt-in `llama-runtime` text/CPU prototype, pinned to the same verified upstream revision as the Eyevue adapter (`0c1e57098bba43ac29e6e3b677cdceebdd22334f`). `-PsharedGemmaPrototype=true` selects one native GGUF/context for both ordinary chat and real next-token-logit decisions. Native scoring uses the model Jinja template with thinking disabled, validates one-token labels and full-prefix delimiter boundaries, rejects overflow and reports full-vocabulary label mass. No server/localhost API is included.
- `SharedGemmaDecisionEngine` compares normal/reversed menus with position-label reassignment and aligns results to original bound candidate indices. Decisions always abstain; Tasker records `SHARED_GEMMA_SHADOW` while returning the incumbent cascade's choice. The session serializes inference/reload/unload, and scoring borrows an already-loaded engine without loading weights. LiteRT/legacy-wrapper sessions do not produce raw-logit shadow results; the existing short-decoded-choice path still works for LiteRT. No GPU, multimodal, calibration or deployment result is claimed.
- Verification: **48 JVM decision/candidate/UTF-8 tests passed**; host checked-JNI smoke passed with the existing Qwen2.5 0.5B Q4_K_M file, including actual logits, chat/scoring state reset, concurrent calls, streaming/grammar and malformed-label/overflow/unloaded-handle rejection. ARM64 native AAR, debug app and Android-test APK built successfully. Opt-in ARM64 `.so`: 6,352,000 bytes; default runtime AAR verified to contain no native libraries. No ADB device was attached, so the provisioned `SharedGemmaRuntimeTest` was compiled but not run. E2B/E4B quality, memory and device latency remain to be measured.
- Source/usage/evidence: `android/CyanBridge/docs/SHARED_GEMMA_DECISIONS.md`; native/test logs under `/tmp/opencode/shared-gemma-*`. No calibration/sealed cohort or phone credentials were touched.

## 2026-10-04 — Resumed bounded ablations and verified campaign summaries

- Compared precisions on the best tuned v3 flat Laya checkpoint using the local RTX 4060 Ti and same padded inputs (1×512 tokens / 12 option slots): existing FP32-weight/BF16-autocast reference **93/120**; FP16-resident **91/120**, BF16-resident **94/120**. Resident 16-bit variants each use **625.5 MiB allocated / 644.6 MiB peak VRAM**, versus reference **1,247 / 1,514.5 MiB**. BF16 p50 forward 33.49 ms and FP16 34.47 ms; the single 120-example development-set difference is not validation evidence. Two reference choices differ between the prior A4000 and local GPU; the fixed-input local reference matches the local variable-length run exactly.
- Upstream-style Q8 per-tensor dynamic INT8 ONNX scored **79/120** (80.8% choice agreement to local reference), flips 23 choices (3 fixes, 17 regressions, 3 both-wrong changes), and causes **8/64 control false actions**. Its static FP32 ONNX source is 1,290,252,004 bytes; Q8 is 914,805,203 bytes (1.41× smaller than FP32 graph but 1.42× larger than the original 614-MiB FP16 checkpoint). ONNX Runtime CPU with four threads p50 **597 ms**; process RSS after run 1,968 MiB and high-water 6,649 MiB. This is not competitive for this selector.
- The campaign-pinned dynamic-axis ONNX export fails at variable sequence lengths because the traced attention reshape bakes in dummy length 16. To complete the Q8 comparison, exported a fixed 512-token / 12-option graph and fed identical padding/attention/marker masks to all modes; reference labels are unchanged from variable-length local inference. Full report: `/tmp/opencode/cyanbridge-specialists-20261003/reports/bounded-v3-laya-flat-precision-fixed512-comparison.json`; hashes/results summarized in `training/laya_needle_specialists_v1/laya-bounded-v3-flat-precision-summary.json`. No Android/NNAPI/LiteRT measurement, calibration, sealed inference or setting mutation occurred.
- Checked upstream Laya current main (`8a6e1328cce2460a0e5aa348ad465bb1b5821cd2`) against our older campaign source pin (`4066d5d5fbf08b66c6757ddeedbd797bd7655bc0`). Upstream now has ONNX dynamic weight-only INT8 Q8 export, CPU-only, default per-tensor; documented 1.4–2.8× size reduction is vs FP32 ONNX, with ~83% eager-output agreement for multilingual; per-channel is much worse. No Q4 or prequantized multilingual Hub artifact. `laya-java` ONNX/JVM port explicitly lists Android as not implemented; not a supported drop-in Android build. HF current multilingual weights are FP16. Documented links and constraints in `docs/BOUNDED_SPECIALIST_DECISIONS.md`.
- Measured Laya bounded-v3 hierarchy inference memory on Luciano's RTX A4000 using the isolated PyTorch environment: one checkpoint is 614.01 MiB/321.9M FP16 values; the inference module loads FP32 parameters and runs BF16 autocast. One 438-token, 12-option batch-1 forward used 1,515 MiB peak allocated / 1,538 MiB reserved VRAM; resident after transfer was 1,247/1,272 MiB. Whole Python RSS was 1.78 GiB after model transfer / 2.08 GiB after inference, with ~3.10 GiB transient load high-water. Linux Python/GPU only; Android export/phone PSS and runtime buffers remain unmeasured. Recorded in `android/CyanBridge/docs/BOUNDED_SPECIALIST_DECISIONS.md`.
- ICproj migration was interrupted by a termination signal during copying; its NVMe original remained intact. Confirmed the LTFS web service and tape mount were still running. Resumed only the previously audited partial destination `/mnt/seagate/ML/ICproj` at idle I/O priority, in a detached process that survives the initiating shell. Original deletion remains gated on full checksum/worktree verification. Migration completion is pending.
- Resumed remote work on idle Luciano GPU 1. Executed both corrected v3 Needle fixed-budget fits, 20-layer exports, base/tuned oracle evaluation and self-routed rollouts. Hierarchy: oracle **61/352 → 68/352**, episodes **14/120 → 9/120**; flat: **11/120 → 12/120**. Hierarchical root tuned **16/120**; tuned invalid choices **41/352 hierarchy / 2/120 flat**, oracle control false-action rates **35.9% / 79.7%**. Final training losses **0.202 / 0.267** do not establish runtime accuracy.
- Published complete eight-report `bounded-v2-summary.json` and `bounded-v3-summary.json` in the compact campaign training directory. Tightened publication checks for exact required variants, contracts/views, held-out scope flags, recomputed metrics/effects and referenced model SHA-256; summary also hashes each full report. V3 has all prediction/model hashes; the two earliest v2 Laya hierarchical reports lack embedded hashes, explicitly recorded rather than retroactively claimed verified.
- Retrieved bounded Needle v2/v3 adapters, archives, training records and full baseline reports directly to Seagate at `/mnt/seagate/ML/cyanbridge-specialists-20261003-artifacts/`; verified **28 files** against a remote SHA-256/size manifest. Large new model copies do not consume the local NVMe.
- Completed reversed-presentation evaluation preserving host IDs/effects and original request/status. Tuned Laya flat **91→92/120**, hierarchy **86→82/120**; tuned Needle flat **12→16/120**, hierarchy **9→20/120**. A staging omission of the flat builder's transitive generator import failed after hierarchical reports completed in both lanes; preserved failed logs, staged an isolated complete directory, and reran only missing flat reports. Published an independently verified eight-report order summary.
- Completed the first-12-development-turns/view base/tuned CUDA teacher-forcing diagnostic. Base predicts `<think>` first on all 24 rows despite `<tool_call>` targets; tuned flat also 12/12 `<think>`, hierarchy 6/12 `<tool_call>`. Teacher-forced token counts improve (hierarchy 124/158→142/158; flat 128/163→140/163) with no entirely correct 12-row target in any variant. Published a compact diagnostic with case IDs, report/adapter/base/data hashes and explicit non-native scope.
- Completed the matched Needle reasoning-target ablation in an isolated script directory. Only `train.needle.jsonl` gains short synthetic bound-label-derived reasoning; requests/status/menus/answers/groups and all other cohort files are preserved. Full token/binding/mask audits passed (training maxima 652 hierarchy / 753 flat, cap 768), followed by both 180-update/batch-8 fits and native oracle/rollout evaluations. Tuned hierarchy **76/352 oracle / 7/120 episodes**, flat **18/120 episodes**. Hierarchical invalid choices drop 41→3 while root accuracy falls 16→12/120 and control false actions rise 35.9%→51.6%; flat invalids rise 2→18 and control false actions fall 79.7%→60.9%, still worse than base. Longer-target losses are not directly comparable. This diagnostic does not identify a complete parity fix or justify promotion.
- Published `bounded-v3-reasoning-summary.json`, explicitly complete for its four Needle variants; regenerated the main v2/v3 and order summaries with current strict checks and per-ablation flags/budgets. Retrieved new artifacts/reports/logs directly to Seagate; all **50 model/adapter/training/report files** passed remote/local SHA-256 and byte-size verification.
- **26 contract/metric tests + 10 campaign publication tests pass**, including reasoning-input preservation, unchanged bindings under reversed order, strict eight-report main campaigns and deliberate four-report single-model ablations. Documentation links and Python/JSON parsing passed; `git diff --check` passed after the prior changes. No real ASR, calibration/sealed inference, phone action/readback, device model download or email send was performed. All models remain shadow-only.

## 2026-10-03 — Paused for local NVMe audit and progress review

- User requested an explanation of NVMe usage before continuing. Paused new training; both Luciano GPUs are idle. Needle version-2 bounded fits/evaluations completed; version-3 Needle has **not** started. Laya version-3 fits and rollout reports are complete.
- Earlier local disk check was 99% with 9.6 GiB available; current filesystem reports ~862 GiB used / 914 GiB capacity, 5.76 GiB available to ordinary processes, and ~46.5 GiB free-but-reserved/unavailable blocks. `df` rounds displayed usage to 100%; it does not mean literally zero free bytes.
- This session's local `/tmp/opencode/cyanbridge-specialists-20261003/` is ~4.2 GiB, including ~3.99 GiB models. Six full Laya safetensors copies at 614 MiB each account for ~3.60 GiB, plus Needle artifacts and small corpora/reports. The training environments/checkpoints were built on Luciano (campaign ~15 GiB there); local copies for verification consumed avoidable NVMe headroom. Seagate has ~157 GiB available and is the appropriate artifact-storage destination before more work.
- Measured existing large local occupants include Documents ~308 GiB (ICproj 100 GiB; CyanBridgeLabs 77 GiB including gemma4-sm8650-port 61 GiB), anaconda3 65 GiB, cache folders ~62 GiB, `/tmp/opencode` 55 GiB including this session, Android AVDs/backups ~47 GiB, and OpenCode data ~23 GiB. Per-path totals can overlap/share hardlinks; this is not a complete privileged filesystem accounting. The audit was read-only apart from its small log; no cleanup or artifact move was performed.
- Completed Needle version-2 bounded comparison: hierarchical oracle turns 62/352 base → 87/352 tuned, but self-routed episodes remain 12/120; flat 10/120 → 11/120. Those inputs lacked the explicit v3 running/readiness facts and are diagnostic. No checkpoint is promoted; actual ASR/native readback/Tasker collection and Android runtime verification remain pending.

## 2026-10-03 — Remote training and state-bounded specialist contracts

- Used Luciano's two idle RTX A4000 16 GB GPUs; PhotoBioPC's GPU was occupied. Created isolated Laya/Needle environments and transferred/hash-checked existing pinned models. No local GPU training or base-conda package installation.
- Executed six Laya head/encoder fits and four Needle LoRA fits on paired public-operation/generated-config pilot views. Preserved source-family groups and official Mobile Actions exclusions, independent calibration/sealed cohorts, exact typed calls, native suppressed-call/error evidence and model hashes. Laya voice encoder+head reached 469/480 open development calls, config 191/210; config hand-written challenges only 11/24. Needle mixed fits did not improve runtime refusal (voice 216/480 versus 218 base; config 66/210 versus 67 base). Full ablation record: `training/laya_needle_specialists_v1/README.md`.
- Incorporated the user's correction: status-defined, turn-eligible action families → settings categories → settings → valid installed model/value/download offer. Implemented a versioned offline host menu/transition contract, paired Laya choices / Needle zero-argument bound-choice tools, freshness/membership/reachable-scope checks and explicit clarify/no-change/defer choices. Root options and every offered leaf/control now have positive supervision; missing/unusable/incompatible models are not offered for selection.
- Added state-matched flat-versus-hierarchical dataset views (300 train / 120 development episodes in both; 880 vs 300 training turns), grouped before state variants. Added per-turn and self-routed rollout evaluation so wrong ancestors cannot be replaced by gold scope, plus audits for bound-label coverage, eligibility, hashes and unclipped inputs/target masks. Initial incomplete root-label coverage runs were archived before final fitting. A large Needle batch exhausted VRAM; switched to a small fixed-update trainer reusing upstream quant-aware LoRA routines.
- The contract is reference training/evaluation tooling, not an Android dispatcher. It proposes download offers and setting effects without executing or claiming readback; actual plugin/state/Tasker evidence integration remains necessary. No phone action, model download to the phone or email was performed. Details: `docs/BOUNDED_SPECIALIST_DECISIONS.md`.
- Executed the initial version-2 state-matched Laya ablation from base: flat 31/120→90/120 proposed-effect episodes; hierarchical 37/120→85/120, despite oracle-turn choices improving 167/352→301/352. Mean hierarchy cost is 2.75 model decisions/episode versus one flat call. Narrow final installed/download choices each pass 6/6 gold-scope turns; root routing and ancestor errors dominate. These are open written development results, not verified setting outcomes.
- A further status audit found that omitting Live running/readiness facts forces inference from an absent Start choice. Version 3 exposes Live running/availability, Tasker readiness and download-in-flight explicitly; version-2 inputs/results remain diagnostic artifacts. Version-3 fitting starts again from original base weights, preserves the same goal cohorts and uses identical per-turn/rollout status rendering. The paired tooling checks now include that state-visibility regression.
- Completed the corrected version-3 Laya fits: flat 39/120→91/120, hierarchical 36/120→86/120 self-routed episodes; hierarchical oracle turns 176/352→305/352. Smaller final menus help local choices but do not yet beat the flat complete-path control. Audited all model inputs/loss masks without clipping (v3 maxima: Laya hierarchy/flat 393/442, Needle 632/727), all offered choices have positive training labels, and 24 contract/metric tests pass. No calibration or sealed inference was performed.

## 2026-10-03 — Laya/Needle specialist variants and public dataset inventory

- Adopted the user's focus on Laya/Needle with separate voice-router, native configuration/plugin selector and phone-UI contracts. Voice/config training can progress from typed app capabilities and readback without waiting for the broad Tasker UI corpus. No larger generative-model training is proposed.
- Reviewed primary MASSIVE, SLURP, CLINC OOS, home-domain NLU Evaluation, AndroidControl, updated GUIOdyssey and AndroidWorld sources; added pinned access/licensing/split metadata in `training/laya_needle_dataset_sources_v1/catalog.json`. MASSIVE 1.1 has 52 locales vs 1.0's 51; SLURP text/audio terms differ; Fluent Speech Commands' publisher expressly excludes product training/testing/development. AndroidControl is the preferred text/a11y UI auxiliary; screenshot descriptions/boxes from GUIOdyssey are not Tasker evidence.
- Parsed pinned Google Mobile Actions in memory: 9,654 rows, 8,693 metadata-train / 961 eval; train is 5,794 single-call / 2,899 multi-call / zero no-call. HF exposes the whole file as train, so internal metadata filtering is mandatory. Seven gold action names give limited settings scope. Evaluation rows were counted, not replayed or used for tuning.
- Parsed pinned DroidCall structured training: 10,071 rows, 7,435 single-call / 2,636 multi-call / zero no-call. Inspected schema conversion needs, answer-name anomalies and one ungrounded address/prose example. Plan reviewed supported/grounded subsets plus product-specific negatives; neither pool is Tasker supervision or already suitable for blind fine-tuning.
- Mapped proposed product labels to existing dashboard enums/actions, native plugin IDs and preferences. Preserved distinctions between AI image wake-word route and image invocation, plugin enable and start, settings navigation and mutation. Gemini Live refresh 2 is an every-speech-turn sentinel, not two seconds; Visual Diary intervals are 1–240 minutes. The proposed voice manifest/dispatcher is not yet implemented.
- Added `docs/LAYA_NEEDLE_SPECIALISTS_AND_DATASETS.md`, source inventory README/catalog, and cross-links; revised roadmap priority toward voice/config data and paired Laya/Needle specialists alongside UI collection. Kept each specialist's split/calibration/runtime gate, current approvals/fallback and provenance separation. No new model training or phone action was run.
- Verification: parsed the source catalog, checked nine revision pins and measured row-count sums, and resolved 26 local links across the five linked documents; `git diff --check` passed. Rechecked publisher licensing/action-space notes. These are inventory/documentation checks, not model-quality or device-runtime results.

## 2026-10-03 — Closer voice/device references and Tasker training findings

- Followed the user's Robotics & devices reference and reviewed its ten catalog entries. Prioritized Evoke's installed recipes/closed inputs, smart-home control-vs-conversation routing, Fusion 360's command detector and RoboJEV's intent/action separation with independent outcomes. Inspected Evoke `0c61ee5`, RoboJEV `2c920c2` and EmbodiedJev's integration review `f08de2e`; report-only demos and simulator evidence are identified explicitly.
- Found a closer Laya worked training example at source `fa9a2a7`: Spanish telephone voice-turn routing, rebuildable 4,740 examples, unseen menus, negation in both directions, corrections, ASR-like text and full encoder/head training. Its author-reported 0.396→0.912 over 217 written fixtures still includes 12 wrong actions and weak confidence gating; later variants used inspected errors. Its random question-level calibration and 1024/256 token configuration must be adapted to our grouped episodes/mobile export.
- Inspected Needle `9571a58` media/wearable/smart-home schemas and their acceptance harness. They are stub environments with no-call/invalid/negation cases, not Tasker training trajectories; harness masking, string case-folding and call sorting must not become our raw/exact/order metrics. Current local trainer supports W4 exact-call scoring and `--val-split 0`, but defaults to random examples. Official DroidCall/Mobile Actions tuning plots force calls and do not prove refusal; the MicroDuck demo did not supply a verified reproducible dataset in this review.
- Documented separate linked voice-route and UI-action datasets: actual ASR provenance/literals, command/chatter/clarification, supported capability menus, current subgoal, fresh Tasker evidence and verified outcome. Proposed contrast pairs distinguish negation from a positive request containing negative words, corrections from blanket refusal, and filled-field DONE from unfinished goals.
- Rechecked our wire/parser: first 160 nodes, bounded text/ID/summary, array-index pairing, unreported flags written as false, zero-area tap points and invalid coordinates defaulting to `(0,0)`. Proposed explicit unknown/provenance/clipping/coordinate-validity records and recall audits; these are design requirements, not implemented new metadata. Identified page-wide literal visibility as distinct from verified field value.
- Added `android/CyanBridge/docs/JEV_VOICE_TASKER_TRAINING_FINDINGS.md` with source pins, concrete model-format examples and collection/training changes; linked it from the roadmap, training recipe, Tasker context and embedding findings. Kept Laya/Needle shadow-only and the existing Gemma/planner/approval boundaries. This research update adds no model training or live device-action result.
- Verification: checked local Markdown link targets, illustrative JSON parsing/choice-tool label consistency, and `git diff --check`. These checks validate the research document, not model accuracy or runtime compatibility.

## 2026-10-03 — Evidence-based phone-agent plan

Current planning entry point: `android/CyanBridge/docs/JEV_PHONE_AGENT_ROADMAP.md`.
This supersedes older next-agent ordering and handoff recommendations below;
historical measurements retain their dates. Reviewed clean baseline `160299c`.

- Re-read Ship With Jev's engineering guide, Browser Use flight search, Hunch and task-done checker, plus the October 1 Jev Layer and Playwright integration entries. Adopt operational questions, explicit unclear/defer, fresh host-approved options, host-owned execution/approvals and attached outcomes. Catalog numbers are author-reported browser evidence, not Android acceptance targets.
- Consolidated results: Gemma margin ≥0.10 remains the conservative incumbent; its 48/48 calibration and 16/16 accepted test choices do not establish ≥98% real-phone precision. Laya/Needle synthetic gains did not transfer reliably; their uncalibrated probabilities/confidence do not authorize actions. Keep both shadow-only. Pro/Gemma YouTube playback previously passed twice, latest 22.9 s; Gmail send/delivery remains unverified because Tasker composer observation is blocked.
- Dataset readiness: Artemis has 29 real emulator episodes / 82 decisions but only three connected families and no missing-control supervision. The six YouTube Tasker frames and all earlier frozen/pilot tests have been inspected. One distinct Tasker Settings episode took 1,821 ms pre-observation-to-post-observation (1,734 ms action-to-verification). None supplies a new train/calibration/final-test split.
- Prioritized next batch: restore/pin Pixel and re-establish Pro baseline → version a complete Tasker episode/candidate collector and stage timers → collect roughly 100–150 fresh episodes / 500–1,000 decisions across at least six independent families, including negative/unfinished/DONE cases → audit recall and reserve grouped holdouts → compare deterministic/compact-Gemma/planner baselines before retraining. Counts are planning targets, not a statistical release guarantee.
- Runtime lane: verify pinned patched GGUF artifact acquisition/build; retain Gemma CPU as baseline, defer Qwen embedding optimization. Needle's small ARM Android runtime makes it the first new device feasibility probe once an ARM64 target exists; host Linux timing is not Android timing. Laya LiteRT comes after a clean development candidate, with tokenizer/head/output parity and actual-device timing. The selected-node mapping, bound-field focus and short production state builder already exist; do not reopen them as missing implementations.
- Promotion plan: separate accuracy/calibration from ABI/runtime parity, and separate inference latency from verified task time. Use a new locked final test, report uncertainty and episode-grouped metrics, require useful reversible-action coverage at the declared precision target and matched planner task-completion results. Keep Gmail as a read-only observation repair track until fresh composer verification works.
- Server-restart notices cancelled historical emulator processes. `adb devices -l` showed no attached target during this review. This planning work did not boot/wipe an AVD, retrain, run a new live workflow or send email. Revalidate local artifacts and device identity before the next HIL; serial reuse is not device provenance.
- Documentation checks: all local Markdown links in the planning/training/model docs resolve; saved Artemis source/pool SHA-256 values match the manifest. Read-only audits confirm 29 Artemis episodes / 82 decisions / three components (`readyForIndependentSplit=false`) and the saved Tasker diagnostic (`readyForTraining=false`).

## 2026-09-27 — Laya/Needle local training pilot

- Follow-up data audit after the synthetic transfer regressions: the available
  local logs contain only six structured `JEV_SHADOW_STATE` decision frames,
  all from one YouTube episode. Older Chrome HIL logs expose repeated
  candidate lists from failed runs but lack independently verified next-action
  and post-action labels. Do not retrain on those logs or on the now-inspected
  held-out cases; collect new episode-disjoint Tasker observations first.
- Created a deterministic **synthetic** English phone-UI decision dataset,
  `android/CyanBridge/training/phone_ui_synthetic_v1/` (576 train, 128
  validation, 128 test decisions per model). Inputs and answers are grouped by
  episode and split by disjoint invented names; explicit no-action/ambiguous
  cases are included. The 169-case generated calibration benchmark and six real
  Tasker states are *not* trained on. Tool IDs for duplicate operations are
  assigned after shuffle to avoid leaking the target's role in its ID.
- Trained a **frozen-encoder Laya choice head** (four epochs, CUDA) using
  `tools/benchmarks/train_laya_phone_ui.py`. Synthetic test top-1 56.3% base →
  86.7% trained. On the six *untouched* Tasker YouTube states, base Laya 5/6 →
  trained 4/6; both failed to defer at the playing-video state. The synthetic
  gain does not transfer to this real episode. Reversing option order dropped
  each model to 3/6 on the same six frames. The frozen 13-locale corpus
  remained unsuitable for auto action: raw held-out 42.5% base versus 45.0%
  trained, **zero** safe actions at its 98% precision gate. Artifacts under
  `/tmp/opencode/phone-ui-models/laya-head-v1/` and read-only reports under
  `/tmp/opencode/phone-ui-laya-*-real-shadow*.json`.
- Needle 3 base exact-call accuracy on the synthetic test: 59/128 (46.1%),
  0/32 for no-match or ambiguous inputs. On the real six-state trace under
  a separate first-four-visible-controls renderer, the 20-layer base chose
  only 1/6 reviewed next steps (host shadow, no execution). A local 20-layer
  LoRA in a dedicated non-base Python environment reached 80/128 validation
  and 83/128 synthetic test exact calls; missing-control no-call remained
  **0/16**. On the same real-state renderer it reached 2/6 and failed at the
  player DONE state. In a serial host-only rerun of 128 test rows, Needle
  p50 was 394 ms base versus 357 ms tuned, not an Android measurement.
  On the frozen 13-locale generated corpus, Needle's held-out raw top-1
  **regressed from 28/40 (70.0%) to 19/40 (47.5%)**; both had zero automatic
  actions at the 98% precision gate, and tuned confidence is unavailable.
  Weights at `/tmp/opencode/phone-ui-models/`; keep them
  shadow-only. See the dataset README for commands and provenance.

## 2026-09-27 — Laya/Needle training research and Jev browser comparison

- Read the official Laya browser fine-tuning write-up and published pipeline,
  Needle 3 fine-tuning/tool-design/runtime/device guides, the open-source
  `browser-use/jev-ultrafast` loop, and Ship With Jev's independent catalog and
  engineering guide. Wrote a concrete phone-specific data, training and
  evaluation recipe in `android/CyanBridge/docs/LAYA_NEEDLE_PHONE_TRAINING.md`,
  linked from the model docs. Key findings: train Laya operation/compatible
  target heads using real post-action DONE and mid-task negatives; current v17s
  adds synthetic long-form episodes and harness fixes, and its model card
  discloses that earlier DAgger samples leaked evaluation goals. Train Needle
  on one-step `query`/`tools`/`answers` plus no-call examples; local Needle LoRA
  does **not** preserve calibrated confidence. Measure candidate recall before
  model accuracy and verified episode outcomes before giving either model any
  action authority. The six-state replay is too small to fine-tune reliably.

## 2026-09-27 — Tasker accessibility framing for Laya / Needle

- Read Laya's official browser-agent training example and Needle's official
  tool design, Python and confidence guides. The prior host replay duplicated a
  long multi-step goal in state and prompt, ranked options by whole-goal terms,
  exposed arbitrary first-three controls, and named Needle tools `action_a`.
  Laya's browser example specifically found that moving element details from
  state to option criteria improved accuracy; its base model needed
  task-specific training. Needle expects short current requests, narrowly named
  tools and evidence-grounded arguments, and may validly emit multiple calls for
  a multi-step request. Details and source links are in
  `android/CyanBridge/docs/TASKER_SMALL_MODEL_CONTEXT.md`.
- Added a **host-only, read-only** current-step renderer for the six captured
  Tasker YouTube states (`tools/benchmarks/phone_accessibility_frame.py`,
  `compare_laya_needle_live_shadow.py --frame compact`). With 1–2 grounded
  controls per step, both checkpoints selected the intended candidate on five
  steps and the player step delegated without inference; two visible suggestion
  options still yielded the exact suggestion when their order was reversed.
  **This is mostly code prefiltering, not a demonstrated 5/5 model accuracy:**
  four of five inferred steps had only one action tool. Workstation warm medians
  were ~40 ms Laya CUDA and ~0.21 s Needle CPU in the latest replay (host load
  varies); no Android runtime/agent
  completion measurement. Report:
  `/tmp/opencode/jev-laya-needle-compact-frame.json`. Keep the production Gemma
  state/gate unchanged until calibration on independent episodes and HIL.

## 2026-09-27 — Laya/Needle live-state shadow trial and Gmail gate

- The user's suspicion about an extra CyanBridge approval gate was checked against
  the real email trace: the ambiguous voice reply remained pending, an explicit
  affirmative reply moved the high-risk `SendEmail` to executed, and Tasker's
  `ACTION_SENDTO` opened the Gmail external composer. **There was no second
  CyanBridge confirmation gate after that approval.** A separate read-only HIL
  proved AutoInput/Tasker never returned a composer observation, so CyanBridge
  correctly did not guess a Send tap; no delivery is established.
- Reimported a profile with bounded per-node text and summary. A fresh opt-in
  `TaskerGmailComposerObserveHilTest` still timed out after 32 seconds on a
  harmless `example.invalid` SENDTO draft, with **no send operation**. Temporarily
  restricting AutoInput UI Query to Gmail and temporarily including invisible
  nodes each also timed out; both settings were restored and the original
  profile reimported. Traces: `/tmp/opencode/jev-gmail-bounded-observe.log`,
  `/tmp/opencode/jev-gmail-filtered-observe.log`, and
  `/tmp/opencode/jev-gmail-all-visible-observe.log`. Do not run another real-email
  send test until a Tasker-observed composer succeeds and the independent
  self-delivery check can run.
- Added a **debug-only, opt-in** `JevShadow` trace of bounded candidates and
  Tasker-observed YouTube state. Replayed six real consecutive decisions through
  the previously provisioned multilingual Laya checkpoint on host CUDA and
  Needle 3 host CPU. This replay is read-only: no model output can reach Tasker
  or bypass CyanBridge policy. Three actionable options plus planner fallback:
  Laya warm p50 ~41 ms/decision (first ~554 ms); Needle p50 ~1.24 s/decision,
  with invalid multiple calls at two of six steps. Laya chose “Navigate up” at
  the suggestion step and “notifications are off” at a video-player step;
  Needle and Laya diverged repeatedly. Two-action and seven-action Laya
  exploratory variants changed the choices, but also gave wrong navigation
  suggestions. Reports: `/tmp/opencode/jev-laya-needle-live-shadow.json`,
  `/tmp/opencode/jev-laya-needle-live-shadow-2actions.json`, and
  `/tmp/opencode/jev-laya-all-needle-3-live-shadow.json`. These are **host
  inference timings and hypothetical choices, not Android inference or either
  model finishing an end-to-end workflow**. The available Pixel is x86_64;
  Needle's released Android runtime is ARM64-only. A real ARM64 target and
  trustworthy calibration are needed before granting either model action
  authority.
- The Pro/Gemma-controlled YouTube HIL passed again in 22.9 seconds. Its
  independent Tasker oracle now accepts positive *advancing time* on the same
  requested video when YouTube hides the Pause overlay between polls, never a
  static progress indicator. Trace:
  `/tmp/opencode/jev-laya-needle-youtube-shadow-hil3.log`. The first shadow
  capture run reached service-observed playback but its older test oracle
  timed out; the improved oracle passed the later run.
- Paused the host USB-tether watchdog and GitHub Actions runner only during
  Android HIL, then restored both. The authenticated `Pixel_9a` remains bootable
  with its private checksum-verified offline backup intact.

## 2026-09-27 — Pro HIL, model comparisons, preserved Pixel

- **Preserved licensed Pixel:** stopped `Pixel_9a` and copied its entire AVD plus
  `.ini` to private `/home/fertroll10/.android/avd-backups/Pixel_9a-pro-20260927/`
  (14 GB, directories mode 700 and files mode 600). The offline userdata image
  SHA-256 matches the original before it was rebooted:
  `e16a5b3de0c171320f889d6af9c4bf1efb09d61a6d565e2c2abcdbddb358cf2e`.
  `/mnt/seagate` ignored mode restrictions, so its interim backup copy was removed
  after the private copy's checksum matched. Original rebooted with the same userdata,
  CyanBridge and licensed Tasker installed; no wipe or paid-app sign-in. Continue
  replacement installs only. The HIL uses `emulator-5554` (Android x86_64).
- **Cloud YouTube HIL passed:** Pro-planner/EmbeddingGemma-gated run reached
  independently observed requested playback in 44 seconds; trace
  `/tmp/opencode/jev-pixel-pro-youtube2.log`. Do not interpret this as a Laya or
  Needle run. The embedding gate can abstain and delegate to Pro.
- **Cloud email HIL remains incomplete:** after grounding the Chrome fixture,
  the run reached spoken readback, clarification after ambiguous reply, explicit
  approval, and executed `SendEmail` handoff. The original Tasker `composeEmail()`
  opened Android's generic share resolver, then returned to Chrome. The Tasker
  profile now requests `ACTION_SENDTO` with a `mailto:` URI; a harmless probe
  opened Gmail's composer. An approved-email continuation prevents re-drafting,
  re-approval, or repeated Send taps, and checks visible recipient/subject before
  any UI Send action. Targeted unit tests, Android-test compilation, Tasker
  profile validation and Tasker observer/executor smoke passed. The subsequent
  Pro email HIL reached `approved_action_executed`, then timed out: AutoInput
  observation of Gmail's external composer produces **no Tasker callback** (even
  with a 65-second read-only diagnostic request). The same observer works on the
  launcher and Gmail welcome screen. The composer was independently visible via
  `uiautomator`; **no Send tap or self-delivery was observed**. Traces:
  `/tmp/opencode/jev-pixel-pro-email3.log`, `jev-pixel-pro-email4.log`,
  `jev-pixel-pro-email-sendto.log`, `jev-gmail-composer-long-observe.log`.
  Do not launch another real-send HIL until Gmail composer observation works;
  Tasker/AutoInput must be the action/observation path, and old approved records
  or drafts must not be auto-rejected/re-sent.
- **Laya and Cactus Needle rechecked on the frozen corpus (host, not phone):**
  multilingual Laya CUDA: 42.5% held-out raw top-1, 0 safe automatic actions,
  median 51.0 ms/decision (p95 64.6). Needle 3 Linux host CPU: 70.0% held-out
  raw top-1, 0 safe automatic actions, median 585 ms/decision including tool
  init (p95 1,151 ms). Both met the ≥98% calibrated auto-precision target only
  by abstaining entirely. Reports:
  `/tmp/opencode/laya-mobile-actions-recheck-20260927.json` and
  `/tmp/opencode/needle3/mobile-actions-recheck-20260927.json`. These are
  **not end-to-end run times**. Needle ships an ARM Android runtime but not an
  x86_64 one for this authenticated AVD; Laya's LiteRT Android host is external
  and task-specific safety calibration has failed. Neither is authorized to
  make live UI decisions on this Pixel. Do not claim either completed YouTube or
  email; an ARM test target and calibrated integration are still required.
- The USB-tether watchdog and user GitHub Actions runner were restored after
  HIL; watchdog PID 1817 is running and `github-actions-runner.service` is active.

## Current handoff: Jev-like local agent (2026-09-26)

This section supersedes the older `tasker-integration-polish` handoff below for
the current branch. The historical August instructions refer to a different
worktree/branch and should not be used as the current emulator or CI state.

- **Branch:** `jev-like-local-agent`; clean before this worklog update, HEAD
  `4551deb`, one commit ahead of `origin/jev-like-local-agent` and **not pushed**.
- **Architecture:** Tasker/AutoInput observes the phone and executes grounded
  actions. CyanBridge constructs bounded legal candidates, applies policy,
  plans, asks for approval, and verifies outcomes. Do not request CyanBridge's
  own accessibility service. Open-ended text goes to its local LLM; sending
  email requires explicit approval before Tasker executes Send.
- **Best current fast-path candidate:** EmbeddingGemma 300M Q8 with cosine
  top-two margin ≥0.10, cascading uncertain steps to the local-LLM planner.
  On the frozen 169-case/13-locale corpus it scored 61% raw top-1 on model
  calibration cases and 70% on held-out model cases; accepted actions were
  48/48 and 16/16 correct, respectively, at ~46% actionable coverage. This is
  small, structurally related benchmark evidence, **not** ≥98% real-world
  precision or end-to-end task success. Qwen embedding was slower and weaker;
  Needle and multilingual Laya had confident wrong choices and no safe
  standalone automatic-action threshold on that corpus. Needle may be tested
  as an optional Gemma agreement gate, not a sole decider.
- **Live mismatch:** actual YouTube/Tasker screen states gave Gemma margins
  ~0.001–0.013, so the production gate abstained rather than accelerating.
  A short production-description probe showed removing `(node N)` text did
  not fix this; long/noisy state is the stronger suspect. Shorten/ground state,
  collect production-like snapshots and legal candidate sets, then recalibrate
  on the exact live representation without weakening send-approval or
  independently observed completion checks. Review disputed corpus labels
  separately and score stage progress as well as safe auto-action precision.
- **End-to-end status:** Tasker observation/click/type HIL passed, but the
  embedding-backed YouTube playback run ended `max_steps_reached` after gate
  abstentions and an LLM Voice Search mistap. Email tests did not reach the
  approval/send success path due to slow planning, detours, or `Brain call
  timed out`. Neither workflow is validated end to end; rerun YouTube without
  concurrent emulator activity, then separately validate draft → spoken
  approval → Send → independently observed delivery. Do not automate paid-app
  authentication or wipe the provisioned Pixel_9a AVD.
- **Release path still needed:** choose/package the patched embedding runtime
  and matched model/tokenizer outside Git/default CI; provide user-facing local
  model acquisition and Tasker/AutoInput profile, permissions, and readiness
  setup. Embedding preferences are currently default-off and enabled only for
  tests. Keep model-free tests separate from opt-in Tasker HIL; external Artemis
  fixture smoke uses a different emulator and does not prove CyanBridge HIL.
- **Unmeasured:** ARM Needle latency on a physical ARM64 target; Laya Android
  accuracy/latency on a physical GPU device. Host GPU timings are not phone
  timings. Delay any claim that one backend can finish user tasks until both
  real workflows pass with grounded state checks.

**Next actions:** capture representative Tasker snapshots; fit the Gemma gate
on shortened live states; rerun undisturbed YouTube HIL; troubleshoot the email
planner and run approval/send HIL; document install/onboarding and provisioned
model delivery. Push `4551deb` only when the shared CI emulator is available.

### Update 2026-09-26 — Jev patterns applied, ADB root cause, HIL reruns (still no pass)

### Cloud-planner follow-up (2026-09-26)

- CPU inference on the Pixel is slow enough to hit both the decision and 60-second brain deadlines. The new classifier state is bounded to 600 chars of current controls, and explicit observed first-result/YouTube exact-suggestion taps skip an unnecessary model call. Chrome site-info popups are dismissed and re-observed rather than offering their warning text as clickable task options. Article email generation now receives a short evidence-only prompt (with recipient/subject code-checked) and 200 output tokens, still yielding a HIGH-risk SendEmail pending action; the service brain deadline is 90s. Unit regressions and HIL Kotlin compilation passed.
- `CYANBRIDGE_HIL_PRO_PLANNER=true` selects Pro for the existing YouTube/email HIL; a missing linked account or non-active server entitlement fails rather than silently falling back to CPU. Added `ProPlannerReadinessHilTest` (token presence and fresh server verification only, no quota use). Tests do not log tokens.
- Examined spare AVDs read-only. `Medium_Phone_API_36` has no Pro prefs or Tasker. `CyanBridge_Walking_Aid_CI` has the owner's verified email, Tasker/AutoInput, Gemma GGUF, and **passes the server Pro-readiness test**, but Tasker itself displays **Trial Over**. Its Tasker readiness probe failed despite branch profile import; do not automate paid-app activation. Both read-only alternate emulator instances were shut down. The licensed `Pixel_9a` had no Pro Subscription prefs before setup; link it through the existing `ProAccountSetupHilTest#requestVerification` / `#verifyAccount` with a real user-provided code, not by copying the alternate AVD's encrypted secrets.
- An interrupted Pixel email instrumentation remained active until its natural 10-minute timeout; it clicked Chrome's site-info warning instead of the first result and **did not request approval or send**. Avoid duplicate email HIL launches while any instrumentation remains active.

- **Ship-with-Jev patterns applied:** fresh bounded A–H actions rebuilt from each
  Tasker observation (never reuse saved coordinates); prose/summary left to the
  detailed local-LLM planner; stage-level `JEV_HIL_STAGE` logging added to both
  HIL tests so failures report `reached=[...]`; playback evidence tightened to
  word-boundary `pause`/`playing` (paused/progress alone no longer counts);
  `run_instrumentation.sh` now refuses auto-retry for local-AI/email classes so
  a lost ADB result cannot overlap two planners or double-send email.
- **ADB drops root-caused:** host `/usr/local/sbin/usb-tether-watchdog` treats
  `emulator-5554` as a tethered phone and repeatedly runs
  `svc usb setFunctions` / Wi-Fi resets on it. Each USB reset restarts emulator
  `adbd` (`adbd socket disconnected`, new `adbd` pid, ~1 s outage) and breaks
  `am instrument`. Host has no `usb0` and routes via Ethernet. Watchdog was
  `STOP`ped for HIL and `CONT`ed afterward; self-hosted runner was stopped and
  restarted. Do not run HIL while that watchdog targets the emulator.
- **YouTube (embedding-gated):** with CI paused, reached
  `tasker_observation_ready → youtube_foreground → requested_content_visible`
  (opened YouTube, tapped Search, typed “Linus Tech Tips”, tapped a suggestion).
  Then stalled on search-results navigation and ended `Brain call timed out`
  after a 60 s single-token generation timeout; last visible text was a
  YouTube Premium upsell. Typed-search candidates now exclude Voice Search.
- **Email (deterministic fixture):** three 10-min runs, ADB stable. Fix 1
  suppressed omnibox retyping on results pages (address-bar node excluded when
  results visible); run 12794 then reached the article and offered
  `Tap Cobalt Horizon first result` as A. Fix 2 suppresses all typing when the
  grounded-answer planner is ready, so the article now leads with
  `Use the detailed planner now…` as A (run 13968). That planner then exceeds
  the 60 s `BRAIN_TIMEOUT_MS` on the emulator CPU (Qwen2.5 0.5B, ~35 s per
  single-token step), so the run ends `Brain call timed out` before a draft or
  approval exists. No email was prepared, approved, or sent in any run.
- **Tests vs strictness:** keep send-approval, no-pre-send Gmail, and observed
  post-send completion strict. The useful relaxation is stage reporting plus
  valid-alternate-tap review, not weaker safety. Next levers are planner
  latency (prompt length, token budget, model speed, timeout policy) and
  result-first candidate ordering (the fallback LLM picks A almost every step),
  then a fresh undisturbed rerun of both workflows.

**Pointers:** `android/CyanBridge/docs/JEV_LIKE_ANDROID_EMBEDDINGS_NEXT_STEPS.md`,
`android/CyanBridge/calibration/README.md`,
`android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/localagent/`,
`tools/benchmarks/benchmark_laya_mobile_actions.py`,
`tools/hil/build_embedding_runtime_aar.sh`,
`tools/hil/run_artemis_fixture.py`. The frozen corpus is
`android/CyanBridge/calibration/local_agent_mobile_actions_v1.jsonl`.

---

## Historical handoff: Tasker-Integration-Polish AI/Gmail CI Tests

Updated: 2026-08-23 (session 2, context limit reached — CONTINUE FROM "Next Steps")

## Mission

Make the new CI tests on branch `tasker-integration-polish` actually green — not "green because skipped". Specifically:

- `LocalAiTaskerChromeHilTest` — CyanBridge local model plans, Tasker/AutoInput drives Chrome against a deterministic web fixture, answer must be grounded in observed page facts.
- `LocalAgentEmailApprovalHilTest` — full real-email flow: Chrome research → summary → SendEmail queued as HIGH-risk → voice prompt → "maybe" gets clarification → literal "yes" approves → Tasker executes Gmail compose+Send → unique `CB-HIL-<ts>` subject must appear in Gmail. Recipient is the user's own account: `fernandosaiyan10@gmail.com` (user explicitly authorized real sends).

Both tests are gated by repo variables that are currently UNSET, so the last branch CI run (32656567419, both workflows success) skipped them. Default gates are green; the two optional layers have never run.

## Current Status

- Branch/worktree: `/tmp/opencode/HeyCyan-tasker-polish`, branch `tasker-integration-polish`, tracking `origin/tasker-integration-polish`, HEAD `617f8e7` + local UNCOMMITTED fixes (see below).
- Core 5-class HIL suite passes on emulator (`OK` per isolated class): fixture smoke, LocalAgent, AutoDiary, AiTaskerProfile, VisualDiary.
- Gmail on emulator `emulator-5554`: SIGNED IN as fernandosaiyan10@gmail.com; first-run screens completed (welcome tour GOT IT → TAKE ME TO GMAIL → notifications Allow → Meet Got it).
- Local model: **currently NOT installed** (deleted to free storage during APK reinstall). Must re-download via catalog UI before running either AI test (exact steps below).
- Web fixture: running on host port **18765** (8765 is occupied on this host by an LTFS server, pid 3381957 — that is also a real CI hazard, fixed in workflow, see below). Process: `nohup python3 tools/hil/serve_web_fixture.py --port 18765` (log `/tmp/opencode/tasker-polish-web-fixture-18765.log`). `adb reverse tcp:18765 tcp:18765` was set; re-run after any emulator restart. Chrome was prelaunched at `http://127.0.0.1:18765/` and marker `CYANBRIDGE_HIL_WEB_SEARCH_72941` verified visible, then HOME pressed.
- Build: `:app:assembleDebug :app:assembleDebugAndroidTest` green (needs both submodules initialized, see below).

## Uncommitted Fixes In The Worktree (all verified locally, need commit+push)

1. `.github/workflows/android-tasker-hil.yml` — fixture port collision fix:
   - Old code hard-coded port 8765; on this self-hosted runner `127.0.0.1:8765` is already used by `ltfs_web.py`, so `serve_web_fixture.py` died with `Address already in use` and the health check would pass against the WRONG server (it only checked HTTP 200).
   - New behavior: allocate an ephemeral host port, `echo port=... >> $GITHUB_OUTPUT` (step id `web_fixture`), `export CYANBRIDGE_HIL_FIXTURE_PORT`, health check requires body marker `CYANBRIDGE_HIL_WEB_SEARCH_72941`, `adb reverse tcp:$port tcp:$port`, Chrome opened at `http://127.0.0.1:$port/`, "Reset Chrome fixture" and "Stop fixture" steps use `steps.web_fixture.outputs.port`.
2. `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/localmodels/device/DeviceCapabilityService.kt` — unit fix: replaced `GIB = 1024^3` divisor with `GIGABYTE = 1_000_000_000.0` (catalog `minRamGb`/`minStorageGb`/`sizeBytes` are decimal GB). Before this, a nominal 4 GB AVD reported 3.82 "GB" and every 4 GB-tier model was rejected ("RAM unsuitable: device has 3.8 GB, model needs at least 4.0 GB").
3. `android/CyanBridge/shared/src/commonMain/kotlin/com/fersaiyan/cyanbridge/localmodels/catalog/LocalModelCatalog.kt` — qwen2.5-0.5b entry: `minStorageGb` 1.0 → 0.75 → **0.5** (with comment). Rationale: 0.5B model is ~0.47 GB; the 6 GB emulator data partition hovers around 0.5–0.8 GB free with Chrome/Gmail/Tasker resident, so a 1.0 GB post-install floor made the supported starter model permanently unloadable. Download headroom check (size+0.35 GB) is separate and still enforced.
4. `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/localmodels/download/LocalModelDownloadManager.kt` — downloader hardening: OkHttp client now `.protocols(listOf(Protocol.HTTP_1_1))` (Hugging Face large-file redirects intermittently reset HTTP/2 streams on Android: `StreamResetException: stream was reset: CANCEL`), and the retry loop catches `java.io.IOException` (covers StreamReset/Socket/DNS) instead of only SocketException+UnknownHostException. Verified: catalog download then completed ("Download complete", Status: ready).

Static checks pass: `python3 tools/hil/validate_tasker_profiles.py`, `bash -n tools/hil/*.sh`, `git diff --check`.

## Submodule Note (fresh worktrees)

This branch adds a second submodule. Both must be initialized or the build fails:
- `third_party/moonshine` @ 79b0217 (missing → `cmake.path ... doesn't exist`)
- `android/CyanBridge/app/src/main/myvu-upstream` @ 66ec6f6 (missing → dozens of `Unresolved reference 'myvu'`)

```bash
git submodule update --init --recursive
git -C third_party/moonshine lfs pull --include="core/speaker-embedding-model-data.cpp,core/third-party/onnxruntime/lib/android/arm64/libonnxruntime.so"
```

CI is fine (workflow checks out submodules recursively + LFS pull); this is only for local worktrees.

## Reproduction Trail (what was observed, in order)

1. `LocalAgentEmailApprovalHilTest` with `CYANBRIDGE_HIL_EMAIL_SEND=true` → fails safely at prereq: "No CyanBridge local model is installed/selected for the email automation HIL" (no email sent).
2. Imported the 0.5B GGUF via app UI (Import model file → Downloads → pick file). Import path has NO capability gate, but load-time gate then crashed the test app: `RAM unsuitable ... 3.8 GB` → fixed by decimal-GB change (#2).
3. After #2, load gate: `Not enough free storage. Need about 1.00 GB` (custom imports fall back to hardcoded min 4.0 RAM/1.0 storage in `LocalChatSessionManager.ensureModelLoaded` capabilityEntry) → decided to use the CATALOG download instead (catalog floor now 0.5).
4. Catalog download failed twice with `stream was reset: CANCEL` (HTTP/2) → fixed by #4; then downloaded successfully via the app UI.
5. `LocalAiTaskerChromeHilTest` then reached model load and failed only on the storage floor (0.75) → lowered to 0.5 (#3). APK replacement then hit `INSTALL_FAILED_INSUFFICIENT_STORAGE`; recovered by deleting `files/local_models` via `run-as` + `cmd package trim-caches 2G` + reinstall (Success).
6. **Stopped here**: about to re-download the model via catalog UI and rerun the local-AI test.

## Next Steps (in order)

1. **Re-install + re-download model** (emulator `emulator-5554`):
   - APKs are already installed (patched build). If needed: `bash tools/hil/install.sh emulator-5554` from the polish worktree.
   - Free storage first if low: `adb shell cmd package trim-caches 2G`; check `adb shell df -k /data` (need ≥ ~1.2 GB free for download headroom 0.4+0.35 GB; after install ~0.5 GB is enough to LOAD).
   - UI recipe (coordinates are for this 1080x2424 AVD, verified working):
     1. `adb shell monkey -p com.fersaiyan.cyanbridge 1` (launch; dismiss any permission dialog with Allow at ~(540,1325))
     2. Tap Settings tab: (980,2190)
     3. Swipe up: `input swipe 540 1900 540 700 500`
     4. Tap "Configure local models" row parent (text bounds ~[147,1140..1282]; tap ~(300,1193) — re-dump `uiautomator` and compute from `text="Configure local models"` if unsure)
     5. In Local models screen: tap "Curated catalog" header to expand (~(500,1157) or wherever the header is; verify `Collapse Curated catalog` appears)
     6. Swipe down: `input swipe 540 2200 540 700 600` until `text="Qwen2.5 0.5B Instruct (Q4_K_M)"` visible with `Device suitable`
     7. Tap its **Download** button — it is the LEFT button of the pair, e.g. bounds [84,1335][529,1461] → tap (300,1387). (The right button is "Info".)
     8. Wait ~90 s; verify `Status: ready` and "Download complete". If "stream was reset" appears, the HTTP/1.1 fix isn't installed — rebuild/reinstall first.
   - Fallback (if catalog download keeps failing): `adb push /tmp/opencode/qwen2.5-0.5b-instruct-q4_k_m.gguf /sdcard/Download/` then app → Local models → "Import model file" → Downloads → tap the file card (left card, e.g. (300,1000)). Caveat: imported-custom models use hardcoded floors (4.0 RAM/1.0 storage) in `LocalChatSessionManager` — with the decimal-GB fix RAM passes (4.1 GB), but storage needs ≥1.0 GB free; trim caches or free space first. Catalog download is preferred.
2. **Run local-AI test** (fixture must be up):
   ```bash
   nohup python3 tools/hil/serve_web_fixture.py --port 18765 >/tmp/opencode/tasker-polish-web-fixture-18765.log 2>&1 &
   adb -s emulator-5554 reverse tcp:18765 tcp:18765
   adb -s emulator-5554 shell am force-stop com.android.chrome
   adb -s emulator-5554 shell am start -W -a android.intent.action.VIEW -d 'http://127.0.0.1:18765/' -p com.android.chrome
   sleep 3; adb -s emulator-5554 shell input keyevent KEYCODE_HOME
   CYANBRIDGE_HIL_LOCAL_AI=true bash tools/hil/run_instrumentation.sh emulator-5554 hardware com.fersaiyan.cyanbridge.hil.LocalAiTaskerChromeHilTest
   ```
   Test must answer with "37", "amber", "cyanbridge", "tasker" (Borealis fixture facts) and end observed on Chrome article marker `CYANBRIDGE_HIL_WEB_ARTICLE_72941`. Debug with `adb logcat -s TaskerLocalAgent LocalChatSession LocalModelDownload` and `run-as com.fersaiyan.cyanbridge cat shared_prefs/local_agent_prefs.xml` (status/last_error). Planner is Qwen 0.5B on CPU — steps are slow; BRAIN_TIMEOUT_MS=60 s per step, test budget 6 min.
3. **Run the real-email test** (user authorized; sends exactly one self-email per run):
   ```bash
   adb -s emulator-5554 shell am force-stop com.android.chrome
   adb -s emulator-5554 shell am start -W -a android.intent.action.VIEW -d 'http://127.0.0.1:18765/' -p com.android.chrome
   sleep 2; adb -s emulator-5554 shell input keyevent KEYCODE_HOME
   CYANBRIDGE_HIL_EMAIL_SEND=true bash tools/hil/run_instrumentation.sh emulator-5554 hardware com.fersaiyan.cyanbridge.hil.LocalAgentEmailApprovalHilTest
   ```
   Preconditions inside the test: NO pre-existing pending actions (assert fails otherwise — resolve via app Pending Actions UI if needed), provider becomes LOCAL_AGENT (or already PRO_SUBSCRIPTION), `RemoteOpenAiPrefs.isActive` false, model installed. Replies "maybe"/"yes" are injected textually via `LocalAgentController.replyToApproval` (no real mic needed), but the voice session still speaks via TTS and listens (silence tolerated; external reply wins).
4. **Fix whatever the tests expose** (product bugs, keep changes minimal), then:
   - `python3 tools/hil/validate_tasker_profiles.py && bash -n tools/hil/*.sh && git diff --check`
   - `./gradlew --no-daemon :app:testDebugUnitTest :shared:portabilityTest`
   - Core suite (5 classes) still green.
5. **Commit** the four files listed above (+ any new fixes) on `tasker-integration-polish` and push. Suggested message themes: fixture port collision + marker health check; decimal-GB capability units; 0.5B storage floor; HTTP/1.1 + IOException retry for model downloads.
6. **Enable CI layers** (requires repo admin; `gh variable list` currently shows NO CYANBRIDGE_* vars):
   ```bash
   gh variable set CYANBRIDGE_HIL_ENABLE_EMAIL_SEND --body true -R FerSaiyan/Alternative-HeyCyan-App-and-SDK
   gh variable set CYANBRIDGE_HIL_ENABLE_LOCAL_AI  --body true -R FerSaiyan/Alternative-HeyCyan-App-and-SDK   # optional but recommended
   ```
   If you cannot set vars, ask the user to add them in GitHub → Settings → Secrets and variables → Actions → Variables.
7. **Verify CI**: push → watch `Android Tasker HIL` run; the previously "skipped" steps (Prepare persistent emulator for AI automation HIL, Prepare deterministic Chrome fixture, Run CyanBridge local AI through Tasker and Chrome, Reset Chrome fixture, Run approved Gmail self-send HIL) must now RUN and PASS. Note the runner's persistent AVD is presumably this same machine's AVD (self-hosted homelab runner) — local emulator state (Gmail sign-in, installed model, Tasker entitlements) IS the CI state; keep it healthy, never wipe.
8. Update this worklog with final results.

## Operational Notes

- ADB on this API 37 emulator drops constantly (`error: device offline/not found`). Pattern that works: `adb kill-server; adb start-server; adb -s emulator-5554 wait-for-device` before each batch; retry failed taps once.
- `run_instrumentation.sh` already: dismisses the 16 KB compat dialog, splits comma-lists into one process per class, and recovers offline emulators via `tools/hil/start_emulator.sh` (cold boot, userdata preserved).
- Storage is the recurring constraint (6 GB /data, ~80–92% used). Recovery recipe that worked: `adb shell am force-stop com.fersaiyan.cyanbridge; adb exec-out run-as com.fersaiyan.cyanbridge sh -c 'rm -rf files/local_models'; adb shell cmd package trim-caches 2G; adb install -r -d <apk>`.
- Gmail/Tasker/AutoInput state, Google account, and accessibility grants must never be wiped (`pm clear`, `-wipe-data` are forbidden).
- The email test's Tasker side executes the approved SendEmail through the Local Agent Tasker project (`android/CyanBridge/tasker/CyanBridge_LocalAgent_Tasker.prj.xml`); if execution fails, check `TaskerExecutionBackend.execute` results in the pending-action record and the Tasker run log. The planner prompt requires a second observation proving compose is gone before finishing.
- Repo variables/secret context: `META_GITHUB_TOKEN` secret exists; `CYANBRIDGE_HIL_*` vars do not (as of this writing).

## Constraints (unchanged)

- No subagents. No credential exposure. No billing/entitlement bypass (Tasker/AutoInput entitlements were legitimately restored earlier via the user's own AutoApps purchases).
- Real email ONLY to `fernandosaiyan10@gmail.com`, one per test run, unique `CB-HIL-<ts>` subject, body labeled as deterministic fixture data.
- Do not `pm clear` CyanBridge/Gmail/Tasker; do not wipe the AVD; do not force-push without approval.

## Real UI collection (2026-09-27, Artemis isolated AVD)

- Created `CyanBridge_Artemis_Data` (Android 36, `emulator-5560`) separate from authenticated `Pixel_9a`. The collector verifies AVD name, boot, absence of Tasker/AutoInput and absence of Android accounts before actions. Artemis checkout revision `371aa6df56880643da57b30da936e9812fb0ec66` supplied `AndroidAdbDriver` + UIAutomator2; no Artemis agent, LLM credentials or accessibility helper were used.
- Collected 14 verified Settings navigation episodes, 5 Clock selected-tab episodes, and 10 YouTube **query-field-only** episodes. Every labeled action has pre/post screenshot and hierarchy hashes, a currently observed binding, and a separate checked postcondition. Unverified Settings transitions into other packages and YouTube's failed network search were excluded. Raw screenshots/XML remain in `/tmp/opencode/artemis-phone-captures/`, not Git.
- Assembled `android/CyanBridge/training/artemis_real_v1/pool.jsonl` and manifest: 29 episodes, 82 decisions (43 TAP, 10 TYPE_LITERAL, 29 DONE), 40 screen signatures, just **three connected screen families**. `readyForIndependentSplit=false` and `readyForTaskerTraining=false`. These rows were **Artemis-observed**, not Tasker-observed; no Laya/Needle retraining was performed on them.
- Python collector/audit suite: 9 tests passing. Android `:app:compileDebugAndroidTestKotlin` passed with `ANDROID_HOME` set. Added an opt-in Tasker Settings parity HIL test to measure whether a target seen by Artemis is also present and retained in the real Tasker candidate set; Tasker execution result must be measured separately.

## Tasker Settings source parity and verified episode (2026-09-28)

- On `Pixel_9a`, Tasker/AutoInput observed 20 Settings nodes and CyanBridge retained `Network & internet` among seven candidates. The opt-in read-only parity HIL passed.
- A first Settings-navigation trial had **already resumed the Network page before the tap**. Its post markers looked good, but the audited pre-action state exposed a false positive; the export was discarded. The final test explicitly starts Settings HOME and refuses an already-open destination, missing Tasker markers, unchanged hashes or stale timestamps.
- The HIL emulator-recovery helper reused `emulator-5554` for `CyanBridge_Walking_Aid_CI` hours later. An AVD-identity check prevented an invalid Pixel export. `CYANBRIDGE_HIL_ARTEMIS_PARITY=true` now pins `Pixel_9a` and fails closed on offline/wrong AVD before and after instrumentation. Pinned HIL passed on `emulator-5562` without clearing app data.
- The final allowlisted Tasker diagnostic is `android/CyanBridge/training/tasker_real_v1/settings_network.json` (episode `tasker_settings_network_1790601149125`). Tasker pre-state: Settings home. Tasker action: tap `Network & internet`. Tasker post-state: `Internet` and `SIMs`, changed hash; separate ADB UIAutomator dump confirmed page title and `Airplane mode`. Pre-observation to verified post-observation: **1,821 ms**; action start to verification: **1,734 ms**. This is one diagnostic episode, not a trainable split or a planner comparison. No email was sent.
