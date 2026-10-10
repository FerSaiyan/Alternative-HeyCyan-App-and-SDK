# HeyCyan BLE photo transfer: issue #23 audit

Date: 2026-10-04

Implementation follow-up: [HeyCyan Live photo repair](HEYCYAN_LIVE_PHOTO_REPAIR.md).
The failure descriptions below document the pre-repair code.

Issue: https://github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK/issues/23

Follow-up: [Gemini Live production photo-count audit](GEMINI_LIVE_PHOTO_COUNT_AUDIT.md)
checks the website API, real session aggregates, and Live capture/event routing.

## Conclusion

The reporter correctly identifies missing retry and an unbounded BLE-command
reservation after thumbnail timeout. Both changes originated in **559abe1
(July 17, 2026)** and are present in release **v2.3.0**, remote `main`, and this
branch. The proposed 250 ms post-notification wait is a sensible hardware
experiment, but neither the official app nor the recovered firmware establishes
it as a protocol requirement.

The initial W610 acquisition failure is not yet conclusively explained: the
issue has no raw failed `0xFD` packet trace. The code does conclusively explain
why one unsuccessful attempt can block later captures and media operations.
The proposed cleanup/retry needs additional SDK lifecycle handling to avoid
mixing responses from two transfers.

This audit adds documentation only. The existing working-tree stream, Walking
Aid, and TuneBuds changes were present before the investigation.

## Evidence reviewed

- Issue body and both comments, including the reporter's separate-attempt-file
  proposal and the maintainer's confirmation of another affected pair.
- Remote `main` fetched through GitHub's contents API; local HEAD `3d7cfe5`;
  release `v2.3.0` (`fc9794c`, September 14).
- `MainActivity.kt`: `MyDeviceNotifyListener`,
  `handleGlassesImageButtonPressed`, `requestSelectedImageSourceForQuestion`,
  `requestImageThumbnailForQuestion`, `receivePictureThumbnail`, and the
  background-command acquisition/release helpers.
- `WalkingAidImageCapture.kt`, `AutoLoopVisualNoteGenerator.kt`,
  `GeminiLiveGlassesImageCapture.kt`, and both session coordinator objects.
- Official app `1.0.86_20260115`: `MainActivity.java:1242–1255,1454–1484`,
  `LargeDataHandler.java:517–544`, `LargeDataParser.java:61–76`, and
  `PictureThumbnailsResponse.java`.
- The **actual bundled AAR**, `app/libs/glasses_sdk_20250723_v01.aar`, inspected
  with `javap`. Its thumbnail callback is `LargeDataHandler$3`; the newer
  decompiled official SDK uses `$6`, but the relevant protocol behavior matches.
- Firmware `WIFIAM01G1_1.00.28_2603031800`, extracted `libaglink.so` and
  `ai_glass_photo`, plus the corrected July 17 kernel/aglink report.
- Existing September 29 local photo-question logcat.

## Current failure sequence

1. Physical AI-photo generates notify type `0x02`.
2. The general notify listener routes a new hardware image-question turn.
3. FAST_PREVIEW immediately starts `getPictureThumbnails()` after reserving a
   background-command permit. There is no transport settle delay.
4. The SDK registers one response handler under **signed key -3 / opcode
   0xFD**, then requests thumbnail packet zero.
5. The caller waits up to **20 seconds** for a final callback with data, then
   performs JPEG dimension validation.
6. If completion never arrives, the image request flag is cleared, but the
   background permit remains reserved. A later request fails permit acquisition
   with `another glasses command owns the SDK BLE/P2P slots`.
7. A late completion or disconnect/reconnect can release that reservation;
   without one it can persist indefinitely.

`currentSession()` can return null during this failure: it reports an exclusive
lease, not the set of background-command permits. The timeout log therefore
does not show the actual stuck reservation.

Detailed uses the saved-photo Wi-Fi/HTTP path rather than the thumbnail response
slot. A successful Detailed transfer is consistent with a working camera and
saved original, while the BLE thumbnail cache/reader fails. Detailed cannot
necessarily start after the permit has become stuck; distinguish runs made
before a failure, after a late completion, and after reconnecting.

## What the SDK actually does

Request payload under `0xFD`:

```text
01 <packet-index-low> <packet-index-high>
```

The outer BLE envelope starts `BC FD` and includes length and CRC. In the full
response frame, the vendor helper reads:

| Bytes | Interpretation |
|---|---|
| 7–8 | Total packet count, little-endian |
| 9–10 | Current packet index, little-endian |
| 11 onward | Image chunk |

- A **zero total count is silently returned from**, without notifying our image
  callback. An application log saying `chunks=0` cannot distinguish that reply
  from no reply at all.
- For a nonfinal packet, the SDK **queues the next request before invoking our
  callback**. Disabling writes only in our callback does not stop that chain.
