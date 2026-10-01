# WalkingAid MVP Review

This document preserves the WalkingAid implementation review and proposed P0-P5 roadmap verbatim so later implementation work can be checked against the original findings.

## Continuous video sources (October 1, 2026)

Walking Aid settings now offers **Periodic photos** plus a device-gated video
button: **EyeVue continuous video** on EyeVue (Android 10+), or **Meta Ray-Ban
continuous video** on Meta. Select a source before enabling Walking Aid. Stop the
plugin before changing sources. The existing photo interval applies to photos;
video is processed as frames arrive, with one pending frame and latest-frame wins.

- EyeVue uses the same vendor BLE `0x67`, Wi-Fi, HTTP activation, and RTSP relay
  as live preview. LibVLC software decoding renders to an ImageReader surface,
  independent of any Activity/window, then applies the preview's -90° rotation.
  Stream audio is muted so it does not compete with spoken hazard warnings.
- Meta uses a single production DAT session/stream, including connection and
  camera-authorization checks. Existing previews/transports retain their session;
  Walking Aid reports a conflict rather than stealing their callbacks.
- Both feed `VisionFrame` into the same local YOLO → motion/tracking → warning/TTS
  worker used for photos. Superseded queued bitmaps are recycled. The active frame
  is recycled after processing; local depth owns a separate bitmap so slow native
  enrichment cannot delay the next YOLO frame. Shutdown joins all inference before
  closing the backend. Only analyzed video
  frames are saved for bounded thumbnail/Q&A history, after immediate warnings.
- Android permission dialogs guide Bluetooth/Wi-Fi setup. Meta setup guides
  registration and camera consent. Startup, disconnection, and frame-stall failures
  stop the plugin and surface an error. Stream frame timestamps currently describe
  receipt at the phone, not measured sensor exposure or transport latency.

CI covers source-button gating, latest-frame backpressure/recycling, cancellation,
transport failures, and lease ownership. The real-model runner additionally uses
12-second H.264 (EyeVue) and HEVC (Meta) clips built from the existing pinned `bus.jpg` and `zidane.jpg`
fixtures. EyeVue substitutes its connection URL while retaining the production
LibVLC decoder/rotation. Meta uses actual `MockDeviceKit` video delivery through
the production manager. Both run the real YOLO11 model, require multiple fresh
frames, and verify detections across the two scenes, plus transport/session cleanup.

Run with `tools/hil/run_walking_aid_model_ci.sh` (see `tools/hil/README.md`).
Verified on the API 36 `CyanBridge_Walking_Aid_CI` emulator: all five real-model
and stream phases pass, along with 28 control/lifecycle/DAT regression tests.
Both streams identify bus/person and then person/tie and release their sessions.
The Meta fixture uses HEVC to match the SDK mock's negotiated decoder; an H.264
fixture can enter STREAMING without yielding frames. Full unit checks: 586 app
and 80 shared portability tests passed. Results are in
`build/hil/results/walking-aid-model-ci/`.
Physical EyeVue/Meta stream verification remains a device-dependent check.

## Implementation Status (July 30, 2026)

P0, P1, P2, and P5 have been implemented in the Android codebase without physical-device validation:

### P0: Make Current Behavior Truthful — COMPLETE

- Capture loop uses start-to-start cadence: `remainingMs = intervalMs - captureElapsedMs; if (remainingMs > 0) delay(remainingMs)`.
- Measured effective cadence is displayed in the notification (not configured interval).
- The latency benchmark shows measured start-to-start cadence, capture breakdown (BLE command → photo-ready, settle delay, BLE transfer + decode), and local detector throughput (renamed from "12 FPS equivalent").
- Safety disclaimer is spoken once at session start, not before each warning.
- Frame age expiry (`MAX_WARNING_FRAME_AGE_MS = 6_000L`) skips stale detection and depth warnings.
- "Clear walking trajectory" replaced with "No supported hazards detected."

### P1: Remove Network and LLM From Critical Warnings — COMPLETE

