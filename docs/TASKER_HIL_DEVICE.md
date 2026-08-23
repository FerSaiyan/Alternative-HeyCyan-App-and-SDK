# CyanBridge Tasker HIL devices

## Principle: preserve the licensed emulator

Tasker and AutoInput are paid/licensed apps whose working state includes Play account entitlement, AutoApps entitlement restoration, user-approved accessibility disclosures and imported Tasker projects. The working HIL emulator is therefore treated as a **persistent golden lab device**, not as an ephemeral CI image.

Do not wipe/recreate/provision it on every CI run. CI should update CyanBridge and the Tasker project files under test while preserving the emulator's Google/Play/licensing and permission state.

## Current golden emulator

Recorded after the Tasker HIL repair on 2026-08-23:

- AVD: `Pixel_9a`
- serial when running: `emulator-5554`
- API 37, `sdk_gphone16k_x86_64`
- Tasker 6.6.20
- AutoInput 3.0.12
- AutoApps installed with the existing AutoInput entitlement restored through the official flow
- Tasker Accessibility enabled
- AutoInput Accessibility enabled

API 37 adbd may disconnect transiently. Device scripts should use `adb -s <serial> wait-for-device` before important operations.

## CI responsibility split

### Golden emulator

Use for Android/Tasker integration behavior that does not require real glasses:

- build/install CyanBridge debug APK
- import branch-exact Tasker projects
- Gemini/ChatGPT profile handshakes
- Local Agent → Tasker → AutoInput UI actions
- AutoDiary observation/exclusion behavior
- Settings/diagnostic checks

### Dedicated physical phone

Use for behavior an emulator cannot establish faithfully:

- real HeyCyan/Meta BLE connection
- camera/media capture from glasses
- Visual Diary end-to-end capture
- background/power behavior with the physical glasses

The dedicated phone may use Android's Stay Awake developer option and a screen-blackout tool as long as Android remains logically interactive for AutoInput/HIL operations.

## Recovery checklist

If a previously green Tasker HIL environment starts failing:

1. Confirm the same persistent AVD was launched; do not start a clean AVD accidentally.
2. Confirm Tasker, AutoInput and AutoApps are still installed.
3. Confirm AutoInput entitlement has not fallen back to an unlicensed state.
4. Confirm both Tasker and AutoInput accessibility services are enabled.
5. If Tasker reports missing Accessibility Access, grant it through Tasker's own disclosure flow rather than `settings put secure`.
6. Re-run `tools/hil/preflight.sh`.
7. Re-import the branch-exact Tasker projects with `tools/hil/sync_tasker_profiles.sh` if project state is stale.
8. Run the HIL classes individually if API 37 adbd drops during a combined invocation.

## Known platform quirk

API 35+ may show the Android 16 KB page-size compatibility dialog when CyanBridge starts because some third-party native libraries are not 16 KB aligned. The HIL instrumentation launcher already dismisses this dialog so it does not steal focus from Tasker/AutoInput tests.

## Security

The emulator is a lab device that contains licensed apps and potentially a signed-in Play account. Do not upload screenshots/UI dumps by default and do not publish or archive its userdata image in repository artifacts. Keep visual diagnostics opt-in and treat the AVD like a physical test phone with credentials.
