package com.fersaiyan.cyanbridge.glasses

import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.DeviceInfoResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** One-shot HeyCyan BLE device-info reads (syncDeviceInfo). */
internal object GlassesDeviceInfoReader {

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