- Local YOLO detection, tracking, deterministic rule evaluation, and TTS now form the immediate critical path.
- Automatic local/cloud LLM rewriting was removed from warnings and readiness.
- Depth and cloud scene descriptions run as cancellable enrichment work and are ignored when superseded or older than six seconds from the capture command.
- Capture-command, estimated-exposure, and receive timestamps are preserved on every `VisionFrame`.

### P2: Build Real Temporal Hazard State — COMPLETE

- `WalkingAidHazardTracker` provides stable IDs, elapsed-time alpha-beta association, new/persistent/approaching/receding/cleared states, and angular-expansion TTC.
- `WalkingAidCameraMotionEstimator` uses low-resolution global image translation to compensate association for camera/head turns without relying on a phone sensor that may be in the user’s pocket.
- Warning cooldowns are scoped to stable track IDs, and telemetry includes frame age, stale status, track state, TTC, and cleared track IDs.
- Focused Robolectric tests cover tracking, TTC, image-motion compensation, stale frames, track clearing, and per-track cooldowns.

### P5: Improve Acquisition — COMPLETE

- Thumbnail quality level is configurable (0–5) via preferences; default is 3.
- Photo settle delay is configurable (0–500 ms) via preferences; default is 250 ms.
- BLE receive loop reuses a single `FileOutputStream` for the lifetime of a transfer.
- Acquisition micro-benchmark tests all quality levels 0–5 with two consecutive captures each and reports avg ms, dimensions, and KB.
- Latency benchmark explicitly reports settle delay in the capture breakdown.
- Effective rate in benchmark uses the actual configured interval.

### Remaining work

P3 (path/depth reasoning) and P4 (detector training) remain future work. Local depth still runs periodically rather than on every frame, and the current cloud-depth keyword heuristic remains uncalibrated pending P3.

## Bottom Line

You are right that YOLO inference is not the principal latency problem. At `55 ms`, the detector is fast enough. The bigger issue is how the service schedules captures and processes warnings after each roughly four-second transfer.

The current implementation can provide occasional scene awareness, but it is not yet reliable enough to describe as a real-time walking or collision-avoidance aid.

## Current Timing

The capture loop performs these steps sequentially:

1. Capture and transfer a thumbnail.
2. Submit it to the vision worker.
3. Wait for the selected interval.
4. Start the next capture.

This is visible in `WalkingAidService.kt:354-386`. The configured interval is therefore a delay after capture, not the interval between capture starts.

With your measured `3463 ms` capture time:

| Selected interval | Effective frame cadence | Decisions per second |
|---|---:|---:|
| 2 seconds | approximately 5.46 seconds | 0.18 Hz |
| 3 seconds | approximately 6.46 seconds | 0.15 Hz |
| 5 seconds | approximately 8.46 seconds | 0.12 Hz |
| 10 seconds | approximately 13.46 seconds | 0.07 Hz |

With the two-second setting, the approximate timeline is:

```text
0.00 s   First capture starts
3.46 s   First image arrives
3.55 s   First local YOLO decision
5.46 s   Second capture starts
8.93 s   Second image arrives
9.01 s   Second local YOLO decision
```

The displayed `12 FPS equivalent` only measures the isolated analysis pipeline. It does not represent the operating frame rate of WalkingAid.

The best possible rate with the current thumbnail transport, even capturing back-to-back with no interval, is approximately:

```text
1 / 3.546 seconds = 0.28 decisions per second
```

## When Warnings Happen

The service does not intentionally wait for several frames before warning.

A warning can happen on the first frame when:

- A critical object is in the center.
- Its box is larger than 35% of image width or height.
- The depth system reports a ground discontinuity.
- A user-watchlist object is detected.

This logic is in `WalkingAidWarningEngine.kt:39-88`.

Approaching-object warnings do require at least two processed frames. The implementation compares the current bounding-box area against the previous area and marks it approaching when it expands by more than 15% in the center region, in `LiteRtVisionBackend.kt:579-597`.

At the two-second setting, the earliest approaching warning would therefore be approximately nine seconds after startup. At the default five-second setting, it would be approximately twelve seconds after startup.

That approach detection is currently weak because:

- Frames are 5-8 seconds apart in practice.
- The wearer may move 7-11 metres between compared frames.
- Head rotation can make a stationary object appear to expand.
- Objects are matched only by class, region, and box overlap.
- Actual elapsed time is not considered.
- There is no stable track ID or camera-motion compensation.

## Parallel Processing

The capture and analysis jobs are separate. After the first frame is submitted, the capture loop can wait or start another capture while the worker analyzes the previous frame.

The channel has capacity one and drops old queued frames in `WalkingAidService.kt:59-63`. This is a good “latest frame wins” concept, but it has limitations:

- It only drops queued frames.
- It cannot cancel analysis already running on a stale frame.
- A slow cloud request can continue while newer captures arrive.
- A warning from an old frame can still be spoken after the scene has changed.
- There is no maximum acceptable frame age before TTS.

Because local YOLO takes only 82 ms, dropping is unlikely on the fully local detector path. It becomes important when cloud depth, cloud vision, or an LLM is involved.

## What Local Models Actually Do

The automatic WalkingAid flow does not currently perform meaningful multi-frame local-LLM reasoning.

The actual flow is:

```text
Current image
    ↓
YOLO detections
    ↓
Optional depth result
    ↓
Deterministic warning rules
    ↓
Optional local/cloud LLM rewrites the warning sentence
    ↓
TTS
```

The local LLM receives something like:

```text
Summarize the hazard concisely in 1 short spoken sentence:
Person directly ahead.
```

It does not receive:

- Previous images.
- The current image.
- Previous YOLO outputs.
- The 15-frame telemetry buffer.
- Object tracks.
- Movement history.
- Depth history.

The telemetry buffer exists in `WalkingAidWarningEngine`, but the automatic warning pipeline never gives it to the state model. It is only included when the user manually asks a question in `WalkingAidChatActivity.kt:261-297`.

Similarly, `WalkingAidImageStore.getRecentDescriptions()` is currently unused. Therefore the earlier design claim about using the last five descriptions for state reasoning is not reflected in the implementation.

The local LLM currently adds latency without adding dependable safety reasoning. It can also change a precise deterministic warning into a less predictable sentence.

## Depth Behavior

Depth needs particular attention.

### Local Depth

Local depth runs when:

```kotlin
frameCount % 3 == 0 || detectionResult.objects.any { it.approaching }
```

That means frames 1, 4, 7, and so on under normal conditions.

With the two-second selection and your transfer latency, local depth runs approximately every 16 seconds. With the default five-second selection, it runs approximately every 25 seconds.

Ground hazards can therefore be absent from most decisions.

The current depth postprocessing also uses global spread in the lower 40% of the image. It is not calibrated to:

- Camera height.
- Camera pitch.
- A walking corridor.
- The ground plane.
- The model’s actual depth scale.
- Stairs versus shadows or floor patterns.

The code also treats the minimum depth value as closest. That must be verified against the exact Depth Anything export because many monocular models produce inverse depth, where larger values represent closer surfaces.

### Cloud Depth

When cloud depth is selected, the real service uploads and analyzes every frame before evaluating warning rules.

The benchmark reports:

```text
Depth estimation: skipped (cloud source selected)
```

That means the benchmark does not measure the configured runtime pipeline. It omits the cloud request entirely.

With the default configuration of local image detection and cloud depth, real latency is closer to:

```text
3.46 s capture
+ 0.08 s YOLO
+ image encoding/upload
+ cloud depth inference
+ network response
+ warning rules
+ optional local LLM rewrite
+ TTS startup
```

Cloud depth should not block immediate local-object warnings.

## YOLO11 Versus Current YOLO-World

| Capability | Current YOLO11n | Current YOLO-World TFLite |
|---|---|---|
| Model size | approximately 3 MB | approximately 73 MB |
| Measured inference | 55 ms CPU | not yet benchmarked |
| Vocabulary | Fixed COCO 80 classes | Fixed COCO vocabulary in this export |
| Runtime text prompts | No | No |
| Custom hazards | Requires training | Requires a newly parameterized/exported vocabulary |
| Android suitability | Good | Much heavier |
| Recommended role | Primary detector | Experimental comparison only |

The important distinction is that the installed YOLO-World file is:

```text
yolo_world_x_coco_zeroshot_rep_integer_quant.tflite
```

