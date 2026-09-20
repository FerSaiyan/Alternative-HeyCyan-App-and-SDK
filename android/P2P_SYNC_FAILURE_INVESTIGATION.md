# HeyCyan P2P Sync Failure Investigation

Date: 2026-09-18

## Scope and evidence

This investigation compares:

- the 82 retained Android support reports from the CyanBridge website database;
- the 55 reports categorized as `P2P/WiFi sync issue`;
- CyanBridge `v2.1.1` and `v2.3.0`;
- the decompiled official HeyCyan album-import and Wi-Fi Direct code.

The support reports are user-submitted failure reports. They do not include successful sync attempts, so the counts below describe the composition of reported failures, not a failure rate. No contact details or report IDs are included here.

## Main result

There is not one P2P defect. The reports fail at three distinct layers:

1. The glasses never expose transfer-mode evidence.
2. Transfer mode starts, but Android and the glasses do not establish a usable P2P/HTTP route.
3. P2P and the glasses IP are valid, but the glasses HTTP server returns `500` or closes `media.config` early.

Restarting the whole flow for every condition destroys useful state and cannot address all three layers. Recovery must begin at the last confirmed checkpoint.

## Failure clusters

The 55 P2P reports classify into mutually exclusive terminal snapshots:

| Terminal snapshot | Reports | Interpretation |
|---|---:|---|
| No transfer evidence and no P2P group after the general wait | 19 | BLE command/device state, discovery, or peer visibility failure |
| No command callback or independent evidence at the 10-second watchdog | 11 | The current official-mode watchdog stops before the 16-second discovery timer can finish |
| P2P group and BLE IP, but no reachable HTTP endpoint | 8 | Routing/network selection or glasses HTTP startup failure |
| `media.config` connection closed with `unexpected end of stream` | 7 | TCP/HTTP reached the glasses; this is not pairing failure |
| Other `media.config` downloader failure | 5 | HTTP-stage failure without enough retained detail |
| Transfer evidence but no P2P group | 3 | Glasses entered Wi-Fi mode, but Android negotiation did not complete |
| HTTP `500` from the glasses | 2 | Glasses firmware HTTP server was reachable but not ready/healthy |

For `v2.3.0` specifically, the 11 submitted P2P failures consist of four general no-evidence/no-group timeouts, three 10-second no-acknowledgement stops, one P2P-plus-IP/no-HTTP failure, two unexpected EOF responses, and one HTTP `500` response.

Thirty reports selected the HeyCyan-compatible flow, 15 selected the custom flow, and 10 older reports did not identify a flow. Among failure reports, the HeyCyan-compatible path commonly reaches HTTP but then fails there; the custom path more commonly retains a group/IP while failing route resolution. This does not establish that either flow has a higher overall success rate because successful attempts are not recorded.

## v2.1.1 versus v2.3.0

The core transport implementation is unchanged between the two releases:

- `WifiP2pManagerSingleton.kt` is byte-for-byte unchanged.
- `WifiP2pBroadcastReceiver.kt` is unchanged.
- `VendorAlbumDownloader.kt` is unchanged.
- `HeyCyanP2pPolicy.kt` is unchanged.
- `network_security_config.xml` is unchanged.
- The bodies of the relevant `MainActivity` transfer functions are unchanged, including `startDataDownload`, transfer-mode command retry, P2P callbacks, HTTP resolution, vendor media-list download, and error-255 handling.

The meaningful platform delta is `targetSdk 35` to `targetSdk 36`, alongside `compileSdk 35` to `36`. The failure-only support sample cannot show that this target change caused a regression. It should be retained as an experiment dimension for Android 16 devices, not treated as the established cause.

## Official HeyCyan comparison

The official app and CyanBridge share the same baseline protocol:

1. Register a command-type-2 device-notify listener.
2. Start peer discovery.
3. Send BLE command `[0x02, 0x01, 0x04]`.
4. Require both a formed P2P group and BLE notify `0x08` containing the glasses IP.
5. Wait one second and fetch `http://<glasses-ip>/files/media.config`.
6. Use WPS PBC and one internal discovery retry plus one connection retry.
7. On notify `0x09` error `255`, reset the glasses P2P state and return the UI to retry.

The official app itself has fragile behavior consistent with its poor pairing reviews:

- it relies on a small fixed retry budget;
- it performs whole-flow retries rather than checkpoint recovery;
- it uses plain system HTTP routing;
- its media-config downloader retries immediately, without a server warm-up backoff;
- it tears down or returns to retry on common errors.

CyanBridge currently reproduces much of that behavior in its HeyCyan-compatible mode. Reproducing it is useful as a baseline, but it should not be the final fallback on modern multi-network/VPN devices.

