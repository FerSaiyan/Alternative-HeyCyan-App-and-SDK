# Experimental LensMoo / XKOENPRO support

This adds a separate Classic Bluetooth SPP transport and Android screen for LensMoo glasses. It does not use the HeyCyan BLE/Wi-Fi protocol or vendor AAR for this device family.

## Setup

1. Pair the glasses in Android Bluetooth settings. Enable the calls profile for microphone and speaker use.
2. Select the LensMoo device profile in CyanBridge. Device names containing `XKOEN` or `LensMoo` are recognized automatically.
3. In the LensMoo screen, select the bonded device and enter its existing LensMoo owner ID. This is the device authorization ID, not the LensMoo account password. Provisioning or discovering that ID is outside this implementation; it currently requires manual configuration.
4. For AI, configure and enable your own OpenAI-compatible endpoint and select Local Agent. The image and transcript are sent only to that configured backend; the LensMoo path does not silently fall back to another provider.
5. Enable the pocket assistant while the application is visible and grant microphone/Bluetooth permissions. Its foreground notification has a stop action. It can then receive the physical AI button while the phone screen is locked.

On the tested glasses, the upper button farther from the lens emits the AI event. The closer upper button is the photo button. Treat this mapping as device-specific.

## Supported behavior

- SPP authentication, device information, and battery checks.
- Explicit 320x240 H.264 preview, with a separate stop button.
- Original JPEG capture and saving to `DCIM/CyanBridge` without LensMoo image processing. Tested JPEG resolution: 1280x720.
- Attach the original photo to a CyanBridge AI conversation.
- Physical AI button: recognize speech through the glasses' HFP microphone, take one photo, stop the camera, download the JPEG, query the configured image-capable backend, and play the answer through the glasses.
- Native chat history retains the question and answer. Remote errors and failed captures do not become successful answers.

Connecting or enabling the assistant does not start preview. Camera shutdown must be confirmed by `5710=0` before media download and AI submission. The observed intermediate `5710=2` is not accepted as idle; the client waits up to 15 seconds before one bounded repeated stop command. A failed confirmation closes the session.

The assistant establishes a fresh authenticated SPP session before each capture. This is a conservative workaround for intermittent media-list failures on the tested firmware, not a claim that its complete internal state machine has been recovered.

## Audio behavior

HFP/SCO microphone and speaker routing requires Android 12 or later. The configured glasses must be available as a communication device; the code does not intentionally fall back to the phone microphone/speaker when that route is unavailable.

Speech synthesis writes a private temporary WAV; a MediaPlayer owned by CyanBridge then plays it with voice-communication attributes and the glasses as its preferred device. This matters because Android checks playback/recording activity for the UID owning the communication route. Direct playback by a separate TTS service caused reproducible route expiry on the tested phone.

Long answers are split at the TTS engine's input limit without dropping text or splitting surrogate pairs. Chunks play sequentially. Temporary audio is removed after completion, failure, or cancellation. An actual route loss pauses playback and permits one recovery; another loss stops with an explicit failure rather than continuing indefinitely.

## Build and tests

From `android/CyanBridge`, using JDK 17 and an installed Android SDK:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The optional Meta DAT dependency is compiled only when its authenticated dependency is configured. Without it, an explicitly unavailable adapter is compiled; it never pretends to connect to a Meta device. The real adapter and its SDK-dependent instrumentation test are retained in separate source directories.

To install a separate debug laboratory app alongside regular CyanBridge:

```sh
./gradlew :app:assembleDebug -PlensMooLab=true -PtestAbi=arm64-v8a
```

`lensMooLab=true` adds the `.lensmoolab` application ID suffix. The default application ID is unchanged.

Hardware tests are opt-in and are not ordinary unit tests. `LensMooDeviceControlTest#longAnswerUsesClientAudioPlayer` requires `lensmooAudio=true` and paired, configured glasses; it speaks a fixed test passage without a camera or AI request. `stopStaleAssistantRecording` requires `lensmooRadio=true`; it probes SDK command C104. The tested firmware did not answer C104, so it is not used by the assistant.

## Hardware evidence and limitations

Validated with XKOENPRO-12 firmware 1.0.2 and a Pixel 11 Pro XL running Android 16:

- Live preview and original JPEG download.
- Physical AI button, HFP speech recognition, camera shutdown, configured remote image response, and audio with a locked phone.
- Consecutive questions and a successful question after over 10 minutes idle. Earlier runs exposed intermittent media/camera failures; the shutdown wait fix was subsequently exercised with an actual `2` then `0` transition.
- 51 seconds of continuous test speech through SCO, followed by a complete 49-second real AI answer heard in the glasses. Android reported CyanBridge as the active playback owner throughout the test.

No firmware flashing, OTA, native video download, high-resolution live preview, wake-word recognition, or all-day battery validation is included. Other firmware, phone models, BLE headsets, Meta-enabled builds, and vendor SDK compatibility have not been hardware-validated here. The initial LensMoo screen is a separate Russian-language experimental UI rather than a complete localized shared dashboard integration.

Raw Bluetooth captures, original images/audio, credentials, owner IDs, API keys, and locally built APKs are intentionally not part of this change. The owner preference is excluded from Android backup; runtime diagnostics log protocol metadata and lengths rather than credential or speech payloads.
