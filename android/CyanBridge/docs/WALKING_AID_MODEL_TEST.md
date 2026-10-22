# Walking Aid on-device real-model CI

The self-hosted Linux CI runs the production Walking Aid LiteRT stack inside an x86_64 Android
emulator. It executes all current local Walking Aid models rather than mocking inference:

- YOLO11n TFLite detection.
- YOLO-World quantized TFLite detection.
- Depth Anything 3 Small FP16 TFLite depth estimation.
- YOLO11 over EyeVue headless LibVLC video frames, with production rotation.
- YOLO11 over Meta DAT video frames delivered by actual `MockDeviceKit`.

It uses two real JPEG scenes (`bus.jpg` and `zidane.jpg`) from the Ultralytics assets repository.
They are decoded from files in the same way as completed JPEG thumbnail transfers from the glasses.
URLs are pinned to a Git commit, and every image/model has an exact size and SHA-256 in
`tools/hil/walking_aid_assets.tsv`. Models and source JPEGs are downloaded into the host cache
and are not committed to Git or attached to CI artifacts. Small derived H.264/HEVC clips are packaged
in the test APK; provenance and checksums are in `app/src/androidTest/assets/walkingaid/README.md`.
Ultralytics assets are offered under AGPL-3.0; model-specific licensing remains documented in
`WalkingAidModelCatalog`.

For the EyeVue video phase only its BLE/Wi-Fi connection URL is substituted with a test clip;
the production decoder, headless output, rotation, Walking Aid acquisition and latest-frame queue
are used. Meta uses the actual DAT SDK mock and production manager/session. Both video phases
require multiple fresh frames and detections from both scenes (person, bus, and tie), then verify
session/transport cleanup. They run in separate instrumentation processes alongside the three
existing model phases. Build with Meta DAT dependencies enabled for these phases.

## Run locally on Linux

Build/install the app and instrumentation APKs, then run the wrapper:

```bash
cd /path/to/HeyCyanSmartGlassesSDK
export JAVA_HOME=/opt/android-studio/jbr

cd android/CyanBridge
./gradlew -PtestAbi=x86_64 :app:assembleDebug :app:assembleDebugAndroidTest
cd ../..

serial="$(bash tools/hil/start_walking_aid_emulator.sh)"
bash tools/hil/install.sh "$serial"
bash tools/hil/run_walking_aid_model_ci.sh "$serial"
```

The wrapper downloads assets once into
`${XDG_CACHE_HOME:-$HOME/.cache}/cyanbridge/walking-aid-model-ci`, verifies them, streams them into
the debuggable app sandbox, runs instrumentation, and removes only test-owned files afterward. If a
user already installed a different file under one of the model names, it is backed up and restored.
It never clears CyanBridge app data, preserving any Pro/HIL account configuration.

## Emulator storage

The debug APK is currently about 157 MiB and the three models occupy about 126 MiB. The dedicated
emulator launcher creates `CyanBridge_Walking_Aid_CI` with an 8 GiB data partition and 4 GiB RAM,
using the locally installed API 36 x86_64 Google Play system image. This avoids replacement-install
failures on a nearly full general automation AVD. The model wrapper requires the model bytes plus
256 MiB free for app and LiteRT working data. It removes stale Walking Aid CI fixtures and asks
Android to trim caches. If `/data` remains too small, it fails before copying a partial model.

For a manually created model-test AVD, `config.ini` should contain this before its first boot:

```ini
disk.dataPartition.size=8G
```

Do not use `-wipe-data` on the licensed Tasker/AutoInput automation AVD. The launcher never modifies
that AVD and can stop it gracefully before the memory-intensive model test when
`CYANBRIDGE_WALKING_AID_STOP_OTHER_EMULATORS=true`.

The test fails on checksum drift, model initialization errors, unsupported tensor layouts, empty
detections, malformed boxes, missing expected COCO classes, or invalid depth output. Timing and
accelerator details are written to `build/hil/results/walking-aid-model-ci/`.

## Qualcomm NPU detector (opt-in build)

Builds with `-PliteRtLmVersion=0.18.0 -PlocalLiteRtNpuAarPath=<NPU runtime AAR>` can run the
detector on the Qualcomm NPU. That happens when `NPU` is the selected compute backend and
`<detector file name>_Qualcomm_<SoC>.tflite` sits next to the plain model; otherwise the
NNAPI → GPU → CPU chain is unchanged. Runtime, AOT compile and evaluation method:
[`NPU_ON_DEVICE.md`](NPU_ON_DEVICE.md).

Results on the Galaxy Z Fold6 (SM8650), COCO val2017, 200 images:

