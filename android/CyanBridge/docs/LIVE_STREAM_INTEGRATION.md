# Using the glasses livestream in other applications

CyanBridge handles Bluetooth mode commands, Wi-Fi negotiation, and RTSP
compatibility for EyeVue, HeyCyan, and TuneBuds. Their live previews expose the
same phone-local endpoint (also used by EyeVue/HeyCyan continuous-video Walking Aid):

```text
rtsp://127.0.0.1:8554/live/
```

Start the stream in CyanBridge before opening this URL. Stop it in CyanBridge
when finished. Closing an external viewer does not stop the glasses session.
The existing glasses-session lease permits one active source at a time.

## On the phone

Use **Copy URL** or **Open in VLC** in the live preview panel. Other Android apps
can connect to that URL too. The stream continues through CyanBridge's existing
live foreground service when switching to another app.

One upstream RTSP-over-TCP connection supplies all viewers, including the
in-app decoder. Encoded RTP/RTCP video and audio are forwarded without
transcoding. Audio configuration is preserved when the upstream supplies audio;
its availability depends on the glasses firmware. A slow TCP viewer is disconnected if its bounded
queue fills, so it cannot hold up the upstream reader or another TCP viewer.

The port is fixed, not assigned per session. If another session or app occupies
8554, startup reports a port-conflict error rather than changing the URL.

The relay does not make different glasses' codecs, resolution, rotation, or
audio identical. EyeVue's required zero-based upstream PLAY range is retained;
HeyCyan and TuneBuds use a normal live PLAY. The inline view still applies its existing
device-specific rendering behavior. External clients receive the encoded feed.

## From a PC over USB

Enable USB debugging, connect the phone, and forward the **same** TCP port:

```bash
adb -s <phone-serial> forward tcp:8554 tcp:8554
ffplay -rtsp_transport tcp -fflags nobuffer -flags low_delay \
  rtsp://127.0.0.1:8554/live/
```

Or record the encoded video without transcoding:

```bash
ffmpeg -rtsp_transport tcp -i rtsp://127.0.0.1:8554/live/ \
  -map 0:v:0 -c:v copy glasses.mkv
```

When finished:

```bash
adb -s <phone-serial> forward --remove tcp:8554
```

Use RTSP-over-TCP for USB forwarding; ADB does not forward the relay's UDP RTP
ports. Use 8554 on both ends because the relay advertises that port in its RTSP
Content-Base. `127.0.0.1` is local to the device running the client: on a PC it
works only after forwarding. The relay listens on the phone's loopback interface
and is not directly exposed over LAN.

## Playback latency

HeyCyan's in-app LibVLC player requests 300 ms network caching and 100 ms live
caching, with late-frame dropping. Standalone VLC retains its own playback
settings; **Open in VLC** passes the URL, not the in-app options. Reducing VLC's
network cache to approximately 300 ms can reduce delay at the cost of more
visible buffering on an unstable link. No glass-to-screen latency guarantee is
implied by these cache sizes. A late-joining decoder may wait for an IDR frame;
HeyCyan firmware tested by Vortex sends SPS/PPS in-band and an IDR roughly every
1.3 seconds.

TuneBuds uses 300 ms network/live caching, matching the official embedded
player's configuration, with RTSP-over-TCP and software decoding. These settings
are independent of external VLC's cache and are not measured camera latency.

## TuneBuds / AB Mate livestream

Select a TuneBuds device profile and connect its Classic Bluetooth/SPP session.
The TuneBuds dashboard exposes Live preview on Android 8+; transport and
notification permissions use the existing rationale/request flow. Recording,
AI photo capture, media sync, and other exclusive glasses sessions must finish
before preview starts.

1. Reserve the existing exclusive `LIVE_PREVIEW` session and start the TuneBuds
   foreground service (notification with Stop, CPU/Wi-Fi locks).
2. Start the phone's local-only hotspot and send its credentials via AB Mate
   `0xE6`, Wi-Fi mode 0 (station).
3. Subscribe to endpoint/Wi-Fi notifications before sending `0xE8`. The default
   payload is `00 00 00`; the existing codec also supports the vendor quality tiers.
   Known busy replies 1/3/5 retry with a bounded deadline; 6 reports unsupported
   firmware. Success alone is insufficient: the `0xE8` URL notification is required.
4. Use that exact device-reported RTSP host, port, path, and query for the shared
   `GlassesRtspRelay`. No static IP/port/path is substituted.
5. Play the loopback relay with the shared LibVLC lifecycle and a TuneBuds inline
   view using stream orientation. Copy URL, Open in VLC, and mute/unmute are
   available while live runs. Video/audio packets share one upstream connection.
