# Walking Aid on-device real-model CI

The self-hosted Linux CI runs the production Walking Aid LiteRT stack inside an x86_64 Android
emulator. It executes all current local Walking Aid models rather than mocking inference:

- YOLO11n TFLite detection.
- YOLO-World quantized TFLite detection.
- Depth Anything 3 Small FP16 TFLite depth estimation.

It uses two real JPEG scenes (`bus.jpg` and `zidane.jpg`) from the Ultralytics assets repository.
They are decoded from files in the same way as completed JPEG thumbnail transfers from the glasses.
URLs are pinned to a Git commit, and every image/model has an exact size and SHA-256 in
`tools/hil/walking_aid_assets.tsv`. The assets are not committed to Git or attached to CI artifacts.
Ultralytics assets are offered under AGPL-3.0; model-specific licensing remains documented in
`WalkingAidModelCatalog`.

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
