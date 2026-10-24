package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

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
        check(BleOperateManager.getInstance().isConnected) { "Glasses are not connected" }
        val permit = GlassesSessionCoordinator.tryAcquireBackgroundCommand()
            ?: throw IllegalStateException("Glasses are busy with another operation")
        val outputFile = File(
            context.getExternalFilesDir("DCIM") ?: context.filesDir,
            "${namePrefix}_${System.currentTimeMillis()}.jpg",
        )
        outputFile.parentFile?.mkdirs()
        outputFile.delete()

        val photoReady = CompletableDeferred<Long>()
        val listener = object : GlassesDeviceNotifyListener() {
            override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
                if (response.loadData.size > 6 && response.loadData[6].toInt() == PHOTO_READY_EVENT) {
                    if (!photoReady.isCompleted) photoReady.complete(System.currentTimeMillis())
                }
            }
        }

        try {
            captureAwaitingPhotoReady.set(true)
            LargeDataHandler.getInstance().addOutDeviceListener(NOTIFY_LISTENER_ID, listener)
            val captureCommand = buildChatAiCaptureCommand(qualityLevel)
            val captureCommandAtMs = System.currentTimeMillis()
            Log.i(
                TAG,
                "Sending Chat-AI thumbnail command=${captureCommand.joinToString(prefix = "[", postfix = "]") { "0x%02X".format(it) }} " +
                    "(no Home-AI trailing mode byte)",
            )
            LargeDataHandler.getInstance().glassesControl(
                captureCommand,
            ) { _, response ->
                Log.i(TAG, "Capture command response dataType=${response.dataType} error=${response.errorCode}")
            }
            val photoReadyAtMs = withTimeoutOrNull(PHOTO_READY_TIMEOUT_MS) { photoReady.await() }
            check(photoReadyAtMs != null) {
                "Glasses did not confirm a fresh photo"
            }
            val settleMs = WalkingAidPreferences.getPhotoSettleDelayMs(context)
            if (settleMs > 0) delay(settleMs)
            receiveThumbnail(outputFile)
            val decoded = BitmapFactory.decodeFile(outputFile.absolutePath)
            check(decoded != null) {
                "Glasses returned an invalid thumbnail"
            }
            decoded.recycle()
            return CapturedThumbnail(
                file = outputFile,
                captureCommandAtMs = captureCommandAtMs,
                estimatedExposureAtMs = photoReadyAtMs,
                receivedAtMs = System.currentTimeMillis(),
            )
        } catch (error: Exception) {
            outputFile.delete()
            throw error
        } finally {
            captureAwaitingPhotoReady.set(false)
            LargeDataHandler.getInstance().removeOutDeviceListener(NOTIFY_LISTENER_ID)
            GlassesSessionCoordinator.releaseBackgroundCommand(permit)
        }
    }

    private suspend fun receiveThumbnail(outputFile: File) {
        val completed = AtomicBoolean(false)
        val acceptingData = AtomicBoolean(true)
        val receivedBytes = java.util.concurrent.atomic.AtomicLong(0L)
        val transfer = CompletableDeferred<Boolean>()
        val writeLock = Any()
        val outputStream = FileOutputStream(outputFile, false)

        LargeDataHandler.getInstance().getPictureThumbnails { _, isComplete, data ->
            if (data != null && data.isNotEmpty()) {
                synchronized(writeLock) {
                    if (acceptingData.get()) {
                        outputStream.write(data)
                        receivedBytes.addAndGet(data.size.toLong())
                    }
                }
            }
            if (isComplete && completed.compareAndSet(false, true)) {
                transfer.complete(receivedBytes.get() >= MIN_IMAGE_BYTES)
            }
        }
        val succeeded = withTimeoutOrNull(TRANSFER_TIMEOUT_MS) { transfer.await() } == true
        synchronized(writeLock) {
            acceptingData.set(false)
            runCatching { outputStream.flush() }
            runCatching { outputStream.close() }
        }
        check(succeeded) {
            "Glasses thumbnail transfer timed out"
        }
    }

    companion object {
        const val THUMBNAIL_QUALITY_LEVEL = 3
        private val captureAwaitingPhotoReady = AtomicBoolean(false)

        fun isAwaitingPhotoReady(): Boolean = captureAwaitingPhotoReady.get()

        fun buildChatAiCaptureCommand(qualityLevel: Int): ByteArray {
            require(qualityLevel in 0..5) { "Thumbnail quality must be between 0 and 5" }
            val quality = qualityLevel.toByte()
            return byteArrayOf(0x02, 0x01, 0x06, quality, quality)
        }

        private const val TAG = "WalkingAidCapture"
        private const val NOTIFY_LISTENER_ID = 7_103
        private const val PHOTO_READY_EVENT = 0x02
        private const val PHOTO_READY_TIMEOUT_MS = 5_000L
        private const val PHOTO_READY_SETTLE_MS = 250L
        private const val TRANSFER_TIMEOUT_MS = 14_000L
        private const val MIN_IMAGE_BYTES = 1_024L
    }
}
