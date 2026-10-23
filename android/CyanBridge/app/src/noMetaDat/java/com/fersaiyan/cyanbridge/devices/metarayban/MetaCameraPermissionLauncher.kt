package com.fersaiyan.cyanbridge.devices.metarayban

import androidx.activity.ComponentActivity

/** No DAT dependency is present: permission can never be granted by this build. */
class MetaCameraPermissionLauncher(activity: ComponentActivity, private val onResult: (Boolean) -> Unit) {
    fun launch() = onResult(false)
}