## Confirmed implementation weaknesses

### 1. The uploaded logcat stream has been empty since v2.1.1

All retained reports from `2.1.1`, `2.2.1`, `2.3.0`, and `2.3.2` contain the structured snapshot but no tagged logcat lines. The collector built one `logcat -s` command from tags including `DAT:CORE:RegistrationManager`. Logcat uses `:` as its tag/priority separator, so colon-bearing DAT tags can invalidate the filter command. Standard error was not captured, leaving an empty result.

The collector now uses a PID-scoped logcat dump with merged standard error. This restores event ordering for future reports and still limits collection to the CyanBridge process.

### 2. The 10-second command watchdog races the 16-second discovery state machine

If neither the SDK callback nor P2P/BLE-IP evidence arrives within ten seconds, HeyCyan-compatible mode tears down the session for manual retry. Peer discovery has a 16-second timeout and its own retry. A slow but viable device can therefore be stopped before discovery recovery executes.

The command callback is also not sufficient evidence of successful transfer mode. Some reports have a fast callback but no subsequent P2P or IP evidence. The callback `dataType` and `errorCode` are logged but not included in the structured snapshot.

### 3. HTTP failures are retried without delay or route adaptation

The HeyCyan-compatible downloader makes two immediate `media.config` attempts. A glasses HTTP server that is still starting returns the same EOF/500 twice before it has time to recover. On Samsung Android 16 reports, the P2P group and authoritative BLE IP were already present; resetting discovery cannot fix those failures.

The compatible flow also deliberately skips explicit P2P network binding. That matches the official app but gives up CyanBridge's route protections on modern multi-network and VPN devices.

### 4. Peer-list debouncing can suppress useful discovery retries

`lastPeerSetHash` ignores an unchanged peer set. `noMatchPeerCount` advances only when the peer set changes, not when a new discovery round completes. If the same TVs/printers remain visible, the intended no-match restart budget may never advance before the global 45-second timeout.

### 5. Current telemetry cannot measure improvement

Only manually submitted failures are retained. There is no success denominator, no duration per checkpoint, no transfer-command error in the structured snapshot, and no record of the recovery strategy used. A retry matrix cannot be evaluated safely from this data alone.

## Proposed checkpoint-driven adaptive recovery

Do not run a Cartesian product of all delays, resets, roles, and routes. Use a bounded state machine and change only the dimension relevant to the failed checkpoint.

### Implemented for CyanBridge Custom flow

The former **Custom flow** is now presented as **Adaptive flow**. The separate **HeyCyan app flow** remains vendor-like for comparison and fallback. The adaptive implementation currently:

- records the checkpoints below in a bounded in-memory event ring and includes the trace, BLE callback fields, route, retry counters, VPN state, and official-app installation state in support reports;
- lets the first 16-second Android discovery round finish instead of treating the 10-second BLE callback watchdog as terminal;
- resends transfer mode without reset at 12 seconds, restarts only Android discovery at 20 seconds, requests the BLE IP again at 28 seconds when a group exists, and permits at most one glasses reset at 36 seconds when no group exists;
- accepts only a paired-MAC or strong known-glasses peer match and no longer uses the weak hex-only peer fallback;
- preserves a formed group and tries verified P2P-network, P2P-local-socket, and system routes against only a device-reported IP;
- retries `media.config` on an absolute 1/2/4/8-second warm-up schedule with `Connection: close`, distinguishing an unreachable route, HTTP status, and truncated body;
- retries only a failed media file with 1/2/4-second backoff, rejects known-length truncated JPG/MP4/Opus bodies, and never resets P2P during file progress;
- stores a hashed phone/glasses profile containing the successful route and warm-up timing, promotes it after two matching successful syncs, and decays an unconfirmed candidate after two failed sessions.

The 85-second initial recovery budget is intentionally bounded. User cancellation, BLE disconnect, successful `media.config`, or teardown cancels pending adaptive recovery. Hardware validation across the reported phone families remains required before changing the HeyCyan-compatible flow.

### Checkpoints

Record monotonic timestamps and outcomes for:

1. `BLE_READY`
2. `TRANSFER_COMMAND_SENT`
3. `TRANSFER_CALLBACK` (data type and error code)
4. `TRANSFER_EVIDENCE` (P2P group or BLE `0x08`)
5. `MATCHING_PEER_FOUND`
6. `CONNECT_REQUEST_ACCEPTED`
7. `P2P_GROUP_FORMED` (phone role and group-owner address)
8. `BLE_IP_RECEIVED`
9. `P2P_NETWORK_IDENTIFIED` (network/interface/local addresses)
10. `TCP_80_CONNECTED`
11. `MEDIA_CONFIG_HEADERS` (status and content length)
12. `MEDIA_CONFIG_COMPLETE`
13. `MEDIA_FILE_PROGRESS`
14. `COMPLETE` or a typed terminal failure

