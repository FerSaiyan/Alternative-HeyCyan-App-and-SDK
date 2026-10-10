<p align="center">
  <img src="android/CyanBridge/CyanBridge_StyledB_transparent.png" alt="CyanBridge" width="120">
</p>

# CyanBridge: Local AI for Smart Glasses

CyanBridge is an Android companion and device-integration project for AI smart glasses.

It started as a working Android alternative for HeyCyan-compatible glasses and has grown into a bridge between smart glasses and the AI stack you want to use: local models on your phone, Gemini Live, self-hosted servers, and optional hosted models.

Local inference does not require a CyanBridge subscription.

[![Android CI](https://github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK/actions/workflows/android-self-hosted.yml/badge.svg?branch=main)](https://github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK/actions/workflows/android-self-hosted.yml)
[![Latest release](https://img.shields.io/github/v/release/FerSaiyan/Alternative-HeyCyan-App-and-SDK)](https://github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK/releases/latest)
[![GitHub stars](https://img.shields.io/github/stars/FerSaiyan/Alternative-HeyCyan-App-and-SDK?style=flat)](https://github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK/stargazers)

[Website](https://cyanbridgelabs.com/) ·
[Google Play](https://play.google.com/store/apps/details?id=com.fersaiyan.cyanbridge) ·
[Latest release](https://github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK/releases/latest) ·
[Meta Ray-Ban beta](https://cyanbridgelabs.com/beta) ·
[Compatibility matrix](https://cyanbridgelabs.com/smart-glasses/compatibility)

> **Own Ray-Ban Meta glasses?** Meta support is moving quickly through Meta's Device Access Toolkit early-access program. Join the [CyanBridge Meta Ray-Ban beta](https://cyanbridgelabs.com/beta) with the email attached to your Meta account so we can send you a DAT invite when a tester slot is available.

## What you can do

- Run LLMs locally on Android with **LiteRT-LM** and **llama.cpp**.
- Download curated Gemma and Qwen models or import your own `.gguf`, `.litertlm`, and `.task` models.
- Use **Gemini Live** with real-time voice and supported glasses-camera context.
- Connect CyanBridge to an **OpenAI-compatible server** you control.
- Ask questions about photos captured from supported glasses.
- Sync photos, videos, and recordings from HeyCyan-compatible devices.
- Use hands-free tools for translation, captions, meetings, visual notes, accessibility, and phone automation.
- Build against the device integrations and shared Android modules in this repository.

The Android app in [`android/CyanBridge/`](android/CyanBridge/) is the main product and development target.

## Local AI on your phone

CyanBridge can run inference directly on Android.

| Runtime | Model format | Use |
| --- | --- | --- |
| LiteRT-LM | `.litertlm`, `.task` | Gemma and multimodal on-device models |
| llama.cpp | `.gguf` | Qwen and other compatible GGUF language models |
| OpenAI-compatible endpoint | HTTP API | Ollama, llama.cpp server, Model Studio, or another server you control |

Local models support configurable context size, sampling settings, CPU/GPU execution, GPU layer offload, model-specific prompt templates, and streaming generation. CyanBridge can fall back to CPU when a requested GPU configuration cannot start.

See [Local Models](android/CyanBridge/docs/local-models.md) for formats, model setup, runtime options, and import instructions.

## Free Gemini Live

Free CyanBridge accounts can use **five Gemini Live sessions per UTC day**, with each session lasting up to **five minutes**.

Gemini Live supports real-time voice conversations and can receive fresh visual context from compatible glasses integrations. CyanBridge also has Economy and Private Live modes for paid accounts.

Local models remain available without a subscription, so Gemini Live is an option rather than a requirement.

## Smart glasses support

CyanBridge keeps device-specific protocols separate. Hardware, firmware, permissions, and vendor APIs determine which features each pair of glasses can expose.

| Glasses / platform | Status | CyanBridge work |
| --- | --- | --- |
| **HeyCyan-compatible glasses** | Supported workflow | BLE connection, device controls, media transfer, image questions, and glasses-aware AI workflows |
| **Ray-Ban Meta / Meta Ray-Ban** | Experimental | Meta DAT registration, device sessions, camera streams, photo capture, capability checks, and active hardware testing |
| **EyeVue** | Experimental | Android connection, media, capture, and device-control work |
| **Meizu MYVU / Star Air** | Experimental | Native BLE/RFCOMM/display integration based on hardware-tested protocol work |
| **MoYoung / W620** | Experimental | Device-specific Android integration |
| **TuneBuds** | Research | Dedicated protocol and media/capture research |
| **MemoMind / XGIMI** | Research | Transport, device state, cards, notifications, and display-oriented protocol research |

Check the [live compatibility matrix](https://cyanbridgelabs.com/smart-glasses/compatibility) for the public device-by-device status.

### Ray-Ban Meta

The Android integration uses Meta's Device Access Toolkit.

Current code covers DAT registration state, device discovery and metadata, device sessions, camera streams, photo capture, display-capability detection, and camera access for features such as Visual Diary and Walking Aid.

Meta controls access to third-party wearable integrations while DAT remains in preview. If you own Ray-Ban Meta glasses, request access at:

**https://cyanbridgelabs.com/beta**

Use the email connected to your Meta account and glasses.

Technical notes are in [`meta_rayban_mvp.md`](android/CyanBridge/meta_rayban_mvp.md).

## HeyCyan media sync

CyanBridge transfers media from compatible HeyCyan glasses over the local device connection.

```text
Glasses
   │
   ├── BLE ──────────────► connection + transfer commands
   │
   └── Wi-Fi Direct ─────► photos / video / audio
                              │
                              ▼
                         Android MediaStore
```

The Android path uses BLE to enter transfer mode and obtain the glasses network information. Wi-Fi Direct carries the files from the glasses media server into Android storage.

Protocol details and implementation notes live in [`android/AGENTS.md`](android/AGENTS.md).

## Built-in tools

CyanBridge includes smart-glasses and phone workflows such as:

- **Local Agent** for supervised phone automation
- **Walking Aid** for vision-assisted environmental context
- **Meeting Spark Notes**
- **Live Caption Relay**
- **Hands-Free Translator**
- **Errand Brain**
- **Auto Diary**
- **Auto Audio**
- **Visual Diary**

Camera, microphone, media, and display capabilities depend on the selected glasses integration.

## Install CyanBridge

### Google Play

For normal Android installation and automatic updates:

[**Get CyanBridge on Google Play**](https://play.google.com/store/apps/details?id=com.fersaiyan.cyanbridge)

### APK

Signed APKs and SHA-256 checksums are also published with GitHub releases:

[**Download the latest CyanBridge release**](https://github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK/releases/latest)

## Build from source

The Android project requires Java 17 or newer.

```bash
git clone https://github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK.git
cd Alternative-HeyCyan-App-and-SDK/android/CyanBridge

JAVA_HOME=/path/to/jdk17 ./gradlew assembleDebug
```

The current Android build resolves Meta DAT packages through GitHub Packages, so builds that include Meta support need GitHub package credentials.

Run the Android unit tests with:

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew testDebugUnitTest
```

The repository also contains Android emulator, hardware-in-the-loop, and iOS KMP CI workflows.

## Repository layout

| Path | What is there |
| --- | --- |
| [`android/CyanBridge/`](android/CyanBridge/) | Main CyanBridge Android app |
| [`heycyan-core/`](heycyan-core/) | Shared HeyCyan Android modules and API boundaries |
| [`android/AGENTS.md`](android/AGENTS.md) | HeyCyan protocol and media-transfer notes |
| [`android/CyanBridge/docs/local-models.md`](android/CyanBridge/docs/local-models.md) | LiteRT-LM and llama.cpp local inference |
| [`android/CyanBridge/meta_rayban_mvp.md`](android/CyanBridge/meta_rayban_mvp.md) | Meta DAT implementation and integration notes |
| [`BRIDGE_RESEARCH_NOTES.md`](BRIDGE_RESEARCH_NOTES.md) | Smart-glasses protocol research |
| [`ios/`](ios/) | KMP/iOS host and vendor integration work |
| [`examples/`](examples/) | Integration examples |
| [`tools/`](tools/) | Development, testing, and hardware tooling |

## Star history

If CyanBridge is useful to you, a star helps other smart-glasses developers find the project.

[![Star History Chart](https://api.star-history.com/svg?repos=FerSaiyan/Alternative-HeyCyan-App-and-SDK&type=Date)](https://star-history.com/#FerSaiyan/Alternative-HeyCyan-App-and-SDK&Date)

## Contributing

Device testing is especially useful.

If you own HeyCyan, EyeVue, TuneBuds, MoYoung, MYVU, Ray-Ban Meta, MemoMind, or another pair of AI glasses, reports with the exact model, firmware version, Android version, logs, and reproducible steps can help turn experimental integrations into reliable ones.

Code contributions around device protocols, local inference, Android/iOS integration, tests, accessibility, and documentation are welcome.

Use [GitHub Issues](https://github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK/issues) for bugs, hardware reports, and feature requests.

## Related projects

CyanBridge has learned from and interoperated with work from independent open-source projects:

- [Meizu MYVU Client](https://github.com/Panny777/Meizu-Myvu-Client) by Panny777
- [OpenVision](https://github.com/rayl15/OpenVision) by rayl15
- [private-agent](https://github.com/orailnoor/private-agent) by orailnoor

Check each upstream project for its license and attribution requirements.

## Licensing

CyanBridge is a mixed-license repository. Original CyanBridge code and
documentation are licensed under [Apache License 2.0](LICENSE) unless a more
specific notice applies.

Third-party code, vendor SDKs, binaries, and model artifacts keep their original
licenses or terms. The root Apache-2.0 license does not override those notices,
and a distributed app may have obligations from linked components such as the
GPL-3.0 MoYoung SDK.

See [LICENSING.md](LICENSING.md) for the repository rules and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the main exceptions.
