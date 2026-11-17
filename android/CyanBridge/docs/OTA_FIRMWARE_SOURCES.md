# OTA Firmware Sources

The Glasses dashboard's OTA section presents one combined update action. The normal
flow never exposes a chip selector, so users cannot choose BLE-first or flash only
one component:

- **Personal firmware files** opens Android's document picker twice. It requires a
  Wi-Fi/V821 `.swu` followed by a Bluetooth/JieLi `.bin`; both are copied into
  app-private storage before any OTA command is sent.
- **Stealth server copy** makes two requests with `channel=stealth`, one for each
  chip's exact current base.
- **Debug server copy** makes two requests with `channel=debug`. The relay rejects
  either request unless the authenticated relay account has the explicit
  `debugFirmwareAccess` entitlement.

The picker requires acknowledgement that the images match the connected glasses
model and both chips. It does not treat a filename extension as proof of image
compatibility.

For either server source, CyanBridge reads all four identifiers in one
`syncDeviceInfo` response, then makes one target-specific request for each chip. The
relay returns an artifact only when its catalog declares that exact base firmware
version, SHA-256 digest, and byte size. The app checks all three for both artifacts
before it starts OTA. A hardware-family match by itself is rejected. If either
request or download fails, neither component is flashed.

## Targets And Order

- V821 Wi-Fi/Linux firmware uses `.swu` via BLE OTA mode, Wi-Fi Direct, and the
  phone's HTTP server.
- JieLi BLE firmware uses `.bin` through the vendor `DfuHandle` state machine.

The decompiled official HeyCyan `OTAActivity` starts Wi-Fi OTA first. On a successful
Wi-Fi completion notification (`loadData[6] == 0x07` and `loadData[7] == 1`), its
*app* explicitly requests and starts the BLE `.bin` update. The glasses do not
autonomously begin BLE DFU because a `.swu` succeeded. The official activity has no
manual picker or chip-target selector in that normal path.

CyanBridge stages and validates the pair before starting. A successful `.swu` is
followed by P2P teardown and route cleanup, a fresh BLE/device-info readiness check,
and only then the `.bin` DFU. A Wi-Fi failure or an unavailable companion artifact
never starts BLE. After successful BLE finalization, the app invalidates stale BLE
firmware state, disconnects/reconnects, and requires another fresh device-info read
before reporting the pair ready. Personal files remain recovery/lab-only unless
their supplier documents compatibility.

When the relay returns HTTP `409` with
`error: "firmware_patch_unavailable"` for an exact-base lookup, CyanBridge offers a
Material 3 patch request dialog instead of substituting another version. The user may
cancel or enter a contact email. Sending the request uses the authenticated
`/logs/submit` relay endpoint and includes the requested target, both chip version
pairs, the relay response, and OTA/BLE/P2P logcat diagnostics. Other relay failures
do not open this dialog. The dialog explains that compatibility between unknown Wi-Fi
and BLE versions cannot be assumed and that review normally requires up to roughly
48 hours.

## Lifecycle Safeguards

- OTA owns the exclusive glasses session for the entire pair workflow, including
  catalog resolution, Wi-Fi teardown, BLE readiness checks, and final verification.
  This prevents concurrent media sync, preview, ADB debug, and background BLE
  command flows.
- The file types are bound to their transports: `.swu` cannot enter BLE DFU and
  `.bin` cannot enter Wi-Fi OTA.
- Server artifacts must match the reported base version, expected byte size, and
  SHA-256 before the OTA manager receives the staged file.
- The OTA manager holds a bounded partial wake lock for the active session, matching
  the official app's wake protection, and releases it after teardown.
- Hardware model, cryptographic signature, and cross-chip compatibility cannot be
  proved from arbitrary local files. Use only recoverable lab hardware for unvetted
  firmware.

## EyeVue And TuneBuds (dashboard family section)

EyeVue and TuneBuds use their own section (`ota/family/FamilyOtaCoordinator`, shared `FamilyOtaMachine`
and `FamilyFirmwareRules`); the HeyCyan section above is unchanged. Both install only the Bluetooth chip's
firmware; the camera (Wi-Fi) chip keeps its firmware.

- **Install a firmware file.** EyeVue: a `.ufw` or the Pro customization `.zip`. TuneBuds: the customization
  `.zip` only. Before anything is sent, the file must pass the container checks the owner's builders and the
  glasses run (EyeVue UFW header/list/flash/app CRCs and chip `AC701N`; TuneBuds POT header CRC16, `QUEN` tag,
  payload and trailer CRC32), and it must be for these glasses: EyeVue compares the model code in the image
  (or the pinned sha256 of a published image) with the glasses' cmd-100 code; a `.zip` must name the
  firmware version the glasses run. A bare TuneBuds `.fot` names no model, so it is refused.
- **Update to the latest official firmware (EyeVue).** The vendor server is public and anonymous:
  `GET https://platform.eyevue-glass.com/api/app/ota/getNewOtaPackage?deviceVersion=<v>&code=<model>`
  (fallback host `api.eyevueai.com`). Only a newer BLE package on an `*.aliyuncs.com` HTTPS URL is offered.
  The download must match the server's Content-Length and Content-MD5/ETag, and the sha256 of the mirrored
  images where the (model, version) is known (`FamilyFirmwareRules.knownEyevueImages`).
- **TuneBuds official updates** are not offered. The vendor server (`api.hetangsmart.com`
  `/v2/project/checkVersionV2`) is public, but it is keyed by a 4-hex project number taken from the BLE
  advert, whose byte layout has never been captured from real glasses; a wrong number would fetch another
  model's firmware.
- **Battery:** at least 50% (the EyeVue app's own limit; no TuneBuds limit is known). **Busy:** no update
  while media sync, live preview, another update (the `GlassesSession.OTA` lease) or a voice question,
  Gemini Live or a recording (`AudioSessionCoordinator`) runs. **Cancel:** before the transfer always; during
  it only where the glasses keep their firmware (EyeVue dual-bank via E4, TuneBuds always: it resumes).
