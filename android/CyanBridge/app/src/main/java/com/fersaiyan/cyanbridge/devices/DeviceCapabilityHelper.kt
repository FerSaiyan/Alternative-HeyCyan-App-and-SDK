package com.fersaiyan.cyanbridge.devices

import android.content.Context
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass

/**
 * Utility helper to query hardware capabilities for the currently selected device profile.
 */
object DeviceCapabilityHelper {

    fun selectedClass(context: Context): DeviceClass {
        return DeviceProfileStore.selectedClass(context)
    }

    fun hasCamera(context: Context): Boolean {
        val selected = selectedClass(context)
        return selected in setOf(
            DeviceClass.HEY_CYAN,
            DeviceClass.EYEVUE,
            DeviceClass.TUNEBUDS,
            DeviceClass.META_RAYBAN,
            DeviceClass.UNKNOWN,
        )
    }

    fun hasOnboardStorage(context: Context): Boolean {
        val selected = selectedClass(context)
        return selected in setOf(DeviceClass.HEY_CYAN, DeviceClass.EYEVUE, DeviceClass.TUNEBUDS, DeviceClass.UNKNOWN)
    }

    fun unavailableCameraReason(context: Context): String? {
        return when (selectedClass(context)) {
            DeviceClass.MEIZU_MYVU -> "Selected device profile (Meizu MYVU) has no camera."
            DeviceClass.GENERIC_AUDIO -> "Selected device profile (Earbuds / Audio-only glasses) has no camera."
            DeviceClass.MOYOUNG_W620 -> "The MoYoung / W620 adapter supports media sync, but direct camera capture is not validated yet."
            else -> null
        }
    }
}
