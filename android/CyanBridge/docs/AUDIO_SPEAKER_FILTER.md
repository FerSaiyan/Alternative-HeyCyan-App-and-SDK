# User-voice filtering with audio embeddings (2026-10-07)

**Question.** Can CyanBridge enroll the wearer with a few spoken phrases and then use audio
embeddings to keep the wearer's speech while rejecting other people and background noise?
The candidates were the EmbeddingGemma 2 audio tower and the Gemma 4 E2B audio encoder,
each compared with a dedicated speaker-verification model.

## Decision

> **Update (2026-10-07, later the same day).** A trained head on the frozen shared encoder
> was tested. It does much better than the raw embeddings, but the decision below still stands.
> See [Addendum: speaker head on the frozen shared Gemma audio encoder](#addendum-2026-10-07-speaker-head-on-the-frozen-shared-gemma-audio-encoder).

- **Neither Gemma model is usable for speaker filtering.** On matched trials (2 s segments,
  5-phrase enrollment) the deployable EmbeddingGemma 2 embedding has an equal error rate
  (EER) of **38.7 %** against other speakers. The dedicated models score **0.2–0.4 %**.
  Gemma 4 E2B's projected audio soft tokens score 35.7 %. Even the best internal layer of the
  shared audio encoder (a host-only diagnostic) reaches only 8.8–9.4 % EER and accepts 3 % or
  less of the wearer's speech in 0 dB babble.
- **Use a dedicated speaker-verification model.** Ship **SpeechBrain ECAPA-TDNN**
  (`speechbrain/spkrec-ecapa-voxceleb`, Apache-2.0, about 21 M parameters). It has been
  exported to a LiteRT `.tflite` (84 MB fp32) that takes raw 16 kHz PCM, with the filterbank
  inside the graph, and runs on the LiteRT Interpreter the app already ships (`litert:1.4.2`).
  **WeSpeaker ResNet34-LM** (CC BY 4.0, 26.5 MB ONNX) is the upgrade candidate. It is slightly
  more accurate and faster on host CPU (25 ms per 2 s window with ONNX Runtime, against 108 ms
  for the ECAPA `.tflite`), but has no verified LiteRT export yet.
- EmbeddingGemma 2 still has a role in CyanBridge as a semantic multimodal embedder
  (for example, spoken-query search). It does not identify speakers.

## Why the Gemma models fail (measured)

- **One shared audio encoder.** EmbeddingGemma 2's audio tower and Gemma 4 E2B's are
  **byte-identical**: 751 of 752 audio tensors are equal (checked with `torch.equal` on
  safetensors). The only difference is the soft-token projector: 1536→512 in EmbeddingGemma 2
  and 1536→1536 in Gemma 4. Pooled tower outputs give identical metrics for both models.
  Evaluating "the Gemma 4 E2B audio encoder" and "the EmbeddingGemma 2 audio tower" is
  therefore one experiment, plus two different heads.
- **Speaker identity is stripped layer by layer.** Dev EER (LibriSpeech test-other, 2 s,
  5 enrollment phrases) for the mean-pooled encoder states:

  | Stage | Dev EER |
  |---|---|
  | Subsampling conv | 7.3 % |
  | Layer 0 | 7.3 % |
  | Layer 1 | 7.5 % |
  | Layer 3 | 13.8 % |
  | Layer 7 | 19.8 % |
  | Layer 11 | 30.3 % |
  | Encoder output | 29.4 % |
  | EmbeddingGemma 2 final | 35.8 % (dev-mean centred; 43.5 % raw) |

  The encoder is trained for content (ASR/semantics), so its speaker information sits at the
  front end. A model-free baseline (mean and standard deviation of the same 128-bin log-mel
  features) already reaches 15.1 % EER on test.
- **The deployable embedding does not even reject noise.** On the 768-d EmbeddingGemma 2
  output, the wearer's speech and MUSAN noise or music give an EER of 34 % (2 s). The encoder
  states separate speech from non-speech almost perfectly (EER ≤ 2 %), so they work as a
  speech detector, not a speaker detector.

## Protocol (identical, matched trials for every model)

- **Speakers.** LibriSpeech test-clean, 40 speakers (20 F, 20 M), all of them targets in turn.
  Each speaker has 2 independent enrollment draws of 5 phrases (2–4 s crops of separate
  utterances). The 3-phrase enrollment is the first 3 phrases of each draw. Test material is
  20 different utterances per speaker, cropped with an energy-based speech check to 1.0, 2.0
  and 3.5 s. Scores are pooled over the 2 draws: per segment length, 1,600 target trials and
  62,400 other-speaker trials.
- **Negative classes**, reported separately:
  - (a) Other speakers: every other test-clean speaker's segments, plus a same-gender subset.
  - (b) Non-speech: 300 MUSAN noise and 100 MUSAN music crops per length, levelled to
    −32…−20 dBFS so loudness is not a cue.
  - (c) The wearer's own segments (10 utterances per speaker) mixed with MUSAN noise (files
    held out from b) or with 4-talker babble from LibriSpeech test-other, at 0, 5 and 10 dB SNR.
    The SNR is measured on speech-active frames. These must be **accepted**, so the table
    reports the acceptance rate at the threshold that gives 5 % false accepts on (a).
- **Scoring.** Cosine to the mean of L2-normalised enrollment embeddings ("mean") or the
  maximum over individual phrases ("max"). One global threshold, no per-speaker tuning.
- **Selections made on a dev set only.** Raw versus dev-mean-centred cosine, and the best
  encoder layer, were chosen on LibriSpeech test-other (33 speakers, disjoint from test-clean),
  never on test trials. CI95 values are 200-sample speaker bootstraps.
- **Precision.** Gemma models ran in bf16 on GPU and fp32 on CPU, never fp16.
  EmbeddingGemma 2 was loaded with `config_kwargs={"vision_config": None}`.

## Results

All values are percentages. "TPR" is the acceptance rate of the wearer at a false-accept rate
(FPR) of 1 % or 5 % on (a). "FAR (b)" is the false-accept rate of noise and music at the 5 %
threshold. The (c) columns give acceptance at the 5 % threshold, for 0, 5 and 10 dB SNR.
Mean scoring throughout. Cosine is raw except where marked "centred".

### Deployable representations

| Model / representation | Enroll | Segment | EER (a) [CI95] | TPR@FPR1% | TPR@FPR5% | EER same-gender | EER (b) | FAR (b) | (c) noise 0/5/10 dB | (c) babble 0/5/10 dB |
|---|---|---|---|---|---|---|---|---|---|---|
| **WeSpeaker ResNet34-LM** | 3 | 1.0 s | 0.9 | 99.1 | 99.9 | 1.2 | 0.1 | 0.1 | 91/97/98 | 52/88/96 |
| | 3 | 2.0 s | 0.5 | 99.6 | 100 | 0.6 | 0.0 | 0.0 | 98/100/100 | 72/96/99 |
| | 3 | 3.5 s | 0.2 | 99.9 | 100 | 0.3 | 0.0 | 0.0 | 100/100/100 | 85/98/100 |
| | 5 | 1.0 s | 0.9 [0.4–1.2] | 99.2 | 99.9 | 1.1 | 0.1 | 0.0 | 91/97/99 | 53/89/97 |
| | 5 | 2.0 s | 0.2 [0.0–0.6] | 99.9 | 100 | 0.3 | 0.0 | 0.0 | 98/100/100 | 72/96/100 |
| | 5 | 3.5 s | 0.2 [0.0–0.5] | 99.9 | 100 | 0.2 | 0.0 | 0.0 | 100/100/100 | 86/99/100 |
| **ECAPA-TDNN (SpeechBrain)** | 3 | 1.0 s | 1.7 | 97.4 | 99.2 | 2.3 | 0.5 | 0.3 | 88/96/96 | 43/76/90 |
| | 3 | 2.0 s | 0.7 | 99.3 | 99.7 | 0.7 | 0.2 | 0.1 | 96/99/99 | 56/88/97 |
| | 3 | 3.5 s | 0.4 | 99.8 | 100 | 0.4 | 0.1 | 0.1 | 99/100/100 | 71/96/99 |
| | 5 | 1.0 s | 1.6 [1.1–2.1] | 97.8 | 99.6 | 2.1 | 0.4 | 0.2 | 88/96/97 | 44/78/92 |
| | 5 | 2.0 s | 0.4 [0.1–0.9] | 99.6 | 99.9 | 0.5 | 0.1 | 0.1 | 97/100/100 | 57/90/98 |
| | 5 | 3.5 s | 0.3 [0.0–0.7] | 99.8 | 100 | 0.3 | 0.0 | 0.1 | 99/100/100 | 73/97/99 |
| **EmbeddingGemma 2 final 768-d** (centred) | 3 | 1.0 s | 43.2 | 3.8 | 12.9 | 43.6 | 45.9 | 7.6 | 10/10/12 | 4/7/11 |
| | 3 | 2.0 s | 40.1 | 7.1 | 17.6 | 40.7 | 37.8 | 2.8 | 12/14/16 | 3/7/13 |
| | 3 | 3.5 s | 38.4 | 9.2 | 21.6 | 38.9 | 29.9 | 1.1 | 16/18/19 | 2/8/15 |
| | 5 | 1.0 s | 41.7 [39.5–43.6] | 3.9 | 13.4 | 42.1 | 44.2 | 6.0 | 10/10/11 | 4/7/11 |
| | 5 | 2.0 s | 38.7 [36.4–40.8] | 7.4 | 19.9 | 39.3 | 33.9 | 1.7 | 12/15/18 | 3/8/14 |
| | 5 | 3.5 s | 36.3 [33.7–38.6] | 10.8 | 22.5 | 37.1 | 24.1 | 0.8 | 15/18/20 | 1/8/15 |
| EmbeddingGemma 2, Matryoshka (MRL) 256-d (centred) | 5 | 2.0 s | 40.1 [37.2–42.1] | 6.2 | 17.7 | 40.8 | 28.5 | 0.7 | 11/14/16 | 4/8/13 |
| EmbeddingGemma 2, Matryoshka (MRL) 128-d (centred) | 5 | 2.0 s | 43.9 [41.4–45.7] | 3.2 | 11.4 | 44.2 | 20.8 | 0.1 | 9/8/10 | 4/7/11 |