| Detector | NPU per frame | CPU per frame | mAP@.5:.95 NPU / CPU |
|---|---:|---:|---|
| YOLO11n | 10.4–11.4 ms | 47–61 ms | 0.297 / 0.290 |
| YOLO-World | 74 ms | 1,650 ms | 0.508 / 0.507 |

Two existing decoding bugs, found while validating the NPU, are fixed for every backend:
- **YOLO-World boxes:** the catalog YOLO-World export outputs undecoded anchor distances;
  boxes collapsed into the top-left corner. They are now decoded on the stride 8/16/32 grid.
- **YOLO11n labels:** YOLO11n's `class_idx` output (uint8, quantization scale 0) was
  multiplied by 0, so every object was a "person". Raw values are now kept for scale 0.

The bus-photo test above only checked for people, so it did not catch either bug.

Device tests (in `src/androidTestLitertNpu`). On a phone, install with
`adb -s <serial> install -r` and run with `am instrument`; never uninstall the app.
- `WalkingAidNpuDetectorTest`:
  - CPU part: CompiledModel bytes equal the Interpreter's.
  - NPU part: detections must equal the host HTP emulation when the fixture has
    emulated results.
  - With `-e yolo_dump_dir <name>` it writes the raw NPU outputs to the app's
    external files dir, for host COCO scoring.
- `WalkingAidNpuBackendTest` drives `LiteRtVisionBackend` itself on the NPU and on CPU:
  - It copies the plain and AOT YOLO11n into the app's files only if absent, and
    deletes only what it created.
  - It does not change Walking Aid settings; it skips when YOLO-World is selected.

```bash
# Fixture from public COCO val2017 images (device-first: no host emulation)
python tools/litert-npu/make_yolo_fixture.py --skip-htp --plain yolo11n.tflite --aot x \
  --qairt-root x --images coco200 --out fixture200-yolo11n
adb -s <serial> push yolo11n.tflite yolo11n_Qualcomm_SM8650_apply_plugin.tflite fixture200-yolo11n /data/local/tmp/npu/yolo/
adb -s <serial> shell am instrument -w -r -e class com.fersaiyan.cyanbridge.hil.WalkingAidNpuDetectorTest \
  -e yolo_fixture /data/local/tmp/npu/yolo/fixture200-yolo11n -e yolo_dump_dir npu-dump-yolo11n \
  -e yolo_plain_model /data/local/tmp/npu/yolo/yolo11n.tflite \
  -e yolo_npu_model /data/local/tmp/npu/yolo/yolo11n_Qualcomm_SM8650_apply_plugin.tflite \
  com.fersaiyan.cyanbridge.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> pull /sdcard/Android/data/com.fersaiyan.cyanbridge/files/npu-dump-yolo11n
python tools/litert-npu/yolo_htp_parity.py --plain yolo11n.tflite --aot x --qairt-root x --images coco200 \
  --npu-raws npu-dump-yolo11n --coco-annotations instances_val2017.json --out report.json
# End to end through LiteRtVisionBackend (16-image fixture with emulated detections)
adb -s <serial> shell am instrument -w -r -e class com.fersaiyan.cyanbridge.hil.WalkingAidNpuBackendTest \
  -e yolo_fixture /data/local/tmp/npu/yolo/fixture-yolo11n \
  -e yolo_plain_model /data/local/tmp/npu/yolo/yolo11n.tflite \
  -e yolo_npu_model /data/local/tmp/npu/yolo/yolo11n_Qualcomm_SM8650_apply_plugin.tflite \
  com.fersaiyan.cyanbridge.test/androidx.test.runner.AndroidJUnitRunner
```

Executed on 2026-10-08:
- **Fold6:** the detector test (16 images, both detectors' 200 images with dumps) and
  the backend test pass. NPU detections equal the host emulation exactly; the CPU
  backend matches host CPU at recall 0.97 / precision 0.98.
- **Pixel 9a AVD:** the CPU parts and the bus-photo integration test pass; the NPU parts
  skip.

Sustained use (`-e yolo_soak_seconds 90`, host sampling `dumpsys meminfo` and temperatures):
- **NPU:** p50 11 → 14.5 ms and p95 ~18 ms for 90 s (about 70 fps); the chip stays at
  ~39 °C and the skin flat at 36.6 °C.
- **CPU:** slows from 41 to 58 ms per frame as the chip heats from 40 to 45 °C (skin 36.8
  → 39.1 °C).
- App PSS is about 240–290 MB in both cases.

The 200-image runs take 8 s (YOLO11n) and 65 s (YOLO-World) on the phone, vs hours in
the x86 emulator, so use the phone when one is attached.
