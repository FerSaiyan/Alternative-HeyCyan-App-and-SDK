package com.fersaiyan.cyanbridge.devices
import com.fersaiyan.cyanbridge.shared.devices.DeviceProfile

import android.content.Context
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass

/**
 * SharedPreferences-backed storage for device class selection.
 *
 * We persist both:
 * - the last selected device profile (for quick UI restore), and
 * - per-MAC class overrides (so user choices survive later scans).
 */
object DeviceProfileStore {
    private const val PREFS = "device_profile"

    private const val KEY_LAST_MAC = "last_mac"
    private const val KEY_LAST_NAME = "last_name"
    private const val KEY_LAST_DETECTED_CLASS = "last_detected_class"
    private const val KEY_LAST_SELECTED_CLASS = "last_selected_class"
    private const val KEY_LAST_USER_OVERRIDDEN = "last_user_overridden"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveLastSelected(context: Context, profile: DeviceProfile) {
        prefs(context).edit()
            .putString(KEY_LAST_MAC, profile.macAddress)
            .putString(KEY_LAST_NAME, profile.advertisedName)
            .putString(KEY_LAST_DETECTED_CLASS, profile.detectedClass.name)
            .putString(KEY_LAST_SELECTED_CLASS, profile.selectedClass.name)
            .putBoolean(KEY_LAST_USER_OVERRIDDEN, profile.userOverridden)
            .apply()

        // Also persist per-device override so a later scan remembers the user's choice.
        if (profile.userOverridden) {
            setUserOverrideForMac(context, profile.macAddress, profile.selectedClass)
        } else {
            clearUserOverrideForMac(context, profile.macAddress)
        }
    }

    fun loadLastSelected(context: Context): DeviceProfile? {
        val p = prefs(context)
        val mac = p.getString(KEY_LAST_MAC, null) ?: return null
        val name = p.getString(KEY_LAST_NAME, null)
        val detected = p.getString(KEY_LAST_DETECTED_CLASS, null)?.let { safeClass(it) } ?: DeviceClass.UNKNOWN
        val selected = p.getString(KEY_LAST_SELECTED_CLASS, null)?.let { safeClass(it) } ?: detected
        val overridden = p.getBoolean(KEY_LAST_USER_OVERRIDDEN, false)
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

    fun getUserOverrideForMac(context: Context, mac: String): DeviceClass? {
        val key = overrideKey(mac)
        val raw = prefs(context).getString(key, null) ?: return null
        return safeClass(raw)
    }

    fun setUserOverrideForMac(context: Context, mac: String, deviceClass: DeviceClass) {
        prefs(context).edit().putString(overrideKey(mac), deviceClass.name).apply()
    }

    fun clearUserOverrideForMac(context: Context, mac: String) {
        prefs(context).edit().remove(overrideKey(mac)).apply()
    }

    private fun overrideKey(mac: String): String {
        // SharedPreferences keys must be simple strings; normalize ':' to '_'.
        return "override_${mac.uppercase().replace(':', '_')}"
    }

    private fun safeClass(name: String): DeviceClass {
        return try {
            DeviceClass.valueOf(name)
        } catch (_: Throwable) {
            // Defensive default for corrupted prefs or enum renames.
            DeviceClass.UNKNOWN
        }
    }
}