### Host-only representations (Gemma 4 E2B encoder = EmbeddingGemma 2 audio tower)

| Representation | Enroll | Segment | EER (a) [CI95] | TPR@FPR1% | TPR@FPR5% | EER same-gender | EER (b) | (c) noise 0/5/10 dB | (c) babble 0/5/10 dB |
|---|---|---|---|---|---|---|---|---|---|
| Gemma 4 E2B projected soft tokens (centred) | 3 | 2.0 s | 37.1 | 6.3 | 18.0 | 37.6 | 0.5 | 4/6/9 | 0/0/1 |
| | 5 | 1.0 s | 41.5 [39.6–43.8] | 3.3 | 11.0 | 42.0 | 0.6 | 3/3/4 | 0/0/1 |
| | 5 | 2.0 s | 35.7 [33.0–37.7] | 7.2 | 20.1 | 36.2 | 0.5 | 4/6/9 | 0/0/2 |
| | 5 | 3.5 s | 30.2 [28.1–32.6] | 11.8 | 30.1 | 31.1 | 0.1 | 7/11/13 | 0/0/1 |
| Encoder output, mean-pooled (both models; centred) | 5 | 2.0 s | 35.4 [32.8–37.1] | 7.2 | 20.1 | 36.4 | 0.6 | 4/6/10 | 0/0/2 |
| Best layer (layer 0, dev-selected) | 3 | 2.0 s | 10.4 | 65.8 | 82.9 | 13.4 | 0.6 | 16/22/32 | 0/3/21 |
| | 5 | 1.0 s | 16.0 [14.2–17.9] | 45.6 | 66.8 | 20.4 | 1.8 | 12/15/24 | 0/7/27 |
| | 5 | 2.0 s | 9.4 [8.0–11.1] | 68.6 | 84.4 | 12.8 | 0.3 | 16/21/33 | 0/3/21 |
| | 5 | 3.5 s | 6.4 [5.2–8.1] | 82.5 | 92.8 | 8.4 | 0.1 | 15/30/36 | 0/3/18 |
| Subsampling-conv output (centred) | 5 | 2.0 s | 8.8 [7.4–10.6] | 69.2 | 86.1 | 12.1 | 0.3 | 13/17/29 | 3/18/50 |
| Log-mel mean+std (no model) | 5 | 2.0 s | 15.1 [13.4–17.2] | 44.9 | 68.6 | 18.1 | 3.1 | 8/10/15 | 0/1/8 |

The full grid (raw and centred, 3 and 5 phrases, mean and max scoring, all lengths, and the
noise and music split of (b)) is in `results/results.json` and `results/summary.md` under
the artifact directory.

### Observations

- **Enrollment size.** Going from 3 to 5 phrases helps a little: ECAPA at 2 s improves from
  0.7 to 0.4 % EER. Five phrases of about 3 s each is a reasonable enrollment flow.
- **Segment length.** 1 s windows roughly double to quadruple the EER of 2 s windows for the
  dedicated models. **Decide on about 2 s windows.**
- **Mean versus max template.** There is no consistent gain for the dedicated models (ECAPA at
  2 s: 0.4 vs 0.5 %; WeSpeaker: 0.2 vs 0.3 %). Use the mean template: one 192- or 256-float
  vector. Max scoring helps the weak Gemma layers (9.4 → 6.5 %), but not enough to matter.
- **Same-gender impostors.** These are only slightly harder for the dedicated models (ECAPA at
  2 s: 0.5 vs 0.4 %). They are much harder for the Gemma layers (12.8 vs 9.4 %).
- **Noise.** At the 5 % threshold, ECAPA and WeSpeaker accept 97–98 % of the wearer's 2 s
  speech in 0 dB MUSAN noise and 99–100 % from 5 dB.
- **Babble is the hard case.** Equal-power 4-talker babble at 0 dB cuts acceptance to 57 %
  (ECAPA) or 72 % (WeSpeaker). It recovers to 90–96 % at 5 dB and 98–100 % at 10 dB. A
  head-worn microphone normally gives the wearer a strong SNR advantage over bystanders.
  Expect drop-outs in loud crowds, and consider a short hysteresis rather than per-window
  hard gating.
- **Non-speech is never confused with the wearer** by the dedicated models. EER (b) is 0.4 %
  or less, and the false-accept rate on noise and music is 0.3 % or less.

## Latency and size (measured on host)

Batch size 1. Times include feature extraction. CPU runs used 4 threads on an Intel Xeon
Silver 4310. GPU runs used an RTX A4000 that was **shared with another user's job at 100 %
utilisation**, so treat GPU numbers as upper bounds.

| Model | Device | 1.0 s (ms) | 2.0 s (ms) | 3.5 s (ms) | Parameters | Weights |
|---|---|---|---|---|---|---|
| WeSpeaker ResNet34-LM (ONNX Runtime) | CPU fp32 | 14 | 25 | 39 | ≈6.6 M* | 26.5 MB ONNX |
| ECAPA-TDNN (PyTorch / SpeechBrain) | CPU fp32 | 117 | 227 | 372 | ≈21 M* | 83 MB ckpt |
| ECAPA-TDNN **LiteRT .tflite** (XNNPACK, ai-edge-litert 1.4.0) | CPU fp32 | — | **108** | — | ≈21 M* | 84 MB `.tflite` |
| ECAPA-TDNN | GPU fp32 | 10 | 10 | 11 | | |
| Gemma 4 E2B audio encoder + projector | CPU fp32 | 203 | 245 | 232 | 307.2 M | 614 MB bf16 |
| Gemma 4 E2B audio encoder + projector | GPU bf16 | 37 | 38 | 51 | | 0.6 GB peak VRAM |
| EmbeddingGemma 2 (audio + text towers, vision dropped) | CPU fp32 | 350 | 346 | 442 | 576.6 M | 1.49 GB bf16 (full) |
| EmbeddingGemma 2 | GPU bf16 | 104 | 89 | 126 | | 1.1 GB peak VRAM |

\* Inferred from fp32 file size.

A speaker template is 192 floats (768 B) for ECAPA or 256 floats for WeSpeaker. Published
EmbeddingGemma 2 LiteRT figures for text plus 10 s of audio, not measured here: S26 Ultra CPU
305 ms / 465 MB, GPU 350 ms; Pixel 11 Pro TPU 193 ms.

## On-device paths

