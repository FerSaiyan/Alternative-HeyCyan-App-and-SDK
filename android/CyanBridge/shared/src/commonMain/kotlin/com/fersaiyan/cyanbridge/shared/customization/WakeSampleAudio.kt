package com.fersaiyan.cyanbridge.shared.customization

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** 16 kHz mono PCM16LE helpers for wake-word samples recorded through the glasses microphone. */
object WakeSampleAudio {
    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_MS = SAMPLE_RATE * 2 / 1000

    /** The glasses' mic path fades in and its FIFO holds stale audio: drop this much at the start of a take. */
    const val HEAD_TRIM_MS = 300L

    fun durationMs(pcm: ByteArray): Long = (pcm.size / 2).toLong() * 1000L / SAMPLE_RATE

    fun trimHead(pcm: ByteArray, ms: Long = HEAD_TRIM_MS): ByteArray {
        val cut = (ms * BYTES_PER_MS).toInt().coerceAtMost(pcm.size)
        return pcm.copyOfRange(cut - cut % 2, pcm.size)
    }

    /** Peak level in dBFS (-120 for silence). */
    fun peakDbfs(pcm: ByteArray): Double {
        var peak = 0
        var i = 0
        while (i + 1 < pcm.size) {
            peak = max(peak, abs(sample(pcm, i)))
            i += 2
        }
        return toDb(peak / 32768.0)
    }

    /** RMS level in dBFS (-120 for silence). */
    fun rmsDbfs(pcm: ByteArray): Double {
        val n = pcm.size / 2
        if (n == 0) return -120.0
        var sum = 0.0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = sample(pcm, i) / 32768.0
            sum += s * s
            i += 2
        }
        return toDb(sqrt(sum / n))
    }

    /** Meter position 0..1 for an RMS level (-60 dBFS .. 0 dBFS). */
    fun meterLevel(pcm: ByteArray): Float = ((rmsDbfs(pcm) + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()

    /** Samples at or above full scale minus 0.1 dB. */
    fun clippedSamples(pcm: ByteArray): Int {
        var count = 0
        var i = 0
        while (i + 1 < pcm.size) {
            if (abs(sample(pcm, i)) >= 32_400) count++
            i += 2
        }
        return count
    }

    /** A canonical 44-byte RIFF/WAVE header plus [pcm] (16-bit mono, [sampleRate]). */
    fun toWav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val data = if (pcm.size % 2 == 0) pcm else pcm.copyOf(pcm.size - 1)
        val out = ByteArray(44 + data.size)
        fun ascii(offset: Int, text: String) = text.forEachIndexed { i, c -> out[offset + i] = c.code.toByte() }
        fun le32(offset: Int, value: Int) {
            for (b in 0 until 4) out[offset + b] = (value ushr (8 * b)).toByte()
        }
        fun le16(offset: Int, value: Int) {
            out[offset] = value.toByte()
            out[offset + 1] = (value ushr 8).toByte()
        }
        ascii(0, "RIFF")
        le32(4, 36 + data.size)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        le32(16, 16)
        le16(20, 1)
        le16(22, 1)
        le32(24, sampleRate)
        le32(28, sampleRate * 2)
        le16(32, 2)
        le16(34, 16)
        ascii(36, "data")
        le32(40, data.size)
        data.copyInto(out, 44)
        return out
    }

    private fun sample(pcm: ByteArray, i: Int): Int = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()

    private fun toDb(linear: Double): Double = if (linear <= 0.000001) -120.0 else 20.0 * log10(linear)
}

/** Splits each family's glasses-mic transport into raw Opus packets (16 kHz mono, 20 ms each). */
object GlassesMicFraming {
    /** HeyCyan: BigData 0x59 frames carry a 6-byte header, then N x 40-byte packets. */
    const val HEYCYAN_HEADER_BYTES = 6
    const val HEYCYAN_PACKET_BYTES = 40

    /** EyeVue: notify 0x46 payloads are N x 40-byte packets (a ragged tail is dropped, as the vendor app does). */
    const val EYEVUE_PACKET_BYTES = 40

    /** TuneBuds: notify 0xB2 bytes are a raw stream of 80-byte packets, independent of notify boundaries. */
    const val TUNEBUDS_PACKET_BYTES = 80

    fun heyCyanPackets(frame: ByteArray): List<ByteArray> =
        if (frame.size <= HEYCYAN_HEADER_BYTES) emptyList()
        else fixedPackets(frame.copyOfRange(HEYCYAN_HEADER_BYTES, frame.size), HEYCYAN_PACKET_BYTES)

    fun eyevuePackets(payload: ByteArray): List<ByteArray> = fixedPackets(payload, EYEVUE_PACKET_BYTES)

    fun fixedPackets(bytes: ByteArray, size: Int): List<ByteArray> =
        (0 until bytes.size / size).map { bytes.copyOfRange(it * size, (it + 1) * size) }
}

/** Re-slices a byte stream into fixed-size packets across chunk boundaries (TuneBuds 0xB2). */
class OpusRepacketizer(private val packetSize: Int) {
    private var pending = ByteArray(0)

    fun append(chunk: ByteArray): List<ByteArray> {
        val bytes = pending + chunk
        val whole = bytes.size / packetSize * packetSize
        pending = bytes.copyOfRange(whole, bytes.size)
        return GlassesMicFraming.fixedPackets(bytes.copyOf(whole), packetSize)
    }
}
