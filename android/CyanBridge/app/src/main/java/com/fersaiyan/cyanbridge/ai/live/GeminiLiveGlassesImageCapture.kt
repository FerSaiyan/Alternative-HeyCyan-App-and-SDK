package com.fersaiyan.cyanbridge.ai.live

import android.content.Context
import android.graphics.BitmapFactory
import com.fersaiyan.cyanbridge.ai.image.ImageThumbnailQuality
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueManager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsManager
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.glasses.GlassesSessionCoordinator
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/** Reuses each selected glasses family's low-latency AI-image path. */
class GeminiLiveGlassesImageCapture(context: Context) {
    private val appContext = context.applicationContext

    suspend fun capture(quality: ImageThumbnailQuality): ByteArray = when (
        DeviceProfileStore.selectedClass(appContext)
    ) {
        DeviceClass.HEY_CYAN -> captureHeyCyan(quality)
        DeviceClass.EYEVUE -> {
            val manager = EyevueManager.getInstance(appContext)
            check(manager.isConnected()) { "Eyevue glasses are not connected" }
            checkNotNull(
                manager.capturePhotoForAi(highQuality = quality == ImageThumbnailQuality.DETAILED),
            ) { "Eyevue photo transfer timed out" }
        }
        DeviceClass.TUNEBUDS -> {
            val manager = TuneBudsManager.getInstance(appContext)
            check(manager.isConnected()) { "TuneBuds glasses are not connected" }
            checkNotNull(manager.capturePhotoForAi()) { "TuneBuds photo transfer timed out" }
        }
        else -> error("Selected glasses do not support automatic Live images")
    }

    private suspend fun captureHeyCyan(quality: ImageThumbnailQuality): ByteArray {
        check(BleOperateManager.getInstance().isConnected) { "Glasses are not connected" }
        val permit = GlassesSessionCoordinator.tryAcquireBackgroundCommand()
            ?: throw IllegalStateException("Glasses are busy with another operation")
        try {
            // Matches the existing Glasses-tab AI image flow: command 0x06 selects thumbnail fidelity.
            LargeDataHandler.getInstance().glassesControl(
                byteArrayOf(0x02, 0x01, 0x06, quality.sdkValue.toByte(), quality.sdkValue.toByte()),
            ) { _, _ -> }
            delay(CAPTURE_SETTLE_MS)
            return receiveThumbnail()
        } finally {
            GlassesSessionCoordinator.releaseBackgroundCommand(permit)
        }
    }

    /** Reads the thumbnail already taken by the glasses' physical AI-photo button. */
    suspend fun captureFromHardwareButton(): ByteArray {
        check(BleOperateManager.getInstance().isConnected) { "Glasses are not connected" }
        val permit = GlassesSessionCoordinator.tryAcquireBackgroundCommand()
            ?: throw IllegalStateException("Glasses are busy with another operation")
        try {
            return receiveThumbnail()
        } finally {
            GlassesSessionCoordinator.releaseBackgroundCommand(permit)
        }
    }

    private suspend fun receiveThumbnail(): ByteArray {
        val output = ByteArrayOutputStream()
        val complete = CompletableDeferred<Boolean>()
        LargeDataHandler.getInstance().getPictureThumbnails { _, isComplete, data ->
            if (data != null && data.isNotEmpty()) output.write(data)
            if (isComplete && !complete.isCompleted) complete.complete(output.size() > 0)
        }
        check(withTimeoutOrNull(TRANSFER_TIMEOUT_MS) { complete.await() } == true) {
            "Glasses thumbnail transfer timed out"
        }
        return output.toByteArray().also { image ->
            check(BitmapFactory.decodeByteArray(image, 0, image.size) != null) {
                "Glasses returned an invalid thumbnail"
            }
        }
    }

    private companion object {
        const val CAPTURE_SETTLE_MS = 4_000L
        const val TRANSFER_TIMEOUT_MS = 10_000L
    }
}
