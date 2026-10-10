# HeyCyan live preview

The old passive RTSP probe is replaced by the realtime-preview flow recovered
from the official HeyCyan app and hardware-tested by vortex1024.

1. Arm BLE IP notifications and Android Wi-Fi Direct discovery.
2. Send `02 01 14 01` through `LargeDataHandler.glassesControl()`.
3. Poll `02 03` until notify `0x08` reports the glasses IP.
4. Bind CyanBridge to the P2P network.
5. Try `rtsp://<glasses-ip>:8554/ch0` first with LibVLC/RTSP-over-TCP.
6. Fall back to the V821/live555 endpoint, including
   `rtsp://<glasses-ip>:554/testH264VideoStreamer`.
7. Stop with `02 01 15 01`, release LibVLC, and tear down P2P.

The feature is gated by `DeviceClass.HEY_CYAN`, not a particular model.
The same session also exposes decoded frames for Walking Aid.
