package com.fersaiyan.cyanbridge.shared.media.rtmp

/** The SPS/PPS sets and NAL length size of an `AVCDecoderConfigurationRecord` (ISO 14496-15). */
class AvcDecoderConfig(
    val nalLengthSize: Int,
    val sps: List<ByteArray>,
    val pps: List<ByteArray>,
) {
    /** Profile, constraint and level bytes from the first SPS, for logs ("avc1.42c01f"). */
    val codecString: String
        get() = sps.firstOrNull()?.takeIf { it.size >= 4 }?.let { set ->
            "avc1." + (1..3).joinToString("") { (set[it].toInt() and 0xFF).toString(16).padStart(2, '0') }
        } ?: "avc1"

    companion object {
        fun parse(record: ByteArray): AvcDecoderConfig {
            val reader = ByteReader(record)
            if (reader.u8() != 1) throw RtmpProtocolException("Unknown AVC configuration version")
            reader.skip(3) // profile, compatibility, level
            val nalLengthSize = (reader.u8() and 0x03) + 1
            if (nalLengthSize == 3) throw RtmpProtocolException("Unsupported 3-byte NAL lengths")
            val sps = List(reader.u8() and 0x1F) { reader.bytes(reader.u16()) }
            val pps = List(reader.u8()) { reader.bytes(reader.u16()) }
            if (sps.isEmpty() || pps.isEmpty()) throw RtmpProtocolException("AVC configuration without SPS or PPS")
            return AvcDecoderConfig(nalLengthSize, sps, pps)
        }
    }
}

/** A parsed FLV video tag body. */
sealed interface FlvVideoPacket {
    data class AvcSequenceHeader(val config: AvcDecoderConfig) : FlvVideoPacket

    /** Coded H.264 data, still in length-prefixed (AVCC) form. */
    class AvcFrame(val keyframe: Boolean, val compositionTimeMs: Int, val data: ByteArray) : FlvVideoPacket

    data object EndOfSequence : FlvVideoPacket

    /** A codec or packet type this receiver does not decode (HEVC, VP9, AV1, metadata). */
    data class Unsupported(val description: String) : FlvVideoPacket
}

/**
 * Parses FLV video tag bodies (the payload of RTMP video messages) in the legacy layout and the
 * Enhanced RTMP layout (`avc1` FourCC). Only H.264 is decoded.
 */
object FlvVideoTag {
    private const val CODEC_AVC = 7
    private const val FOURCC_AVC1 = 0x61766331L // "avc1"

    fun parse(payload: ByteArray): FlvVideoPacket {
        val reader = ByteReader(payload)
        val first = reader.u8()
        val frameType = (first ushr 4) and 0x07
        if (first and 0x80 != 0) {
            // Enhanced RTMP: IsExHeader | FrameType(3) | PacketType(4), then a FourCC.
            val packetType = first and 0x0F
            val fourCc = reader.u32()
            if (fourCc != FOURCC_AVC1) return FlvVideoPacket.Unsupported("enhanced codec ${fourCcName(fourCc)}")
            return when (packetType) {
                0 -> FlvVideoPacket.AvcSequenceHeader(AvcDecoderConfig.parse(reader.rest()))
                1 -> FlvVideoPacket.AvcFrame(frameType == 1, reader.s24(), reader.rest())
                2 -> FlvVideoPacket.EndOfSequence
                3 -> FlvVideoPacket.AvcFrame(frameType == 1, 0, reader.rest())
                else -> FlvVideoPacket.Unsupported("enhanced packet type $packetType")
            }
        }
        val codecId = first and 0x0F
        if (frameType == 5) return FlvVideoPacket.Unsupported("video info/command frame")
        if (codecId != CODEC_AVC) return FlvVideoPacket.Unsupported("FLV codec id $codecId")
        val packetType = reader.u8()
        val compositionTime = reader.s24()
        return when (packetType) {
            0 -> FlvVideoPacket.AvcSequenceHeader(AvcDecoderConfig.parse(reader.rest()))
            1 -> FlvVideoPacket.AvcFrame(frameType == 1, compositionTime, reader.rest())
            2 -> FlvVideoPacket.EndOfSequence
            else -> FlvVideoPacket.Unsupported("AVC packet type $packetType")
        }
    }

