package com.fersaiyan.cyanbridge.glasses

import android.content.Context
import com.fersaiyan.cyanbridge.bridge.core.GlassesCapability
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueManager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsManager
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles

/** Selected native AI-photo transport shared by Live, Walking Aid and visual notes. */
class GlassesAiPhotoCapture(context: Context) {
    private val appContext = context.applicationContext

    data class Photo(val jpeg: ByteArray, val commandAtMs: Long, val estimatedExposureAtMs: Long)

    /** One family's AI-photo transport. */
    private fun interface Source {
        suspend fun capture(context: Context, quality: Int, settleMs: Long, heyCyanShutter: Boolean): Photo
    }

    suspend fun capture(quality: Int, settleMs: Long = 250, heyCyanShutter: Boolean = true): Photo {
        val device = DeviceProfileStore.selectedClass(appContext)
        val source = sources[device]
            ?.takeIf { GlassesFamilyProfiles.forClass(device).supports(GlassesCapability.AI_PHOTO_CAPTURE) }
            ?: error("Selected glasses do not support native AI-photo capture")
        return source.capture(appContext, quality, settleMs, heyCyanShutter)
    }

    private companion object {
        /** Native paths other than HeyCyan have one verified quality and no exposure timestamp. */
        fun nativeSource(device: DeviceClass, capture: suspend (Context) -> ByteArray) =
            Source { context, _, _, _ ->
                val commandedAt = System.currentTimeMillis()
                val jpeg = capture(context)
                check(DeviceProfileStore.selectedClass(context) == device) { "Glasses selection changed during capture" }
                Photo(jpeg, commandedAt, commandedAt)
            }

        val sources: Map<DeviceClass, Source> = mapOf(
            DeviceClass.HEY_CYAN to Source { _, quality, settleMs, heyCyanShutter ->
                val photo = HeyCyanPhotoCapture.capture(quality, settleMs, heyCyanShutter)
                Photo(photo.jpeg, photo.commandAtMs, photo.photoReadyAtMs)
            },
            DeviceClass.EYEVUE to nativeSource(DeviceClass.EYEVUE) { EyevueManager.getInstance(it).capturePhotoForAi() },
            DeviceClass.TUNEBUDS to nativeSource(DeviceClass.TUNEBUDS) { TuneBudsManager.getInstance(it).capturePhotoForAi() },
        )
    }
}
