package com.fersaiyan.cyanbridge.glasses

import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class HeyCyanPhotoReadyTimeoutException : IllegalStateException("Glasses did not confirm a fresh photo")

/** One capture owner, fresh-photo waiter and thumbnail receiver for background consumers. */
object HeyCyanPhotoCapture {
    private val owner = HeyCyanCaptureOwner()
    fun isActive(): Boolean = owner.isActive()

    data class Photo(val jpeg: ByteArray, val commandAtMs: Long, val photoReadyAtMs: Long)

    suspend fun capture(quality: Int, settleMs: Long = 250, shutter: Boolean = true): Photo =
        owner.capture(quality, settleMs, shutter)

    /** Only call in response to an unowned, fresh physical-photo notification. */
    suspend fun receiveHardwarePhoto(): ByteArray = owner.receiveHardwarePhoto()
}

internal class HeyCyanCaptureOwner(
    private val connected: () -> Boolean = { BleOperateManager.getInstance().isConnected },
    private val configure: (Int) -> Unit = { quality ->
        val q = quality.toByte()
        LargeDataHandler.getInstance().glassesControl(byteArrayOf(2, 1, 6, q, q)) { _, _ -> }
    },
    private val trigger: () -> Unit = {
        LargeDataHandler.getInstance().glassesControl(byteArrayOf(2, 1, 1)) { _, _ -> }
    },
    private val listen: ((Long) -> Unit) -> Unit = { onReady ->
        val sdk = LargeDataHandler.getInstance()
        val listener = object : GlassesDeviceNotifyListener() {
            override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
                if (response.loadData.size > 6 && response.loadData[6].toInt() == 0x02) {
                    onReady(System.currentTimeMillis())
                }
            }
        }
        sdk.addOutDeviceListener(7_104, listener)
    },
    private val unlisten: () -> Unit = { LargeDataHandler.getInstance().removeOutDeviceListener(7_104) },
    private val receive: (suspend (Long) -> ByteArray)? = null,
    private val photoReadyTimeoutMs: Long = 5_000,
) {
    private val active = AtomicBoolean(false)
    fun isActive(): Boolean = active.get()

    suspend fun capture(quality: Int, settleMs: Long, shutter: Boolean): HeyCyanPhotoCapture.Photo =
        withPermit { permit ->
            val ready = CompletableDeferred<Long>()
            val started = System.currentTimeMillis()
            try {
                require(quality in 0..5)
                listen { ready.complete(it) }
                check(GlassesSessionCoordinator.isBackgroundCommandActive(permit))
                configure(quality)
                if (shutter) {
                    delay(250)
                    check(GlassesSessionCoordinator.isBackgroundCommandActive(permit))
                    trigger()
                }
                val readyAt = withTimeoutOrNull(photoReadyTimeoutMs) { ready.await() }
                    ?: throw HeyCyanPhotoReadyTimeoutException()
                check(GlassesSessionCoordinator.isBackgroundCommandActive(permit))
                HeyCyanPhotoCapture.Photo(receiveThumbnail(settleMs, permit), started, readyAt)
            } finally {
                unlisten()
            }
        }

    suspend fun receiveHardwarePhoto(): ByteArray = withPermit { permit ->
        receiveThumbnail(250, permit)
    }

    private suspend fun receiveThumbnail(settleMs: Long, permit: BackgroundGlassesCommandPermit): ByteArray =
        receive?.invoke(settleMs) ?: HeyCyanThumbnailReceiver(
            valid = { GlassesSessionCoordinator.isBackgroundCommandActive(permit) },
        ).receive(settleMs)

    private suspend fun <T> withPermit(block: suspend (BackgroundGlassesCommandPermit) -> T): T {
        check(connected()) { "Glasses are not connected" }
        return GlassesPhotoCapture.withCapture(DeviceClass.HEY_CYAN) { lease ->
            val claimed = active.compareAndSet(false, true)
            try {
                check(claimed) { "Photo capture is already active" }
                block(lease.permit)
            } finally {
                if (claimed) active.set(false)
            }
        }
    }
}
