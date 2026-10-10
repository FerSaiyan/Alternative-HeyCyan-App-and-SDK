# Walking Aid real-model test

The connected-device integration test downloads the catalog YOLO11n and Depth Anything 3 Small
models when they are missing, downloads Ultralytics' public `bus.jpg` fixture, and runs the real
TFLite Walking Aid stack. It verifies detector output, user-focus fuzzy matching against labels in
that output, and Depth Anything inference.

The test is opt-in so the regular Android instrumentation suite does not unexpectedly download
about 60 MB of models. Connect an Android phone with USB debugging enabled, then run:

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidVisionStackIntegrationTest \
  -Pandroid.testInstrumentationRunnerArguments.walkingAidRealModels=true
```

The models and image are cached in the instrumentation app sandbox for the duration of the test.
Android's connected-test task may uninstall that debug app after completion. This test intentionally
fails on model incompatibility, unsupported tensor layouts, empty detections, or invalid depth output
so it can be used as an on-device model debugging tool.

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
