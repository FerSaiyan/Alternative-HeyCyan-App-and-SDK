# EyeVue live preview

## Vendor reference and current flow

`android/eyevue-jadx/sources/com/eyevue/glassapp/view/live/EyevueLiveActivity.java`
uses LibVLC, a texture-backed `VLCVideoLayout`, and RTSP-over-TCP. It starts
live mode over BLE (`0x67`), joins the reported Wi-Fi network, calls the HTTP
live trigger, then plays `/xxx.mov`. The T-series uses `/h264` instead.

CyanBridge keeps the verified BLE/Wi-Fi/HTTP sequence. The inline LibVLC
player and external VLC now connect to a loopback RTSP relay. Only that
relay connects to the glasses. Copy/Open in VLC must use the displayed
`rtsp://127.0.0.1:<port>/live/` address for the current session.

The relay retains both H264 and AAC tracks, their SDP codec configuration,
RTP clocks, and packet contents. Each local client gets independent SETUP,
PLAY, PAUSE, and TEARDOWN handling. The upstream PLAY waits for the first
local client's subscriptions and PLAY response, so the initial H264 keyframe
is delivered instead of discarded. TCP clients have bounded writer queues.
UDP clients get real RTP/RTCP port pairs, and packets originate from those
advertised ports. Local RTCP BYE packets do not terminate the glasses session.

## Confirmed implementation errors in previous attempts

- The WIP fan-out relay's `readResponse()` consumed headers **and body**.
  Its DESCRIBE handler then attempted to read `Content-Length` bytes again.
  This explains the 715-byte SDP timeout in the captures. Headers and SDP
  had already arrived; there is no evidence that the body was withheld due
  to user-agent formatting or leaked sessions.
- The later single-owner rewrite started an upstream reader immediately
  after DESCRIBE while the client handler also read SETUP/PLAY responses
  from the same socket. Two readers could consume each other's protocol bytes.
- Direct LibVLC playback made the dashboard's Open in VLC action start a
  second glasses session. Captures show our stream ending and reconnecting
  while the other client was opening. Shared viewing requires the local relay.
- Manual `Surface` attachment omitted LibVLC's normal layout helper and
  attached after `play()`. Attachment errors were silently ignored. View
  rotation/scaling also enlarged the entire portrait canvas instead of
  laying out a landscape video viewport first.

## Rendering and audio

`EyevueLiveVideoView` uses the vendor's `attachViews(..., false, true)` API.
Its inner landscape layout has swapped dimensions and is rotated 90 degrees
left inside a clipped portrait viewport. LibVLC handles its TextureView,
surface recreation, aspect ratio, and decoder output size. The player attaches
views before starting playback.

The Samsung capture `eyevue-libvlc-attachfix-20260930.log` contains decoder
buffer deadlocks and an unknown MediaCodec output format. External VLC selected
`avcodec`. The current inline player explicitly selects that software decoder
for video and audio. AAC configuration (`MPEG4-GENERIC/16000`, `config=1408`)
is passed through unchanged.

Mute audio controls only the inline speaker monitor. Opening external VLC
mutes that monitor to avoid two delayed speakers playing the same microphone
feed; the relay continues forwarding audio. External VLC receives the original
sideways picture: the dashboard rotation is a view transform, not re-encoding.

## Verification

`EyevueRtspRelayTest` uses a simulated glasses RTSP server and real loopback
TCP/UDP sockets. It checks complete and fragmented SDP reads, video/audio fan-out,
independent sessions, source ports, PLAY ordering, slow-viewer isolation, and
Stop during a blocked handshake. `EyevueLiveVideoViewTest` checks the rotated
viewport geometry. These do not substitute for testing the physical glasses.

Hardware retest: start live, check upright inline video and sound, open the
displayed relay URL in VLC, return to the dashboard, then Stop from the
notification. New logs should show one glasses handshake and first packets
on channels 0 (video) and 2 (audio), rather than repeated DESCRIBE timeouts.
