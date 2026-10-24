package com.fersaiyan.cyanbridge.devices.metarayban

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.shared.prefs.AppPrefs.MetaPermissionRequests
import com.fersaiyan.cyanbridge.shared.prefs.edit
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set

internal object MetaDatPermissions {
    fun required(sdk: Int = Build.VERSION.SDK_INT): Array<String> = if (sdk >= 31) arrayOf(
        Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
    ) else arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)

    fun missing(context: Context): Array<String> = required().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }.toTypedArray()

    fun recordRequestResult(context: Context, result: Map<String, Boolean>) {
        MetaPermissionRequests.edit(context) {
            result.keys.forEach { this[MetaPermissionRequests.requested[it]] = true }
        }
    }

    fun permanentlyDenied(activity: Activity): Boolean {
        return missing(activity).any {
            MetaPermissionRequests.requested[it][activity] && !activity.shouldShowRequestPermissionRationale(it)
        }
    }
}