| Model | LiteRT path | Status |
|---|---|---|
| EmbeddingGemma 2 | **Exists.** Bundle `litert-community/embeddinggemma-2-740m-litert-lm` @ `24d962e906c7d332c6428e71c9676855024569e2` (485 MB CPU/GPU file; SoC-specific NPU files 0.54–0.90 GB; audio encoder QAT mixed int2/int4/int8; "Audio soft tokens: 12", streamed audio). Use LiteRT-LM Android **0.17.1+** `EmbeddingEngine` (0.18.0 is the complete line): `EmbeddingEngineConfig(modelPath, backend, visionBackend, audioBackend, cacheDir, modelFd, maxInputLength, visionTokensPerImage, activationDataType)` → `computeEmbedding(List<InputData>, EmbeddingOptions(normalize, insertSpecialTokens, outputSize, visionTokensPerImage))` → `EmbeddingResponse.embedding: FloatArray`. Audio input is **`InputData.Audio(bytes: ByteArray)`** (confirmed in the 0.18.0 classes; AAR sha256 `706cbb8a…595f`). The native library decodes those bytes with miniaudio and logs "Only mono audio is supported", so pass an **encoded mono 16 kHz WAV**, not raw floats (*inferred from native strings, not run*). | Runtime path exists. It only returns the final pooled 768-d embedding, which is the representation measured at **38.7 % EER**. The on-device QAT audio encoder can only match or degrade the bf16 host result. **Not worth an on-device test for speaker filtering.** |
| Gemma 4 E2B | `litert-community/gemma-4-E2B-it-litert-lm` @ `b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1` (2.0–3.3 GB). The LiteRT-LM Kotlin API for this model is `Engine`/`Conversation` text generation only. `EmbeddingEngine` targets `ModelType.EMBEDDING` models. The native library has internal `AudioEmbeddings` / `ProjectedAudioEmbeddings` executor tensors, but no JNI method returns them. | **No exposed audio-encoder output** (*inferred from AAR inspection*). Extracting the encoder section from the `.litertlm` and running it with the Interpreter was not attempted. The encoder is the same network as EmbeddingGemma 2's, and its best layer is still 20–40× worse than a dedicated model. |
| **ECAPA-TDNN** (recommended) | Exported here with `litert-torch` 0.9.4: `ecapa_spkrec_voxceleb_2s.tflite`, input `float32[1, 32000]` (2 s, 16 kHz, [-1, 1]), output `float32[1, 192]`. The STFT is a fixed conv1d DFT, followed by SpeechBrain's own Filterbank and sentence mean normalisation, so no DSP is needed in Kotlin. Host parity against SpeechBrain on 200 real items: min cosine 0.9999998. It also loads on `ai-edge-litert==1.4.0` (min parity 0.99999995), which suggests the app's existing `com.google.ai.edge.litert:litert:1.4.2` Interpreter can run it. | **Exists; host-verified.** Device not yet run. The probe test below is written but has not been built or run. |
| WeSpeaker ResNet34-LM | ONNX from the model repo. The app has no ONNX Runtime. A LiteRT conversion (ONNX→TF via `onnx2tf`, or the PyTorch checkpoint via `litert-torch`, with the Kaldi fbank in-graph or in Kotlin) was **not attempted**. | Candidate upgrade. |

### On-device probe (written, not built or run)

`app/src/androidTest/java/com/fersaiyan/cyanbridge/hil/AudioSpeakerLiteRtProbeTest.kt` uses
only the existing `org.tensorflow.lite.Interpreter` dependency. It is skipped unless both
arguments below are passed. It logs `AUDIO_SPK` lines covering load time, per-clip parity
against host LiteRT embeddings, median and p90 latency, and enrollment scores. It asserts
parity ≥ 0.995 and that the lowest clean-target score exceeds the highest other-speaker or
noise score. The fixture is 16 public LibriSpeech/MUSAN 2 s clips (1.1 MB), produced by
`tools/benchmarks/audio_speaker_make_fixture.py`.

```bash
# from /mnt/seagate/ML/cyanbridge-audio-speaker-20261007 (main session owns the devices)
adb push litert/ecapa_spkrec_voxceleb_2s.tflite /data/local/tmp/audio_speaker/
adb push litert/fixture /data/local/tmp/audio_speaker/
adb shell am instrument -w \
  -e class com.fersaiyan.cyanbridge.hil.AudioSpeakerLiteRtProbeTest \
  -e audio_speaker_model /data/local/tmp/audio_speaker/ecapa_spkrec_voxceleb_2s.tflite \
  -e audio_speaker_fixture /data/local/tmp/audio_speaker/fixture \
  -e audio_speaker_threads 4 \
  com.fersaiyan.cyanbridge.test/androidx.test.runner.AndroidJUnitRunner
```

The app's `testInstrumentationRunner` is `androidx.test.runner.AndroidJUnitRunner`; `tools/hil/run_instrumentation.sh` wraps the same `am instrument -w -r` call.

## Integration sketch (not implemented)

1. **VAD first.** Only score windows that contain speech. The dedicated models already reject
   noise and music, but a VAD saves compute.
2. **Windows.** 2 s windows with a 0.5–1 s hop. Smooth decisions over 2–3 windows (hysteresis)
   so babble dips do not chop the wearer's speech.
3. **Enrollment.** 5 phrases of 2–4 s each, ideally recorded through the glasses microphone
   that will be used at runtime. Split each phrase into 2 s windows. The template is the mean
   of the L2-normalised window embeddings.
4. **Threshold.** Calibrate on the device and microphone. LibriSpeech thresholds will not
   transfer to the glasses microphone or Bluetooth codec. Expose a sensitivity setting
   (FPR target of 1–5 %).
5. **Privacy.** A speaker embedding is **biometric data**. Keep enrollment audio and templates
   **on the device only**: app-private storage, never synced, never sent to cloud models, and
   never logged. Delete the template on user request or sign-out. Get explicit consent at
   enrollment. Bystanders are scored but never enrolled or stored.

## Measured versus inferred; limitations

- **Measured.** Every EER, TPR and FAR value above; host latencies; parameter counts for the
  Gemma models; the identity of the audio-tower weights; ECAPA LiteRT parity on ai-edge-litert
  2.2.0 and 1.4.0; the LiteRT-LM 0.18.0 class surface.
- **Inferred, not measured.**
  - Phone latency and memory.
  - That `InputData.Audio` expects an encoded WAV.
  - That Gemma 4 encoder outputs are not reachable through LiteRT-LM.
  - That the quantized on-device EmbeddingGemma 2 is no better than bf16 on host.
  - That `litert:1.4.2` on Android accepts the exported ops, which is based on the Python
    1.4.0 runtime.
  - Parameter counts for ECAPA and WeSpeaker, which come from file sizes.
