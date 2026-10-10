package com.fersaiyan.cyanbridge.glasses

import android.os.SystemClock
import android.util.Log
import com.oudmon.ble.base.communication.ILargeDataResponse
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.PictureThumbnailsResponse
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class HeyCyanThumbnailTransferException(message: String, cause: Throwable? = null) : GlassesPhotoTransferException(message, cause)

/** Caller must hold the Android background permit through receive AND cleanup. */
internal class HeyCyanThumbnailReceiver(
    private val transport: Transport = SdkTransport(),
    private val decode: (ByteArray) -> Boolean = GlassesPhotoValidation::isCompleteJpeg,
    private val timeoutMs: Long = 10_000,
    private val quietMs: Long = 750,
    private val drainLimitMs: Long = 2_000,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val valid: () -> Boolean = { true },
) {
    interface Transport {
        fun attach(callback: (ByteArray) -> Unit)
        fun request(index: Int)
        fun detach()
    }

    suspend fun receive(settleMs: Long = 250): ByteArray {
        val lock = Any()
        var attempt: Attempt? = null
        var lastPacketAt = clock()
        try {
            check(valid()) { "Photo command ownership expired" }
            transport.attach { frame ->
                synchronized(lock) {
                    lastPacketAt = clock()
                    if (valid()) attempt?.accept(frame)
                    else attempt?.result?.completeExceptionally(IllegalStateException("Photo command ownership expired"))
                }
            }
            delay(settleMs)
            repeat(2) { number ->
                val current = Attempt { index ->
                    check(valid()) { "Photo command ownership expired" }
                    transport.request(index)
                }
                synchronized(lock) {
                    check(valid()) { "Photo command ownership expired" }
                    attempt = current
                    transport.request(0)
                }
                val bytes = try {
                    withTimeoutOrNull(timeoutMs) { current.result.await() }
                } finally {
                    synchronized(lock) { attempt = null }
                }
                check(valid()) { "Photo command ownership expired" }
                if (bytes != null && decode(bytes)) return bytes
                Log.w(TAG, "attempt=${number + 1} failed packets=${current.nextIndex} bytes=${current.bytes.size()}")
                // Keep the SAME raw handler while draining; it never chains discarded packets.
                // A tail arriving during retry cannot start an attempt: only index zero can.
                if (number == 0) check(drain { synchronized(lock) { lastPacketAt } }) {
                    "Glasses thumbnail transfer did not become idle"
                }
            }
            error("Glasses thumbnail transfer timed out or returned an invalid image")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw HeyCyanThumbnailTransferException(error.message ?: "Glasses thumbnail transfer failed", error)
        } finally {
            synchronized(lock) { attempt = null }
            withContext(NonCancellable) {
                try { drain { synchronized(lock) { lastPacketAt } } }
                finally { synchronized(lock) { transport.detach() } }
            }
        }
    }

    private suspend fun drain(lastPacket: () -> Long): Boolean {
        val started = clock()
        // Require a full quiet window after closing, even if the last packet was old.
        while (clock() - maxOf(started, lastPacket()) < quietMs) {
            if (clock() - started >= drainLimitMs) return false
            delay(minOf(quietMs, 50))
        }
        return true
    }

    private class Attempt(val request: (Int) -> Unit) {
        val result = CompletableDeferred<ByteArray?>()
        val bytes = ByteArrayOutputStream()
        var nextIndex = 0
        private var total = -1

        fun accept(frame: ByteArray) {
            if (result.isCompleted) return
            if (frame.size < 11) { result.complete(null); return }
            val count = u16(frame, 7)
            val index = u16(frame, 9)
            if (index == 0 || index + 1 == count || index != nextIndex || index % 32 == 0) {
                Log.i(TAG, "packet index=$index total=$count expected=$nextIndex payloadBytes=${frame.size - 11}")
            }
            // Discard late tails from a previous attempt, without issuing another request.
            if (nextIndex == 0 && index != 0) return
            if (count == 0) { result.complete(null); return }
            if (index < nextIndex) return // Duplicate already accepted packet.
            if (index != nextIndex || index >= count || (total != -1 && total != count) ||
                frame.size == 11 || bytes.size() + frame.size - 11 > MAX_BYTES
            ) { result.complete(null); return }
            total = count
            bytes.write(frame, 11, frame.size - 11)
            nextIndex++
            if (nextIndex == total) result.complete(bytes.toByteArray())
            else try { request(nextIndex) } catch (error: Exception) { result.completeExceptionally(error) }
        }

        private fun u16(data: ByteArray, offset: Int) =
            (data[offset].toInt() and 255) or ((data[offset + 1].toInt() and 255) shl 8)
    }

    /** Replace only FD's handler; leave control, audio and notification slots alone. */
    internal class SdkTransport : Transport {
        private val sdk = LargeDataHandler.getInstance()
        // The AAR exposes the response map but keeps its packet-request helper private.
        // Reuse its exact framing/queue/MTU logic rather than inventing a parallel BLE writer.
        private val requestPacket = LargeDataHandler::class.java
            .getDeclaredMethod("syncPictureThumbnails", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        private var handler: ILargeDataResponse<PictureThumbnailsResponse>? = null

        override fun attach(callback: (ByteArray) -> Unit) {
            val owned = ILargeDataResponse<PictureThumbnailsResponse> { opcode, response ->
                if (opcode and 255 == 0xFD) callback(response.subData)
            }
            check(sdk.respMap.putIfAbsent(-3, owned) == null) { "Thumbnail response slot is already owned" }
            handler = owned
        }

        override fun request(index: Int) { requestPacket.invoke(sdk, index) }

        override fun detach() {
            handler?.let { sdk.respMap.remove(-3, it) }
            handler = null
        }
    }

    private companion object {
        const val TAG = "HeyCyanThumbnail"
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}
