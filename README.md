# Alternative HeyCyan App and SDK

This repository is the source workspace for CyanBridge's Android companion,
HeyCyan vendor integration, and smart-glasses interoperability research.

It is not a finished, drop-in SDK for every pair of glasses. The active product
path is the Android app in [`android/CyanBridge`](android/CyanBridge). The rest
of the repository includes vendor references, reusable modules, prototypes, and
research needed to support more devices without hiding their limitations.

## Start here

| If you want to... | Start with... |
| --- | --- |
| Build or use the Android companion | [`android/CyanBridge/README.md`](android/CyanBridge/README.md) |
| Connect and sync media from HeyCyan glasses | [`android/AGENTS.md`](android/AGENTS.md) |
| Work on shared Android modules | [`heycyan-core/README.md`](heycyan-core/README.md) |
| Build the iOS shell or inspect the vendor demo | [`ios/README.md`](ios/README.md) |
| Investigate MemoMind/XGIMI protocol support | [`BRIDGE_RESEARCH_NOTES.md`](BRIDGE_RESEARCH_NOTES.md) |

## What CyanBridge does today

### Android companion

The Android app is the most complete part of this repository. It currently
includes:

- HeyCyan device scanning, pairing, connection management, and device state.
- Media sync from compatible HeyCyan glasses: BLE starts transfer mode, Wi-Fi
  Direct carries the files, and photos, videos, and supported recordings are
  saved to Android media storage.
- Local chat history, configurable local-model runtimes, and optional
  OpenAI-compatible remote inference.
- Meeting capture, transcription and summarization plumbing, notes, privacy
  settings, data backup/export, and local-data cleanup controls.
- A CyanBridge Model Studio bridge that can announce Studio session events and
  handle its internal approval requests through TTS, speech recognition, and a
  fail-closed allow/deny response.

The app must be tested with real glasses before a device-specific feature is
considered reliable.

### Device and platform status

| Area | Current status | Notes |
| --- | --- | --- |
| HeyCyan Android path | Active | BLE connection and the BLE plus Wi-Fi Direct media-transfer flow are the primary supported path. |
| HeyCyan vendor controls | Device-dependent | The bundled vendor AAR exposes camera, recording, device-info, and media commands. Validate each command on physical hardware. |
| CyanBridge local and remote chat | Included | The app contains local runtime support and an OpenAI-compatible remote-server option. Model availability depends on the phone and configuration. |
| CyanBridge Model Studio bridge | Experimental | Relays Studio events and approval requests over an authenticated WebSocket. It is not a substitute for reviewing desktop work. |
| MemoMind/XGIMI | Experimental research | RFCOMM framing, device info, battery, cards, notifications, and selected settings are mapped. The adapter still needs sustained physical-device validation. |
| Meta Ray-Ban | Partial setup only | Optional registration plumbing exists when the Meta DAT SDK is available. Sessions, camera streaming, photo capture, and display rendering are explicitly not implemented. |
| Even/Mentra runtimes | Prototype | Adapter and runtime experiments are present, not a supported consumer device path. |
| iOS | CI-validated host | A simulator-targeted KMP host is built and tested via a GitHub Actions macOS workflow (framework link, Xcode compilation, simulator launch, screenshot); the vendor QCSDK path still requires a physical device and needs hardware validation. |

## Build the Android app

Use Android Studio's bundled JDK or another Java 17+ JDK:

```bash
cd android/CyanBridge
JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
```

Run unit tests with:

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest
```

Android and shared-code CI runs on the local Linux Mint GitHub Actions runner.
See [`docs/SELF_HOSTED_RUNNER.md`](docs/SELF_HOSTED_RUNNER.md) for the
no-`sudo` runner service and maintenance commands.

For device integration, use a physical Android phone with Bluetooth and the
required nearby-device, microphone, notification, and Wi-Fi permissions. The
Android emulator cannot validate glasses pairing or media transfer.

## How HeyCyan media sync works

The supported transfer path is intentionally simple:

1. Connect to the glasses over BLE.
2. Ask the glasses to enter transfer mode and report their Wi-Fi address.
3. Join the Wi-Fi Direct network.
4. Read `http://<glasses-ip>/files/media.config`.
5. Download each listed file from `http://<glasses-ip>/files/<filename>`.
6. Store photos, videos, and compatible audio in Android media storage.

See [`android/AGENTS.md`](android/AGENTS.md) for the confirmed command sequence,
network-routing requirements, and audio-format caveats. Do not substitute the
phone's Wi-Fi Direct group-owner address for the glasses address.

## Repository map

| Path | Purpose |
| --- | --- |
| `android/CyanBridge/` | CyanBridge Android app and the primary development target. |
| `android/glasses_sdk_20250723_v01.aar` | Vendor Android SDK artifact used by the HeyCyan path. |
| `android/HeyCyanOfficialApp/` | Decompiled vendor app used as protocol reference. |
| `heycyan-core/` | Shared Android modules for BLE, connectivity, data, audio, and API boundaries. |
| `ios/CyanBridgeKMPHost/` | Simulator-capable SwiftUI host for the shared KMP framework. |
| `ios/QCSDKDemo/` | Vendor iOS demo and device-only protocol reference. |
| `BRIDGE_RESEARCH_NOTES.md` | Detailed MemoMind/XGIMI transport and protocol findings. |
| `WIFI_TRANSFER_ARCHITECTURE.md` | Historical technical background for the HeyCyan transfer design. |

## Upstream projects and acknowledgements

CyanBridge is made possible by the work of other open-source developers. Please
visit these projects, star the repositories, follow their maintainers, and
consider donating or sponsoring them through any support links in their
repositories or profiles:

| Project | How it contributed |
| --- | --- |
| [Meizu MYVU Client](https://github.com/Panny777/Meizu-Myvu-Client) by [Panny777](https://github.com/Panny777) | Hardware-verified MYVU / Star Air protocol client. Its BLE, ECDH, RFCOMM relay, heartbeat, and display transport are used by the native MYVU integration. |
| [OpenVision](https://github.com/rayl15/OpenVision) by [rayl15](https://github.com/rayl15) | Important reference for the Meta Ray-Ban integration direction, wearable connection architecture, and glasses-based AI workflows. |
| [private-agent](https://github.com/orailnoor/private-agent) by [orailnoor](https://github.com/orailnoor) | Inspiration for CyanBridge's local-agent architecture, especially the Accessibility-based observe, decide, execute, and observe loop. |

These projects remain independent works with their own licenses and
maintainers. See each repository for its licensing, contribution, and support
information. If you use or benefit from them, a star, a follow, a useful issue
or pull request, and financial support where available are meaningful ways to
give back.

## Privacy and safety

- Keep pairing, recording, transfer, and notification permissions explicit.
- Review the app's privacy settings before enabling capture, transcription, or
  desktop approval bridging.
- The HeyCyan transfer server uses local HTTP over the direct device network;
  do not expose it to an untrusted network.
- Do not send unknown protocol commands or OTA payloads to personal hardware.
- Treat experimental device adapters as research until they have repeatable,
  documented hardware tests.

## Vendor material and licensing

The bundled `.aar`, `QCSDK.framework`, decompiled vendor apps, firmware files,
and protocol notes are not a promise that their underlying vendor components are
open source or redistributable. Review the relevant vendor terms and applicable
law before distributing, modifying, or using them outside personal research and
development. This repository does not currently provide a single project-wide
license for all included material.
