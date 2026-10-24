package com.fersaiyan.cyanbridge.media

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.prefs.MediaPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set

object OfficialHeyCyanApp {
    const val PACKAGE_NAME = "com.glasssutdio.wear"

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
        MediaPrefs.SyncWarnings.officialHeyCyanWarningSuppressed[context] = true
    }

    fun appInfoIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", PACKAGE_NAME, null),
    )

    private fun isWarningSuppressed(context: Context): Boolean =
        MediaPrefs.SyncWarnings.officialHeyCyanWarningSuppressed[context]

    fun isInstalled(context: Context): Boolean = try {
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
