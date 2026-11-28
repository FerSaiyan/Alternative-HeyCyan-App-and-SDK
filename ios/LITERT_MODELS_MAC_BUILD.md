# CyanBridge iOS: build the LiteRT model test (round 2)

These are instructions for the person, or the coding agent, building on the Mac. Read all of them
before starting. Round 1, the small `LiteRtGpuBench` app, worked: thank you. This round builds the
**real CyanBridge app**.

## Goal

Prove that the CyanBridge iOS app itself can download its vision, OCR and speaker models (YOLO11n,
PP-OCRv6, WeSpeaker) from Hugging Face and run them on the iPhone GPU. You build the app plus one test
and send Akio a zip. Akio runs it on Google's iPhones.

This branch also moves the app's on-device LLM runtime (LiteRT-LM) from 0.17.1 to 0.20.0, so this build
checks that it still compiles.

Branch: `ios-litert-models`

## What you need

- **Xcode.** Your 27.2 is fine: round 1 built with it and ran on Google's Xcode 26.2.
- **Signed in to Xcode with access to the CyanBridge team `MZ7DJZ88T2`.** That is the team you used in
  round 1. The app's bundle ID `com.fersaiyan.cyanbridge` and its Hotspot / Wi-Fi entitlements need it.
- **JDK 17.** Check with `/usr/libexec/java_home -v 17`. If it is missing: `brew install --cask temurin@17`,
  or use Android Studio's bundled JBR. Xcode runs Gradle to build the shared Kotlin framework.
- **An Android SDK.** Gradle needs one even for the iOS framework, because the shared module also builds
  for Android.
  - Install Android Studio, or `brew install --cask android-commandlinetools`, then `yes | sdkmanager --licenses`.
  - The script finds `~/Library/Android/sdk` or `$ANDROID_HOME` and writes `sdk.dir` into the git-ignored
    `android/CyanBridge/local.properties`.
- **cmake** (`brew install cmake`). The script uses it once to build `llama.xcframework` from source.
- **Network:** github.com, huggingface.co (the test downloads about 75 MB of models) and Maven/Gradle.
  Plan for about 10 GB of free disk, mostly Gradle caches.

## Steps

```bash
git fetch origin ios-litert-models
git checkout ios-litert-models

# 1. Pre-flight: builds the app for the iOS Simulator (unsigned) and runs only the LiteRT test.
#    The first run compiles the Kotlin framework and llama.cpp, so it takes 15-30 minutes.
ios/scripts/host_litert_sim_test.sh

# 2. The signed device archive for Google Device Run
DEVELOPMENT_TEAM=MZ7DJZ88T2 ios/scripts/build_host_xctest_zip.sh
```

**Step 1** must end with `** TEST SUCCEEDED **` and print `LITERT_SELFTEST PASS ...` lines.
- There is one `OK` line per file: 4 models and 2 data files.
- On the simulator the GPU lines run on the Mac's GPU, which is fine.
- If the test fails but the app built, send the `LITERT_SELFTEST` lines anyway (see "What to send"). They
  say which model failed and why.

**Step 2** writes `ios/out/CyanBridgeKMPHost-litert-xctest.zip` and a `.sha256` file next to it. Before
zipping, it checks:
- the app contains `CyanBridgeShared`, `CyanLiteRtModels`, `CLiteRT`, `LiteRtMetalAccelerator` and
  `CLiteRTLM`;
- the signature verifies;
- **every LiteRT call of the new framework binds to `CLiteRT`, not to LiteRT-LM's private copy.**
  Exit code 3 means that check failed. Send the output; don't work around it.

It also limits the test run to `testLiteRtModelsDownloadAndRun`. The older `testAppLaunches` is a UI-test
style method that this bundle type can't run.

## What to send Akio

1. `ios/out/CyanBridgeKMPHost-litert-xctest.zip` and its `.sha256`, through a private channel.
2. The end of the step 2 output (the "LiteRT 2.4.0 symbols bound to CLiteRT" line, archive, sha256 and
   BUILD_INFO).
3. The last ~40 lines of the step 1 output.
4. Any source change you had to make, as a `git diff`, or a commit on a new branch named
   `ios-litert-models-mac-fixes`, pushed.

