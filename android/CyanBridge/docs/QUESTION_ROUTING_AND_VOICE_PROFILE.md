# Decision-model question routing and voice profile (foundations)

Updated: 2026-10-24 on the retired `heycyan-eyevue-tunebuds-protocol-fixes` branch; merged into
`cyan-refactor` on 2026-10-26. Since that merge, the routing mode, web engine and vision-assistance
choice are typed preferences (`AssistantPrefs.QuestionRouting`, `AppPrefs.Accessibility`), and
enrollment records through `AudioSessionCoordinator` like every other microphone capture.

## Goals

1. Let a decision model choose how a glasses voice question is handled: answer, look with the
   camera, act on the phone (local agent), start Gemini Live, or ignore it. This is the default
   whenever the answering model hears audio; on-device STT is only a fallback.
2. Enroll the wearer's voice during onboarding (three short phrases in the app language),
   so a speaker-verification model can later ignore other people. **Answers are not gated
   yet**; this change only builds and observes.

## Question routing

`Settings → AI & automation → Question routing` (`QuestionRoutingPrefs`, shared enum
`QuestionRoutingMode`, default **SMART** since 2026-10-24):

| Mode | Voice question (wake word / AI button, voice route) |
|---|---|
| **SMART** (default) | If the answering model hears audio (local Gemma 4 LiteRT/GGUF, or an audio-capable Pro model): the spoken decision below. Otherwise Classic |
| CLASSIC | On-device STT first, then the transcript router (`AssistantRequestRouter`); untranscribed audio goes straight to the model |
| SHADOW (debug) | Classic, plus the old `QUESTION_DECISION_SHADOW` log for transcripts |

A photo-mapped wake and the photo button take the photo and record the question in parallel. In
SMART mode on the local model (LiteRT, llama.cpp) that photo question goes through the same decision
(next section); Classic mode, a transcribed (STT) question, the Pro relay and Gemini Live keep the
single answer: photo + the recording in one request (system instruction, bare audio), no decision.

### Photo questions with the decision (SMART, 2026-11-23)

`ImageQuestionFlow.runDecisionPhotoQuestion` → `SpokenQuestionFlow.runWithPhoto(recording, photo)`:

```text
system = the spoken questions' turn (glasses instruction + route tags), identical, so the LiteRT-LM
         prefill cache (CPU clone / GPU-NPU spare) serves it for both kinds of question
photo in first (or both together): decide with the photo attached
  user = time, photo, PHOTO_QUESTION_NOTE ("…so you can see what I'm looking at. Don't reply [camera]…"), recording
  photo first: [time, photo, note + "My question comes next."] "OK." (prefilled while they speak) | [recording]
  [camera] = an answer about this photo (Gemma 4 E2B still starts most visual replies with "[camera]\n[answer] …");
  a bare [camera] gets one more turn ("answer from the photo you have")
recording in first: decide on it alone, exactly like a spoken question, while the photo transfers
  [camera] waits for the button's photo (no second photo) and answers with it in the conversation;
  every other route acts before the photo is in
reply  = [answer] (streams to speech as written)  [search] <q> (the photo stays in the conversation)
         [phone] <task> (only if LocalAgentController.unavailableReason == null, else answered)
         [live] (Gemini Live opens with the photo)  [ignore]
follow-ups (5 s window) = spoken decision turns in the same conversation; [camera] takes a new photo
```

Deciding on the recording with the photo's turn after it (the 2.5.1 recording-first prefill) made Gemma
answer the photo instead of the question (host probe: plain/time/ignore 0/9 → mostly [camera] or a
description), so that order is decided on the recording alone.

The note goes with the photo (`GenerationConfig.photoNote`, message role `photo_note`), not in the system
turn. Scored on the host with `eval_spoken_route_tags.py --mode image` and on the phone/AVD with
`PhotoQuestionPrefillTest` (`decide_*` modes).

### Since 2026-11-23 (branch `251-routing-followups`)

- **Two system turns:** with `[phone]` when the Local Agent can run phone tasks
  (`LocalAgentController.unavailableReason == null`), else `SPOKEN_DECISION_ROUTES_NO_PHONE` (phone requests
  are answered "I can't control your phone"). The one in use is the cached one; `SpokenSystemTurnPrimer`
  re-primes it in the background when phone control is toggled, Tasker is installed/removed, or the app
  resumes with a different setup. `[phone]` without an agent is still answered as a safety net.
