# HeyCyan live preview

Updated October 3, 2026. This replaces the July passive-probe instructions.

## Current flow

1. Require BLE, Wi-Fi and the applicable Android transport permissions.
2. Register the BLE IP listener and start Wi-Fi Direct discovery.
3. Send realtime-preview start `02 01 14 01` through the vendor SDK.
4. Match the paired glasses' P2P peer, including full-BLE-MAC peer names.
   Keep one pending connect request through startup disconnected broadcasts.
5. Wait for the glasses' IP in notify `0x08`, bytes 7–10, and a formed P2P group.
   The group-owner address can be the phone; it is not the RTSP target.
6. Bind to the P2P network. If process binding is rejected, require a TCP probe
   whose source address belongs to the selected P2P network.
7. Start the shared phone-local relay at `rtsp://127.0.0.1:8554/live/`. Its
   upstream tries `rtsp://<glasses-ip>:8554/ch0` first. Port 554 and firmware
   paths such as `testH264VideoStreamer` remain compatibility candidates.
8. Play the relay with LibVLC. Send `syncHeartBeat(4)` every five seconds while
   the live coroutine is active. External viewers share the upstream session.
9. On Stop, send `02 01 15 01`, close the relay/player, restore network binding,
   and remove the P2P group. Retain the transport lease until teardown is
   confirmed. Closing an external viewer alone does not stop live mode.

The SDK does not correctly parse the realtime work types 20/21: its default
`errorCode=1` is not a reason to retry mode entry. The BLE IP notification and
actual RTSP playback establish readiness.

## Protocol references

- [Vortex's hardware test report](https://gist.github.com/vortex1024/2a9e0bada7692900a3bb4285362d8a2a):
  M02S, Galaxy A10 / Android 11, official app 1.0.103 realtime commands,
  TCP RTSP, in-band H.264 parameter sets and heartbeat behavior.
- October 2 CyanBridge testing: Samsung Z Fold 6 / Android 15, full-MAC peer
  matching, duplicate-connect protection, and verified direct-route fallback.
- The V821 firmware contains `ai_glass_livestream` and live555 strings. These
  explain fallback endpoints; the official and hardware-tested 8554/ch0 URL
  takes priority over static-analysis candidates.

The local official-app decompilation (`1.0.86_20260115`) contains `wifi/ap/TempWifiHelper.java`,
but not the newer `RealTimePreviewActivity`. AP start `02 01 14 02` is recorded
by Vortex; the realtime credential source and AP hardware behavior still need
verification. CyanBridge currently starts HeyCyan live in Wi-Fi Direct mode.

## Testing

Connect the glasses via BLE, start Live preview in CyanBridge, and verify that
the camera appears and the displayed URL is the shared local endpoint. Open
that URL in another player, close one viewer, and confirm the other continues.
Stop from CyanBridge and start again to check port reuse and transport cleanup.

```bash
adb logcat -s LivePreview WifiP2pManagerSingleton WifiP2pBroadcastReceiver VLC
```

Unit tests cover RTSP multi-client forwarding, fixed-port conflicts/reuse,
device-specific PLAY behavior, peer matching, startup disconnected broadcasts,
and direct-route verification. An Android decoder test sends real H.264 over
mock RTSP to two LibVLC outputs through the production relay. Physical-device
testing is still required for BLE/Wi-Fi negotiation and OEM routing behavior.

See [Livestream integration](LIVE_STREAM_INTEGRATION.md) for external clients,
USB/ADB forwarding, recording, latency settings, and AP findings.