It is a reparameterized model with the COCO vocabulary embedded during export. The official YOLO-World TFLite workflow requires supplying `--custom-text` before export. The resulting model is then specialized for those text classes.

Our Android backend provides only an image tensor to the model and always maps class IDs through the same COCO class list in `LiteRtVisionBackend.kt:740-753`.

Therefore the current YOLO-World option is not open-vocabulary from the user’s perspective. Typing “warn me about low branches and potholes” does not dynamically teach it those categories.

It might produce better COCO accuracy than YOLO11n because it is substantially larger, but it will not solve the missing hazard vocabulary.

## General User Prompts

There are two different prompt fields, and neither dynamically controls local YOLO.

### Focus Description

The “What should Walking Aid pay extra attention to?” field goes through `WalkingAidFocusMapper`.

It recognizes a limited English mapping to COCO classes, such as:

```text
traffic → car, bus, truck, bicycle, motorcycle
pets → bird, cat, dog
trip hazards → backpack, suitcase, ball, skateboard, bottle, chair
```

Problems:

- Only English phrases work.
- Only COCO classes can be returned.
- Abstract requests such as “anything dangerous” resolve to nothing.
- “Low branches,” “potholes,” “curbs,” and “bollards” cannot become detector classes.
- A watchlist detection warns even when small or outside the direct walking path.
- The placeholder example is not a default value. If the field is empty, no personalized classes are configured.

### Custom Instructions

The custom instructions are appended to cloud vision/depth prompts and LLM rewriting. They do not affect local YOLO inference.

### Would Proper YOLO-World Help?

Proper open-vocabulary YOLO-World could accept concrete noun categories such as:

```text
pothole
bollard
low tree branch
construction barrier
open cabinet door
white cane
electric scooter
```

It will not reliably interpret abstract concepts such as:

```text
anything dangerous
things I might collide with
unsafe walking conditions
```

Those prompts require an intermediate layer that translates user intent into concrete visual categories. Even then, zero-shot detection accuracy would need extensive testing before it could influence safety warnings.

For non-technical users, model selection and raw prompting should not be the primary interface. Better options are:

- Everyday walking.
- Indoor navigation.
- Street and crossing awareness.
- Stairs and ground changes.
- Crowded environments.
- Personal objects to notice.

The app can convert those modes into a reviewed hazard vocabulary and show the user exactly what it understood.

## Current Usefulness

The plugin can currently be useful for:

- Periodically reporting a large person or vehicle ahead.
- Detecting some large common COCO objects.
- Providing scene history and manual image questions.
- Slow exploration while standing or moving cautiously.
- Testing smart-glasses vision transport and local detection.

It is not currently dependable for:

- Sudden collision warnings.
- Road crossing.
- Fast-moving bicycles or vehicles.
- Reliable curb or stair detection.
- Potholes and drop-offs.
- Thin poles, signs, branches, and open doors.
- Determining that the path is safe.
- Continuous free-path guidance.

One particularly dangerous description is:

```text
Clear walking trajectory
```

The service records this whenever YOLO returns zero objects. Zero detections only mean “no supported object was detected above threshold.” It must never be interpreted as a clear or safe path.

## Recommended Architecture

The critical warning path should become:

```text
Newest fresh frame
    ↓
Fast local safety detector
    ↓
Tracked hazard state + path corridor
    ↓
Immediate deterministic warning
    ↓
TTS/earcon
```

Everything else should be parallel and non-blocking:

```text
Local/cloud depth enrichment
Cloud scene description
Open-vocabulary discovery
LLM phrasing
Image history
Manual question answering
```

An LLM should never delay “Car ahead” or “Stop, obstacle ahead.”

## Improvement Priorities

### P0: Make Current Behavior Truthful

1. Change capture scheduling so the interval represents start-to-start cadence, not capture time plus delay.
2. When capture takes longer than the selected interval, begin the next capture immediately.
3. Display measured effective cadence rather than configured cadence.
4. Rename `12 FPS equivalent` to “local detector throughput.”
5. Measure real capture-to-TTS latency, including cloud depth and LLM rewriting.
6. Replace “Clear walking trajectory” with “No supported hazards detected.”
7. Expire warnings from frames older than a defined threshold.
8. Speak the safety disclaimer when WalkingAid starts, not before the first urgent warning.