- Completion is just `index + 1 == total`. The SDK does not validate that prior
  indexes arrived in order or belong to this attempt.
- The response handler is **not automatically removed on completion**.
  `LargeDataParser` retrieves it from `respMap` by opcode on every response.
- A second `getPictureThumbnails()` replaces that same map entry. A late packet
  from the first request can consequently be delivered to the second callback.
  There is no attempt identifier in the helper's exposed callback; the recovered
  frame fields establish count/index, not a proven capture transaction ID.

### Executed SDK probe

A standalone Java probe invoked the actual AAR callback with synthetic frames,
using only a local `android.util.Log` stub. No BLE or firmware was modified:

```text
PASS: total=0 produces no application callback.
PASS: final packet index=1,total=2 signals completion without prior index=0.
PASS: same callback still accepts a late duplicate after completion.
```

Probe source and bytecode evidence:
`/tmp/opencode/issue23-sdk/` (`ThumbnailSdkProbe.java`, `bytecode.txt`,
`thumbnail-callback-bytecode.txt`, `probe-results.txt`). These establish helper
behavior, not the cause of the reporter's first failed hardware read.

## Assessment of the issue's suggestions

### Post-0x02 settle delay: reasonable, unproven

The reporter is correct that the advertised 500 ms wait belongs only to the
parallel **audio** coroutine. It does not delay FAST_PREVIEW. Walking Aid waits
its configurable photo-settle value, default 250 ms.

However, official `MainActivity.java` also calls `getPictureThumbnails()`
directly from its `0x02` branch, after some speech/UI setup, with no explicit
250 ms wait. That incidental setup can change timing, but its duration has not
been measured. Walking Aid's constant/default is an application workaround,
not firmware documentation.

Use 250 ms before the first request as a measured hardware trial. Preserve the
fresh-photo notification prerequisite and the known working software-capture
sequence; do not take a second photo merely to retry reading the first.

### One retry: valuable, but not blind after a partial transfer

Restore bounded recovery, preferably distinguishing:

- No image data / explicit zero-count reply: one delayed packet-zero restart.
- Completed but invalid image: close the first attempt before retrying.
- Partial transfer timeout: stop/detach the old response handler and account for
  its already queued/in-flight packet before reopening the same response slot.

The signed `respMap[-3]` entry can be managed specifically; `cleanMap()` clears
unrelated command handlers and is unsuitable for this narrow repair. Ownership
checks are needed so cleanup does not remove a replacement handler.

### Separate files: useful, insufficient on their own

Separate temporary files prevent an old closure appending to the same physical
file. They **do not** prevent an old wire response being dispatched to the new
closure after the SDK map entry is replaced. Freeze each attempt's writer on
timeout/completion/cancellation and serialize handler lifecycle as well.

### Release exactly once: correct after transport cleanup

Move application permit ownership to the outer workflow's `finally`, after
stopping acceptance of data and cleaning up the owned response handler. Holding
the permit indefinitely was an intentional isolation policy in July, but it
has no bounded recovery and leaves users stuck.

Releasing it alone is incomplete: the old SDK handler can still enqueue requests
and mutate the discarded image. Removing the handler alone also needs handling
for a parser callback already in progress. The receiver needs a closed state
and serialized ownership/packet validation.

### Logging/freshness: keep and strengthen

Current code already logs callback count, payload/total bytes, elapsed time and
completion. Add attempt number, raw packet total/index, reason for termination,
handler ownership, permit state, and full decode result. The bundled helper
logs raw packets under **GLASSES_LOG**, which is useful immediately.

The current `isDecodableImageFile()` reads dimensions with
`inJustDecodeBounds=true`; that is a header/dimension check, not a full JPEG
decode. A new file's `lastModified()` establishes recent reception, not by
itself a fresh camera exposure. Continue requiring `0x02`, accepting only an
ordered complete transfer, and refusing an old gallery-image fallback.

## When the behavior changed

| Commit / date | Relevant change |
|---|---|
| `cd805664` / April 3 | Added no-chunk retry after five seconds. Software capture waited three seconds after shutter; hardware-triggered reads did not have that explicit delay. |
| `c4dc909` / July 15 | Refactored capture to wait for `0x02`; replaced the software shutter's fixed three-second wait. Retained two complete thumbnail attempts, truncating the output before each and waiting 1.5 s between them. |
| **`559abe1` / July 17** | **Removed the two-attempt loop, added background permit handoff, released it in completion/submit-exception paths only, and skipped outer-finally release once transfer started.** This introduced both confirmed recovery defects. |
| `62cbb17` / July 29 | Increased timeout from 10 s to 20 s, improved logs, explicitly documented keeping the slot isolated, and rejected stale fallback. It did not restore retry or bounded cleanup. The 500 ms audio-only log is from this change. |
| `5fa7ac5`, `543ac5b` / July 29–30 | Walking Aid introduced/tuned a separate photo acquisition path with a 250 ms settle default. That wait was not applied to the main question path. |
| `5f51308` / September 2 | Restored the two-command HeyCyan **software shutter** flow after single-command variants failed on the maintainer's firmware. It did not change post-`0x02` thumbnail recovery. |
| `fc9794c` / September 14 | Released v2.3.0 containing the defects above. |

