# Keeping CyanBridge data across debug reflashes

## Why data was lost

- Debug builds were signed with the machine's default debug key (`~/.android/debug.keystore`), release builds
  with `keystore.properties` (`AkiosKeystore.jks`). The package id is the same (`com.fersaiyan.cyanbridge`),
  so switching between them fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and Android Studio offers to
  uninstall. An uninstall (or "clear data") deletes:
  - `files/local_agent_memory/` (screen capture days, daily facts/summaries, supervision actions),
  - `getExternalFilesDir()` (recordings and glasses photos in `DCIM/`),
  - the Android Keystore key that wraps the Memory Vault master key (`VaultKeyManager` /
    `DeviceKeyManager`). Screen OCR is stored only in the encrypted vault, so even a backup zip made before
    the reinstall restores ciphertext that the new install cannot decrypt (unless a vault passphrase was set).
- A version downgrade (debug `versionCode` lower than an installed release) also makes Studio offer to
  uninstall.

## What changed

1. `app/build.gradle`: debug builds are signed with the release key when `keystore.properties` exists
   (opt out: `-PcyanbridgeDefaultDebugKey`). Debug and release builds now replace each other in place.
2. `scripts/install_debug_keep_data.sh`: installs with `adb install -r -d` and never uninstalls. On a
   signature mismatch it stops (exit 3) and explains.
3. One-time migration for a phone that still has a build signed with the default debug key:

   ```bash
   cd android/CyanBridge
   scripts/install_debug_keep_data.sh --build --rotate-from-debug-key
   ```

   This re-signs the APK with an APK Signature Scheme v3 proof-of-rotation (debug key → release key,
   lineage stored in `~/.android/cyanbridge-debug-to-release.lineage`, Android 9+). The phone accepts it as
   an update and keeps all data, including the vault key. Afterwards plain `scripts/install_debug_keep_data.sh
   --build` works. Verified on an Android 16 (API 37) emulator: data written under the debug-signed install
   survived the rotated install and a later release-key-only install, `firstInstallTime` unchanged.

   In Android Studio, avoid accepting "uninstall and reinstall" prompts; use the script instead.

## Durable mirror of Auto Diary captures (Tasker)

`tasker/CyanBridge_AutoDiary_Tasker.prj.xml` now also appends every Auto Diary observation to
`/sdcard/Documents/CyanBridgeMirror/autodiary/YYYY-MM-DD.jsonl` (one JSON object per line:
`{mirror_version, source, tz_offset_min, observation:{created_at_ms, package_name, text_summary, nodes}}`).
Shared storage survives app reinstalls and data clears, and Tasker globals live in Tasker, so the mirror keeps
working even while CyanBridge is being reflashed.

- Switch: Tasker global `%CB_MirrorEnabled` (`1` by default, `0` disables). Folder: `%CB_MirrorDir`.
- Errors land in `%CB_MirrorLastError`. Tasker needs "All files access" to write to `Documents/`.
- Re-import the project in Tasker (it replaces the CyanBridge AutoDiary profiles/tasks).
- The mirror is plaintext (outside the encrypted vault). Syncthing (Send Only on the phone) copies it to the
  PC, where MyClone's `scripts/import_cyanbridge_mirror.py` turns it into a contract bundle.
- Not covered: Visual Diary glasses photos are taken by the app itself (`MetaRaybanManager` →
  `getExternalFilesDir(DCIM)`), so they need an app-side change to be mirrored.
