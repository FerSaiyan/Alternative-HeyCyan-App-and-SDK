package com.fersaiyan.cyanbridge.devices

import android.content.Context
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfile
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles

/**
 * Queries the capability profile ([GlassesFamilyProfiles]) of the currently selected device.
 */
object DeviceCapabilityHelper {

    fun selectedClass(context: Context): DeviceClass {
        return DeviceProfileStore.selectedClass(context)
    }

    fun selectedProfile(context: Context): GlassesFamilyProfile =
        GlassesFamilyProfiles.forClass(selectedClass(context))

    fun hasCamera(context: Context): Boolean = selectedProfile(context).hasCamera

    fun unavailableCameraReason(context: Context): String? = selectedProfile(context).cameraUnavailableReason
}
