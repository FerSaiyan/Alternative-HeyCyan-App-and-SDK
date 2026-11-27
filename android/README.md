# HeyCyan Glasses SDK - Android

Android SDK for controlling HeyCyan smart glasses via Bluetooth Low Energy (BLE).

## Files

- `glasses_sdk_20250723_v01.aar` - Android SDK library (AAR format)
- `CyanBridge/` - Sample Android application demonstrating SDK usage
- `Android_SDK_Development_Guide_CN.pdf` - SDK documentation (Chinese)

## Quick Start

1. Add the AAR file to your Android project's `libs` directory
2. Add the dependency in your app's `build.gradle`:
   ```gradle
   implementation files('libs/glasses_sdk_20250723_v01.aar')
   ```
3. See the `CyanBridge` project for implementation examples

## Requirements

- Android 5.0+ (API level 21)
- Bluetooth Low Energy support
- Android Studio

## Sample Application

The `CyanBridge` directory contains a complete Android application demonstrating:
- Device scanning and connection
- Photo/video/audio capture controls
- Battery status monitoring
- AI image generation
- Device information retrieval

## Support

For technical support or questions about the Android SDK, please see our GitHub issues or contact the HeyCyan development team.

## OTA Firmware Acquisition

### How the app gets firmware updates

The HeyCyan app queries the `last-ota` API to check for firmware updates:

```
POST https://www.qlifesnap.com/glasses/app-update/last-ota
Content-Type: application/json
token: <auth-token>
```

Request body includes `appId`, `uid`, `hardwareVersion`, `romVersion`, `os`, `mac`, `country`, `dev`.

### Querying for firmware

Pass your own account token; none is published here.

```bash
# BT firmware (.bin) — returns encrypted container for JieLi processor
python3 scripts/probe_last_ota.py \
  --token '<auth-token>' \
  --hardware-version AM01G1_V9.2 \
  --rom-version AM01G1_9.20.00_2510111600

# Wi-Fi firmware (.swu) — returns cpio archive for V821 processor
python3 scripts/probe_last_ota.py \
  --token '<auth-token>' \
  --hardware-version WIFIAM01G1_V9.2 \
  --rom-version WIFIAM01G1_9.2_1.00.00_2501010000
```

### API Response Codes

| retCode | Meaning |
|---------|---------|
| 0 | Update available (response includes `data.downloadUrl`) |
| 401 | Token expired or not logged in |
| 60001 | No upgraded version (already on latest) |

### OTA Delivery Lanes

| Lane | URL pattern | Auth | Format | Target SoC |
|------|------------|------|--------|------------|
| `.bin` CDN | `api2.qcwxkjvip.com/download/ota/...` | **None** | Encrypted container | JieLi (BT) |
| `.swu` (factory) | `qcwxfactory.oss-cn-beijing.aliyuncs.com/bin/glasses/...` | **None** (mostly 403) | cpio archive | V821 (Wi-Fi) |
| `.swu` (watchface) | `qcwxwatchface.oss-cn-hangzhou.aliyuncs.com/ota/...` | **None** (URL from API) | cpio archive | V821 (Wi-Fi) |

The Wi-Fi hardware version naming pattern is `WIFI<BT_HW_VERSION>` (e.g. `AM01G1_V9.2` -> `WIFIAM01G1_V9.2`).
Query the `last-ota` API with `WIFI*` hardwareVersion to get `.swu` download URLs.

### Known Firmware Families

**BT families (`.bin`)**: A01, A02, A02E02, A03, A06, A08, AM01, AM01C, AM01G1, AM01G2, AM01W, AM02, E02, G01

**Wi-Fi families (`.swu`)**: WIFIA01, WIFIA02, WIFIA03, WIFIA03BV, WIFIA03PRO, WIFIA02E02, WIFIAM01, WIFIAM01C, WIFIAM01G1, WIFIAM01G2, WIFIAM01W

### Reference Files

- `HeyCyanOfficialApp/last_ota_capture_reference.json` — captured API request/response examples with all known firmware URLs
- `last_ota_wifi_cn.json`, `last_ota_wifi_us.json` — saved API responses
