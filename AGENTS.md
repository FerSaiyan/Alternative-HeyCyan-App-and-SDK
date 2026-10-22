# AGENTS.md - HeyCyanSmartGlassesSDK

## Purpose
Software Development Kit (SDK) for HeyCyan Smart Glasses. Contains core libraries and sample applications for integrating features like camera access, sensor data, and display control.

## Project Structure
- `android/`: Android-specific SDK code and demo apps.
- `ios/`: iOS-specific SDK code and sample projects.
- `examples/`: Cross-platform or specific usage examples.
- `firmware_dump/`: Hardware firmware reference or dumps.
- `QCSDK.framework/`: Pre-compiled framework for iOS development.

## Android Data Transfer Notes

- Media transfer (BLE + Wi-Fi Direct HTTP) reverse-engineering notes live in `android/AGENTS.md`.

## Development Environment
- **Conda Environment:** `LocalGPT_llama2`
- **STRICT RULE:** Never use `pip install` or download libraries into the `base` conda environment.

## Execution Rules
- Review `WIFI_TRANSFER_ARCHITECTURE.md` before working on data transmission features.
- Ensure any demo app changes are tested against the physical device if available.

## Compute and Storage Policy
- **Do not use the shared `luciano` host** for runtime patch builds (LiteRT-LM, llama.cpp), Gemma 4 E2B fine-tunes, merges or `.litertlm` exports. Its NVMe is shared with other students. Run these on the local workstation instead: 14 cores / 28 threads, 62 GB RAM, RTX 4060 Ti 16 GB.
- **Use the local cores.** Bazel at `JOBS≈20` with a RAM cap (see `android/CyanBridge/tools/litert-decisions/build_aar.sh`). Earlier remote speedups came from parallelism, not the host.
- **Storage.** Large artifacts (models, bundles, AARs, datasets, reports) go on Seagate (`/mnt/seagate/ML/…`). Verify every copy with SHA-256. Run bulk copies, hashing and other background disk work on the Seagate HDD under `ionice -c3` (idle class); interactive Gradle/Bazel builds keep normal priority.
  - The Seagate (`sda`) uses the `mq-deadline` scheduler, which **ignores ionice classes**. For idle priority to work, switch to BFQ (`sudo modprobe bfq && echo bfq | sudo tee /sys/block/sda/queue/scheduler`). Otherwise pause long bulk copies (`kill -STOP` / `kill -CONT`) while building.
- **Gradle.** The worktree lives on the HDD, so builds are I/O-bound: a full x86_64 rebuild took 92 min under concurrent rsyncs, with system-wide I/O pressure at about 50%. Redirect build outputs to the NVMe with a per-invocation init script (`-I ~/.cache/cyanbridge-gradle/nvme-build.gradle`, which sets `layout.buildDirectory` under `~/.cache/cyanbridge-gradle/build`). For real speed, keep the active Android worktree on the NVMe.
- **Bazel caches** may use the NVMe temporarily; expunge them after use.
- **Qualcomm NPU.** The app ships exactly one QNN runtime: **QAIRT 2.50.0.260828**. It matches LiteRT 2.3.0 and the LiteRT that LiteRT-LM 0.18 pins.
  - Compile NPU models with `~/nvme_tmp/npu-litert230/env` (ai-edge-litert 2.3.0). The older `recipe_env` uses QAIRT 2.47, whose runtime libraries the 2.3.0 dispatch library rejects.
  - Before any device run, check the compiled context's SoC/arch/build (`android/CyanBridge/tools/litert-npu/inspect_aot_context.py`) and HTP-emulator parity (`htp_emulator_parity.py`): a full-offload compile alone is not proof. See `android/CyanBridge/docs/NPU_ON_DEVICE.md`.
  - **Device first for NPU tests.** When the Fold6 is attached, run NPU accuracy and latency checks on it: dump raw NPU outputs with the device tests and score them on the host. 200 YOLO frames take seconds there, versus hours in the x86 HTP emulator, and the phone matched the emulator exactly. Use the emulator only without a phone or to bisect a graph. The AVD has no NPU, so it only covers CPU paths and skips.
- **Device tests.** Use the Pixel 9a AVD when no phone is attached. The Galaxy Z Fold6 (`RQCX700KSDF`) is approved for adb tests: pass `adb -s <serial>` when more than one device is attached, and use `install -r` only, never uninstall.