## Rules for an agent doing this

- Run only the commands above, plus the installs they ask for: Xcode components, JDK 17, Android SDK
  tools, cmake, through Homebrew or Android Studio.
- **Allowed edits:** only to fix compile errors, and minimal. That covers Swift in
  `ios/CyanBridgeKMPHost/*.swift`, `ios/CyanBridgeKMPHostTests/*.swift`, `ios/LiteRtModels/**`,
  `ios/LocalModels/Sources/CyanLocalModels/*.swift`, and Kotlin in
  `android/CyanBridge/shared/src/iosMain/**/litert/`.
  - Don't change what the code does.
  - Don't edit the generated `LiteRtModelCatalog.kt`.
  - Report every edit.
- **Don't:**
  - change library versions (LiteRT-Swift 2.4.0, LiteRT-LM 0.20.0, llama.cpp tag);
  - add dependencies;
  - commit `ios/out/`, `local.properties` or `ios/LocalModels/Frameworks/`;
  - upload the archive anywhere public.
- **Signing problems** (team access, provisioning): stop and ask the Mac's owner or Akio.

## Troubleshooting

| Symptom | What to do |
|---|---|
| `missing: JDK 17` / `Android SDK` / `cmake` | Install what it names (see "What you need") and rerun. |
| Gradle: `SDK location not found` or license errors | `yes | sdkmanager --licenses` (Android SDK cmdline tools), then rerun. |
| Gradle fails inside Xcode with sandbox / permission errors | The scripts already pass `ENABLE_USER_SCRIPT_SANDBOXING=NO`; if you build from the Xcode UI, set that build setting too. |
| `No profiles for 'com.fersaiyan.cyanbridge'` / entitlement errors | Your Apple ID needs access to team `MZ7DJZ88T2`; ask Akio. |
| Swift compile error in `LiteRtModelBridgeImpl.swift` or `CyanLiteRtRunner.swift` | Minimal fix, report it. These files were written against LiteRT-Swift 2.4.0 (the same API round 1 compiled) and the Kotlin interface `LiteRtModelBridge` (Kotlin `Int` = Swift `Int32`, `Long` = `Int64`, `List<NSData>?` = `[Data]?`). |
| Swift compile error in `ios/LocalModels` (LiteRT-LM wrapper or `LiteRtTextModel.swift`) | This is the 0.17.1 → 0.20.0 update. Fix minimally and report it. Don't revert to 0.17.1. |
| Build step 2 exits with code 3 | LiteRT 2.4.0 calls bind to LiteRT-LM's copy. Send the output; Akio will decide. |
| Test fails with a download error | Check access to huggingface.co. Downloads are pinned to one repo revision and verified by SHA-256. |

## How it works (for reference)

- **Shared Kotlin, `android/CyanBridge/shared/src/commonMain/.../litert/`:** a generated catalog of the
  model files, with each one's pinned Hugging Face URL, size, SHA-256, tensors and how iOS should run it:
  - YOLO11n and WeSpeaker on GPU FP16;
  - the OCR detector on GPU FP16 with FP32 accumulation;
  - the OCR reader on the CPU.
  It also holds the platform-neutral self-test. Android can reuse all of it.
- **iOS Kotlin, `.../iosMain/.../litert/IosLiteRtModels.kt`:** downloads with NSURLSession, verifies size
  and SHA-256, and stores the files in Application Support, kept out of iCloud backups. It runs models
  through `LiteRtModelBridge`.
- **Swift:**
  - `ios/CyanBridgeKMPHost/LiteRtModelBridgeImpl.swift` implements the bridge.
  - `ios/LiteRtModels` is a dynamic framework over LiteRT-Swift 2.4.0 with the Metal accelerator. It is
    dynamic so its LiteRT calls bind to LiteRT 2.4.0's `CLiteRT` and not to the LiteRT copy inside
    LiteRT-LM's `CLiteRTLM`.
- **The test:** `CyanBridgeKMPHostTests.testLiteRtModelsDownloadAndRun`. You can also run the app with the
  environment variable `CYANBRIDGE_LITERT_SELFTEST=1`. Either way, the report goes to
  `Documents/litert-selftest.txt`.
