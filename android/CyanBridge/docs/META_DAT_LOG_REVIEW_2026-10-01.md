# Meta DAT log review — 2026-10-01

Report: `log_mukhte4r_6fb6f47a`, CyanBridge 2.3.0, Samsung SM-F976U,
Android SDK 37. Hardware DAT path (`debugMockEnabled=false`).

## Findings

- The recorded events cover **2026-09-28 00:00–00:12 UTC**. Commit `a13fffc`
  was made later that day, at 14:39 UTC. The log therefore predates that fix;
  the version label alone does not identify the installed source revision.
- Registration reaches `REGISTERED` and discovery returns a compatible wearable.
  There is no evidence in this report of an account/release-channel access block.
- `cameraPermission: All discovered devices are powered off or disconnected`
  occurs immediately after discovery. Discovery alone is not connection readiness.
  SDK 0.8.0 exposes `Device.linkState`; its `SpecificDeviceSelector` also requires
  `CONNECTED`. Wait for that link and retry transient permission transport errors.
- Later checks return `Granted`, but the snapshot still says `FAILED` with the
  old camera error. Successful permission checks previously did not clear it.
- The session reaches `STARTED`, then the new stream immediately changes
  `STARTING -> STOPPED -> CLOSED`. This matches the initial-STOPPED observer bug
  repaired in `a13fffc`. The fix is present in the current branch.
- `STREAM_ERROR` events are explicitly non-terminal in this SDK. They are logged
  as warnings; terminal errors and failed start operations remain failures.
- Display capability is false for this device, which is normal for camera-only
  glasses. Local-agent automation and accessibility are not prerequisites for DAT
  photo capture. The report does not establish a missing notification permission
  as the cause of the camera failure.

## Follow-up implementation

- Camera readiness now includes the selected DAT device's connection state;
  diagnostics include that state and current camera authorization.
- The manager attaches to an already-initialized process-wide SDK instead of
  treating `ALREADY_INITIALIZED` as a setup failure. The official SDK mock exposed
  this case because it initializes Wearables before the manager starts observing.
- Permission transport errors are retried within a bounded check. Actual denial
  returns to the UI for Meta's permission request; it is never converted to success.
- Setup checks run once per ready-device state/resume rather than on every UI
  refresh. Successful camera checks clear recovered camera errors and retain
  unrelated failures.
- Setup popups guide Android permissions, permanently denied app-settings
  permissions, Bluetooth, Meta registration, reconnection, and the separate Meta
  glasses camera authorization. Capture errors open an actionable setup dialog.
- Main and pairing screens share Android permission requirements, including
  Location for Android 10–11. Permission requests and camera checks are guarded
  against duplicate button presses.
- The debug UI mock is deterministic and debug-only. It simulates camera checks
  consistently, preserves a running preview during one-shot capture, and releases
  its lease/state on disable. Removed its speculative reflection-based activation
  of a second mock backend.
- CI also exercises Meta's **actual SDK MockDeviceKit** through the production
  manager, separately from the UI mock and controlled stream/display observer tests.
- That test found a current-build native packaging collision: `pickFirst` selected
  LibVLC 3.6.0's old `libc++_shared.so`, causing Meta's `libfbjni.so` to fail loading
  a `basic_stringstream` vtable at stream startup. A cached AAR transform removes
  only LibVLC's bundled STL, preserving VLC and using the modern shared runtime
  provided by the other native dependencies. The SDK mock test initializes LibVLC
  and DAT in the same process to catch this regression.

## Verification scope

- App unit tests: **583 passed**, zero failures/errors/skips.
- Shared portability tests: **80 passed**, zero failures/errors/skips.
- Meta emulator instrumentation: **21 passed**, covering lifecycle regressions,
  actual SDK mock photo capture, UI mock behavior, and guided setup dialogs.
- Debug app and instrumentation APK builds pass. The normal debug APK includes
  modern STL binaries for ARM64 and x86_64 with the required fbjni symbol.
- SDK mock logcat confirms `CONNECTED`, camera `Denied -> Granted`, a saved HEIC
  photo, session/stream cleanup, and `DISCONNECTED` after power-off.
- Evidence: `/tmp/opencode/meta-dat-review/final-checks.log`,
  `/tmp/opencode/meta-dat-review/verified-logcat.txt`, and
  `build/hil/results/instrumentation-emulator-*-Meta*.txt`.

Mock/emulator tests can verify app sequencing, permission routing, usable image
bytes, and cleanup. A successful physical connection on the reported Samsung/
Android 17 build still requires a hardware retest with updated CyanBridge and
Meta AI. Do not equate a mock capture with that hardware result.