There is **no evidence that a 250 ms post-0x02 wait was removed from the main
hardware-button path**. It was missing already; the firm regression date is
the removal of recovery and introduction of the unbounded reservation.

This is source-history attribution, not a hardware bisect proving every device
first failed on July 17.

## Firmware evidence and its limits

Extracted `libaglink.so` contains distinct view names `AG_VD_SG_PHOTO_END`,
`AG_VD_THUMB`, and `AG_VD_THUMB_END`. The corrected kernel analysis found
`aglink_take_photo_cache`, `aglink_photo_data_handle`,
`aglink_photo_save_thread`, and `aglink_app_take_photo_save_finish_cb`.
Those are consistent with multiple photo/cache/save stages and support treating
saved originals and BLE thumbnails as separate completion boundaries.

They **do not prove** the W610's BLE `0x02` ordering, its precise thumbnail-ready
latency, or a required 250 ms wait. The reviewed firmware family has not been
mapped to the reporter's exact W610 hardware/firmware. The tiny user-space
`ai_glass_photo` binary is not the whole capture implementation; older notes
claiming all photo ownership is on JieLi are superseded by the kernel findings.

References relative to the repository root:

- `firmware_dump/RESULTS/reports/rv32_span__015000_075000/v821_kernel_aglink_led_driver_2026-07-17.md`, especially Kernel Photo-Handling Evidence.
- `firmware_dump/RESULTS/reports/v821_camera_modes_static_analysis_2026-07-15.md`, with its July 17 correction.
- `android/CyanBridge/COMPOSE_MIGRATION_PLAN.md:55–56`: documents the older image-question race and the then-present two-attempt recovery. The retry statement is historical, not accurate for current code.

## Other paths that matter to acceptance

- `AutoLoopVisualNoteGenerator` has a similar completion-owned permit and
  unbounded reservation on transfer timeout.
- Walking Aid releases its permit in `finally`, but its thumbnail stream/closed
  state is not protected by a `finally` around every throwing/cancelled read.
- `GeminiLiveGlassesImageCapture` reserves the **shared KMP coordinator**, while
  MainActivity/Walking Aid/auto-loop use the **Android coordinator**. These are
  separate singleton objects, not a common lock. Automatic Live still capture
  can overwrite the same vendor thumbnail handler while an Android image
  workflow believes it has exclusive ownership. This is relevant to testing
  with an assistant already active; it is not established as the reporter's
  assistant-inactive failure cause.

## Recommended repair and hardware acceptance

Use a narrow shared HeyCyan thumbnail receiver that owns the response handler,
validates packet sequencing, freezes/cleans up each attempt, and keeps one
Android permit through settle/read/retry/cleanup. Start with the 250 ms wait
and one bounded retry of the same fresh capture; handle partial-response
recovery explicitly rather than only swapping files. Reuse it across HeyCyan
consumers and use the same Android coordinator for Live stills.

Capture failure logs before adjusting Bluetooth MTU, resetting all SDK handlers,
changing shutter opcodes, or altering external-assistant integration. The
official API/command matches our implementation; there is no evidence here
supporting those broader changes.

```bash
adb logcat -v threadtime -s DeviceNotify ImageQuestionTransfer AIHijack \
  GlassesSession GLASSES_LOG WalkingAidCapture AutoLoopVisualNote GeminiLive
```

Log mode/build and glasses BLE/Wi-Fi firmware versions. Redact raw JPEG payloads
from shared diagnostics, retaining response total/index/length and timing.

Acceptance should include five physical presses with visibly different scenes,
all producing fresh fully decoded JPEGs; repeated software captures; a forced
no-data failure followed by a successful capture/media operation without
restarting the app; a late first-attempt packet during retry; and repeats with
automatic Live still capture active. JPEG/mtime alone cannot establish scene
freshness. Compare 0 ms and 250 ms settle on the W610 and the maintainer's pair.

Existing `/tmp/opencode/ai-image-question-logcat.txt` records two successful
September 29 AIMB-G3 Clearer BLE previews (640x480; 37,538/38,979 bytes;
2,451/5,792 ms). They support intermittent/model/state-dependent behavior, but
are software-button runs rather than the W610 physical-button reproduction.

No ADB device was connected during this audit. The SDK probe passed; physical
W610 failure capture, delay A/B testing, and firmware-specific acceptance remain
to be performed.
