# Gemini Live photo counts and HeyCyan transfer failures

Date: 2026-10-04

Implementation follow-up: [HeyCyan Live photo repair](HEYCYAN_LIVE_PHOTO_REPAIR.md).
The investigation below describes the state before that local repair.

Companion to [the issue #23 BLE photo audit](HEYCYAN_BLE_PHOTO_ISSUE_23_AUDIT.md).

## Production evidence

A read-only query against the configured production analytics KV returned all
203 Gemini Live records from the preceding 30 days, without truncation. All
were session records; there were no separate per-turn usage records in this
sample. No user identifiers, emails, image contents, or transcripts were
included in the saved aggregate report.

| Sample | Sessions | 0 images | 1 image | More than 1 |
|---|---:|---:|---:|---:|
| All | 203 | 163 | 37 | 3 |
| Excluding flagged test accounts | 190 | 153 | 36 | 1 |
| Free | 176 | 140 | 35 | 1 |
| Economy | 25 | 21 | 2 | 2 |
| Private | 2 | 2 | 0 | 0 |
| Marked successful | 144 | 107 | 35 | 2 |
| Last 7 days | 47 | 38 | 9 | 0 |

Thus **200/203 (98.5%)** recorded sessions had zero or one image; excluding
flagged test accounts, **189/190 (99.5%)** did. The median duration across all
records was 289.424 seconds, so the whole pattern is not explained by immediate
startup failure. However, the two Private records were approximately 20 ms
startup failures, not evidence about functioning Private camera sessions.

These are network-session records, not necessarily unique user conversations.
They lack glasses model, selected refresh mode, capture-attempt count, transfer
failure count and failure reason. They cannot establish what proportion of
users tried automatic HeyCyan vision or how many BLE attempts failed.

The production alias inspected through Vercel was READY, created October 1 at
18:13:22 UTC. The CLI inspection did not return a Git SHA. Both the website's
checked-in HEAD and its current working files agree on the essential image
counting behavior described below; unfinished local changes are not assumed
to be the exact deployed source.

Evidence: `/tmp/opencode/issue23-live-production-summary.json` and
`/tmp/opencode/issue23-vercel-inspect.json`.

## What the website's image count means

Website repository:
`/home/fertroll10/Documents/CyanBridgeLabs/Cyanbridge_website`.

- `app/api/pro/live/free/route.ts` handles both Free and Economy sockets. It
  increments `imageCount` when a validated incoming message contains
  `realtimeInput.video`. It records that count at socket close.
- That path does not read the glasses or receive failed BLE attempts. A
  thumbnail that never reaches Android's socket is invisible to the website.
- The configured proxy image cap is **10**, not one. It does not explain the
  concentration at zero or one. The implementation also increments before
  rejecting an over-limit message; avoid treating the field as confirmed
  model-processed images.
- Private goes directly from Android to Google. `/api/pro/live/usage` receives
  client image deltas, and `/api/pro/live/end` receives the session total.
- `lib/gemini-live-analytics.ts` uses session rows for dashboard image counts,
  avoiding a sum of both per-turn deltas and overlapping end totals.

The existing dashboard label “Images processed during Live sessions” is stronger
than the measurement. Free/Economy measures images **received by the proxy**;
Private measures a **client-reported socket enqueue count**. Neither establishes
that Google successfully decoded or used an image.

There is no evidence here of a server counter artificially clipping to one.
For the predominantly proxied production sample, the low count is principally
an absence of image messages arriving at the proxy, rather than a rounding bug.

### Private reporting caveats

`GeminiLiveClient` increments `visualInputCount` only when OkHttp accepts a
video message into its send queue. It sends image deltas at turn completion and
a total at stop/error cleanup. Counter baselines advance before the asynchronous
usage HTTP request has succeeded; failed reports are logged but not retried.
The end-report method ignores HTTP error statuses. Missing counts are normalized
to zero by the website. Private counts can therefore be incomplete or absent.

These caveats do not explain the Free/Economy proxy observations. Fix reporting
reliability without treating failed captures as billable image inputs.

## Why zero or one is often expected

`GeminiLiveVisionPreferences.kt` defaults `live_image_delay` to **0**, meaning
**only the initial image**. `automaticRefreshIntervalMs()` then returns null,
and `GeminiLiveVisionController` disables automatic captures.

- A voice-started session with no initial image normally has zero images.
- An image-started session normally has one image in this mode.
- The **every significant speech turn** option persists the legacy value 2.
  Automatic capture starts on a locally detected transition into speech, not
  on an unconditional timer. Silence does not trigger pictures.

This default was introduced in `238db66` on September 2. The existing preference
test explicitly asserts that the default keeps only the initial image.

Changing this default alone would not repair transfer errors; it would cause
more capture attempts through the currently conflicting transport paths.

## Confirmed Android wiring defects

### 1. Manual photo routing into an active Live session is disconnected

`GeminiLiveForegroundService` and `GeminiLiveActivity` register handlers with
`GeminiLiveImageButtonRouter`, but **there are no callers of
`handleImageButton()` anywhere in the current Kotlin source**.

The call was added to MainActivity's `0x02` branch in `1093f20` on July 29, then
removed in `62cbb17` later that day. It was never restored. Physical AI-photo
notifications now enter the general image-question workflow rather than that
registered Live handler.

This is a confirmed routing regression; hardware behavior still depends on
whether the Activity's general listener is registered and AI hijack enabled.

### 2. Automatic Live capture can collide with the main thumbnail workflow

`GeminiLiveGlassesImageCapture` uses
`com.fersaiyan.cyanbridge.shared.glasses.GlassesSessionCoordinator`.
MainActivity, Walking Aid and auto-loop capture use
`com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator`.

They are separate objects and do not share command ownership. Both can acquire
a permit while targeting the vendor's one `0xFD` callback slot.

In addition, the automatic HeyCyan Live path:

1. Sends the vision and shutter commands.
2. Waits a fixed four seconds, without registering its own fresh-photo waiter.
3. Calls `getPictureThumbnails()` once.

If the general MainActivity listener is registered and hijack enabled, the
same capture's `0x02` reaches it. MainActivity's capture flag was not set by
the Live capture object, so it treats that event as a hardware-button image
request and can start a competing thumbnail read immediately.

Example timing, illustrating the race rather than a measured failure:

```text
t=0       Live owns shared-KMP permit; issues capture
t=~2 s    photo-ready reaches MainActivity; Android permit is independently free
          MainActivity starts its thumbnail read
t=~4 s    Live starts its read and replaces the same vendor response handler
```

The first consumer can time out, the second can receive the tail of the first
transfer, and the main workflow can retain its permit as described in issue
#23. The same collision can occur in reverse order. Different scheduling and
photo sizes change whether it manifests.

The shared-coordinator Live capture path was added in `d826b85` on July 28. The
removed event-router interception on July 29 exposes this two-consumer path.
This is a demonstrable source-level race, not yet a packet-traced reproduction
of the user's latest physical-device errors.

### 3. “Image sent” can be reported without an accepted send

`GeminiLiveClient.sendVideoFrame()` returns Unit and silently exits if inactive,
setup is incomplete, input is empty, or the image limit is reached. A failed
socket enqueue also leaves the image count unchanged. The vision controller
and manual/initial-image callers still mark the visual context as sent or show
a sent status after calling it.

Therefore a “fresh still sent” status does not itself establish a successful
image enqueue, and the count can correctly remain zero despite that UI text.

## Diagnostic coverage

A read-only search of the latest 200 production debug-log submissions found
no matching thumbnail timeout/invalid-thumbnail/busy-slot/Live capture-error
lines. This does not disprove the observed errors: the submitted logs are a
bounded sample, and analytics session rows do not contain Android BLE failures.
Evidence: `/tmp/opencode/issue23-live-debug-log-summary.json`.

The prior local September 29 logs contain successful initial-image transfers,
but do not establish repeated automatic captures throughout a Live session.

## Repair requirements

1. Use the same Android coordinator for every HeyCyan thumbnail consumer.
2. Give each programmatic capture an explicit owner and a fresh `0x02` waiter,
   so its notification does not start another workflow. Route genuinely manual
   button events to an active Live session exactly once.
3. Use the shared bounded thumbnail receiver described in issue #23: settle,
   ordered packet validation, full decode, controlled retry and cleanup.
4. Return a send result and only record/show “sent” after a successful enqueue.
5. Add nonbillable per-session diagnostics: selected device/mode, capture
   attempts, fresh-photo timeouts, transfer/decode failures, queued images and
   send failures. For proxied sessions, preserve server-received counts as a
   separate field. Do not store image contents or transcripts for this purpose.
6. Make missing Private telemetry distinguishable from a known zero, with
   reliable/idempotent reporting. Keep its billing repair separate from image
   acquisition.

Validate repeated captures in **every-turn mode**, physical-photo input during
an existing session, and a forced failure followed by recovery. First-image-only
mode should continue producing zero/one intentionally; success criteria must
include attempted-capture and failure counts, not raw image count alone.

This follow-up is an investigation, with no deployed website or production
Android logic change. No ADB device was connected for physical validation.

The existing focused preference, vision-policy and Android session-coordinator
unit tests passed **19/19** (`/tmp/opencode/issue23-live-vision-tests.log`). They
confirm the default/policy and individual coordinator behavior, but do not
cover the missing router caller or cross-coordinator capture collision.
