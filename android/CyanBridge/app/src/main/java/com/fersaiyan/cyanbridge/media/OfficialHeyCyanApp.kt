package com.fersaiyan.cyanbridge.media

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass

object OfficialHeyCyanApp {
    const val PACKAGE_NAME = "com.glasssutdio.wear"

    private const val PREFS_NAME = "media_sync_warnings"
    private const val KEY_WARNING_SUPPRESSED = "official_heycyan_warning_suppressed"

    fun shouldWarn(
        selectedClass: DeviceClass,
        installed: Boolean,
        suppressed: Boolean,
    ): Boolean = selectedClass == DeviceClass.HEY_CYAN && installed && !suppressed

    fun shouldWarn(context: Context, selectedClass: DeviceClass): Boolean = shouldWarn(
        selectedClass = selectedClass,
        installed = isInstalled(context),
        suppressed = isWarningSuppressed(context),
    )

    fun suppressWarning(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WARNING_SUPPRESSED, true)
            .apply()
    }

    fun appInfoIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", PACKAGE_NAME, null),
    )

    private fun isWarningSuppressed(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WARNING_SUPPRESSED, false)

    private fun isInstalled(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(
                PACKAGE_NAME,
                PackageManager.ApplicationInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(PACKAGE_NAME, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
