package com.fersaiyan.cyanbridge.shared.bridge.audio

import com.fersaiyan.cyanbridge.shared.platform.PlatformLogger

/**
 * Parser for WQ Record Protocol V2 frames used by MemoMind glasses.
 *
 * ## Frame format (partially known)
 *
 * ```text
 * ┌─────────┬──────────────┬──────────────────┬──────────────────┐
 * │ Magic   │ frameCnt     │ Payload (Opus)   │ [CRC32 optional] │
 * │ (1 byte)│ (uint?)      │ (variable)       │ (4 bytes)        │
 * └─────────┴──────────────┴──────────────────┴──────────────────┘
 * ```
 *
 * ## Known constraints (from decompiled binary analysis)
 *
 * - **Magic byte**: Value unknown — the Dart runtime constant needs BLE sniffing
 *   to confirm. Error string found in binary:
 *   `[wq_record_v2] !! magic byte mismatch: expected `
 * - **frameCnt**: Range-checked at receiver, but byte-size and endianness
 *   are unknown. Error string:
 *   `[wq_record_v2] !! frameCnt out of range: `
 * - **Payload**: Opus-encoded audio data (24000 Hz, mono, variable bitrate)
 * - **CRC32**: Optional 4-byte trailer controlled by a `needCRC` flag.
 *   When present, it covers the magic + frameCnt + payload fields.
 */
class WqRecordParser {

    companion object {
        private const val TAG = "WqRecordParser"

        // TODO: Verify magic byte value via BLE sniffing
        const val MAGIC_BYTE: Byte = 0xA5.toByte()

        // TODO: Verify frameCnt encoding via BLE sniffing
        const val FRAME_CNT_SIZE = 2

        /** Smallest possible valid frame: magic(1) + frameCnt(2) = 3 bytes. */
        private const val MIN_FRAME_SIZE = 1 + FRAME_CNT_SIZE
    }

    data class WqFrame(
        val magic: Byte,
        val frameCnt: Int,
        val opusPayload: ByteArray,
        val crc32: Int? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WqFrame) return false
            return magic == other.magic &&
                frameCnt == other.frameCnt &&
                opusPayload.contentEquals(other.opusPayload) &&
                crc32 == other.crc32
        }

        override fun hashCode(): Int {
            var result = magic.hashCode()
            result = 31 * result + frameCnt
            result = 31 * result + opusPayload.contentHashCode()
            result = 31 * result + (crc32 ?: 0)
            return result
        }
    }

    /**
     * Parses one frame from a BLE notification on characteristic 0x2020.
     *
     * Returns null when the frame is shorter than magic + frameCnt or has no payload.
     * Because [MAGIC_BYTE] is still a placeholder, a magic mismatch is only logged
     * unless [strict] is true, so captured data can still be analysed.
     */
    fun parse(data: ByteArray, strict: Boolean = false): WqFrame? {
        if (data.size < MIN_FRAME_SIZE) {
            PlatformLogger.w(TAG, "Frame too short: ${data.size} bytes (minimum $MIN_FRAME_SIZE)")
            return null
        }

        val magic = data[0]
        if (magic != MAGIC_BYTE) {
            PlatformLogger.w(
                TAG,
                "Magic byte mismatch: expected 0x${MAGIC_BYTE.toUByte().toString(16)}, " +
                    "got 0x${magic.toUByte().toString(16)}",
            )
            if (strict) {
                return null
            }
        }

        val frameCnt = when (FRAME_CNT_SIZE) {
            1 -> data[1].toInt() and 0xFF
            2 -> (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
            4 -> (data[1].toInt() and 0xFF) or
                ((data[2].toInt() and 0xFF) shl 8) or
                ((data[3].toInt() and 0xFF) shl 16) or
                ((data[4].toInt() and 0xFF) shl 24)
            else -> {
                PlatformLogger.w(TAG, "Unsupported FRAME_CNT_SIZE: $FRAME_CNT_SIZE")
                return null
            }
        }

        val payloadStart = 1 + FRAME_CNT_SIZE

        if (payloadStart >= data.size) {
            PlatformLogger.w(TAG, "No payload in frame (header only)")
            return null
        }

        val payloadEnd = data.size
        val opusPayload = data.copyOfRange(payloadStart, payloadEnd)

        PlatformLogger.d(
            TAG,
            "Parsed frame: magic=0x${magic.toUByte().toString(16)}, " +
                "frameCnt=$frameCnt, payloadSize=${opusPayload.size}",
        )

        return WqFrame(
            magic = magic,
            frameCnt = frameCnt,
            opusPayload = opusPayload,
        )
    }
}
