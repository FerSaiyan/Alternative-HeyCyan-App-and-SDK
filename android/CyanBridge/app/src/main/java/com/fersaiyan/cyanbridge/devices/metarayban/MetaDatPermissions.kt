package com.fersaiyan.cyanbridge.devices.metarayban

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

internal object MetaDatPermissions {
    fun required(sdk: Int = Build.VERSION.SDK_INT): Array<String> = if (sdk >= 31) arrayOf(
        Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
    ) else arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)

    fun missing(context: Context): Array<String> = required().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }.toTypedArray()

    fun recordRequestResult(context: Context, result: Map<String, Boolean>) {
        val editor = context.getSharedPreferences("meta_permission_requests", Context.MODE_PRIVATE).edit()
        result.keys.forEach { editor.putBoolean(it, true) }
        editor.apply()
    }

    fun permanentlyDenied(activity: Activity): Boolean {
        val prefs = activity.getSharedPreferences("meta_permission_requests", Context.MODE_PRIVATE)
        return missing(activity).any { prefs.getBoolean(it, false) && !activity.shouldShowRequestPermissionRationale(it) }
    }
}
