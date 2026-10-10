package com.fersaiyan.cyanbridge.glasses

import android.graphics.BitmapFactory
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

open class GlassesPhotoTransferException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Shared camera ownership across native transports and the HeyCyan SDK. */
object GlassesPhotoCapture {
    class Lease internal constructor(
        val device: DeviceClass,
        internal val permit: BackgroundGlassesCommandPermit,
        private val job: Job,
    ) {
        fun ensureActive() {
            check(job.isActive && GlassesSessionCoordinator.isBackgroundCommandActive(permit)) {
                "Photo capture ownership expired"
            }
        }

        internal fun cancel() { job.cancel(CancellationException("$device photo transport disconnected")) }
    }

    private var active: Lease? = null

    @Synchronized fun isCapturing(device: DeviceClass): Boolean = active?.device == device
    @Synchronized fun currentDevice(): DeviceClass? = active?.device

    /** Cancel only this transport's capture; a disconnect must not unlock another device. */
    fun invalidate(device: DeviceClass) {
        val lease = synchronized(this) { active?.takeIf { it.device == device } }
        lease?.cancel()
    }

    suspend fun <T> withCapture(device: DeviceClass, block: suspend (Lease) -> T): T = coroutineScope {
        val permit = GlassesSessionCoordinator.tryAcquireBackgroundCommand(device)
            ?: error("Glasses are busy with another operation")
        val lease = Lease(device, permit, currentCoroutineContext().job)
        try {
            synchronized(this@GlassesPhotoCapture) {
                check(active == null) { "Another photo capture is still cleaning up" }
                active = lease
            }
            block(lease)
        } finally {
            synchronized(this@GlassesPhotoCapture) { if (active === lease) active = null }
            GlassesSessionCoordinator.releaseBackgroundCommand(permit)
        }
    }
}

/** Packet assemblers bound memory; consumers additionally require a complete JPEG and full decode. */
object GlassesPhotoValidation {
    const val MAX_IMAGE_BYTES = 4 * 1024 * 1024

    fun isCompleteJpeg(bytes: ByteArray): Boolean =
        bytes.size in 4..MAX_IMAGE_BYTES && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[bytes.lastIndex - 1] == 0xFF.toByte() && bytes.last() == 0xD9.toByte() &&
            (BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { it.recycle(); true } ?: false)

    fun requireJpeg(bytes: ByteArray): ByteArray {
        if (!isCompleteJpeg(bytes)) throw GlassesPhotoTransferException("Glasses returned an incomplete or invalid JPEG")
        return bytes
    }
}
