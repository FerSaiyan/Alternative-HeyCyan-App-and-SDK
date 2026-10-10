# Audio and image question review — 2026-10-01

Reviewed current branch `heycyan-eyevue-tunebuds-protocol-fixes` at `05463fb`,
the original audio-question WIP, the worklog, and saved September 29 logcats.

## Conclusion

Direct local audio + image + instruction text is the right direction for the
offline requirement. The existing question capture still uses Android's default
SpeechRecognizer, and the saved run explicitly starts network recognition.
However, the earlier diagnosis of a *Google server-side onset deadline* was more
specific than the evidence supports. The saved patch is unfinished and needs
revision before application.

## What the logs establish

Evidence: `/tmp/opencode/ai-image-full-logcat.txt`.

- Lines 5217–5524: the app finishes its cue, starts recognition, and receives
  microphone-ready. The audio HAL opens `PAL_DEVICE_IN_BLUETOOTH_SCO_HEADSET`.
- At 18:28:41.371: `NetworkSpeechRecognizer: Online recognizer - start listening`.
  Google also starts its offline SODA recognizer at 18:28:41.373.
- Lines 5252, 5339, 5432–5433: the requested locale is `en`; SODA reports that
  the locale has no matching language pack and fails with error 12. The log
  separately lists an installed `en-US` system pack. This is concrete evidence
  of a locale/language-pack problem in the offline recognition attempt.
- Lines 6091–6284: microphone closure is requested roughly 500 ms after it
  opens; final recognition has zero hypotheses and `withSpeech: false`;
  `NO_SPEECH_DETECTED` becomes Android error 7. The app then discards the
  optional question. The same sequence repeats around 18:29:01–02.
- This happens before the app's 3.3-second onset timeout. It establishes an
  early end in the recognizer path, but does not identify the precise component
  or policy responsible for requesting closure. The offline failure does not
  by itself prove why the online recognizer ended early either.

Additional evidence:

- `ai-image-question-logcat2.txt`, lines 12, 38: the selected mic is `xk one Pro`
  earbuds, not the glasses. One retry reports speech and remains open about
  11 seconds, then returns no results (lines 48–51). A universal sub-second
  recognizer deadline is not established by these captures.
- Bluetooth selection and packet movement do not establish that intelligible
  user speech reached recognition. The current route helper chooses the first
  Bluetooth communication device; it does not identify the connected glasses.
- `ai-image-question-logcat.txt`, lines 7–8 and 62: the image arrives and the
  non-Live Pro image query produces a reply. In that example the failure is
  obtaining the spoken question, rather than transferring the image.
- That same file, lines 15–30, records a separate Live launch/UI exception:
  Snackbar has no suitable parent, from a TTS callback thread. The current
  `launchGeminiLiveQuestion` still uses that Snackbar path. AudioRecord alone
  does not address this failure.
- These saved failures exercised Pro/non-Live or Live routes. They are not
  evidence of an end-to-end local LiteRT failure or success.

There are speech fixtures in `app/src/androidTest/assets/audio/` and upstream
LiteRT WAV fixtures. No raw microphone recording tied to the failing September
29 question attempts was located in the paths searched. Fixtures can test
endpointing; they cannot prove what the glasses mic delivered in those runs.
No ADB devices were attached during this review.

## Existing local speech activity detection

The recollection that the app already has energy-based detection is correct.
Paths below are relative to `app/src/main/java/com/fersaiyan/cyanbridge/`.

| Implementation | What it does / where it is used |
| --- | --- |
| `media/autocapture/AmbientSpeechDetector.kt` | AudioRecord, Bluetooth routing, 20 ms RMS frames, energy/voiced-fraction decision. Called by `AutoAudioCaptureService` to extend recording loops. |
| `media/autocapture/SpeechActivityDetector.kt` | Pure streaming RMS accumulator, threshold 250, aggregate speech-presence decision. No current caller found in app sources. |
| `ai/transcription/SilenceCompactor.kt` | Adaptive RMS threshold and context padding for already-recorded PCM. Used by local multimodal transcription. |
| `ai/live/GeminiLiveSpeechActivityDetector.kt` | Mean-absolute energy, sustained onset and silence-tail state. Used by Gemini Live to schedule image refreshes; it does not own Gemini's turn endpointing. |

These are lightweight energy algorithms, not evidence of a separate trained
neural VAD model in the question flow. None currently endpoints the single-shot
glasses question recorder. Reuse/extract the appropriate energy primitive and
add question-specific endpoint state; do not use a minute-long aggregate
speech-presence decision as a turn endpoint.

## Existing LiteRT path and a separate media-loss defect

- `MainActivity.runChosenProviderQuery` already accepts `audioPath` and images.
  Its LOCAL image caller currently supplies only the image and text produced
  after optional speech recognition; it does not supply the spoken audio.
- `localmodels/provider/LocalModelsProvider.kt:23–49, 130–155` preserves both
  configured model system instructions and runtime System messages in the text
  sent with media. The native Conversation system instruction is empty, but
  the instructions are carried in the multimodal text part. That composition
  should remain intact when attaching audio; extra user text should also be
  preserved deliberately.
- `localmodels/engine/LiteRtLocalInferenceEngine.kt:350–374` builds ImageFile,
  AudioFile, and Text parts in one turn. No separate transcription is required.
- **Confirmed defect:** lines 274–279 retry a failed media request with
  `userContents = null`, which sends text only. An attachment-processing error
  can therefore yield an answer that never saw the image or heard the question.
  Missing attachment files are also silently omitted at lines 359, 363.
  A corrected implementation must report these failures rather than present a
  text-only response as a successful multimodal answer.
