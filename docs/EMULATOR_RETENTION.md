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

## Audit and consolidation proposal (2026-11-11, not executed)

Each AVD was cold-booted once with `-read-only -no-snapshot -memory 3072` on port 5610, so
nothing below changed any userdata. Sizes are the AVD directories on the NVMe.

| AVD | Size | Image, pages | Root | Google / Play | Tasker, AutoInput | CyanBridge | Unique userdata | Used by |
|---|---|---|---|---|---|---|---|---|
| `Pixel_9a` | 17 GB (/data 13 of 16 GB used) | 37.2-beta3 Play ps16k, 16 KB | no (`user`) | 1 Google account, Play | Tasker 6.6.20, AutoInput 3.0.12, AutoApps 1.8.13; both accessibility services on | 2.3.0, **release key**, debuggable (local build); Pro shows "standard, active, expires 2026-12-02" but no API token ("Subscription unavailable") | 480 MB local models in the app, 2.5 GB under /sdcard/Android, `/data/local/tmp` jev-embedding, mm, npu | `CYANBRIDGE_HIL_EMULATOR_AVD` (all `start_emulator.sh` users: self-hosted CI emulator steps, Tasker HIL, Jev-like), Artemis-parity pin, `export_tasker_settings_episode.py` |
| `CyanBridge_LiteRT_CI` | 20 GB (/data 8.4 of 23 GB used) | same image, 16 KB | no | Play, no account | none | 2.3.0, release key, debuggable | 1.2 GB app cache (WebView/LiteRT, regenerable), `gq` fixtures | **nothing** names it; only `start_emulator.sh`'s `head -n1` fallback would pick it (first alphabetically) |
| `CyanBridge_Walking_Aid_CI` | 8.1 GB (/data 6.1 of 7.7 GB) | android-36 Play image, 4 KB | no | no account | Tasker 6.6.20 + AutoInput 3.0.12 installed, accessibility on, no account | 2.3.0, debug key | ~1.5 MB | Walking Aid real-model step (`start_walking_aid_emulator.sh`); parity and crash-sweep host |
| `HeyCyan_Frida` | 2.6 GB | android-36 google_apis `userdebug`, 4 KB | `adb root` and `su 0` | no account | none | 2.3.0, debug key | `frida-server` in /data/local/tmp, official HeyCyan app `com.glasssutdio.wear` | other projects (Frida, Unbound launcher); no CI |

Names in scripts that are **not** AVDs: `CyanBridge_LocalAgent_Tasker`, `_AutoDiary_Tasker`,
`_VisualDiary_Tasker`, `_HIL_Tasker` (`.prj.xml` Tasker projects) and `CyanBridge_Gemini` /
`CyanBridge_ChatGPT` (`tasker/*.xml`). Stale AVD names: `CyanBridge_Artemis_Data` (deleted; still in
`training/artemis_real_v1` and `tools/benchmarks/collect_artemis_*.py`), `CyanBridge_Meta_DAT_CI`,
`Medium_Phone_API_36`, and the `cyanbridge_audio` hint in `start_emulator_audio.sh`.
`start_walking_aid_emulator.sh` still creates its AVD when missing and, with
`CYANBRIDGE_WALKING_AID_STOP_OTHER_EMULATORS=true` (set by the workflow), kills **every** other
emulator on the machine, including ones other agents started.

**The CI install failure.** Local builds are signed with the release key whenever
`keystore.properties` exists (debug included), and CI builds use the debug key. `start_emulator.sh`
reuses the first running emulator (emulator-5590, a persistent Pixel_9a holding the release-key app),
so `install -r` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

**Proposal (owner decides).** Keep `HeyCyan_Frida` (root, Frida) and `Pixel_9a` (Play, Tasker,
account). Run every CI emulator step on a `-read-only` Pixel_9a instance on its own port, so CI can
replace the app without touching the persistent phone. Archive `CyanBridge_LiteRT_CI` and
`CyanBridge_Walking_Aid_CI` to the Seagate (stopped AVD, `tar --sparse` of `<name>.avd` + `<name>.ini`
into `/mnt/seagate/avd-archive/`, verified by file count and sizes). This frees 28.1 GB, plus 2.3 GB
for the android-36 Play image that only Walking_Aid_CI uses. A cautious first step is to archive only
LiteRT_CI (20 GB) and move Walking Aid after one green CI run on Pixel_9a (16 KB pages, 1.9 GB free).

Script changes:
1. `start_emulator.sh`: require `CYANBRIDGE_HIL_EMULATOR_AVD` (drop the `head -n1` fallback); use
   `CYANBRIDGE_HIL_EMULATOR_PORT` (default 5584); reuse only `emulator-$port`, and only when
   `emu avd name` equals the AVD; boot with `-port $port -read-only -no-snapshot -memory 4096`; wait
   on that serial only, not on `find_serial emulator`.
2. `install.sh`: on `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, when
   `CYANBRIDGE_HIL_UNINSTALL_ON_SIGNATURE_MISMATCH=true`, `adb uninstall` the app (and `.test`) and
   install again; otherwise fail with that hint. Only the workflow's read-only instance sets it.
3. `android-self-hosted.yml`: set those two variables for the emulator steps; run the Walking Aid
   step on `steps.emulator.outputs.serial` (or set `CYANBRIDGE_WALKING_AID_AVD=Pixel_9a` with
   `-read-only`), drop `STOP_OTHER_EMULATORS`; add an `if: always()` step that runs
   `adb -s emulator-5584 emu kill`.
4. `start_walking_aid_emulator.sh`: remove the `avdmanager create` path and kill only its own serial.
5. Tasker HIL and Jev-like workflows keep the same AVD through the same script; they import the
   branch's Tasker projects each run, so a read-only instance works for them.

Alternative that keeps the app data: give the self-hosted runner the machine's
`keystore.properties`, so CI builds carry the release key like local builds.
