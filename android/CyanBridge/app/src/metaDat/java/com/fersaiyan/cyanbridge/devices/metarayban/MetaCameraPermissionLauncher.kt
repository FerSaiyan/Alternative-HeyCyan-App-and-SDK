package com.fersaiyan.cyanbridge.devices.metarayban

import androidx.activity.ComponentActivity
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus

/** Keeps vendor permission types out of activities compiled without DAT. */
class MetaCameraPermissionLauncher(activity: ComponentActivity, onResult: (Boolean) -> Unit) {
    private val launcher = activity.registerForActivityResult(Wearables.RequestPermissionContract()) {
        onResult(it.getOrDefault(PermissionStatus.Denied) == PermissionStatus.Granted)
    }
    fun launch() = launcher.launch(Permission.CAMERA)
}
