# Shared glasses photo ownership

Implemented on 2026-10-04, extending the [HeyCyan repair](HEYCYAN_LIVE_PHOTO_REPAIR.md)
to EyeVue and TuneBuds's native AI-image transports.

## Ownership contract

`GlassesPhotoCapture.withCapture(device)` reserves the Android
`GlassesSessionCoordinator` background permit and records a device-tagged owner.
It releases ownership only after its caller's cleanup finishes. Live, dashboard
AI questions and any future native consumer must call the manager's
`capturePhotoForAi()` method or the selected-device `GlassesAiPhotoCapture` adapter;
listening to the public photo broadcast is not a
capture API. HeyCyan Live/Walking Aid/auto-loop use this same owner through
`HeyCyanPhotoCapture`. MainActivity's existing HeyCyan workflow remains excluded
through the coordinator's permit.

Live, Walking Aid periodic photos and automatic/standalone visual notes share
`GlassesAiPhotoCapture`. EyeVue and TuneBuds use their native paths even when the
HeyCyan SDK is disconnected. Native exposure time is estimated from command time;
only HeyCyan exposes the photo-ready timestamp. Native quality stays on its one
verified AI-photo path; HeyCyan's quality, settle and shutter settings are preserved.

Photo acquisition excludes media sync, live preview, OTA and other background
commands. Native dashboard saved-photo/video/audio commands also reserve shared
ownership during their command sequence. TuneBuds Detailed holds ownership
through camera saving, Wi-Fi transfer and noncancellable hotspot cleanup.

Permits carry their device class. Oudmon/HeyCyan disconnect handling clears only
HeyCyan permits, and cannot unlock a native photo. EyeVue/TuneBuds disconnect or
reconnect cancels only the matching photo job, keeping its permit until cleanup
finishes. EyeVue ignores callbacks from a replaced GATT object; TuneBuds ignores
data returned by a replaced RFCOMM socket.

## Native acquisition

`NativeGlassesPhotoCapture` installs one owned waiter before issuing the shutter:

| Device | Verified AI-photo request | Image stream | Deadline |
| --- | --- | --- | --- |
| EyeVue | `AB 55 00 03 22 31 53` | AA15 start/data/end (`151/152/153`) | 12 s |
| TuneBuds | `0xE1` payload `1` | `0xE3`, status `1` continuation / `0` final | 15 s |

The deadline includes the actual command write/acknowledgement and image wait.
A request error, timeout, invalid JPEG or disconnect cannot return a prior photo.
Native streams push chunks, so failed acquisition does not automatically issue
another shutter. HeyCyan retains its two bounded thumbnail-read attempts.

Both native assemblers bound images at 4 MiB and reset on cancellation/cleanup.
EyeVue requires a fresh start frame. TuneBuds requires JPEG-start bytes before
opening a buffer, discards orphan tails, and resets on error/overflow. All three
families require JPEG start/end markers and full bitmap decoding before delivery.

After each native transfer the waiter is detached and late chunks are dropped
during a 750 ms quiet drain, capped at 2 s, in `NonCancellable`. The stream buffer
is reset before the shared permit is released. The next capture creates a new
waiter and issues a new request; there is no cached-image fallback.

The vendor EyeVue assembler strips the four-byte chunk prefix without using it
as an ordering/attempt identifier (`ModBleResponse.onRequestSyncFileResponse`).
No attempt identifier is proven for either native stream. Bounded draining cannot
prove that an arbitrarily delayed, structurally valid new stream belongs to the
latest shutter. TuneBuds Detailed still selects the newest timestamped storage
filename; its storage workflow has no proven fresh-photo correlation. These
protocol limits need hardware investigation before stronger freshness claims.

## Manual Live input and reporting

When a Live handler is registered for a native device, an unsolicited AI stream
start reserves a receive-only owner immediately and passes its acquisition to
Live. Live records an attempt before waiting; transfer/decode failure is counted
as acquisition failure. Programmatic streams already have an owner and never
start a second manual acquisition. Receiving a native manual image does not
trigger another shutter. A saved-only EyeVue manual photo that emits no AA15
stream does not become a Live image.

Routing is device-filtered and registration-identity-aware. Activity/service
callbacks dispatch to main. Stop, duplicate input or a replaced registration
cancels the pending native acquisition, which still performs bounded cleanup.

`GlassesPhotoTransferException` classifies native transfer failures alongside
HeyCyan thumbnail failures. All devices share Live's serialized enqueue counts,
image limits, truthful queued/rejected send status and nonbillable diagnostics.
The website contract accepts all device classes without a device-specific schema
change. Its diagnostics endpoint was deployed to production on 2026-10-04;
`cyanbridgelabs.com` and `cyanbridge.vercel.app` returned `401` to unauthenticated
POSTs and the homepage returned `200`.

## Hardware acceptance

Software verification: 208 focused tests passed across shared capture/coordinator,
Live diagnostics/policy, EyeVue, TuneBuds and Walking Aid. Coverage includes
cross-family exclusion, nested command timeouts, invalid images, cancellation,
device-scoped disconnect, bounded late traffic, receive-only manual acquisition,
registration replacement, stream overflow/recovery and production-adapter
selection without HeyCyan fallback. The integrated ARM64 debug APK builds.
Log: `/tmp/opencode/unified-native-photo-tests-build.log`.

No ADB device was connected during this extension. On EyeVue and TuneBuds:

1. Capture at least five changing scenes in Live every-turn mode and five via
   dashboard AI questions. Confirm distinct complete images and one shutter per
   acquisition.
2. Feed manual AI-photo streams while MainActivity is stopped. Confirm one Live
   attempt, one queued image and no additional shutter. Repeat while an automatic
   capture owns the stream and verify no duplicate manual read/send.
3. Interrupt command acknowledgements, drop final frames, send partial/invalid
   JPEGs, disconnect and stop Live during reception. Verify bounded completion,
   buffer reset, acquisition diagnostics and permit release after cleanup.
4. After each failure, capture again and start media sync/live preview. Try
   dashboard photo/video commands during reception; they must not preempt it.
5. Switch device selection and reconnect; old-transport callbacks must not unlock
   or deliver an image to the new device/session. Check acquisition and socket
   enqueue diagnostics separately from Gemini's response.

TuneBuds Detailed additionally needs its Wi-Fi transfer regression run after the
new ownership wrapper. W610 acceptance remains in the HeyCyan repair document.
