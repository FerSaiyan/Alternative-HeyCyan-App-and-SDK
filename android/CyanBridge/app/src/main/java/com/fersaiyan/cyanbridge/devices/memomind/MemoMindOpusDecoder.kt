package com.fersaiyan.cyanbridge.devices.memomind

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.fersaiyan.cyanbridge.shared.audio.OpusPacketDecoder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the MemoMind microphone stream (40-byte Opus packets, mono, 16 kHz input, 20 ms) with
 * the platform MediaCodec decoder and returns 16 kHz mono PCM16LE. The decoder needs the Opus
 * header and pre-skip as codec-specific data, and may output 48 kHz whatever the header says,
 * so the real output rate is read and integer-decimated to 16 kHz. Not thread-safe. The platform
 * Opus decoder (`audio/opus`) exists since Android 5.0, below the app's minSdk, so no library
 * is bundled.
 */
class MemoMindOpusDecoder : OpusPacketDecoder {
    override val sampleRateHz: Int = SAMPLE_RATE

    private var codec: MediaCodec? = null
    private var outputRate = SAMPLE_RATE
    private var outputChannels = 1
    private var presentationUs = 0L

    override fun start() {
        release()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
            setByteBuffer("csd-1", ByteBuffer.wrap(longLe(PRE_SKIP_NS)))
            setByteBuffer("csd-2", ByteBuffer.wrap(longLe(SEEK_PRE_ROLL_NS)))
        }
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
            configure(format, null, null, 0)
            start()
        }
        presentationUs = 0
        outputRate = SAMPLE_RATE
        outputChannels = 1
    }

    /** Queues one packet (never dropping it) and returns whatever PCM became available. */
    override fun decode(packet: ByteArray): ByteArray {
        val decoder = codec ?: return ByteArray(0)
        val out = ByteArrayOutputStream()
        var queued = false
        var attempts = 0
        while (!queued && attempts++ < MAX_QUEUE_ATTEMPTS) {
            val index = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (index >= 0) {
                decoder.getInputBuffer(index)?.apply {
                    clear()
                    put(packet)
                }
                decoder.queueInputBuffer(index, 0, packet.size, presentationUs, 0)
                presentationUs += FRAME_US
                queued = true
            }
            drain(decoder, out)
        }
        if (!queued) Log.w(TAG, "Opus decoder had no free input buffer; packet lost")
        return toTarget(out.toByteArray())
    }

    override fun release() {
        val decoder = codec ?: return
        codec = null
        runCatching { decoder.stop() }
        runCatching { decoder.release() }
    }

    private fun drain(decoder: MediaCodec, out: ByteArrayOutputStream) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = decoder.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val actual = decoder.outputFormat
                outputRate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                outputChannels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                Log.i(TAG, "Opus decoder output ${outputRate} Hz x$outputChannels")
                continue
            }
            if (index < 0) return
            if (info.size > 0) {
                val buffer = decoder.getOutputBuffer(index) ?: return
                val chunk = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(chunk)
                out.write(chunk, 0, chunk.size)
            }
            decoder.releaseOutputBuffer(index, false)
        }
    }

    /** Downmixes and decimates decoder output to 16 kHz mono (box filter over each group). */
    private fun toTarget(pcm: ByteArray): ByteArray {
        val factor = if (outputRate % SAMPLE_RATE == 0) outputRate / SAMPLE_RATE else 1
        val channels = outputChannels.coerceAtLeast(1)
        if (factor == 1 && channels == 1) return pcm
        val input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val group = factor * channels
        val output = ByteBuffer.allocate(input.remaining() / group * 2).order(ByteOrder.LITTLE_ENDIAN)
        while (input.remaining() >= group) {
            var sum = 0
            repeat(group) { sum += input.get().toInt() }
            output.putShort((sum / group).toShort())
        }
        return output.array()
    }

    companion object {
        private const val TAG = "MemoMindOpus"
        const val SAMPLE_RATE = 16_000
        private const val FRAME_US = 20_000L
        private const val DEQUEUE_TIMEOUT_US = 5_000L
        private const val MAX_QUEUE_ATTEMPTS = 100
        private const val PRE_SKIP_SAMPLES_48K = 0
        private const val PRE_SKIP_NS = PRE_SKIP_SAMPLES_48K * 1_000_000_000L / 48_000
        private const val SEEK_PRE_ROLL_NS = 80_000_000L

        /** RFC 7845 identification header: version 1, mono, 16 kHz input, mapping family 0. */
        fun opusHead(): ByteArray = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray(Charsets.US_ASCII))
            put(1)
            put(1)
            putShort(PRE_SKIP_SAMPLES_48K.toShort())
            putInt(SAMPLE_RATE)
            putShort(0)
            put(0)
        }.array()

        private fun longLe(value: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
    }
}
