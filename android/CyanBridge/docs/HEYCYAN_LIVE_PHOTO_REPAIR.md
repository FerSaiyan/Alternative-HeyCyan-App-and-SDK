# HeyCyan Live photo repair

Implemented locally on 2026-10-04. Background:
[Live photo-count audit](GEMINI_LIVE_PHOTO_COUNT_AUDIT.md) and
[issue #23 transfer audit](HEYCYAN_BLE_PHOTO_ISSUE_23_AUDIT.md).

## Ownership and notification routing

All Android HeyCyan thumbnail consumers now use the Android
`GlassesSessionCoordinator`. Live, Walking Aid and auto-loop acquisition use
`HeyCyanPhotoCapture`; MainActivity retains its existing capture/notification
and Detailed-image workflow but uses the same permit and thumbnail receiver.
There are no Android callers of the KMP coordinator or the vendor thumbnail
convenience helper remaining.

Programmatic background captures register a photo-ready listener before their
commands and keep their ownership marker until thumbnail cleanup finishes.
MainActivity and Live's button listener suppress their fallback handling while
this owner is active. Live and auto-loop keep the known quality/shutter sequence;
Walking Aid keeps its existing Chat-AI single-command capture. A missing `0x02`
fails after five seconds instead of reading a previous photo.

`GeminiLiveImageButtonRouter` owns a dedicated vendor notification listener for
the active Live session. This restores physical-photo input even while
MainActivity is stopped. MainActivity defers unowned manual events to that
registered listener, so they are delivered once. A MainActivity programmatic
capture keeps precedence: its permit prevents the Live listener from reading
its photo. UI/service callbacks dispatch onto the main thread.

## Bounded thumbnail receiver

`HeyCyanThumbnailReceiver` installs a raw `PictureThumbnailsResponse` handler
specifically under signed opcode -3 (`0xFD`), using `putIfAbsent`. It does not
call `cleanMap` or change unrelated response/listener slots.

- Read only after a fresh-photo event; default settle is 250 ms. Walking Aid
  continues to supply its configured settle delay.
- Parse total/index from the actual SDK response and require packet zero,
  stable total and contiguous indexes. Ignore duplicate accepted packets and
  late tails before a retry's first packet. Reject malformed/empty chunks,
  changed totals and image sizes exceeding 4 MiB.
- Issue the next packet request only after validation. The vendor's private
  `syncPictureThumbnails(int)` helper is invoked through one adapter to preserve
  its framing, queue and MTU behavior. Its name is retained in ProGuard rules.
- Wait at most ten seconds per attempt and make at most two attempts at reading
  the same photo. Validate a JPEG end marker and a full bitmap decode, recycling
  the decoded bitmap. Do not expose partial bytes to a file or caller.
- Freeze the attempt before retry/return/cancellation. Keep the same raw handler
  installed while draining responses: a discarded packet cannot trigger the
  SDK helper's automatic request chain.
- Require a 750 ms quiet window before retry, bounded by two seconds. If that
  window cannot be reached, abort the retry. Final cleanup also drains for a
  bounded period in `NonCancellable`, then compare-removes only the owned
  handler. Release the caller's permit in `finally` after this cleanup.

Permit invalidation on disconnect/reconnect is checked before commands and
thumbnail requests; a late response cannot chain another request after its
permit has been cleared.

The response format has no proven capture/attempt ID. The quiet window plus
packet-order validation cannot distinguish an arbitrarily delayed packet with
the exact metadata currently expected by a later transfer. Hardware timing
validation is therefore still required; no source-only repair can prove this
case away. The implementation stops application-owned request chains, rejects
unmatched late tails, and prevents concurrent consumers from replacing FD's
handler.

## Send results and diagnostics

`sendImage`/`sendVideoFrame` now return whether OkHttp accepted the message.
Initial/manual/automatic consumers update sent-context state only on acceptance;
manual and automatic statuses say **queued**, not model-processed. Image count
and limit checks are serialized across callers. Free/Economy uses the server's
existing ten-image limit so an eleventh image does not terminate the socket;
Private retains its existing 540-input safety limit.

Nonbillable Live snapshots track capture attempts, completed captures, failed
acquisitions, photo-ready timeouts, thumbnail-transfer failures, queued images
and rejected sends. The latter two failure types are subsets of failed
acquisitions. Captures completed before Live starts are not counted as Live
attempts, but their initial image enqueue is counted.

Website implementation lives in
`/home/fertroll10/Documents/CyanBridgeLabs/Cyanbridge_website`. The authenticated
`/api/pro/live/vision` endpoint stores idempotent revision snapshots in a separate
analytics namespace. Free/Economy correlates with a UUID WebSocket query field;
Private correlates with its reservation ID. The admin session table displays
capture diagnostics separately from its existing image count. Historical rows
with no snapshot explicitly say diagnostics are unavailable.

See the website's `docs/GEMINI_LIVE_VISION_DIAGNOSTICS.md` for the wire contract,
retention, reporting limitations and rollout order. The website was subsequently
deployed on 2026-10-04; production aliases and unauthenticated endpoint rejection
were checked. Cross-device ownership is described in
[Shared glasses photo ownership](UNIFIED_GLASSES_PHOTO_CAPTURE.md).

## Verification

The focused Android suite passed 38/38 tests covering coordinator exclusion, owned-event routing,
missing notifications, failed-transfer recovery, cancellation, disconnect
invalidation, duplicate/late/out-of-order packets, bounded retry, truncated JPEG
rejection, the actual AAR's raw callback slot, and concurrent enqueue counts.
The integrated ARM64 debug APK builds.

Website validation: 7 focused API/analytics tests, lint, production build and
Prisma schema validation passed. The smoke suite plus new endpoint E2E passed
23 tests. The full website suite had 118 passes, 47 skips, 4 not run and 19
failures in existing account-delete localized text, checkout/database setup and
legacy homepage/viewport assertions; none targeted the new vision functionality.
See `/tmp/opencode/live-photo-website-e2e.log` for those outstanding failures.

## Hardware acceptance

No ADB device was connected during implementation. Before release, run on W610:

1. Five distinct physical photos during an existing Live session, including
   while MainActivity is stopped. Each should produce one manual read/enqueue.
2. Five speech-triggered photos in every-turn mode. Each `0x02` should go to its
   existing capture owner, without a second MainActivity thumbnail read.
3. Force missing photo-ready, zero-count and partial-transfer failures. A later
   fresh capture/media operation must recover without app restart/reconnect.
4. Stop Live during notification wait and during thumbnail transfer. Check
   owned-handler removal, permit release and absence of subsequent chained
   requests from late callbacks.
5. Compare 0 and 250 ms settle delays, and inspect photo-ready/transfer/queued
   diagnostics separately. Initial-only mode should still intentionally produce
   zero/one image for voice/image starts.

Useful log tags: `DeviceNotify`, `ImageQuestionTransfer`, `HeyCyanThumbnail`,
`GlassesSession`, `GeminiLive`, `GLASSES_LOG`. Share packet count/index/length and
timing, excluding image payloads and credentials.
