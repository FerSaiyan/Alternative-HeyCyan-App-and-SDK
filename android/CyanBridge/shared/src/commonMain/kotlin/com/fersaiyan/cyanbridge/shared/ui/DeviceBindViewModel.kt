package com.fersaiyan.cyanbridge.shared.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.ScannedDevice

/** A pairing the user confirmed; the host connects [device] as [deviceClass]. */
data class DeviceBindConnection(val device: ScannedDevice, val deviceClass: DeviceClass)

/**
 * State and pairing choices of [DeviceBindScreen]. The host owns the scanner and the connection:
 * it feeds scan results in, and runs the [DeviceBindConnection] the confirm calls return.
 */
class DeviceBindViewModel : ViewModel() {
    var devices by mutableStateOf<List<ScannedDevice>>(emptyList())
        private set
    var isScanning by mutableStateOf(false)
    var connectingDevice by mutableStateOf<ScannedDevice?>(null)
        private set
    var selectedClass by mutableStateOf(DeviceClass.HEY_CYAN)
        private set

    fun onScanStarted() {
        devices = emptyList()
    }

    fun onDevicesChanged(devices: List<ScannedDevice>) {
        this.devices = devices
    }

    fun selectDevice(device: ScannedDevice) {
        connectingDevice = device
        selectedClass = pairingChoiceFor(device.effectiveSelectedClass())
    }

    fun selectDeviceClass(deviceClass: DeviceClass) {
        selectedClass = deviceClass
    }

    fun dismissConnection() {
        connectingDevice = null
    }

    /**
     * The first picker's choice. The consumer-glasses sentinel (and any consumer protocol) goes
     * through [confirmManualProtocol]; Meta, MYVU and audio-only devices connect as chosen.
     * Nothing happens while no device is selected or [canConnect] refuses (missing permission).
     */
    fun confirmConnection(canConnect: () -> Boolean): DeviceBindConnection? {
        val device = connectingDevice ?: return null
        if (!canConnect()) return null
        val deviceClass = selectedClass
        if (deviceClass in CONSUMER_PROTOCOLS || deviceClass == DeviceClass.UNKNOWN) {
            return confirmManualProtocol(
                if (deviceClass in CONSUMER_PROTOCOLS) deviceClass else DeviceClass.HEY_CYAN,
                canConnect = { true },
            )
        }
        connectingDevice = null
        return DeviceBindConnection(device, deviceClass)
    }

    /** The second picker's concrete consumer protocol (HeyCyan, EyeVue, TuneBuds or MoYoung). */
    fun confirmManualProtocol(deviceClass: DeviceClass, canConnect: () -> Boolean): DeviceBindConnection? {
        val device = connectingDevice ?: return null
        if (!canConnect()) return null
        connectingDevice = null
        return DeviceBindConnection(device, deviceClass.takeIf { it in CONSUMER_PROTOCOLS } ?: DeviceClass.HEY_CYAN)
    }

    companion object {
        /** The protocols behind the HeyCyan pairing choice, which then asks for the exact one. */
        val CONSUMER_PROTOCOLS = setOf(
            DeviceClass.HEY_CYAN,
            DeviceClass.EYEVUE,
            DeviceClass.TUNEBUDS,
            DeviceClass.MOYOUNG_W620,
        )

        /**
         * Type choices offered only for devices detected as that family: Mentra Live pairs
         * through its SDK, so other devices never show it.
         */
        fun detectedOnlyChoicesFor(device: ScannedDevice): List<DeviceClass> = listOfNotNull(
            DeviceClass.MENTRA_LIVE.takeIf {
                device.detectedClass == DeviceClass.MENTRA_LIVE || device.selectedClass == DeviceClass.MENTRA_LIVE
            },
        )

        fun pairingChoiceFor(detected: DeviceClass): DeviceClass =
            if (detected in CONSUMER_PROTOCOLS || detected == DeviceClass.UNKNOWN) DeviceClass.HEY_CYAN else detected
    }
}
