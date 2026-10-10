# TuneBuds capture and Wi-Fi media command audit

Reviewed October 4, 2026 on `heycyan-eyevue-tunebuds-protocol-fixes`.

## Main result

CyanBridge's AB Mate photo/video/audio command IDs and payloads match the
decompiled TuneBuds 1.0.9 APK. The biggest functional gap was Wi-Fi topology:
ordinary media sync implemented only the phone local-only hotspot. The official
app also supports Wi-Fi Direct and a glasses-hosted AP, preferring P2P for files.

This review adds an isolated TuneBuds P2P file-sync path and corrects HTTP and
session-lifecycle differences. The previous successful hotspot downloads remain
the hardware baseline; the new P2P integration requires a physical retest.

The full historical investigation is in
[`android/TUNEBUDS_REVERSE_ENGINEERING.md`](../../TUNEBUDS_REVERSE_ENGINEERING.md).
Protocol references are under `android/TuneBudsOfficialApp/tunebuds-jadx-clean/sources/`.

## Commands compared

The payload column excludes the normal five-byte AB Mate frame header. Commands
travel over the glasses' bonded Classic Bluetooth RFCOMM/SPP connection.

| Action | Command / request payload | Vendor reference | CyanBridge result |
|---|---|---|---|
| Saved photo | `E1`, `00` (recording mode) | `GlassCameraTurnOnRequest.java:6-24`, `ABMateManager.java:680` | Matches. Manual capture now also waits 2.5 seconds and performs the existing saving-aware close before handing the camera to sync. |
| AI preview photo | `E1`, `01`, receive `E3` chunks | Same request, `AIPicPayloadHandler`, `models/AIPicPackage` | Matches; separate from saved-photo HTTP transfer. |
| Start video | `E4`, empty | `GlassStartRecordVideoRequest.java:6-19`, `ABMateManager.java:724-744` | Matches; do not prepend `E1` or invent a stop payload. |
| Start audio | `E5`, empty | `GlassStartRecordAudioRequest.java:6-19`, `ABMateManager.java:747-767` | Matches; audio files use the same HTTP file manager as photos/videos. |
| Stop recording / transfer cleanup | `E2`, empty | `GlassCameraCloseRequest.java:6-19`, `ABMateManager.turnOffCamera` / `turnOffSubSystem` | Matches. Status `1` means saving and is retried; `2` transferring and `3` updating remain failures. Recording flags also clear on an `E2` notification with closed status `0`. |
| Configure Wi-Fi | `E6`, TLVs below | `GlassWifiConfigRequest.java:23-40` | Byte-for-byte match. |
| Start file server | `E7`, empty | `GlassFileManageRequest`, `ABMateManager.java:1519-1553` | Requires reply `0` AND an `E7` UTF-8 endpoint notification. Busy `1/3/5` retries; `4` is low battery, other replies are terminal. |
| P2P readiness | `EC` notification value `2`, `F2` notification six bytes | `AbMateWifiConnector.java:141-165`, `WifiDirectAddressPayloadHandler.java:12-27` | Both signals are now required before Android P2P negotiation. |
| Counts / storage | `EF` / `EA`, empty | `GlassMediaFileCountRequest`, `GlassStorageInfoRequest` | Existing three-u32 / two-u32 LE decoding matches. |
| Work state | `F6`, empty | `GlassWorkStateRequest.java:6-14` | Raw state retained; this audit does not assign unverified meanings to work-state numbers. |
| Delete after import | `EB`, original filename UTF-8 | `InternalFileAbility.java:192-199` | CyanBridge deliberately retains originals. No delete/format command is sent by sync. |

Responses `1/2/3` to `E4/E5` mean busy/init failure/no space. A successful
recording-start reply does not mean that the finished file is already saved:
stop and wait for the saving-aware `E2` close before syncing.

### Wi-Fi payload

```text
01 01 <mode>
02 <SSID UTF-8 byte length> <SSID bytes>
03 <password UTF-8 byte length> <password bytes>
04 01 <channel>
```

The WearKit adapter defines modes `0` phone hotspot (glasses station), `1`
glasses-hosted AP, `2` Wi-Fi Direct. The low-level request's older `MODE_AP` /
`MODE_STATION` constants are not a reason to swap the adapter's modes.

## P2P sync sequence

Official references: `AbMateWifiP2pConnector.java:33-66` and
`P2pConnector.java:311-334,369-423`.

1. Generate `AiGlassDb_<0..9999>`.
2. Subscribe to `E7`, `EC`, and `F2` notifications before sending commands.
3. Send `E6` mode `2`, the generated SSID, empty password, channel `0`.
4. Send empty `E7`; retry only the vendor's busy statuses `1/3/5`.
5. Await `EC=2` and the six-byte `F2` peer address (up to 30 seconds).
6. Discover Android P2P peers and match the reported MAC **or** generated SSID.
   Do not fall back to an arbitrary peer, even if discovery returns only one.
7. Connect using WPS PBC. Set phone group-owner intent to `15` on Android 10+,
   `0` on older Android, matching the vendor.
