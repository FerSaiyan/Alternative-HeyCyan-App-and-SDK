package com.fersaiyan.cyanbridge.glasses

import android.content.Context
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueManager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsManager
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass

/** Selected native AI-photo transport shared by Live, Walking Aid and visual notes. */
class GlassesAiPhotoCapture(context: Context) {
    private val appContext = context.applicationContext

    data class Photo(val jpeg: ByteArray, val commandAtMs: Long, val estimatedExposureAtMs: Long)

    suspend fun capture(quality: Int, settleMs: Long = 250, heyCyanShutter: Boolean = true): Photo {
        val device = DeviceProfileStore.selectedClass(appContext)
        if (device == DeviceClass.HEY_CYAN) {
            val photo = HeyCyanPhotoCapture.capture(quality, settleMs, heyCyanShutter)
            return Photo(photo.jpeg, photo.commandAtMs, photo.photoReadyAtMs)
        }
        // Native AI paths currently have one verified quality and no exposure timestamp.
        val commandedAt = System.currentTimeMillis()
        val jpeg = when (device) {
            DeviceClass.EYEVUE -> EyevueManager.getInstance(appContext).capturePhotoForAi()
            DeviceClass.TUNEBUDS -> TuneBudsManager.getInstance(appContext).capturePhotoForAi()
            else -> error("Selected glasses do not support native AI-photo capture")
        }
        check(DeviceProfileStore.selectedClass(appContext) == device) { "Glasses selection changed during capture" }
        return Photo(jpeg, commandedAt, commandedAt)
    }
}
