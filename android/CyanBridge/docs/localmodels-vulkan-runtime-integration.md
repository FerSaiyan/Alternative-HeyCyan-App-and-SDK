# Local Models Vulkan Runtime Integration

This app uses `io.github.ljcamargo:llamacpp-kotlin:0.4.0` and prefers GPU execution, with an automatic CPU fallback when GPU initialization is unavailable.

This guide describes the "build your own runtime" path so we can ship a custom Android AAR with Vulkan-enabled llama.cpp and load it in this app without changing the chat product surface.

## 1) Outcome

- Build a custom llama runtime AAR with Vulkan backend support.
- Keep app code unchanged at call sites (same `org.nehuatl.llamacpp` API surface).
- Switch runtime at build time via Gradle property:
  - `-PlocalLlamaRuntimeAarPath=app/libs/local-llama/custom-llama-runtime.aar`

## 2) Prerequisites

- Android Studio + SDK/NDK installed.
- CMake + Ninja on the build host.
- Vulkan-capable target device (Android 11+ recommended, Vulkan 1.2 preferred).

## 3) Build a custom runtime AAR (fork)

Use `ljcamargo/kotlinllamacpp` as a starting point because its Kotlin/JNI package shape matches the current app integration.

Recommended high-level fork tasks:

1. Fork and clone `https://github.com/ljcamargo/kotlinllamacpp`.
2. Update native backend wiring to a llama.cpp revision with Android Vulkan support.
3. Ensure CMake enables Vulkan backend at native build time (and links `vulkan`).
4. Preserve Java/Kotlin package and JNI method signatures (`org.nehuatl.llamacpp`) so app code remains compatible.

Backend flags to ensure in the fork's native CMake/Gradle flow:

- `GGML_VULKAN=ON`
- `CMAKE_BUILD_TYPE=Release`
- `ANDROID_ABI=arm64-v8a`

Optional compatibility flag:

- `GGML_OPENCL=ON` for devices where OpenCL path is better supported than Vulkan.

Build the forked AAR (example):

```bash
JAVA_HOME="/opt/android-studio/jbr" ./gradlew :llamaCpp:assembleRelease
```

Expected output (path may vary by fork layout):

- `llamaCpp/build/outputs/aar/llamaCpp-release.aar`

## 4) Integrate into this app

This repo now supports local runtime override in `app/build.gradle` via `localLlamaRuntimeAarPath`.

### Option A: helper script

```bash
./scripts/localmodels/use_local_llama_runtime.sh /absolute/path/to/llamaCpp-release.aar
JAVA_HOME="/opt/android-studio/jbr" ./gradlew :app:assembleDebug -PlocalLlamaRuntimeAarPath=app/libs/local-llama/custom-llama-runtime.aar
```

### Option B: direct property

```bash
JAVA_HOME="/opt/android-studio/jbr" ./gradlew :app:assembleDebug -PlocalLlamaRuntimeAarPath=/absolute/path/to/llamaCpp-release.aar
```

If the file is missing, Gradle now fails fast with a clear error.

## 5) App runtime expectations

This app already supports selecting compute backend and GPU layers in Local Models settings.

- If GPU init succeeds, generation runs in GPU mode.
- If GPU init fails, app falls back to CPU with explicit status/fallback reason.

Manifest is prepared with optional native loader declarations:

- `libvulkan.so`
- `libOpenCL.so`

## 6) Verification checklist

1. Install debug build on a Vulkan-capable device.
2. Confirm Local Models -> Generation Settings uses the default GPU backend and automatic GPU-layer selection.
3. Run warm-up probe and confirm backend in result line.
4. Confirm no fallback message appears for GPU-capable devices.
5. Send a normal chat prompt and validate status badge shows GPU generation path.

## 7) Rollback

To return to the published runtime:

- Build without `localLlamaRuntimeAarPath`.
- Or remove the local AAR property from your command/CI.