- **Pro relay** (website PR #27): photo and voice questions use the same decision. The route list is in
  `system_context` (up to 8000 chars), the time and photo note in `turn_note`, and the reply ends with a
  `[heard] <question>` line that the app removes. Follow-ups send the conversation as `history` and resend the
  conversation's photo (`RelayConversation`, `PHOTO_RESENT_NOTE`). There is no streaming on the relay.
- **Streaming:** local `[answer]` replies (voice and photo) speak phrase by phrase while they are written.
- **`[live]` from a photo question** starts Live at once. The photo opens the session if it comes within 7 s
  (`GeminiLiveOpeningImage`); otherwise Live goes on with the plain prompt.

### Spoken decision (SMART)

```text
record once → model hears the recording; system = glasses instruction + route tags + clock
reply starts with a tag (shared/assistant/SpokenDecision.kt), the app acts as soon as it is written:
  [answer] <answer>   → spoken
  [camera]            → "Okay. I'll check what you see." + photo; the same recording is answered with it
  [phone] <task>      → local agent (Tasker); it confirms calls/messages/email/alarms by voice first
  [live]              → Gemini Live
  [search] <query>    → answered without web access for now (lookups not built yet)
  [ignore]            → silence (talking to someone else, "never mind")
no or unknown tag → the whole reply is the answer; stray tags are never spoken
```

- Engine-agnostic: the tags are prompt + parser, so the same flow runs on LiteRT, llama.cpp and the
  Pro relay (`/audio-query` with `spoken_question`). Generation stops as soon as a non-answer route
  (and its one-line payload) is written.
- Prompt text: `ImageQuestionDefaults.SPOKEN_DECISION_ROUTES`, scored on the host with
  `tools/benchmarks/eval_spoken_route_tags.py` (greedy tag + tag probabilities) and on the phone with
  `QuestionPromptProbeTest` (variant `route2` is the earlier zero-shot layout).
- Phone tasks were previously reachable only through the transcript router (EXECUTE_UI_TASK); they
  now come from the decision model. Voice-driven app setting changes are not implemented.
- Superseded: the decide-once flow (`⊲A⊳`/`⊲B⊳` after a `Q:` line, `DecideOnce.kt`, LiteRT only) is
  no longer called by the app; its engine code stays for `QuestionPathBenchmarkTest`.

The transcript router (`AssistantRequestRouter`: on-device STT text → regex tables → single-token →
JSON classifier) is **deprecated for glasses questions** and remains only for models that cannot hear
audio (and Classic mode). On the Fold6 it misrouted most visual questions (3/10) and on-device STT
packs existed for only en and pt-BR.

### Evidence (decide-once era, 2026-10-22/23; glasses_questions_v1: 252 questions, 9 languages, stock TTS voices)

Host, zero-shot Gemma 4 E2B QAT (bf16, transformers), decision only
(`tools/benchmarks/eval_glasses_question_routing.py`, results in
`/mnt/seagate/ML/cyanbridge-glasses-questions-20261022/eval/`):

| Decision input | visual, no photo | plain, no photo | visual, photo | plain, photo |
|---|---|---|---|---|
| Transcript text (generic 2-option menu, both orders) | 92/108 | 107/108 | 108/108 | 98/108 |
| Raw audio, same menu | 13/108 | 108/108 | 108/108 | 21/108 |
| Model writes the question from audio, then decides on it | 84/108 | 106/108 | 108/108 | 87/108 |
| v3: audio, `Q:` turn then separate decision turn, greedy | 108/108 | 38/108 | – | – |
| v3 with label scores and threshold 0.98 (needs the JNI scoring patch) | 96/108 | 106/108 | – | – |

From raw audio alone the decision follows the photo's presence, not the question. Deciding after
the words are written down (by Android STT or by the model itself) is what works.

Pixel 9a AVD, stock `gemma-4-E2B-it.litertlm` on CPU, app decide-once (v2 prompt), test families
en + pt-BR: 4/4 visual → camera, 6/6 plain → answer, correct answers in both languages; ambiguous
"tell me more about this" → answer (made up a reply); 1/12 answer turns lost the question. AVD CPU
timings: decision ≈ 9–10 s, total ≈ 17 s answer / 30–37 s camera (Fold6 numbers pending).
Full dev+test on-device run: see worklog.

### Next steps for the decision model

- Raise zero-shot accuracy per class to ≥ 90 % on GPU and NPU (host + `QuestionPromptProbeTest`),
  weakest so far: camera (3/8) and ignore (0/8) on the Fold6 GPU, live untested on the phone.
- If prompting stalls: a small LoRA on the multilingual audio fixtures (visual/plain/web/time from
  glasses_questions_v1, `questions_action_ignore_draft.jsonl`, `questions_live_draft.jsonl`), merged
  into the answer model; or tag-probability thresholds via the LiteRT scoring patch.
- Ignore: combine with the voiceprint (another speaker → ignore) instead of relying on the model.
- `[search]`: the "Live info lookups" route (bundled holidays, opt-in relay search).
- Voice-driven app settings as typed operations (the `bounded-specialist-v3` contract).

## Voice profile

### Enrollment UI

`VoiceEnrollmentScreen` is in `:shared` commonMain, beside `FeatureOnboardingScreen`, so iOS
can reuse it. It uses stable Compose and Material 3 only; the app is not on the Material 3
Expressive alphas. The expressive feel comes from springs:

- the voice shape's corners, scale and rotation spring between idle, listening and done;
- a halo breathes and follows the microphone level;
- the phrase pills stretch;
- the record button squishes its corners while pressed.

Phrases and strings come from compose resources in all 9 app languages, so they follow the
app language. Each phrase is about 3 s, which yields 1–2 two-second windows. All three are
realistic glasses queries, so they match the runtime speech.

Android host: `VoiceEnrollmentActivity` and `VoiceEnrollmentViewModel`. It is onboarding
step 4 of 5 (between optional features and open source), and Settings → Voice profile opens
it standalone (enroll, enroll again, delete).

Per phrase:
1. Explicit consent switch and the RECORD_AUDIO permission. The capture chain matches
   `AudioQuestionRecorder`: `VOICE_COMMUNICATION`, 16 kHz mono, RMS ≥ 250 counts as voiced.
2. Quality check: no speech, < 1.2 s voiced, or > 1 % clipped samples → retry.
3. After three phrases, if the model is installed, a leave-one-out check: any phrase whose
   cosine to the template of the others is < 0.35 (uncalibrated) is recorded again.
   This catches someone else speaking a phrase.
4. Commit. Phrases are written to `pending/` and only replace the old profile on completion,
   so an abandoned re-enrollment keeps the previous profile.

### Storage and privacy

`noBackupFilesDir/voiceprint/`, which is excluded from auto-backup, device transfer and the
Tasker/Syncthing mirror. It holds:
- `phrase-N.pcm`: raw 16 kHz PCM. It is kept so the template can be rebuilt for another
  model or variant without enrolling again; CPU and NPU WeSpeaker embeddings differ
  (cos ≈ 0.97).
- `enrollment.properties`: language, input route, consent time.
- `template-<modelId>.bin`
- `observations.csv`: numbers only, last 500.

It is deleted from Settings → Voice profile → Delete, and by Clear local data. Nothing is
logged except scores. *Follow-up:* encrypt with the Keystore key like the Memory Vault.

### Model

`SpeakerModels.DEFAULT = ECAPA` (`ecapa_spkrec_voxceleb_2s.tflite`, 84 MB fp32, 192-d,
raw PCM in, filterbank in the graph). It runs on the Interpreter the app already ships
(`litert:1.4.2`). WeSpeaker int8 is also cataloged (11.6 MB, 256-d), and
[AUDIO_SPEAKER_FILTER.md](AUDIO_SPEAKER_FILTER.md) measured it as more accurate
(0.5 % vs 0.7 % EER with 3 phrases at 2 s). Switching `DEFAULT` rebuilds templates from the
stored phrases.

The exports are not published, so a model is installed by staging it:

```bash
adb push /mnt/seagate/ML/cyanbridge-audio-speaker-20261007/litert/ecapa_spkrec_voxceleb_2s.tflite \
  /sdcard/Android/data/com.fersaiyan.cyanbridge/files/speaker_models/
```

`SpeakerModelStore` verifies size and SHA-256, then moves the file into `filesDir/speaker_models/`.
Enrollment without a model is saved, and the template is built on first use.

### Observation (no gating)

In non-CLASSIC modes, every question recorded by `captureOptionalImageQuestionFromBluetoothMic`
(voice and image flows) is scored against the template in the background. The result is
logged as `SPEAKER_OBSERVE score=… windows=… provisionalAccept=…` (tag `VoiceProfile`) and
appended to `observations.csv`. `PROVISIONAL_THRESHOLD = 0.40` is a placeholder between the
fixture's other-speaker max (0.29) and target min (0.51). It decides nothing.

### Before gating answers

1. Collect observations through the glasses microphone (SCO / LC3 codec). Onboarding
   enrolls on the phone microphone; offer re-enrollment through the glasses and compare.
2. Fit the threshold per microphone route to an FPR target, and expose sensitivity.
3. Add hysteresis over 2–3 windows; handle questions shorter than 1 s, which currently
   yield no score.
4. Decide the failure behaviour: ignore, or ask "was that you?". Never gate when there is
   no template or no model.
5. Optionally use the NPU variant (12 ms per window on SM8650) with its own template.
