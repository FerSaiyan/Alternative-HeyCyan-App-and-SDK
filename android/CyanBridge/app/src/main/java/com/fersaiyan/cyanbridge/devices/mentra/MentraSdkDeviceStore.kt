package com.fersaiyan.cyanbridge.devices.mentra

import android.content.Context
import com.fersaiyan.cyanbridge.shared.devices.mentra.MentraSdkModel
import com.fersaiyan.cyanbridge.shared.prefs.AppPrefs.MentraSdkDevice as Saved
import com.fersaiyan.cyanbridge.shared.prefs.edit
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.fersaiyan.cyanbridge.shared.prefs.store
import com.mentra.bluetoothsdk.Device
import com.mentra.bluetoothsdk.DeviceModel

/**
 * The SDK record of the paired display glasses. Their SDK name is not their Bluetooth name (G1:
 * the pair number, G2: the serial number, AR99: "SN: …"), so the record the SDK scanner returned
 * is kept and restored as the SDK default device on launch. Mentra Live keeps using the profile's
 * Bluetooth name, as before.
 */
internal object MentraSdkDeviceStore {
    fun save(context: Context, device: Device) {
        Saved.edit(context) {
            this[Saved.model] = device.model.deviceType
            this[Saved.deviceName] = device.name
            this[Saved.address] = device.address
            this[Saved.projectName] = device.projectName
        }
    }

    /** The saved glasses when they are [model]; null for another model or none. */
    fun load(context: Context, model: MentraSdkModel): Device? {
        val prefs = Saved.store(context)
        if (prefs[Saved.model] != model.sdkDeviceType) return null
        val name = prefs[Saved.deviceName]?.takeIf { it.isNotBlank() } ?: return null
        return Device(
            model = model.sdkModel(),
            name = name,
            address = prefs[Saved.address]?.takeIf { it.isNotBlank() },
            projectName = prefs[Saved.projectName]?.takeIf { it.isNotBlank() },
        )
    }
}

/** The SDK's [DeviceModel] for this model. */
internal fun MentraSdkModel.sdkModel(): DeviceModel = DeviceModel.entries.first { it.deviceType == sdkDeviceType }
