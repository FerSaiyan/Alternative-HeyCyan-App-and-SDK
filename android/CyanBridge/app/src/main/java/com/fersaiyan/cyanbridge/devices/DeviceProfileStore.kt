package com.fersaiyan.cyanbridge.devices
import com.fersaiyan.cyanbridge.shared.devices.DeviceProfile

import android.content.Context
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.prefs.AppPrefs.DeviceProfile as Profile
import com.fersaiyan.cyanbridge.shared.prefs.edit
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.remove
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.fersaiyan.cyanbridge.shared.prefs.store

/**
 * SharedPreferences-backed storage for device class selection.
 *
 * We persist both:
 * - the last selected device profile (for quick UI restore), and
 * - per-MAC class overrides (so user choices survive later scans).
 */
object DeviceProfileStore {
    fun saveLastSelected(context: Context, profile: DeviceProfile) {
        Profile.edit(context) {
            this[Profile.lastMac] = profile.macAddress
            this[Profile.lastName] = profile.advertisedName
            this[Profile.lastDetectedClass] = profile.detectedClass.name
            this[Profile.lastSelectedClass] = profile.selectedClass.name
            this[Profile.lastUserOverridden] = profile.userOverridden
        }

        // Also persist per-device override so a later scan remembers the user's choice.
        if (profile.userOverridden) {
            setUserOverrideForMac(context, profile.macAddress, profile.selectedClass)
        } else {
            clearUserOverrideForMac(context, profile.macAddress)
        }
    }

    fun loadLastSelected(context: Context): DeviceProfile? {
        val p = Profile.store(context)
        val mac = p[Profile.lastMac] ?: return null
        val name = p[Profile.lastName]
        val detected = p[Profile.lastDetectedClass]?.let { safeClass(it) } ?: DeviceClass.UNKNOWN
        val selected = p[Profile.lastSelectedClass]?.let { safeClass(it) } ?: detected
        val overridden = p[Profile.lastUserOverridden]
        return DeviceProfile(mac, name, detected, selected, overridden)
    }

    fun selectedClass(context: Context): DeviceClass =
        loadLastSelected(context)?.selectedClass ?: DeviceClass.UNKNOWN

    fun isMetaSelected(context: Context): Boolean =
        selectedClass(context) == DeviceClass.META_RAYBAN

    fun isMeizuMyvuSelected(context: Context): Boolean =
        selectedClass(context) == DeviceClass.MEIZU_MYVU

    fun isEyevueSelected(context: Context): Boolean =
        selectedClass(context) == DeviceClass.EYEVUE

    fun isTuneBudsSelected(context: Context): Boolean =
        selectedClass(context) == DeviceClass.TUNEBUDS

    fun isMoyoungW620Selected(context: Context): Boolean =
        selectedClass(context) == DeviceClass.MOYOUNG_W620

    fun getUserOverrideForMac(context: Context, mac: String): DeviceClass? {
        val raw = overrideFor(mac)[context] ?: return null
        return safeClass(raw)
    }

    fun setUserOverrideForMac(context: Context, mac: String, deviceClass: DeviceClass) {
        overrideFor(mac)[context] = deviceClass.name
    }

    fun clearUserOverrideForMac(context: Context, mac: String) {
        overrideFor(mac).remove(context)
    }

    // SharedPreferences keys must be simple strings; normalize ':' to '_'.
    private fun overrideFor(mac: String) = Profile.classOverride[mac.uppercase().replace(':', '_')]

    private fun safeClass(name: String): DeviceClass {
        return try {
            DeviceClass.valueOf(name)
        } catch (_: Throwable) {
            // Defensive default for corrupted prefs or enum renames.
            DeviceClass.UNKNOWN
        }
    }
}
