package com.fersaiyan.cyanbridge.glasses

import android.os.SystemClock
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One waiter per capture. Native push streams cannot retry a read without another shutter. */
internal class NativeGlassesPhotoCapture(
    private val device: DeviceClass,
    private val connected: () -> Boolean,
    private val trigger: suspend () -> Unit,
    private val resetStream: () -> Unit,
    private val decode: (ByteArray) -> Boolean = GlassesPhotoValidation::isCompleteJpeg,
    private val quietMs: Long = 750,
    private val drainLimitMs: Long = 2_000,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val manualScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private class Attempt(val lease: GlassesPhotoCapture.Lease) {
        val result = CompletableDeferred<ByteArray>()
    }
    private val lock = Any()
    private var active: Attempt? = null
    private var lastPacketAt = 0L
    private var draining = false

    fun onPacket() { synchronized(lock) { lastPacketAt = clock() } }

    /** Reserve camera ownership at a native stream START, before its first image chunk. */
    fun onManualStreamStart(timeoutMs: Long, started: (Deferred<ByteArray>) -> Unit) {
        synchronized(lock) {
            if (active != null || draining || !GlassesSessionCoordinator.canRunBackgroundCommand()) return
            val acquisition = manualScope.async(start = CoroutineStart.UNDISPATCHED) {
                receive(timeoutMs, requestShutter = false)
            }
            started(acquisition)
        }
    }

    /** True means this frame was consumed/dropped by an owner, so it must not become manual input. */
    fun onPhoto(image: ByteArray): Boolean = synchronized(lock) {
        lastPacketAt = clock()
        val attempt = active
        if (attempt != null) {
            runCatching { attempt.lease.ensureActive() }
                .onSuccess { attempt.result.complete(image.copyOf()) }
                .onFailure { attempt.result.completeExceptionally(it) }
            true
        } else draining || GlassesPhotoCapture.isCapturing(device)
    }

    fun onFailure(message: String) = synchronized(lock) {
        active?.result?.completeExceptionally(GlassesPhotoTransferException(message))
    }

    suspend fun capture(timeoutMs: Long): ByteArray = receive(timeoutMs, requestShutter = true)

    private suspend fun receive(timeoutMs: Long, requestShutter: Boolean): ByteArray = GlassesPhotoCapture.withCapture(device) { lease ->
        check(connected()) { "$device glasses are not connected" }
        val attempt = Attempt(lease)
        try {
            resetStream()
            synchronized(lock) { active = attempt }
            val jpeg = withTimeoutOrNull(timeoutMs) {
                lease.ensureActive()
                if (requestShutter) trigger() // The write/ack shares the SAME bounded capture deadline.
                attempt.result.await()
            } ?: throw GlassesPhotoTransferException("$device photo transfer timed out")
            lease.ensureActive()
            if (!decode(jpeg)) throw GlassesPhotoTransferException("$device returned an invalid photo")
            jpeg
        } catch (error: TimeoutCancellationException) {
            throw GlassesPhotoTransferException("$device photo command timed out", error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: GlassesPhotoTransferException) {
            throw error
        } catch (error: Exception) {
            throw GlassesPhotoTransferException("$device photo capture failed: ${error.message}", error)
        } finally {
            synchronized(lock) { active = null; draining = true }
            withContext(NonCancellable) {
                try {
                    val started = clock()
                    while (clock() - synchronized(lock) { maxOf(started, lastPacketAt) } < quietMs &&
                        clock() - started < drainLimitMs
                    ) delay(minOf(quietMs, 50))
                } finally {
                    resetStream()
                    synchronized(lock) { draining = false }
                }
            }
        }
    }
}