- Runtime type alone is not proof that an imported LiteRT model supports both
  modalities. The current media gate checks only `modelRuntime == LITERT`.
  Model/package capability and actual attachment delivery need verification.
- `MediaInferenceRoutingPolicy.resolve` can route an unavailable LOCAL media
  request to Pro or Tasker; the latter image branch calls the relay. The
  corrected offline local question path must not turn model unavailability
  into an implicit network request. An explicitly enabled remote OpenAI backend
  is a separate existing setting and should be identified accurately.

## Review of the parked audio patch

Original stash commit: `75c25289d6f2ae7755a364f088798ed834888de9`.
It is currently `stash@{1}`, not the `stash@{0}` named in the old worklog.
The mixed stash contains EyeVue and tooling work too. Its MainActivity delta
against its own first parent is the audio-question WIP.

Preserved without applying:
`/tmp/opencode/audio-image-review-20261001/`

- `MainActivity.audio-wip.patch`: original MainActivity delta.
- `MainActivity.stashed.kt`: original full file for reference.
- `AudioQuestionRecorder.kt`, `AudioQuestionRecorderTest.kt`: new files from
  the stash's untracked-files parent.
- `manifest.json`: source commit, review HEAD, sizes, SHA-256 hashes.

Useful pieces: direct audio attachment plumbing, 16 kHz mono PCM/WAV capture,
pre-roll, onset timeout distinct from silence hangover, and pure endpoint tests.

Problems requiring correction:

1. **Not compilable as saved.** New voice code ends at line 6003 of the snapshot;
   the old recognizer listener and startup code remain immediately after it.
   `speakVoiceListeningCue` and `VOICE_QUESTION_ONSET_TIMEOUT_MS` are referenced
   without definitions. Imports for the two new audio types were not added.
2. **Long speech is still cut.** `VoiceEndpoint` unconditionally stops at 20
   seconds, even on voiced frames. Its claim that long questions are never cut
   is false. A resource cap must be separate from natural turn completion and
   must not silently submit a truncated question as complete.
3. **It duplicates energy detection.** Threshold 280 and minimum voiced duration
   400 ms are uncalibrated for the actual glasses recordings. A short valid
   question can be rejected, and background noise can count as speech.
4. **Cancellation does not own the image capture job.** The capture starts in
   a separate lifecycleScope launch; continuation cancellation clears the audio
   route but does not cancel that launch. Capture can continue after cancellation.
   Route teardown also lacks the per-query ownership check used in voice capture.
5. **Non-local spoken image questions are discarded.** The patch records their
   audio but sends only a default text prompt to Pro/external paths. Logging
   that fact does not correct the original symptom. Follow-ups can likewise
   trigger another generic description. Capture/routing must be scoped to
   routes that can consume the result.
6. **Voice behavior is changed beyond audio capture.** The planned replacement
   removes ANSWER/ANALYZE_IMAGE/EXECUTE_UI_TASK/CLARIFY routing and the existing
   Gemini Live voice launch. A blanket replacement would lose camera/phone
   commands and send Live model selections through `/audio-query`. These need
   an audio-aware design or an explicitly bounded change, rather than deletion.
7. **Question WAV lifetime is unmanaged.** The patch writes cache files but does
   not delete them after inference or cancel/abandon handoff. Ownership should
   cover recording, inference, and final cleanup.

## Concurrent EyeVue changes

`3872ff3` makes sensible protocol corrections: inbound AC55, manual versus AI
shutter separation, and video-start value 0 match the decompiled vendor sources.
`4e1e45a` fixes a real media-format problem by saving EyeVue WAV as WAV instead
of wrapping it as Opus; its audio MediaStore path and per-file sync tolerance
also address transport/import behavior.

The later live-preview patches repair RTSP response/body consumption, shared
video/audio delivery, and LibVLC rendering. Their purpose and current socket
tests make sense for EyeVue live preview. Those changes do not connect spoken
question audio to LiteRT, and are not validation of the AI question fix.

One change needs follow-up: `AutoAudioCaptureService.kt:375–383` now releases the
shared vendor response-slot permit after a six-second ACK timeout. This solves
an indefinite lock, but idempotent permit release does not prove that a late SDK
reply cannot be delivered to a newer command's singleton callback slot. The new
coordinator tests exercise bookkeeping, not that vendor reply-isolation issue.

## Verification and corrected next steps

Ran `:app:testDebugUnitTest` with five selected classes: GeminiLive speech
detector, LocalModelsProvider prompt preservation, EyevueProtocol,
EyevueRtspRelay, GlassesSessionCoordinator. **33 tests passed**, zero skipped,
failures, or errors. Current debug compilation dependencies were up to date.
Log: `/tmp/opencode/audio-image-review-tests-20261001.log`.
These tests cover current committed code, not the uncompiled parked recorder.

1. Use the preserved WIP as reference, not a full-file restore or stash pop.
2. Implement cancellable question capture with verified glasses-mic identity,
   frame-level energy detection, pre-roll, sustained onset, and silence-based
   completion. Test long speech, pauses, quiet speech, noise spikes, cancellation,
   and any resource limit explicitly.
3. Wire LOCAL image/voice requests to one LiteRT turn containing raw audio,
   optional image, configured/runtime system instructions, and appropriate extra
   text. Keep no-speech default image description separate from a recording error.
4. Remove silent attachment omission and text-only retry for media requests;
   keep unsupported LOCAL media requests local and report the capability problem.
5. Preserve Live and external routing behavior deliberately; separately design
   audio-aware voice intent handling. Pro image+audio needs its combined endpoint;
   the website change remains a plan until requested.
6. Verify first with deterministic media-delivery/endpoint tests, then on the
   phone/glasses with network disabled for LOCAL. Capture PCM and actual routed
   device identity alongside logs to settle the remaining microphone questions.
