package com.fersaiyan.cyanbridge.customization

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueManager
import com.fersaiyan.cyanbridge.devices.memomind.MemoMindOpusDecoder
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsManager
import com.fersaiyan.cyanbridge.shared.customization.GlassesMicCapture
import com.fersaiyan.cyanbridge.shared.customization.GlassesMicFraming
import com.fersaiyan.cyanbridge.shared.customization.OpusRepacketizer
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.ILargeDataResponse
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.AiChatResponse
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.TimeSource

/**
 * A glasses-mic take: the family opens its Opus stream, packets are decoded in one coroutine
 * (MediaCodec is not thread-safe) to 16 kHz mono PCM16. A take ends at its length (counted in
 * 20 ms packets), on [GlassesMicCapture.record]'s stop signal, or when the glasses stop streaming.
 */
internal abstract class OpusMicCapture : GlassesMicCapture {
    /** Opens the stream; [onPackets] receives raw 20 ms Opus packets on any thread. */
    protected abstract suspend fun open(onPackets: (List<ByteArray>) -> Unit): Boolean

    protected abstract suspend fun close()

    /** Runs while the stream is open (HeyCyan heartbeat). */
    protected open suspend fun keepAlive() = Unit

    override suspend fun record(maxMs: Long, stop: Deferred<Unit>, onPcm: (ByteArray) -> Unit): ByteArray = coroutineScope {
        val packets = Channel<ByteArray>(Channel.UNLIMITED)
        val decoder = MemoMindOpusDecoder().also { it.start() }
        val pcm = ByteArrayOutputStream()
        var count = 0
        try {
            if (!open { list -> list.forEach { packets.trySend(it) } }) throw IOException("Could not open the glasses microphone")
            val keeper = launch { keepAlive() }
            val opened = TimeSource.Monotonic.markNow()
            var last = opened
            while (!stop.isCompleted && count * PACKET_MS < maxMs) {
                val packet = withTimeoutOrNull(POLL_MS) { packets.receive() }
                if (packet == null) {
                    if (count == 0 && opened.elapsedNow().inWholeMilliseconds > FIRST_AUDIO_TIMEOUT_MS) {
                        throw IOException("The glasses sent no audio")
                    }
                    if (count > 0 && last.elapsedNow().inWholeMilliseconds > STREAM_GAP_END_MS) break
                    continue
                }
                count++
                last = TimeSource.Monotonic.markNow()
                val out = decoder.decode(packet)
                if (out.isNotEmpty()) {
                    pcm.write(out)
                    onPcm(out)
                }
            }
            keeper.cancel()
        } finally {
            withContext(NonCancellable) { runCatching { close() } }
            decoder.release()
            Log.i(TAG, "${javaClass.simpleName}: $count packets, ${pcm.size()} PCM bytes")
        }
        pcm.toByteArray()
    }

    private companion object {
        const val TAG = "GlassesMicCapture"
        const val PACKET_MS = 20L
        const val POLL_MS = 100L
        const val FIRST_AUDIO_TIMEOUT_MS = 5_000L
        const val STREAM_GAP_END_MS = 800L
    }
}

/**
 * HeyCyan: QC work mode 7 (`BC 41 03 00 90 52 02 01 07`) opens the AI audio stream, mode 0x0B
 * (`…57 02 01 0B`) closes it, heartbeat `BC 45 02 00 C2 40 07 01` every 3 s; Opus arrives on
 * BigData 0x59 with a 6-byte header. The glasses also send 0x73 `03 01` ("AI mic opened") for our
 * own start, so MainActivity's wake handler skips 0x03 while [isCapturing].
 */
internal object HeyCyanMicCapture : OpusMicCapture() {
    private const val CMD_AI_AUDIO = 0x59
    private const val HEARTBEAT_MS = 3_000L
    private const val LATE_EVENT_GUARD_MS = 600L
    private val START = byteArrayOf(0x02, 0x01, 0x07)
    private val STOP = byteArrayOf(0x02, 0x01, 0x0B)

    /** True from the start command until shortly after the stop command. */
    val isCapturing = AtomicBoolean(false)

    override suspend fun open(onPackets: (List<ByteArray>) -> Unit): Boolean {
        if (!BleOperateManager.getInstance().isConnected) return false
        if (!isCapturing.compareAndSet(false, true)) return false
        val sdk = LargeDataHandler.getInstance()
        sdk.initPackageNotify(
            ILargeDataResponse<AiChatResponse> { cmdType, response ->
                if (cmdType != CMD_AI_AUDIO) return@ILargeDataResponse
                response?.subData?.let { onPackets(GlassesMicFraming.heyCyanPackets(it)) }
            },
        )
        sdk.glassesControl(START) { _, _ -> }
        return true
    }

    override suspend fun keepAlive() {
        while (true) {
            delay(HEARTBEAT_MS)
            runCatching { LargeDataHandler.getInstance().syncHeartBeat(7) }
        }
    }

    override suspend fun close() {
        try {
            val sdk = LargeDataHandler.getInstance()
            runCatching { sdk.glassesControl(STOP) { _, _ -> } }
            runCatching { sdk.removeGptNotify() }
            delay(LATE_EVENT_GUARD_MS)
        } finally {
            isCapturing.set(false)
        }
    }
}

/** EyeVue: cmd 0x57 opens the stream (0x46 notifies of N x 40-byte packets), 0x56 closes it. */
internal class EyevueMicCapture(context: Context) : OpusMicCapture() {
    private val manager = EyevueManager.getInstance(context)

    override suspend fun open(onPackets: (List<ByteArray>) -> Unit): Boolean {
        if (!manager.isConnected()) return false
        return manager.startMicStream { payload -> onPackets(GlassesMicFraming.eyevuePackets(payload)) }
    }

    override suspend fun close() = manager.stopMicStream()
}

/** TuneBuds: request 0xB0 [02] opens Opus over notify 0xB2 (80-byte packets), 0xB0 [00] closes it. */
internal class TuneBudsMicCapture(context: Context) : OpusMicCapture() {
    private val manager = TuneBudsManager.getInstance(context)

    override suspend fun open(onPackets: (List<ByteArray>) -> Unit): Boolean {
        val repacketizer = OpusRepacketizer(GlassesMicFraming.TUNEBUDS_PACKET_BYTES)
        return manager.startMicStream { bytes -> onPackets(synchronized(repacketizer) { repacketizer.append(bytes) }) }
    }

    override suspend fun close() = manager.stopMicStream()
}
