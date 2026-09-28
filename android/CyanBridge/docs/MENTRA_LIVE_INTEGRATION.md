# Mentra Live direct Bluetooth integration (experimental)

Branch: mentra-live-integration.

This integrates the native Mentra Bluetooth SDK into CyanBridge Android, not
the MentraOS miniapp HTTP relay. The default version is 3.2.0-dev.227; the
glasses firmware must match the SDK version. Override the dependency via
-PmentraSdkVersion=matching-version only after checking the Android API.

The non-Meta Android minimum is 28. When Meta DAT is configured, the required
minimum remains 29. SDK usage analytics are disabled in both the manager and
the Android manifest.

Open Pair Mentra Live from the pairing screen; grant Bluetooth permissions and
choose the correct glasses. Gemini Live then uses continuous Mentra SDK PCM
as its only microphone source rather than starting a second Android AudioRecord.
Select the Mentra Live Bluetooth media output in Android to hear AI responses.

The first camera integration sends still photos over BLE to an authenticated
one-shot phone-local loopback receiver. Photos are not sent to Mentra cloud.
This can be slower than Wi-Fi; live video streaming is not implemented.

Hardware acceptance checks: verify pairing and reconnect, firmware compatibility,
audio output and microphone route, image upload and request-ID correlation,
timeouts and cancellations, disabled SDK telemetry, dependency conflicts and
existing Meta DAT and HeyCyan functionality with real glasses and an Android phone.
The Android emulator cannot establish physical compatibility.
