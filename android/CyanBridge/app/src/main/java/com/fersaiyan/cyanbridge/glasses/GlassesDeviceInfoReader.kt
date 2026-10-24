package com.fersaiyan.cyanbridge.glasses

import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.DeviceInfoResponse
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** One-shot HeyCyan BLE device-info reads (syncDeviceInfo). */
internal object GlassesDeviceInfoReader {
    /**
     * Get the glasses' Wi-Fi hardware version via BLE syncDeviceInfo.
     * Returns empty string if BLE is not connected.
     */
    suspend fun getGlassesWifiHardwareVersion(): String {
        if (!BleOperateManager.getInstance().isConnected) return ""
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            LargeDataHandler.getInstance().syncDeviceInfo { _, response ->
                if (cont.isActive) {
                    cont.resume(response?.wifiHardwareVersion ?: "") {}
                }
            }
            // Timeout after 5 seconds
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                kotlinx.coroutines.delay(5000)
                if (cont.isActive) cont.resume("") {}
            }
        }
    }

    /**
     * Get the glasses' Wi-Fi firmware version via BLE syncDeviceInfo.
     */
    suspend fun getGlassesWifiFirmwareVersion(): String {
        if (!BleOperateManager.getInstance().isConnected) return ""
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            LargeDataHandler.getInstance().syncDeviceInfo { _, response ->
                if (cont.isActive) {
                    cont.resume(response?.wifiFirmwareVersion ?: "") {}
                }
            }
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                kotlinx.coroutines.delay(5000)
                if (cont.isActive) cont.resume("") {}
            }
        }
    }

    /**
     * Read the full [DeviceInfoResponse] from the glasses via BLE syncDeviceInfo.
     * Returns null if BLE is not connected or the request times out.
     *
     * The response contains both BLE identifiers (hardwareVersion, firmwareVersion)
     * and Wi-Fi identifiers (wifiHardwareVersion, wifiFirmwareVersion).
     */
    suspend fun readGlassesDeviceInfo(): DeviceInfoResponse? {
        if (!BleOperateManager.getInstance().isConnected) return null
        return withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                try {
                    LargeDataHandler.getInstance().syncDeviceInfo { _, response ->
                        if (cont.isActive) {
                            cont.resume(response) {}
                        }
                    }
                } catch (error: Exception) {
                    Log.w("DebugOta", "syncDeviceInfo failed while preparing OTA", error)
                    if (cont.isActive) cont.resume(null) {}
                }
            }
        }
    }
}
