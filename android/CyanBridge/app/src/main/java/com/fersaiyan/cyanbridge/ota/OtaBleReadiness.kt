package com.fersaiyan.cyanbridge.ota

import android.util.Log
import com.fersaiyan.cyanbridge.glasses.GlassesDeviceInfoReader
import com.fersaiyan.cyanbridge.ui.MyApplication
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** BLE reconnect + fresh device-info gate that [OtaManager] runs between combined OTA stages. */
internal class OtaBleReadiness {
    /** Address of the glasses the current OTA session was started against. */
    var otaExpectedDeviceAddress: String? = null

    /** Require a live BLE link and a new four-field device-info response between OTA stages. */
    suspend fun awaitFreshBleReadiness(stage: OtaReadinessStage): Boolean {
        val bleManager = BleOperateManager.getInstance()
        val reportedDeviceAddress = runCatching { DeviceManager.getInstance().deviceAddress }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val deviceAddress = reportedDeviceAddress ?: otaExpectedDeviceAddress
            ?: run {
                Log.e("Ota", "Cannot perform $stage readiness check without the glasses address")
                return false
            }
        otaExpectedDeviceAddress = otaExpectedDeviceAddress ?: deviceAddress
        if (reportedDeviceAddress != null &&
            !reportedDeviceAddress.equals(otaExpectedDeviceAddress, ignoreCase = true)
        ) {
            Log.e(
                "Ota",
                "Refusing $stage readiness for a different BLE device: " +
                    "expected=$otaExpectedDeviceAddress, reported=$reportedDeviceAddress",
            )
            return false
        }

        if (stage == OtaReadinessStage.AFTER_BLE) {
            // Match OTAActivity$dfuOpResult$1: invalidate the cached BLE version before
            // disconnecting, then repopulate it only from the fresh post-update read.
            val application = MyApplication.getInstance()
            application.firmwareVersion = ""
            application.hardwareVersion = ""
            delay(1_000)
            if (bleManager.isConnected) {
                try {
                    bleManager.disconnect()
                } catch (error: Exception) {
                    Log.e("Ota", "Could not disconnect for post-DFU readiness", error)
                    return false
                }
                val disconnected = withTimeoutOrNull(10_000) {
                    while (bleManager.isConnected) delay(100)
                    true
                } ?: false
                if (!disconnected) {
                    Log.e("Ota", "BLE did not disconnect for post-DFU readiness")
                    return false
                }
            }
        }

        if (!bleManager.isConnected) {
            try {
                bleManager.reConnectMac = deviceAddress
            } catch (_: Throwable) {
                // Optional SDK property; connectDirectly still uses the explicit address.
            }
            try {
                bleManager.connectDirectly(deviceAddress)
            } catch (error: Exception) {
                Log.e("Ota", "Could not reconnect for $stage readiness", error)
                return false
            }
        }

        val connected = withTimeoutOrNull(30_000) {
            while (!bleManager.isConnected) delay(250)
            true
        } ?: false
        if (!connected) {
            Log.e("Ota", "BLE did not reconnect for $stage readiness")
            return false
        }

        val reconnectedDeviceAddress = runCatching { DeviceManager.getInstance().deviceAddress }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (reconnectedDeviceAddress != null &&
            !reconnectedDeviceAddress.equals(otaExpectedDeviceAddress, ignoreCase = true)
        ) {
            Log.e(
                "Ota",
                "Refusing $stage readiness after reconnect for a different BLE device: " +
                    "expected=$otaExpectedDeviceAddress, reported=$reconnectedDeviceAddress",
            )
            return false
        }

        val deviceInfo = GlassesDeviceInfoReader.readGlassesDeviceInfo() ?: run {
            Log.e("Ota", "Fresh syncDeviceInfo failed for $stage")
            return false
        }
        val ready = listOf(
            deviceInfo.hardwareVersion,
            deviceInfo.firmwareVersion,
            deviceInfo.wifiHardwareVersion,
            deviceInfo.wifiFirmwareVersion,
        ).all { !it.isNullOrBlank() }
        if (!ready) {
            Log.e("Ota", "Fresh syncDeviceInfo was incomplete for $stage")
            return false
        }
        MyApplication.getInstance().hardwareVersion = deviceInfo.hardwareVersion.orEmpty()
        MyApplication.getInstance().firmwareVersion = deviceInfo.firmwareVersion.orEmpty()
        Log.i(
            "Ota",
            "Fresh $stage readiness passed: ble=${deviceInfo.hardwareVersion}/${deviceInfo.firmwareVersion}, " +
                "wifi=${deviceInfo.wifiHardwareVersion}/${deviceInfo.wifiFirmwareVersion}",
        )
        return true
    }
}