This would make the two-second setting run back-to-back at approximately 3.5 seconds per frame instead of 5.5 seconds.

### P1: Remove Network and LLM From Critical Warnings

1. Evaluate local YOLO rules immediately.
2. Speak an urgent deterministic warning immediately.
3. Run depth in parallel.
4. Run cloud description only for optional richer context.
5. Remove automatic LLM rewriting from urgent messages.
6. Cancel or ignore cloud results when a newer frame supersedes them.
7. Include frame age in every warning decision.

### P2: Build Real Temporal Hazard State

1. Timestamp the capture command and estimated exposure time, not just bitmap arrival.
2. Track objects using stable IDs and elapsed time.
3. Use a Kalman filter or ByteTrack-style association.
4. Account for wearer head movement using optical flow or available motion sensors.
5. Estimate time-to-collision from angular expansion and actual time delta.
6. Maintain hazard states such as new, persistent, approaching, receding, and cleared.
7. Use cooldowns per tracked hazard rather than per class name.

With four-second snapshots, movement estimates will remain weak. Temporal tracking becomes truly useful only after acquisition improves substantially.

### P3: Improve Path and Depth Reasoning

1. Define a trapezoidal walking corridor instead of dividing the whole image into thirds.
2. Calibrate the corridor for camera mounting position and field of view.
3. Associate depth values with detected objects.
4. Analyze ground-plane discontinuities inside the walking corridor.
5. Validate Depth Anything output direction and normalization.
6. Run lightweight depth on every frame or maintain a properly aged depth state.
7. Add dedicated stair, curb, pothole, and drop-off evaluation.

### P4: Improve the Detector

The strongest direction is not replacing YOLO11n with the current YOLO-World file. It is training a compact detector or segmenter on actual walking-hazard data.

The dataset should include glasses-perspective examples of:

- Stairs up and down.
- Curbs and sidewalk edges.
- Potholes and holes.
- Bollards and thin poles.
- Low branches and overhead obstacles.
- Construction barriers.
- Open doors and cabinet doors.
- Bags, boxes, scooters, and objects on the ground.
- Dogs and leashes.
- Wheelchairs, bicycles, and mobility devices.
- Wet floors and strong shadows.
- Night, glare, blur, rain, and head tilt.

A fine-tuned compact YOLO detector or segmentation model will likely outperform a generic open-vocabulary model on these specific hazards while remaining fast.

YOLO-World can later be added as an optional discovery detector for personalized nouns, not as the primary safety detector.

### P5: Improve Acquisition

The capture protocol needs its own benchmark broken into:

```text
Capture command acknowledgement
Photo-ready wait
Fixed 250 ms settling delay
BLE transfer
JPEG file writing
JPEG decoding
```

Experiments should include:

- Thumbnail quality levels 0 through 5.
- Removing or reducing the fixed 250 ms settle delay.
- Reusing one buffered output stream instead of reopening the file for every BLE chunk.
- Capturing back-to-back.
- Measuring image size versus transfer time and detection recall.
- Investigating whether the glasses expose a lower-latency preview or Wi-Fi stream.

If HeyCyan hardware cannot deliver at least several fresh frames per second, the feature should be positioned as a “Scene Advisor” rather than a collision-avoidance WalkingAid. Meta DAT or a phone-camera mode may support a genuinely continuous version.

## Product Recommendation

I would keep YOLO11n as the default for now and hide the model choice from normal users. The next work should be:

1. Fix the capture cadence and benchmark end-to-speech latency.
2. Make local deterministic warnings immediate.
3. Remove cloud depth and LLM rewriting from the blocking path.
4. Build a calibrated path corridor and reliable freshness handling.
5. Collect a real glasses-perspective hazard dataset.
6. Fine-tune a compact hazard detector.
7. Evaluate proper YOLO-World later as an optional personalized-watchlist system.

Switching to the existing YOLO-World model now would increase model size and likely inference cost without delivering the open-vocabulary behavior users expect.