8. Require a formed group containing the matched glasses. Find the Android
   `Network` corresponding to that group's interface and use its socket factory
   and DNS resolver for HTTP. No global process binding is needed.
9. Download from the glasses' `E7` endpoint. `groupOwnerAddress` remains an
   Android link diagnostic and is never substituted as the HTTP server address.
10. On Stop, failure, or completion: cancel HTTP, run `E2` cleanup, stop discovery,
    cancel negotiation, remove the group, close the channel, clear temporary
    files, then release the `MEDIA_SYNC` lease.

`TuneBudsFileStartup` owns command/notification ordering and deadline handling.
`TuneBudsP2pTransport` owns Android group negotiation/teardown; it does not call
the HeyCyan P2P singleton or its vendor reset commands.

## Transport selection and differences retained

- Ordinary dashboard sync tries P2P when the device reports Wi-Fi support bit
  `0x04`. With unknown capabilities it preserves the previously validated
  phone-hotspot route.
- If P2P transport setup fails or times out, it closes the camera and awaits P2P
  teardown before trying the phone hotspot. Terminal `E7` replies, including low
  battery, do not trigger a topology retry. Cancellation never starts a fallback.
- The hotspot compatibility path remains available even when capability flags
  are incomplete/inaccurate; the earlier hardware tests established it works on
  the tested `E1749` unit.
- If Android does not expose a routable P2P `Network`, this implementation reports
  failure and falls back rather than making unbound requests. Some Samsung builds
  may therefore continue using the working hotspot route.
- The official fallback order is **P2P → glasses AP → phone hotspot**
  (`InternalFileAbility.java:576-617`). The glasses AP mode-1 connector is still
  absent from CyanBridge; the implemented order is P2P → phone hotspot.
- Detailed AI-photo transfer keeps the previously validated hotspot flow. Its
  `E1 00 → delay → saving-aware E2 → E6 00 → E7` sequence remains distinct from
  the `E1 01 / E3` preview-photo flow.

## HTTP/media corrections

References: `MediaDownloadManager.java:175-189,259-346,495-520` and
`HttpTransManagerV2.java:50-96`.

- Preserve the notified endpoint's host, explicit port, and path prefix.
  When a port is omitted, the vendor downloader uses **8080**, not HTTP's default
  80. Preserve an explicit `:80` across repeated URL normalization.
- Strip accidental HTTP status/header lines before JSON-array / JSON-object /
  newline manifest detection. Keep original remote filenames for requests.
- Send `Connection: close` and `User-Agent: TSClient/1.0` for both manifests and
  binary media, not just manifests.
- Percent-encode filename segments; preserve directory prefixes and base URL
  query parameters. The extensionless `video-...` request stays extensionless;
  only the local saved name receives `.mp4`.
- Import JPG/JPEG as images, MP4 as video, and Opus/Ogg/WAV as audio through the
  existing `DCIM/CyanBridge` MediaStore importer. Valid `OggS` recordings pass
  through; WAV is stored as WAV. Raw Opus uses the existing wrapping fallback.
- Reject empty/truncated binary responses, remove partial temporary files, and
  cancel blocked HTTP reads promptly on Stop. No partial response reaches import.
- Monitor Bluetooth, `EC` failure values `0/4/5`, and hotspot/P2P link loss during
  transfer. Report photo/video/audio counts separately in the shared progress UI.
- Serialize dashboard multi-step commands so rapid recording toggles do not
  evaluate stale local flags concurrently. Sync is blocked while known recording
  or AI capture is active, and repeated Sync cannot start while cleanup owns its
  job/lease.

## Verification and hardware retest

Regression coverage includes immediate/reordered P2P notifications; empty E7
payload; busy/low-battery handling; missing peer/open signals; startup deadlines;
Wi-Fi failure during negotiation; MAC/SSID matching; JSON/text/header manifests;
explicit/default ports; actual local HTTP requests for all three media types;
vendor headers and encoded paths; truncated/empty files; cancellation of a
stalled audio download. Existing TuneBuds codec/action-gate, device-gating, and
shared player-lifecycle regressions are included in the focused Gradle run.

The focused run passed **39 tests** and `:app:assembleDebug`. The build/test log
is `/tmp/opencode/tunebuds-media-audit-verification.log`. The APK is
`app/build/outputs/apk/debug/app-debug.apk`.
The final incremental check also passed all 12 file-startup/HTTP tests and rebuilt
the APK successfully; log: `/tmp/opencode/tunebuds-media-audit-final-verification.log`.

No ADB device was connected during this review. Historical hardware successes
in the reverse-engineering notes establish the prior hotspot implementation,
not a hardware result for the new P2P path.

For a physical retest, force-stop the official app, then capture one saved photo,
one short video, and one short audio recording. Stop recordings before sync.
Verify files are playable, counts are reported correctly, originals remain on
the glasses, and another sync can start after Stop and after successful cleanup.
Repeat Stop during P2P discovery and during a large video download, then verify
the fallback hotspot path and Bluetooth/Wi-Fi loss cleanup.

```bash
adb logcat -s TuneBudsManager TuneBudsSpp TuneBudsP2p TuneBudsMediaSync DataDownload
```