6. Stop or connection failure closes the relay, sends camera-close `0xE2` with
   the existing busy-saving retry, releases the hotspot and native player, then
   releases the session lease and stops the foreground service. Repeated Stop
   does not release the lease while cleanup is still underway.

The SDK's `Compat.isSupport()` is not a hardware capability check. Project
`5621`'s acquired V821 SWU confirms a live555 server, but individual models still
need a successful `0xE8` exchange/playback. This integration does not flash firmware.

```bash
adb logcat -s TuneBudsLive TuneBudsManager TuneBudsRtspRelay TuneBudsLiveService VLC
```

Hardware checks: start and stop during hotspot/endpoint negotiation; play inline
and in external VLC concurrently; stop one viewer; test Bluetooth/Wi-Fi loss;
restart preview and then media sync to verify hotspot/port reuse. No ADB device
was connected during implementation, so TuneBuds hardware playback remains pending.

## HeyCyan Android compatibility and AP findings

Reference: [Vortex's M02S report](https://gist.github.com/vortex1024/2a9e0bada7692900a3bb4285362d8a2a).

- Wi-Fi Direct start: `02 01 14 01`; stop: `02 01 15 01`.
- The BLE `0x08` notification supplies the **glasses'** IP. The group-owner
  address may belong to the phone and is never substituted as the target.
- The official upstream URL `rtsp://<glasses-ip>:8554/ch0` is tried first.
- Live mode uses `syncHeartBeat(4)` every five seconds, as reported for the
  official realtime-preview activity. This stops with the preview coroutine.
- Peer matching supports names embedding the full BLE MAC as well as paired
  names/addresses. Only one connect request is issued during group negotiation;
  startup disconnected broadcasts do not trigger a duplicate request.
- Normal Android process binding remains the first routing path. If Android
  rejects it, a direct TCP socket must reach the glasses from a local address
  on the selected P2P network. Neither a Samsung-specific IP nor model name is
  hardcoded. These are conditional workarounds, not replacement negotiation.
- Prior direct playback was confirmed on a Samsung Z Fold 6 / Android 15.
  Vortex independently tested M02S with a Galaxy A10 / Android 11. The new
  shared relay still needs physical-device validation; these results do not
  establish compatibility with every OEM or firmware.

### AP mode is a separate transport

Vortex recovered `02 01 14 02` for starting live in AP mode from official app
1.0.103, but did not test it. In the older local decompilation,
`android/HeyCyanOfficialApp/app/src/main/java/com/glasssutdio/wear/wifi/ap/TempWifiHelper.java`
from version `1.0.86_20260115` joins a supplied SSID and WPA2 password using `WifiNetworkSpecifier` and
`ConnectivityManager.requestNetwork()`, then binds to the returned network.
Its disconnect path unregisters the callback and restores network binding.

That local dump does not contain the newer `RealTimePreviewActivity`, and the
AP helper alone does not establish where the realtime SSID/password come from.
There is consequently no automatic P2P-to-AP fallback in this implementation.
Adding AP support requires recovering the credential notification/response,
handling Android's Wi-Fi approval and location requirements, and testing mode
entry, IP resolution, cancellation, and cleanup on glasses hardware. The shared
relay can be reused after either transport supplies a validated upstream URL.

## Verification

`GlassesRtspRelayTest` covers shared video/audio, TCP and UDP transport,
independent viewer teardown, bounded slow-viewer handling, first-frame delivery,
fixed-port reuse/conflicts, and Allwinner SDP without H.264 fmtp.
Its fixed-endpoint test includes the TuneBuds V821 `testH264VideoStreamer` path.
`TuneBudsRtspStartupTest` verifies immediate URL notification handling, vendor
payloads, busy/unsupported statuses, endpoint deadlines, and Wi-Fi failures.
`LibVlcLivePreviewLifecycleTest` verifies cleanup ownership and startup-error
reporting without substituting native-decoder tests for hardware verification.

October 3 TuneBuds integration verification passed 38 focused Android JVM tests
and `:app:assembleDebug` on `heycyan-eyevue-tunebuds-protocol-fixes`. This includes
the existing TuneBuds codec, action gate, device-profile gating, and EyeVue rotated
viewport regressions. No connected Android device was available for native or
glasses hardware playback verification.

`GlassesRtspRelayDecodeTest` sends a real H.264 fixture over a mock RTSP source
through the production relay to two Android LibVLC decoders. It exercises both
upstream URL/range profiles and verifies that one viewer stopping does not
interrupt the other. BLE/Wi-Fi hardware negotiation is not simulated by this
decoder test.
