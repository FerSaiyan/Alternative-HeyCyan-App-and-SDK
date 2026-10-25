package com.fersaiyan.cyanbridge.shared.devices.memomind

/** What the recording link carries (GlassSDK AUDIO_PROTOCOL.md). */
sealed interface MemoMindRecordingMessage {
    /** `52 13 mode 31 28 00`: the effective format after `52 12 mode`. */
    data class FormatSelected(val compact: Boolean) : MemoMindRecordingMessage

    /** `52 11 noise ts ts ts`: capture started; [timestamp] is the low 24 bits of the producer clock. */
    data class Started(val noiseMode: Int, val timestamp: Int) : MemoMindRecordingMessage

    /** One packet of [frames] 40-byte Opus packets (mono, 16 kHz input, 20 ms each). */
    class Audio(val streamCommand: Int, val frames: List<ByteArray>) : MemoMindRecordingMessage {
        override fun toString() = "Audio(stream=0x${streamCommand.toString(16)}, frames=${frames.size})"
    }
}

/**
 * Splits the recording byte stream into control notifications and Opus packets. Reads may
 * split or combine packets; unrelated or malformed bytes are skipped until the next `0x52`.
 * Set [compact] from the negotiated format before audio arrives (legacy when the firmware did
 * not answer the negotiation).
 */
class MemoMindRecordingParser(var compact: Boolean = false) {
    private var buffer = ByteArray(2048)
    private var size = 0

    /** Bytes skipped while resynchronising since the last [reset]. */
    var skippedBytes: Long = 0
        private set

    fun reset(compact: Boolean = false) {
        this.compact = compact
        size = 0
        skippedBytes = 0
    }

    fun append(chunk: ByteArray): List<MemoMindRecordingMessage> {
        if (size + chunk.size > MAX_BUFFERED_BYTES) {
            // A stuck stream: drop what is buffered rather than grow without bound.
            skippedBytes += size
            size = 0
        }
        if (size + chunk.size > buffer.size) buffer = buffer.copyOf(maxOf(size + chunk.size, buffer.size * 2))
        chunk.copyInto(buffer, size)
        size += chunk.size
        val messages = mutableListOf<MemoMindRecordingMessage>()
        while (size > 0) {
            if (buffer[0].toInt() and 0xFF != MemoMindRecordingControl.MAGIC) {
                skip(1)
                continue
            }
            if (size < 2) break
            val command = buffer[1].toInt() and 0xFF
            when {
                command == FORMAT_RESPONSE || command == START_NOTIFICATION -> {
                    if (size < 6) break
                    messages += if (command == FORMAT_RESPONSE) {
                        MemoMindRecordingMessage.FormatSelected(compact = buffer[2].toInt() == MemoMindRecordingControl.FORMAT_COMPACT)
                    } else {
                        MemoMindRecordingMessage.Started(
                            noiseMode = buffer[2].toInt() and 0xFF,
                            timestamp = GmCodec.readUInt24(buffer, 3),
                        )
                    }
                    consume(6)
                }
                command in AUDIO_STREAMS -> {
                    if (size < HEADER_SIZE) break
                    val frameCount = buffer[6].toInt() and 0xFF
                    val validHeader = buffer[2].toInt() == 0 && buffer[3].toInt() == 0 &&
                        (buffer[11].toInt() and 0xFF) == 0xFF && frameCount in 1..8
                    if (!validHeader) {
                        skip(1)
                        continue
                    }
                    val recordSize = if (compact) OPUS_PACKET_SIZE else LEGACY_RECORD_SIZE
                    val packetSize = HEADER_SIZE + frameCount * recordSize
                    if (size < packetSize) break
                    val frames = extractFrames(frameCount, recordSize)
                    if (frames == null) {
                        skip(1)
                        continue
                    }
                    messages += MemoMindRecordingMessage.Audio(command, frames)
                    consume(packetSize)
                }
                else -> skip(1)
            }
        }
        return messages
    }

    private fun extractFrames(count: Int, recordSize: Int): List<ByteArray>? = List(count) { index ->
        val record = HEADER_SIZE + index * recordSize
        if (compact) {
            buffer.copyOfRange(record, record + OPUS_PACKET_SIZE)
        } else {
            if (buffer[record + 4].toInt() != OPUS_PACKET_SIZE) return null
            buffer.copyOfRange(record + LEGACY_OPUS_OFFSET, record + LEGACY_OPUS_OFFSET + OPUS_PACKET_SIZE)
        }
    }

    private fun skip(count: Int) {
        skippedBytes += count
        consume(count)
    }

    private fun consume(count: Int) {
        buffer.copyInto(buffer, 0, count, size)
        size -= count
    }

    companion object {
        const val FORMAT_RESPONSE = 0x13
        const val START_NOTIFICATION = 0x11

        /** The stream of a capture started with `52 01 00`; 0x90, 0x91, 0x93, 0x94 belong to other consumers. */
        const val STREAM_NORMAL_RECORDING = 0x92
        val AUDIO_STREAMS = 0x90..0x94

        const val HEADER_SIZE = 12
        const val OPUS_PACKET_SIZE = 40
        const val LEGACY_RECORD_SIZE = 49
        const val LEGACY_OPUS_OFFSET = 9

        /** Opus frame duration of the published capture profile. */
        const val FRAME_DURATION_MS = 20
        const val INPUT_SAMPLE_RATE = 16_000

        private const val MAX_BUFFERED_BYTES = 64 * 1024
    }
}
