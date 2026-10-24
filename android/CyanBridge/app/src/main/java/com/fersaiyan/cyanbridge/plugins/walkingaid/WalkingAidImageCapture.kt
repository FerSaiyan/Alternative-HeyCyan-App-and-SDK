package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.content.Context
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager
import com.fersaiyan.cyanbridge.glasses.HeyCyanPhotoCapture
import com.fersaiyan.cyanbridge.glasses.GlassesAiPhotoCapture
import java.io.File

/** Captures a fresh, low-resolution image suitable for local Walking Aid inference. */
class WalkingAidImageCapture(context: Context) {
    private val context = context.applicationContext

    data class CapturedThumbnail(
        val file: File,
        val captureCommandAtMs: Long,
        val estimatedExposureAtMs: Long,
        val receivedAtMs: Long,
    )

    suspend fun captureFreshThumbnail(namePrefix: String): CapturedThumbnail =
        captureFreshThumbnailWithQuality(namePrefix, WalkingAidPreferences.getThumbnailQualityLevel(context))

    suspend fun captureFreshThumbnailWithQuality(namePrefix: String, qualityLevel: Int): CapturedThumbnail {
        if (DeviceProfileStore.isMetaSelected(context)) {
            val manager = MetaRaybanManager.getInstance(context)
            if (!manager.isInitialized.value) manager.initialize()
            val captureCommandAtMs = System.currentTimeMillis()
            val photo = manager.capturePhotoOnce()
            val file = manager.savePhotoForProcessing(photo, namePrefix)
            return CapturedThumbnail(
                file = file,
                captureCommandAtMs = captureCommandAtMs,
                estimatedExposureAtMs = captureCommandAtMs,
                receivedAtMs = System.currentTimeMillis(),
            )
        }

        val outputFile = File(
            context.getExternalFilesDir("DCIM") ?: context.filesDir,
            "${namePrefix}_${System.currentTimeMillis()}.jpg",
        )
        outputFile.parentFile?.mkdirs()
        outputFile.delete()

        try {
            val photo = GlassesAiPhotoCapture(context).capture(
                quality = qualityLevel,
                settleMs = WalkingAidPreferences.getPhotoSettleDelayMs(context),
                heyCyanShutter = false,
            )
            outputFile.writeBytes(photo.jpeg)
            return CapturedThumbnail(
                file = outputFile,
                captureCommandAtMs = photo.commandAtMs,
                estimatedExposureAtMs = photo.estimatedExposureAtMs,
                receivedAtMs = System.currentTimeMillis(),
            )
        } catch (error: Exception) {
            outputFile.delete()
            throw error
        }
    }

    companion object {
        fun isAwaitingPhotoReady(): Boolean = HeyCyanPhotoCapture.isActive()
    }
}