Maintain a small in-memory/session-file event ring rather than relying only on logcat. Include it in support uploads even if logcat is unavailable.

### Recovery branches

#### A. No callback and no evidence

- Keep discovery alive through at least its 16-second timeout.
- At approximately 12 seconds, resend `[0x02,0x01,0x04]` once without destroying the Android P2P channel.
- If still absent after the discovery round, send `[0x02,0x01,0x0F]`, wait for callback arrival or a bounded timeout, reinitialize discovery, and resend transfer mode once.
- Stop after a bounded full-session budget and provide power-cycle/official-app-conflict guidance.

#### B. Callback received, but no P2P or BLE IP

- Record callback `dataType` and `errorCode`; do not treat callback arrival as success.
- Continue discovery rather than canceling at ten seconds.
- Retry the transfer command without reset first. Reset only if the next round still has no evidence.

#### C. BLE IP present, but no group

- Preserve the glasses transfer mode.
- Restart Android peer discovery/channel only; do not reset the glasses first.
- Match exact expected name, then MAC suffix, then a single strong model/MAC candidate. Never connect to an arbitrary TV/printer.
- Count completed discovery rounds, not peer-list changes.

#### D. Group formed and BLE IP present, but TCP/HTTP unavailable

- Preserve the group and BLE transfer mode.
- Try routes in this order: verified P2P `Network.openConnection`, socket bound to the P2P local address, then plain system routing as the official baseline.
- Re-evaluate the P2P `Network` after group formation instead of caching a null/early result.
- Probe only the authoritative BLE IP first. Subnet scanning is a final fallback and must verify `/files/media.config`, not merely port 80.

#### E. HTTP `500` or unexpected EOF

- Do not reset P2P.
- Close the failed connection and retry with server warm-up backoff, for example 1, 2, 4, and 8 seconds within a fixed budget.
- Disable connection reuse for the retry (`Connection: close`) so a half-closed firmware socket is not reused.
- Distinguish TCP failure, response-code failure, header success/body truncation, and complete body.
- Accept `media.config` only after the response body is complete and parseable.

#### F. Media file fails after config succeeds

- Keep the proven group, IP, and route.
- Retry only the current file with resumability if the server supports it; otherwise restart that file.
- Never issue a glasses P2P reset while another file is making progress.

### Bounded strategy budget

A safe first implementation should cap one user-visible sync attempt at roughly 75-90 seconds:

- at most three transfer-mode sends;
- at most two Android discovery-channel restarts;
- at most one glasses P2P reset before HTTP is proven;
- at most one full group teardown/recreate;
- no resets after `MEDIA_CONFIG_HEADERS` or file progress;
- immediate cancellation when the user stops or BLE disconnects.

### Per-device learning

Cache the last successful strategy using a key made from phone manufacturer/model/SDK plus glasses model/hardware/firmware. Start the next attempt with that strategy, but retain the baseline and bounded fallbacks. Promote a strategy only after repeated success and decay it after failures so a stale network environment is not permanent.

Candidate learned fields include:

- route method;
- successful server warm-up delay;
- whether Android channel restart was needed;
- whether a glasses reset was needed;
- observed phone/group-owner role;
- typical checkpoint durations.

Do not learn unsafe peer matching or unlimited retry counts.

## Measurement plan

Before changing protocol behavior broadly, record both success and failure outcomes (with user consent and no media names/content):

- random attempt/session ID;
- app version and strategy version;
- phone model and Android SDK;
- glasses model and firmware identifiers;
- checkpoint durations and typed failure;
- route strategy and retry counters;
- official HeyCyan package installed/running state if Android exposes it;
- VPN presence and selected P2P interface summary;
- final result and transferred byte/file counts.

Roll out in phases:

1. Restore logs and add checkpoint telemetry without changing recovery.
2. Add delayed HTTP retry and route adaptation for sessions already at P2P+IP.
3. Replace the 10-second destructive watchdog with coordinated command/discovery recovery.
4. Fix discovery-round accounting.
5. Enable per-device strategy learning only after enough success/failure data exists.

The highest-value first behavior experiment is HTTP-stage recovery because 14 of the 30 HeyCyan-compatible failure reports already reached the downloader. It can be improved without destabilizing BLE pairing or tearing down working P2P groups.
