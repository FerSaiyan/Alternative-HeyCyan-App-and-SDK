# Mentra Live direct Bluetooth integration

This integrates the native Mentra Bluetooth SDK into CyanBridge Android, not
the MentraOS miniapp HTTP relay. The default version is 3.2.0-dev.227; the
glasses firmware must match the SDK version. Override the dependency via
-PmentraSdkVersion=matching-version only after checking the Android API.

The non-Meta Android minimum is 28. When Meta DAT is configured, the required
minimum remains 29. SDK usage analytics are disabled in both the manager and
the Android manifest.

Pair Mentra Live like the other glasses: open Scan (Device Bind), choose the
Mentra Live row and connect. The Mentra SDK scanner joins the scan once a
Mentra Live is seen, bonds and saves the device; the app reconnects to it on
launch with the SDK's setDefaultDevice + connectDefault. Glasses paired to
another phone need three power-button presses to open their pairing window.

Voice questions and Gemini Live use the Mentra SDK PCM as the only microphone
source. Select the Mentra Live Bluetooth media output in Android to hear answers.
AI photos travel over BLE to an authenticated one-shot phone-local loopback
receiver and are never sent to Mentra cloud.

See docs/refactor/MENTRA_AUDIT.md for the capability matrix and the hardware
checklist in docs/refactor/HARDWARE_TEST_CHECKLIST.md.
