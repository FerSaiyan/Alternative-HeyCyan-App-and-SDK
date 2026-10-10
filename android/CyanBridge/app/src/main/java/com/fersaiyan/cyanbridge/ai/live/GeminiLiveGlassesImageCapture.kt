package com.fersaiyan.cyanbridge.ai.live

import android.content.Context
import com.fersaiyan.cyanbridge.ai.image.ImageThumbnailQuality
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.mentra.MentraPhotoCapture
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.glasses.HeyCyanPhotoCapture
import com.fersaiyan.cyanbridge.glasses.GlassesAiPhotoCapture
import com.fersaiyan.cyanbridge.glasses.GlassesPhotoValidation

/** Reuses each selected glasses family's low-latency AI-image path. */
class GeminiLiveGlassesImageCapture(context: Context) {
    private val appContext = context.applicationContext

    suspend fun capture(quality: ImageThumbnailQuality): ByteArray = when (
        DeviceProfileStore.selectedClass(appContext)
    ) {
        DeviceClass.HEY_CYAN, DeviceClass.EYEVUE, DeviceClass.TUNEBUDS ->
            GlassesAiPhotoCapture(appContext).capture(quality.sdkValue).jpeg
        DeviceClass.MENTRA_LIVE -> MentraPhotoCapture(appContext).capture()
        else -> error("Selected glasses do not support automatic Live images")
    }

    /** Reads the thumbnail already taken by the glasses' physical AI-photo button. */
    suspend fun captureFromHardwareButton(photo: ManualGlassesPhoto = ManualGlassesPhoto(DeviceClass.HEY_CYAN)): ByteArray {
        check(DeviceProfileStore.selectedClass(appContext) == photo.device) { "Glasses selection changed during photo delivery" }
        return when (photo.device) {
            DeviceClass.HEY_CYAN -> HeyCyanPhotoCapture.receiveHardwarePhoto()
            DeviceClass.EYEVUE, DeviceClass.TUNEBUDS -> GlassesPhotoValidation.requireJpeg(
                checkNotNull(photo.acquisition) { "Native manual photo has no fresh acquisition" }.await(),
            )
            else -> error("This glasses family has no manual Live photo transport")
        }
    }
}
