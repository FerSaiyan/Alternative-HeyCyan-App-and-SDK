# Emulator inventory and recovery

Reviewed October 6, 2026. The GitHub repository variable
`CYANBRIDGE_HIL_EMULATOR_AVD` is now `Pixel_9a`.

## Retained CI targets

- `Pixel_9a`: provisioned, persistent Tasker/AutoInput automation phone. Keep its
  userdata, account state, permissions, and installed apps. `start_emulator.sh`
  uses the configured name when starting a new emulator; an already connected
  emulator can still be reused by the existing script.
- `CyanBridge_Walking_Aid_CI`: dedicated Android 36 Google Play x86_64 AVD with
  an 8 GiB data partition and 4 GiB RAM. Android self-hosted CI selects it by
  name through `start_walking_aid_emulator.sh`.

## Removed Artemis data emulator

`CyanBridge_Artemis_Data` was an isolated Android 36 x86_64 emulator used for
the opt-in Artemis fixture and observed UI dataset collection, separate from
the licensed Pixel target. Its allocated storage was approximately 5.46 GB.

Configuration before removal:

- Registration: `~/.android/avd/CyanBridge_Artemis_Data.ini`.
- Data directory: `~/.android/avd/CyanBridge_Artemis_Data.avd`.
- System image: `system-images;android-36;google_apis_playstore;x86_64`.
- Hardware profile: `medium_phone`; 1080 × 2400 display, density 420.
- RAM: `1536M`; four virtual CPU cores; 6 GiB data partition.
- Historical collection serial: `emulator-5560`.
- Original registration and config are retained in `docs/emulator-configs/`.

The dataset and its audits remain under
`android/CyanBridge/training/artemis_real_v1/`. That README documents 29 observed
episodes / 82 decisions and the pinned Artemis revision
`371aa6df56880643da57b30da936e9812fb0ec66`. Raw captures were recorded separately
under `/tmp/opencode/artemis-phone-captures/`; they were not part of this AVD
deletion. The observations are not Tasker/AutoInput evidence.

### Recreate an isolated target

Use the already installed Android 36 system image:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export JAVA_HOME=/opt/android-studio/jbr
printf 'no\n' | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
  --name CyanBridge_Artemis_Data \
  --package 'system-images;android-36;google_apis_playstore;x86_64' \
  --device medium_phone
"$ANDROID_HOME/emulator/emulator" -avd CyanBridge_Artemis_Data -port 5560 \
  -no-snapshot -gpu swiftshader_indirect
```

Reapply the RAM/data-partition settings if reproducing the historical hardware
shape. Start from the newly generated config and use the saved config as a
reference: its `<build>`/`<temp>` fields were emulator-generated values, not
portable paths. Reprovision fixture apps as needed. The saved metadata does not
restore the deleted userdata. Use this AVD only for isolated Artemis work.

## Removed Meta DAT CI registration

`CyanBridge_Meta_DAT_CI.ini` pointed to
`/tmp/opencode/meta-dat-review/avd`, with `target=android-36`. That directory was
already absent before cleanup. Only a stale registration existed; no device
config, app inventory, userdata, or snapshots were available to preserve.
The original INI is retained in `docs/emulator-configs/`.

The current Android CI runs `MetaDatLifecycleTest` and `MetaDatSdkMockTest`
through the standard HIL emulator; it does not require an AVD with this name.
For a future isolated Meta DAT review, create a fresh Android 36 x86_64 target
with `avdmanager`, install the branch's debug/instrumentation APKs, and run the
desired test classes with `tools/hil/run_instrumentation.sh`. Prefer a durable
AVD directory rather than restoring the obsolete temporary path.

## Other approved removals

- `Medium_Phone_API_36` (directory `Medium_Phone.avd`): approximately 7.32 GB;
  generic Android 36 development emulator, without a named CI dependency.
- `Flutter_Test_API36.avd`: unregistered/incomplete directory, approximately
  16 KB.
- `~/.android/avd-backups/Pixel_9a-pro-20260927/`: approximately 14.16 GB;
  archived Pixel userdata and INI. The live `Pixel_9a` AVD remains intact.

The Android 36 and Pixel Android 37.2-beta3 system images remain installed for
the retained targets and future emulator recreation.