- **Domain.** LibriSpeech is clean, close-talk read speech. Real glasses audio (wind,
  far-field bystanders, Bluetooth codecs, the wearer's own voice through bone and air) will be
  worse for every model. The ranking is not in doubt (a 90× EER gap), but absolute numbers will
  move.
- **Training overlap.** Both baselines were trained with noise augmentation, and WeSpeaker's
  recipe lists MUSAN. Results on the MUSAN noise class (b, c) may therefore be optimistic for
  the baselines. Babble is built from LibriSpeech, which neither baseline was trained on.
- **Music provenance.** The music subset came from a parquet repack
  (`dys-asr/musan-sr16000` @ `6b90f661bfe51e873d9c79bb3df7b9f442f0bdb1`, shards 00000 and
  00009, 132 tracks) without per-file names, so per-file MUSAN music licences cannot be traced.
  Overall MUSAN is CC BY 4.0. The music class is only a negative class, and all conclusions
  also hold on the noise class alone.
- **Scope.** No microphone, device or personal audio was used.

## Reproduction

Host: Luciano. Work directory `/tmp/opencode/cyanbridge-audio-speaker-20261007`, venv
`audio-venv` (Python 3.12, torch 2.7.0+cu128, torchaudio 2.7.0, transformers 5.19.0,
sentence-transformers 6.1.0, speechbrain 1.1.1, onnxruntime 1.30.0; freeze in
`logs/pip_freeze.txt`). The separate CPU `convert-venv` (litert-torch 0.9.4, torch 2.13.0+cpu,
ai-edge-litert 2.2.0) was used only for the LiteRT export.

```bash
P=audio-venv/bin/python; S=/tmp/opencode/cyanbridge-specialists-20261003/sources
$P audio_speaker_build_trials.py --librispeech-dir data/librispeech_asr \
   --speakers-txt data/meta/LibriSpeech/SPEAKERS.TXT --musan-noise-dir data/musan-noise/noise \
   --musan-music-dir data/musan-music-wav --out trials            # seed 20261007
CUDA_VISIBLE_DEVICES=1 $P audio_speaker_embed.py --model {ecapa|wespeaker|eg2|gemma4} --model-dir ... \
   --trials trials --out emb --work work --device cuda:0           # wespeaker on cpu
$P audio_speaker_eval.py --trials trials --emb-dir emb --out results
$P audio_speaker_latency.py --model ... --device {cuda:0|cpu} --threads 4 --warmup 10 --out results/latency.jsonl
convert-venv/bin/python audio_speaker_export_litert.py --model-dir models/spkrec-ecapa-voxceleb \
   --trials trials --work work --out litert/ecapa_spkrec_voxceleb_2s.tflite
convert-venv/bin/python audio_speaker_make_fixture.py --trials trials \
   --tflite litert/ecapa_spkrec_voxceleb_2s.tflite --out litert/fixture
python3 tools/benchmarks/audio_speaker_test_metrics.py            # 13 unit tests, pure NumPy
```

**Pins, revisions and licences.**

| Item | Revision | Licence |
|---|---|---|
| google/embeddinggemma-2 | `914f7f89142e33e77833254d9c9b90c3cef7303b` | Apache-2.0 |
| google/gemma-4-E2B-it | `3e22461f65e89153144f8adb70e3b8c2cc9845a7` | Apache-2.0 (Gemma 4 licence link) |
| speechbrain/spkrec-ecapa-voxceleb | `0f99f2d0ebe89ac095bcc5903c4dd8f72b367286` | Apache-2.0 |
| Wespeaker/wespeaker-voxceleb-resnet34-LM | `f0c48c298fd835726c27956a5d617bad7115627e` | CC BY 4.0 |
| openslr/librispeech_asr (test-clean `7113aa4c…7df0`, test-other `38e0c86a…9249`) | `71cacbfb7e2354c4226d01e70d77d5fca3d04ba1` | CC BY 4.0 |
| LibriSpeech `SPEAKERS.TXT` (from the openslr SLR12 tarball head) | sha256 `3350f093…8f76` | CC BY 4.0 |
| bilguun/musan-noise (930 files) | `d179de7b0c6adf58bf295495b481386b8fdb97c1` | CC BY 4.0 |
| dys-asr/musan-sr16000 music shards (`e92302fd…a085`, `ca511b52…2231`) | `6b90f661bfe51e873d9c79bb3df7b9f442f0bdb1` | see Music provenance above |

**Artifacts.** Stored at `/mnt/seagate/ML/cyanbridge-audio-speaker-20261007/`; every file is
listed in `SHA256SUMS` (39 files, verified against Luciano).

| File | sha256 |
|---|---|
| `results/results.json` | `13f5fc7e22a8b9b898888c2ba15d5d487e3546b3ebbac080ae0e3c30facc3d35` |
| `results/summary.md` | `fff1b10370295e99b170e7c91f551329f14dac59f4d6689226c519dc2c3980bc` |
| `results/latency.jsonl` | `8cbe6c934e17670fd1e5c5e33adfaa02cb581f47ed9a9b047e2d980004edf9af` |
| `trials/items.jsonl` | `32e23da35ec45a8a2dd558e07cbae91ab430322be5346adb60c598c7183641cd` |
| `trials/build_info.json` | `05848d1b0e087e78e58b1b59c520e91048759a07611921ac446b6707629eb63d` |
| `litert/ecapa_spkrec_voxceleb_2s.tflite` | `f88f074f4446310ea6cf5d4089ddfb13bb8b7a8de76437086e47886ba5883259` |
| `litert/fixture/manifest.json` | `c65658c9d988d0099a2bc27f65adc98d82d21bc429cb27ff76f40f2b60c1d839` |

The trial PCM (`audio_pcm16.npy`, sha256 `118f2d50…0776`, 837 MB) and the Gemma
representation archives (about 420 MB each) remain on Luciano only.

## Addendum (2026-10-07): speaker head on the frozen shared Gemma audio encoder

**Question.** Can the audio encoder that Gemma 4 E2B and EmbeddingGemma 2 share be made good
at speaker verification with a small trained head, so the app keeps one model resident on
4 GB phones instead of adding ECAPA or WeSpeaker? The head must leave the tower unchanged,
because E2B's own audio understanding uses it.

### Answer

- **Accuracy: mostly recovered, but only from the early layers.** A 0.92 M-parameter head
  (learned layer weights over the subsampling conv and layers 0–3, attentive statistics
  pooling, 192-d output) reaches **0.8 % EER** on the matched LibriSpeech trials. ECAPA scores
  0.4 % and WeSpeaker 0.2 %. Out of domain (VCTK) the head scores **1.3 %**, against 0.5 % for
  ECAPA and 0.1 % for WeSpeaker.
- **Noise robustness is the weak point.** In 0 dB MUSAN noise on VCTK, the head accepts 47 %
  of the wearer's speech; ECAPA accepts 95 %.
- **A head on the encoder output only reaches 4.6 % EER** (8.4 % on VCTK). The encoder output
  is the only representation a phone can get from the stock bundle without copying weights.
- **No real memory saving.**
  - The early-layer activations the good head needs cannot be read from the shared runtime.
    LiteRT-LM does not expose them, and the Android Interpreter cannot read intermediate
    tensors.
  - Getting them means shipping a truncated copy of the encoder (conv plus layers 0–3). At the
    stock INT2/INT4 quantization that copy is about 31 MB, about the size of an int8 ECAPA
    (estimated 21–25 MB).
  - It also costs more compute per window than ECAPA.
- **Recommendation unchanged: ship a dedicated speaker model.** Use ECAPA now (move it to int8
  for RAM) and treat WeSpeaker as the upgrade.
- **Keep the head as a fallback.** It is 0.95 MB in int8. It becomes attractive only if a
  future LiteRT-LM exposes per-layer audio-encoder states, or if the app runs the E2B audio
  encoder itself.

### Literature (web research, 2026-10-07)

Each row's source, URL and date was collected by a web-research sub-agent. The numbers were
read from abstracts and tables and were not re-derived here.

| Work | Encoder | Frozen? | Head / method | Result vs dedicated | Source (date) |
|---|---|---|---|---|---|
| Gemma 3n / Gemma 4 audio docs | USM-based Conformer; 25 tok/s (Gemma 4), 6.25 tok/s (3n) | n/a | n/a | Documented for ASR and speech translation only; no speaker use. | https://ai.google.dev/gemma/docs/capabilities/audio (updated 2026-06-05); https://developers.googleblog.com/introducing-gemma-3n-developer-guide/ (2025-06-26) |
| EmbeddingGemma 2 card / guide | 300 M audio encoder, same architecture as Gemma 4 | n/a | n/a | Retrieval benchmarks (MSEB, MAEB) only; no speaker evaluation. | https://huggingface.co/google/embeddinggemma-2 ; https://developers.googleblog.com/embeddinggemma-2-the-developer-guide/ (2026-10-06) |
| rnagabh/gemma4-audio-encoder (community) | Gemma 4 audio tower, 304.8 M | yes | Raw cosine | Same-speaker 0.656 vs different-speaker 0.622; the README says it is "not optimized for speaker discrimination". | https://huggingface.co/rnagabh/gemma4-audio-encoder (2026-04-03) |
| USM; USM-SCD | USM Conformer | partly | Speaker-change detection | No speaker-embedding evaluation; turn detection only. | https://arxiv.org/abs/2303.01037 (2023-03); https://arxiv.org/abs/2309.08023 (2023-09) |
| SUPERB | wav2vec2, HuBERT, … | **yes** | Weighted layer sum + x-vector | VoxCeleb1 EER 5–6 % (FBANK 9.56 %) | https://arxiv.org/abs/2105.01051 (2021-05) |
| WavLM | WavLM Large | yes / fine-tuned | Weighted sum + x-vector / ECAPA | Frozen 3.77 % (SUPERB); fine-tuned 0.38–0.62 % on Vox1-O. Speaker tasks weight the lower layers. | https://arxiv.org/abs/2110.13900 (2021-10) |
| MHFA | WavLM Base+ | **yes** / fine-tuned | Multi-head factorized attentive pooling (0.4–2.2 M) | Frozen 2.16 %, fine-tuned 0.59–0.80 %, on Vox1-O; ECAPA 0.90 % | https://arxiv.org/abs/2210.01273 (2022-10) |
| PETL adapters; UniPET-SPK; Sang & Hansen | WavLM / HuBERT | backbone frozen, adapters inside | Adapters / prompts | Close to full fine-tuning (0.55 % on Vox1-O), but they change the tower's computation | https://arxiv.org/abs/2210.16032 (2022-10); https://arxiv.org/abs/2501.16542 (2025-01); https://arxiv.org/abs/2403.00293 (2024-03) |
| Whisper-SV; Whisper-PMFA | Whisper large-v2 | frozen / fine-tuned | Layer selection + aggregation | 2.22 % (frozen, VoxCeleb1-only training); 1.42 % fine-tuned | https://arxiv.org/abs/2407.10048 (2024-07); https://arxiv.org/abs/2408.15585 (2024-08) |
| MiDashengLM (X-ARES) | Whisper-large-v3 encoder (used by Qwen-style audio LLMs) | yes | MLP on final features | VoxCeleb1 speaker ID: 24.8 % accuracy (Dasheng MAE encoder: 73.3 %) | https://arxiv.org/abs/2508.03983 (2025-08) |
| SSL speaker-attribute probing | 11 SSL models | yes | Layer probes | Speaker information peaks in early and middle layers and declines toward the output | https://arxiv.org/abs/2501.05310 (2025-01, v2 2025-09) |

**No public speaker head or adapter exists for the Gemma 3n, Gemma 4 or EmbeddingGemma 2
audio encoder, nor for USM** (searched on Hugging Face, GitHub, arXiv and the web, 2026-10-07).
EmbeddingGemma 2 was released on 2026-10-06, so very recent work may not be indexed yet.

**Takeaways.**
- The literature agrees with the decay we measured: ASR- and semantics-trained encoders drop
  speaker identity toward the output.
- Frozen backbones with a learned layer weighting and a real pooling head land between FBANK
  baselines and fine-tuned systems.
- Closing the remaining gap usually needs adapters inside the encoder, which changes the shared
  computation.

### Experiments (Luciano, RTX A4000, measured)

**Training.**
- Data: LibriSpeech train-clean-100 and train-clean-360 (CC BY 4.0), 1,172 speakers. At most
  36 utterances per speaker, one 3 s crop each, 42,138 crops in total.
- The training speakers were checked to be disjoint from the 40 test-clean target speakers and
  the 33 test-other dev speakers (`audio_speaker_head_prepare_train.py`).
- The encoder was frozen in bf16 and features were recomputed online. The torch log-mel port
  matches `Gemma4AudioFeatureExtractor` to within 4.8e-7. Pooled layer states have cosine
  1.0000 against the stored benchmark representations.
- Loss: AAM-softmax (m = 0.2, s = 30). AdamW with cosine decay, batch 128, random crops of
  1.6–3.0 s.
- Augmentation:
  - 0–20 dB babble from 1–3 other training speakers (p = 0.4);
  - 5–30 dB white, pink or brown noise (p = 0.3);
  - ±6 dB gain.
  - **MUSAN was not used**, because it is a test negative class.
- Checkpoints were selected only on the dev split (test-other). Each configuration was run once
  with one seed.
- Runs lasted 5,000 steps (A2, A3, B1, C) or 3,000 steps (A1, A4, B2, B3, CTL).
- Test scoring used the unchanged `audio_speaker_eval.py` on the same items and metrics as the
  tables above.

**Heads.**
- "ASP": learned softmax layer weights (each layer LayerNorm-ed), then attentive statistics
  pooling, then linear 192, then BN.
- "TDNN": three 512-channel dilated conv layers before the ASP.
- "C, LoRA": a separate branch re-runs a frozen copy of layers 0–3 with rank-8 LoRA on every
  linear (1.15 M parameters). The shared tower's outputs are untouched.
- "int8": simulated per-output-channel symmetric int8 weights. EER was unchanged to ±0.04 points
  for every head.

**LibriSpeech test-clean**: 2 s segments, 5-phrase enrollment, dev-chosen scoring. Columns are
EER [CI95], TPR at 1 % / 5 % FPR, same-gender EER, EER against MUSAN noise (b), and false
accepts on noise (b) at the 5 % threshold. The (c) columns give wearer acceptance at the 5 %
threshold in 0 / 5 / 10 dB noise and in 0 / 5 / 10 dB babble.

| Representation | Params (int8) | EER (a) | TPR@1 / @5 | Same-g | EER (b) | FAR (b) | (c) noise | (c) babble |
|---|---|---|---|---|---|---|---|---|
| WeSpeaker ResNet34-LM (baseline) | ≈6.6 M | 0.2 [0.0–0.6] | 99.9 / 100 | 0.3 | 0.0 | 0.0 | 98/100/100 | 72/96/100 |
| ECAPA-TDNN (baseline) | ≈21 M | 0.4 [0.1–0.9] | 99.6 / 99.9 | 0.5 | 0.1 | 0.1 | 97/100/100 | 57/90/98 |
| Previous: best mean-pooled layer (0) / sub-conv | 0 | 9.4 / 8.8 | 69 / 84–86 | 12–13 | 0.3 | – | 13–16/17–21/29–33 | 0–3/3–18/21–50 |
| **A2: sub + layers 0–3, ASP** | 0.92 M (0.95 MB) | **0.8 [0.4–1.3]** | 99.4 / 100 | 1.0 | 0.5 | 2.9 | 90/95/99 | 85/99/99 |
| A3: sub + layers 0–3, TDNN | 4.66 M (4.7 MB) | 1.0 [0.4–2.0] | 99.0 / 99.6 | 1.4 | 0.9 | 3.4 | 91/96/99 | 86/98/99 |
| C: layers 0–3 LoRA branch + TDNN | 4.66 M + 1.15 M LoRA | 1.2 [0.6–2.3] | 98.8 / 99.4 | 1.4 | 0.9 | 3.1 | 96/99/99 | 93/99/99 |
| A1: sub-conv only, ASP | 0.92 M | 1.7 [1.1–2.7] | 97.5 / 99.2 | 2.0 | 1.1 | 2.1 | 57/71/86 | 63/91/96 |
| A4: sub-conv only, TDNN | 4.66 M | 2.1 [1.6–3.1] | 96.2 / 98.8 | 2.8 | 1.5 | 2.7 | 57/70/84 | 67/91/96 |
| B1: sub + all 12 layers, TDNN | 4.66 M | 1.4 [0.5–2.8] | 98.5 / 99.1 | 1.6 | 1.0 | 2.0 | 91/96/99 | 80/97/99 |
| B3: layers 8–11 only, ASP | 0.92 M | 2.1 [1.4–2.9] | 95.8 / 99.1 | 2.8 | 0.9 | 1.1 | 83/89/95 | 62/91/97 |
| B2: encoder output (1536-d) only, TDNN | 5.97 M (6.0 MB) | 4.6 [3.2–6.0] | 85.7 / 95.7 | 6.4 | 0.5 | 0.0 | 68/78/86 | 23/73/91 |
| B2: encoder output only, ASP | 1.38 M (1.4 MB) | 5.8 [4.5–7.1] | 77.7 / 93.6 | 7.8 | 2.1 | 0.4 | 72/80/87 | 35/70/83 |
| CTL: same training, log-mel input (no Gemma), TDNN | 2.37 M | 1.5 [0.8–2.4] | 98.1 / 99.2 | 1.9 | 1.5 | 3.2 | 67/81/92 | 79/95/98 |
| **Stock LiteRT E2B section** (INT2/INT4), A2 head on its layer 0–3 states | 0.92 M | **0.8 [0.3–1.4]** | 99.2 / 100 | 1.0 | 0.4 | 2.5 | 90/95/99 | 86/99/99 |
| Stock LiteRT E2B section, B2-TDNN head on its output (XNNPACK) | 5.97 M | 4.6 [3.4–5.9] | 83.0 / 95.9 | 6.6 | 0.6 | 0.0 | 65/78/86 | 15/68/89 |
| Stock LiteRT E2B section, mean-pooled output (no head) | 0 | 34.9 [32.2–37.2] | 6.9 / 20.9 | 35.8 | 0.1 | 0.0 | 4/5/9 | 0/0/1 |

At 1 s windows, A2 scores 0.9 % EER, against 1.6 % for ECAPA and 0.9 % for WeSpeaker. That
1 s result is in domain.

**Out-of-domain check: VCTK 0.92** (CC BY 4.0; `audio_speaker_build_trials_vctk.py`).
- 40 test speakers (22 F), 12 dev speakers, mic1, the same protocol.
- Non-speech negatives and noise mixtures use the same MUSAN files; babble comes from VCTK dev
  speakers. Music is not used.
- No model compared here was trained on VCTK.

| Representation | EER (a) 2 s [CI95] | TPR@1 / @5 | Same-g | (c) noise 0/5/10 | (c) babble 0/5/10 | EER (a) 1 s |
|---|---|---|---|---|---|---|
| WeSpeaker | 0.1 [0.0–0.4] | 99.9 / 99.9 | 0.2 | 95/99/100 | 84/100/100 | 2.7 |
| ECAPA | 0.5 [0.3–0.8] | 99.8 / 99.9 | 0.7 | 95/99/100 | 68/95/99 | 3.7 |
| **A2 (sub + 0–3, ASP)** | **1.3 [0.7–1.8]** | 98.3 / 99.8 | 1.8 | **47/60/78** | 53/85/95 | 4.4 |
| B1 (all layers, TDNN) | 1.6 [1.2–2.2] | 97.0 / 99.8 | 2.4 | 56/65/78 | 54/85/94 | – |
| C (LoRA branch) | 1.7 [1.3–2.2] | 96.8 / 99.6 | 2.5 | 58/72/84 | 72/90/95 | – |
| A3 / A1 / A4 | 2.4 / 2.8 / 4.6 | – | – | – | – | – |
| CTL (log-mel, no Gemma) | 3.3 [2.3–4.9] | 91.6 / 97.8 | 4.4 | 30/45/65 | 60/82/89 | – |
| B3 (layers 8–11) | 5.5 | – | – | – | – | – |
| B2 (output only) TDNN / ASP | 8.4 / 11.6 | 53.7 / 83.6 (TDNN) | 12.3 | 39/52/63 | 6/48/70 | – |
| Mean-pooled sub-conv / best layer / encoder output / soft tokens / log-mel | 23.3 / 27.9 / 46.7 / 46.4 / 40.1 | – | – | – | – | – |

**What the experiments show.**
- **The speaker information is in the early layers.** The learned layer weights put almost all
  of their mass on the conv and layers 0–3; in B1, layers 5–11 together get about 15 %. Heads
  on layers 0–3 beat heads on the conv alone by about 2× in EER. Heads on layers 8–11 or on the
  output are 3–6× worse.
- **A trained head recovers 10× over mean pooling**: layer 0 went from 9.4 % to 0.8 %. But a
  log-mel head with no Gemma at all, trained the same way, already reaches 1.5 % (3.3 % on
  VCTK). The Gemma layers contribute roughly a 2× improvement on top of the head and data, not
  the bulk of it.
- **LoRA did not help.** The rank-8 branch scored 1.2 % against 0.8 %, within the CIs. It also
  defeats sharing, because the branch must re-run layers 0–3 itself.
- **The stock on-device quantization is harmless for this use.** The A2 head on activations from
  the INT2/INT4 LiteRT section (host reference kernels) gives the same 0.8 % as on bf16. Layer
  0–1 states match bf16 at cosine 1.000, the encoder output at 0.993.
- **Two caveats limit the head's numbers:**
  - **Domain advantage.** The head trained on LibriSpeech speakers with LibriSpeech babble. That
    explains why on LibriSpeech its babble acceptance (85 % at 0 dB) beats ECAPA's (57 %). On
    VCTK the order flips and the gap to ECAPA widens from 2× to 2.6× in EER.
  - **Noise robustness.** The head was never trained on real noise (MUSAN was held out), so it
    accepts only 47 % of the wearer at 0 dB on VCTK, against 95 % for ECAPA. Training with a
    non-test noise corpus would likely help; that was not tested.

### On-device: can one resident model serve both? (inspected locally)

| Item | Finding | Status |
|---|---|---|
| E2B bundle `gemma-4-E2B-it.litertlm` section 4 `tf_lite_audio_encoder_hw` | Self-contained TFLite flatbuffer (weights inside): 94,052,280 B, **offset 1,393,082,368 (16 KiB aligned)**, sha256 `eca6f5f0…ccfc`. Constant data: 66.1 MB INT2 (type 19), 12.6 MB INT4, 12.0 MB FP32. Signature `serving_default`: `src_inputs` f32[1,1,816,128] (log-mel, 10 ms hop = 8.16 s) + `mask` bool[1,1,816], producing f32[1,204,1536] + mask. Per-layer weights: subsampling 4.35 MB, each Conformer layer 6.66 MB. | Measured |
| Run that section with a plain LiteRT Interpreter | Loads and runs on `ai-edge-litert` 2.1.6 / 2.2.0 (Python, XNNPACK). Output vs bf16 torch encoder: mean cosine 0.993. On Android, `FileChannel.map(READ_ONLY, 1393082368, 94052280)` → `Interpreter(MappedByteBuffer)` should work against the same bundle file without copying it. Not run on a device. The section has **no log-mel front end**: the app must port `Gemma4AudioFeatureExtractor` (20 ms window, 512-point FFT, 128 HTK mel bins, log(x + 1e-3)). | Host measured; Android inferred |
| Read early-layer activations from it | Not possible through the Android Interpreter API, which reads only graph outputs. On the host it needs `experimental_preserve_all_tensors` plus reference kernels (debug only; 1.7 s per window). The layer outputs are tensors 317 (conv) and 503/687/871/1055 (layers 0–3). | Measured (host) |
| Truncated early-layer encoder | Graph surgery on that section: keep ops up to layer 3 and expose tensor 1055 as an output. That yields about **31 MB** at the stock quantization (4.35 + 4 × 6.66 MB). A re-export from HF weights would be 101.8 M params ≈ 102 MB int8 / 51 MB int4. Either way it is a second copy, not shared. | Estimated (not built) |
| Tap the running LiteRT-LM engine | The `Engine`/`Conversation` API returns no audio-encoder states. | Inferred (earlier AAR inspection) |
| EmbeddingGemma 2 bundle | The audio encoder is a 4.2 MB streaming graph (109 subgraphs, KV/conv-state inputs, `STABLEHLO_COMPOSITE` ops). Its weights sit in a separate `TFLiteWeights` section (type 7, 89.5 MB), so a plain Interpreter cannot load it. | Measured (structure); loadability inferred |
| Compute | The stock section always processes an 8.16 s window: 496–516 ms on host (Xeon E5-2680 v4, 4 threads). ECAPA `.tflite` takes 84–93 ms per 2 s window on the same host. | Measured (host) |

**Verdict.**
- The only zero-copy option is to load the E2B audio section from the bundle file and put a
  head on its output. That gives 4.6 % (LibriSpeech) / 8.4 % (VCTK) EER. On RAM it is the
  *worst* option: XNNPACK unpacks the INT2/INT4 weights into anonymous memory (+171 MB), and
  every 8 s window costs about 0.5 s of host CPU.
- The accurate early-layer head needs a second ~31 MB encoder slice. That is the same size
  class as an int8 ECAPA, so nothing is saved.

### RAM on a 4 GB phone

| Configuration | Extra files | Extra resident memory | Basis |
|---|---|---|---|
| Gemma 4 E2B alone (stock bundle) | 2.59 GB | PSS 0.65–0.77 GB after load; ≈2.1 GB while scoring | **Measured** (Galaxy Z Fold6, main session) |
| + ECAPA fp32 `.tflite` | 84 MB | +205 MB RSS (anon +115, file +90) | **Host-measured** (Xeon, ai-edge-litert 2.1.6, XNNPACK); phone similar but not measured |
| + ECAPA int8 (dynamic range; not exported yet) | ≈21–25 MB | ≈+50–80 MB | Estimated |
| + WeSpeaker ResNet34-LM (needs LiteRT conversion) | 26.5 MB (ONNX) | ≈+50–100 MB | Estimated |
| + E2B audio section mapped from the bundle + B2 output head | 0 + 6.0 MB | +272 MB RSS (anon +171, file +101) | **Host-measured** for the section; the head is <10 MB |
| + truncated encoder (conv + layers 0–3, stock quantization) + A2 head | ≈31 MB + 0.95 MB | ≈+60–100 MB | Estimated from per-layer bytes |
| + truncated encoder re-exported int8 + A2 head | ≈102 MB + 0.95 MB | ≈+150–250 MB | Estimated |

### Reproduction (2026-10-07 addendum)

Work directory on Luciano: `/tmp/opencode/cyanbridge-audio-speaker-20261007`
(`audio-venv`, torch 2.7.0+cu128, CUDA verified).
- The LiteRT runs used `convert-venv` (ai-edge-litert 2.2.0).
- The bundle probes used `gemma4-sm8650-port/environment/NPU_env` (ai-edge-litert 2.1.6,
  litert_lm_builder).
- The new footprint on Luciano is about 5.7 GB under `head/`. Most of it is the 4.0 GB training
  pool and the 0.75 GB VCTK PCM, both kept for reruns.

```bash
P=audio-venv/bin/python; M=/tmp/opencode/cyanbridge-specialists-20261003/sources/gemma-4-E2B-it
HF_HOME=$PWD/hf_home $P scripts/audio_speaker_head_prepare_train.py --out head/pool --trials trials
CUDA_VISIBLE_DEVICES=0 $P scripts/audio_speaker_head_train.py --name A2_l0-3_asp --model-dir $M \
   --pool head/pool --trials trials --out head/runs/A2_l0-3_asp --reps sub 0 1 2 3 --head asp --steps 5000
#  other runs: --reps sub | sub 0..11 | 8 9 10 11 | out | mel ; --head tdnn ; --lora-layers 4 --lora-r 8 ; --steps 3000
HF_HOME=$PWD/hf_home $P scripts/audio_speaker_build_trials_vctk.py --musan-noise-dir data/musan-noise/noise --out head/trials_vctk
$P scripts/audio_speaker_head_train.py ... --trials head/trials_vctk --embed-only head/emb_vctk/<name>.npz
convert-venv/bin/python scripts/audio_speaker_head_litert_encoder.py --tflite head/e2b_audio_encoder_hw.tflite \
   --model-dir $M --trials trials --out head/litert_stock_ref --no-delegate --capture sub 0 1 2 3 \
   --heads A2_l0-3_asp=head/runs/A2_l0-3_asp/head.pt --workers 16
$P scripts/audio_speaker_eval.py --trials trials --emb-dir <dir with ecapa, wespeaker, head npz> --out head/eval_final
NPU_env/bin/python -I tools/benchmarks/audio_speaker_head_bundle_probe.py {sections|layers|rss} ...
```

**Pins and licences.**

| Item | Revision | Licence |
|---|---|---|
| openslr/librispeech_asr, clean/train.100 + clean/train.360 (training) | `71cacbfb7e2354c4226d01e70d77d5fca3d04ba1` (shard hashes in `pool/pool_info.json`) | CC BY 4.0 |
| VCTK 0.92 via `sanchit-gandhi/vctk` parquet mirror (OOD test) | `73ef4ee7d49a6fed4ea1efd65f82b4c95faeb9de` (shard hashes in `trials_vctk/build_info.json`) | CC BY 4.0 (VCTK); the mirror's card has no licence field |
| MUSAN noise (test negatives only, never trained on) | as above | CC BY 4.0 |
| E2B LiteRT-LM bundle section 4 (`tf_lite_audio_encoder_hw`) | sha256 `eca6f5f0eae4997e9f2a74ee120a5eb7f9ee4f687196777e631b650d91a2ccfc` | Gemma 4 (Apache-2.0) |

**Artifacts.** Copied to `/mnt/seagate/ML/cyanbridge-audio-speaker-20261007/head/` and
verified byte-for-byte against Luciano (SHA-256, 88 files). `head/SHA256SUMS` lists every file
in that directory.

| File | sha256 |
|---|---|
| `head/eval_final/results.json` | `e940393ce739a21f5465f699cd54cdd7f467e1d778ca82a146ef90a5b83eb195` |
| `head/eval_final/summary.md` | `cf11970b6ebfffaa7ddbcdc17d578914816ba0f37ad5a689cb64034ea0aa23df` |
| `head/eval_vctk/results.json` | `4c02ae5999ef3207665e9731849e53244bee4f72f6e8f95e9dfb9daf23324052` |
| `head/eval_vctk/summary.md` | `05b910ce6adba13b1005dee1abb8dbd5886a2e99a74c806dac75dc11ef33efad` |
| `head/runs/A2_l0-3_asp/head.pt` | `0648f501dcd64be45f9df17e08ee3fcc06ae8a6ac9e8aec4313ed67c8d484ace` |
| `head/runs/B2_out_tdnn/head.pt` | `0d42fd4f48459483cf4c5b96792a29b0734ae99fa54880f42ff9deed3440c5f3` |
| `head/litert_stock_ref.npz` | `1dec5c3760aa757a82562f2d09d1f0ab8072f2413b0b4436626dd9d7d8744a8b` |
| `head/litert_stock_xnn.npz` | `58815c6f3988357010bc29c899da0444b9accf6ab66517c02112489e9140dc89` |
| `head/trials_vctk/items.jsonl` | `3f15a70aea53c6d1ccc5d8c72e4077b0aa79df424c657585bd813e2c14fa153b` |
| `head/bundle_probe/sections.json` | `1393b27adfc11b819bb3cdb6fada6abf7b99d2e247a0a2176814114cd5eb97df` |
| `head/SHA256SUMS` | `f3ad8c2323d7ea8eee7a5d2484be492b5542d617878c0b59ed34b5677f1f08db` |

The training pool PCM (`pool_pcm16.npy`, 4.0 GB, sha256 `f01f0369…a189`), the VCTK trial PCM
(sha256 `398ac3ed…d58`) and the VCTK embeddings remain on Luciano only.

**Measured versus inferred (addendum).**
- **Measured.** Every EER, TPR and FAR value above; head parameter counts and int8 sizes; the
  structure, offsets, per-layer bytes and host loadability of the bundle sections; host RSS
  and latency.
- **Inferred.**
  - Android loading of the section through `MappedByteBuffer`.
  - Phone RAM for every option except stock E2B.
  - Sizes of a truncated encoder and of int8 ECAPA or WeSpeaker.
  - That LiteRT-LM exposes no encoder states.
- **Limits.**
  - One seed per configuration.
  - A small dev set (33 speakers) was used to pick checkpoints.
  - Head differences of 0.2–0.5 points are inside the CIs.

## Addendum (2026-10-07, main session): WeSpeaker ResNet34-LM on LiteRT, run on the AVD

WeSpeaker is the WeNet community's open-source speaker-recognition toolkit
(`wenet-e2e/wespeaker`). The model is `Wespeaker/wespeaker-voxceleb-resnet34-LM`
(`f0c48c29`, CC BY 4.0): a ResNet34 trained on VoxCeleb with large-margin
fine-tuning, about 6.6M parameters, 256-d output. It was converted locally with
Google's LiteRT Torch (`litert_torch.convert`, 0.9.4, torch 2.13 CPU, isolated venv)
from the PyTorch checkpoint and WeSpeaker source `9fecd6cb`, using
[`tools/benchmarks/audio_speaker_export_wespeaker_litert.py`](../../../tools/benchmarks/audio_speaker_export_wespeaker_litert.py).

- **Same contract as the ECAPA export.** Input `float32[1, 32000]` (2 s, 16 kHz,
  [-1, 1]); output `float32[1, 256]`. WeSpeaker's inference front end is inside the
  graph: int16 scaling, Kaldi fbank (80 mel, Hamming, pre-emphasis 0.97, DC removal,
  512-point FFT) and mean normalisation. The per-frame linear chain is folded into
  one fixed conv1d.
- **Verified on host.** fbank max abs diff vs `torchaudio.compliance.kaldi.fbank`
  is 6e-4. Embeddings vs the official ONNX export: cosine 0.999994. LiteRT fp32 vs
  PyTorch: 0.99999999999.
- **int8.** Quantize only the ResNet weights (weight-only int8, float compute).
  Dynamic int8 also quantized the front end's activations and constants and
  destroyed the features (cosine 0.015). Weight-only int8 over the whole graph
  gave 0.982. ResNet-only weight-only int8 gives 0.994 and keeps the fixture
  scores.

Pixel 9a AVD (x86_64, CPU, 4 threads), existing `AudioSpeakerLiteRtProbeTest` and
the same LibriSpeech/MUSAN fixture clips (host embeddings from each LiteRT variant):

| Model | File | Device vs host cos | Median per 2 s | Worst target / best other (fixture) |
|---|---:|---:|---:|---|
| WeSpeaker fp32 | 27.5 MB | 0.99999999 | 241 ms | 0.514 / 0.288 |
| **WeSpeaker int8 (ResNet weights)** | **11.6 MB** | 0.99999999 | 227 ms | 0.507 / 0.292 |
| ECAPA fp32 | 84.1 MB | 0.99999999 | 238 ms | 0.492 / 0.249 |

Emulator timings measured while the host was under heavy disk I/O pressure; not
phone timings. The two-speaker fixture is a sanity check only. Accuracy comes from
the matched trials above: WeSpeaker 0.2% EER versus ECAPA 0.4% at 2 s with 5
phrases. **Updated recommendation:** WeSpeaker int8 (11.6 MB) is now the stronger
default for a 4-GB phone. It is ~7× smaller than ECAPA fp32 and more accurate,
with equal latency. Keep ECAPA as the fallback. Artifacts (models, fixtures,
`export_report.json`): `/mnt/seagate/ML/wespeaker-litert-20261007/litert/`
(fp32 `75dddb04…`, int8 `56afd341…`).

### NPU (Qualcomm SM8650 / Hexagon V75) compilation, 2026-10-08

Compiled with the NVMe recipe environment documented in
`~/nvme_tmp/gemma4-sm8650-work/README.md` (`recipe_env`: ai-edge-litert 2.2.0,
ai-edge-litert-sdk-qualcomm 2.2.0 = QAIRT 2.47.0.260601114230) and the port's
`aot_compile.py`. That environment and the `.tflite` files used here are host-side
only; nothing has run on a phone yet.

| Graph | NPU offload | Notes |
|---|---|---|
| WeSpeaker single graph, fp32 | 88/88 ops, 1 partition | **Unusable:** HTP computes float graphs in fp16 and the in-graph Kaldi front end overflows. A GPU fp16 simulation gives 1200/1200 non-finite embeddings and 50% EER. |
| WeSpeaker weight-only int8 | compile fails | QNN rejects per-channel `DEQUANTIZE` |
| WeSpeaker fully static W8A16 | 87/92 ops, 6 partitions | int16 `DEPTHWISE_CONV_2D` (front end + first conv) and 3 `PAD` ops stay on CPU |
| ECAPA single graph, fp32 | 467/467 ops, 1 partition | Same fp16 front-end overflow risk (power spectra up to ~1.6e5 > 65504); not simulated |
| **WeSpeaker ResNet head** (split), fp32 → fp16 on HTP | **71/71 ops, 1 partition, 13.5 MB context** | fp16 simulation: EER 0.25%, same as fp32 (cos ≥ 0.99999) |

**Recommended NPU layout:** a tiny fp32 front end on CPU,
`wespeaker_frontend_2s.tflite` (PCM `[1,32000]` → fbank `[1,198,80]`), then the
AOT head `wespeaker_head_2s_Qualcomm_SM8650_apply_plugin.tflite` (`[1,198,80]` →
`[1,256]`). Composed with the single graph on host: cosine 0.9999998
([`audio_speaker_export_wespeaker_split.py`](../../../tools/benchmarks/audio_speaker_export_wespeaker_split.py),
[`audio_speaker_fp16_sim.py`](../../../tools/benchmarks/audio_speaker_fp16_sim.py)).
Artifacts and SHA256SUMS: `/mnt/seagate/ML/wespeaker-litert-20261007/npu-aot-litert220/`.

**Device runtime still needed:**
- LiteRT 2.2.0 `CompiledModel` with the NPU accelerator. The app's current
  `Interpreter` cannot run dispatch models.
- `libLiteRtDispatch_Qualcomm.so`. Not in the pip SDK, so build it from LiteRT
  2.2.0 locally or get it from Google's Android NPU distribution.
- The QAIRT 2.47 Android libraries (`libQnnHtp.so`, `libQnnSystem.so`,
  `libQnnHtpPrepare.so`, `libQnnHtpV75Stub.so`, and the hexagon-v75 skel) from
  `recipe_env`.

Google's EmbeddingGemma 2 SM8650 bundles were compiled with QAIRT 2.49 (text) and
2.49/2.50 (740M), so those need matching runtime libraries. Recompiling the
WeSpeaker head with the same QAIRT would let one runtime serve both.

**Update (2026-10-08): whole model on the NPU.** `KaldiFbankFp16Safe` keeps every
front-end intermediate within fp16 range: DFT gain 0.5 on [-1, 1] PCM, mel floor
at the smallest normal fp16 value, saturation below fp16 max, and the scale
restored after the log. The single graph then compiles fully: **91/91 ops, one
partition**. GPU fp16 simulation gives EER 0.25%, the same as fp32, with no
non-finite outputs
([`audio_speaker_export_wespeaker_npu.py`](../../../tools/benchmarks/audio_speaker_export_wespeaker_npu.py)).
Its embeddings differ from the CPU model's (mean cosine 0.97), so enrol and verify
with the same variant; its device fixture is `fp16safe/fixture-fp16safe/`. This
replaces the CPU+NPU split as the recommended NPU layout. App requirements are in
[`NPU_ON_DEVICE.md`](NPU_ON_DEVICE.md).

**Correction (2026-10-08, later): the fp16-safe graph is wrong on HTP; use `KaldiFbankNpu`.**
Running the AOT contexts in the QAIRT SDK's x86 HTP emulator
([`htp_emulator_parity.py`](../tools/litert-npu/htp_emulator_parity.py)) showed the
fp16-safe whole graph producing unrelated embeddings (cosine ≈ 0.06 vs CPU) with
both QAIRT 2.47 and 2.50, although it compiles fully and passes fp16 simulation. A
stage-by-stage bisection
([`audio_speaker_frontend_stages.py`](../../../tools/benchmarks/audio_speaker_frontend_stages.py))
traced it to the first op: HTP computes the 400-tap, stride-160 DFT `conv1d`
incorrectly. `KaldiFbankNpu` computes identical features (max difference 6e-6) from
160-sample blocks as a stride-1, kernel-3 convolution. Results with QAIRT 2.50:
- The whole model compiles fully: 94/94 ops, one partition, context SoC 57 / V75.
- In emulation it matches CPU at cosine ≥ 0.99995.
- On the 1,200 test-clean crops, emulated-HTP EER is 0.25 %, the same as CPU ([`audio_speaker_htp_emulator_eer.py`](../../../tools/benchmarks/audio_speaker_htp_emulator_eer.py)).

The emulator does run the split ResNet head correctly (cosine 0.999997), so the
emulator itself is sound. The NPU export is now `wespeaker_resnet34_lm_2s_npu.tflite`
with `fixture-npu/`. The CPU `CompiledModel` path passes on the Pixel 9a AVD (parity
0.99999999, 227 ms per 2 s) next to the unchanged `Interpreter` test. Artifacts:
`/mnt/seagate/ML/wespeaker-litert-20261007/npu-qairt250/`. The `fp16safe` AOT model
is superseded. Runtime, packaging and the single-QAIRT (2.50) decision are in
[`NPU_ON_DEVICE.md`](NPU_ON_DEVICE.md).

**On the phone (2026-10-08, Galaxy Z Fold6, opt-in NPU build).**
`LiteRtNpuSpeakerTest` passes on CPU and NPU:

| WeSpeaker NPU variant, 2 s window | Median latency | Parity vs host | Target min / other max |
|---|---:|---:|---|
| NPU (AOT SM8650, QAIRT 2.50) | **12.3 ms** (load 295 ms) | cos ≥ 0.99993 | 0.518 / 0.283 |
| CPU (CompiledModel) | 226 ms | cos ≥ 0.9999999 | 0.517 / 0.284 |

That is about 18× faster at the same accuracy, so speaker filtering can run on every
2 s window without loading the CPU. Memory (`dumpsys meminfo`) is not measured yet.