    private fun fourCcName(value: Long): String =
        (0..3).map { ((value ushr (24 - it * 8)) and 0xFF).toInt().toChar() }.joinToString("")
}

/** H.264 NAL unit helpers for converting RTMP (AVCC) frames into an Annex B elementary stream. */
object AvcNal {
    const val TYPE_IDR = 5
    const val TYPE_SPS = 7
    const val TYPE_PPS = 8
    const val TYPE_AUD = 9

    private val START_CODE = byteArrayOf(0, 0, 0, 1)
    private val ACCESS_UNIT_DELIMITER = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())

    fun type(nal: ByteArray): Int = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1F

    /**
     * Splits length-prefixed NAL units. Some encoders put Annex B data in RTMP anyway; data that
     * does not parse as length-prefixed but starts with a start code is split on start codes.
     */
    fun splitAvcc(data: ByteArray, nalLengthSize: Int): List<ByteArray> =
        try {
            splitLengthPrefixed(data, nalLengthSize)
        } catch (error: RtmpProtocolException) {
            if (looksLikeAnnexB(data)) splitAnnexB(data) else throw error
        }

    private fun splitLengthPrefixed(data: ByteArray, nalLengthSize: Int): List<ByteArray> {
        val units = mutableListOf<ByteArray>()
        var position = 0
        while (position < data.size) {
            if (position + nalLengthSize > data.size) throw RtmpProtocolException("Truncated NAL length")
            var length = 0L
            repeat(nalLengthSize) { length = (length shl 8) or (data[position + it].toLong() and 0xFF) }
            position += nalLengthSize
            if (length <= 0 || position + length > data.size) throw RtmpProtocolException("NAL length out of range")
            units += data.copyOfRange(position, position + length.toInt())
            position += length.toInt()
        }
        return units
    }

    private fun looksLikeAnnexB(data: ByteArray): Boolean =
        data.size >= 4 && data[0].toInt() == 0 && data[1].toInt() == 0 &&
            (data[2].toInt() == 1 || (data[2].toInt() == 0 && data[3].toInt() == 1))

    fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val units = mutableListOf<ByteArray>()
        var start = -1
        var index = 0
        while (index + 2 < data.size) {
            if (data[index].toInt() == 0 && data[index + 1].toInt() == 0 && data[index + 2].toInt() == 1) {
                if (start >= 0) units += trimmed(data, start, index)
                index += 3
                start = index
            } else {
                index++
            }
        }
        if (start >= 0 && start < data.size) units += data.copyOfRange(start, data.size)
        return units.filter { it.isNotEmpty() }
    }

    /** Drops the zero byte that belongs to a following four-byte start code. */
    private fun trimmed(data: ByteArray, start: Int, end: Int): ByteArray {
        var last = end
        while (last > start && data[last - 1].toInt() == 0) last--
        return data.copyOfRange(start, last)
    }

    /**
     * One access unit in Annex B form: an access unit delimiter, the SPS/PPS of [config] in front
     * of an IDR picture that does not carry its own, then [units].
     */
    fun accessUnit(units: List<ByteArray>, config: AvcDecoderConfig?): ByteArray {
        val writer = ByteWriter(units.sumOf { it.size + 4 } + 64)
        if (units.firstOrNull()?.let(::type) != TYPE_AUD) writer.bytes(ACCESS_UNIT_DELIMITER)
        val hasIdr = units.any { type(it) == TYPE_IDR }
        val hasSps = units.any { type(it) == TYPE_SPS }
        var parametersWritten = !hasIdr || hasSps || config == null
        for (unit in units) {
            if (!parametersWritten && type(unit) != TYPE_AUD) {
                config!!.sps.forEach { writer.bytes(START_CODE).bytes(it) }
                config.pps.forEach { writer.bytes(START_CODE).bytes(it) }
                parametersWritten = true
            }
            writer.bytes(START_CODE).bytes(unit)
        }
        return writer.toByteArray()
    }
}
