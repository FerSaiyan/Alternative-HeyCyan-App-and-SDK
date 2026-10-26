package com.fersaiyan.cyanbridge.shared.media.ts

import com.fersaiyan.cyanbridge.shared.media.rtmp.ByteWriter

/**
 * A minimal MPEG-2 transport stream muxer (ISO 13818-1) for one H.264 video stream: PAT and PMT
 * in front of every keyframe, one PES packet per access unit, and the PCR on the video PID. It
 * feeds a local player, so there is no rate control, no SDT and no audio.
 */
class MpegTsMuxer(
    private val videoPid: Int = DEFAULT_VIDEO_PID,
    private val pmtPid: Int = DEFAULT_PMT_PID,
) {
    private val continuity = HashMap<Int, Int>()

    /**
     * Muxes one Annex B access unit. Timestamps are in milliseconds; PCR equals the DTS so a
     * player shows each frame as soon as its own buffering allows.
     */
    fun muxVideo(accessUnit: ByteArray, ptsMs: Long, dtsMs: Long, keyframe: Boolean): ByteArray {
        val out = ByteWriter(accessUnit.size + accessUnit.size / 8 + 3 * PACKET_SIZE)
        if (keyframe) {
            writeSection(out, PAT_PID, patSection())
            writeSection(out, pmtPid, pmtSection())
        }
        val pts = ptsMs * 90
        val dts = dtsMs * 90
        writePes(out, pesPacket(accessUnit, pts, dts), pcr = dts, randomAccess = keyframe)
        return out.toByteArray()
    }

    private fun nextContinuity(pid: Int): Int {
        val value = continuity[pid] ?: 0
        continuity[pid] = (value + 1) and 0x0F
        return value
    }

    private fun patSection(): ByteArray = section(
        tableId = 0x00,
        tableIdExtension = TRANSPORT_STREAM_ID,
        body = ByteWriter(4).u16(PROGRAM_NUMBER).u16(0xE000 or pmtPid).toByteArray(),
    )

    private fun pmtSection(): ByteArray = section(
        tableId = 0x02,
        tableIdExtension = PROGRAM_NUMBER,
        body = ByteWriter(9)
            .u16(0xE000 or videoPid) // PCR PID
            .u16(0xF000) // no program descriptors
            .u8(STREAM_TYPE_H264).u16(0xE000 or videoPid).u16(0xF000)
            .toByteArray(),
    )

    /** A long-form PSI section with version 0, current, section 0 of 0, and its CRC. */
    private fun section(tableId: Int, tableIdExtension: Int, body: ByteArray): ByteArray {
        val sectionLength = 5 + body.size + 4
        val writer = ByteWriter(3 + sectionLength)
        writer.u8(tableId).u16(0xB000 or sectionLength).u16(tableIdExtension).u8(0xC1).u8(0).u8(0).bytes(body)
        val withoutCrc = writer.toByteArray()
        return writer.u32(Crc32Mpeg2.compute(withoutCrc)).toByteArray()
    }

    private fun writeSection(out: ByteWriter, pid: Int, section: ByteArray) {
        out.u8(SYNC_BYTE).u16(0x4000 or pid).u8(0x10 or nextContinuity(pid))
        out.u8(0) // pointer field
        out.bytes(section)
        out.fill(0xFF, PACKET_SIZE - 5 - section.size)
    }

    private fun pesPacket(payload: ByteArray, pts: Long, dts: Long): ByteArray {
        val hasDts = dts != pts
        val headerDataLength = if (hasDts) 10 else 5
        val packetLength = 3 + headerDataLength + payload.size
        val writer = ByteWriter(9 + headerDataLength + payload.size)
        writer.u8(0).u8(0).u8(1).u8(STREAM_ID_VIDEO)
        // Video PES packets may leave the length unbounded (0) when it does not fit 16 bits.
        writer.u16(if (packetLength > 0xFFFF) 0 else packetLength)
        writer.u8(0x80).u8(if (hasDts) 0xC0 else 0x80).u8(headerDataLength)
        writeTimestamp(writer, if (hasDts) 0x3 else 0x2, pts)
        if (hasDts) writeTimestamp(writer, 0x1, dts)
        writer.bytes(payload)
        return writer.toByteArray()
    }

    private fun writeTimestamp(writer: ByteWriter, prefix: Int, value90k: Long) {
        val ts = value90k and MAX_33_BITS
        writer.u8((prefix shl 4) or (((ts ushr 30) and 0x07).toInt() shl 1) or 1)
        writer.u16(((((ts ushr 15) and 0x7FFF).toInt()) shl 1) or 1)
        writer.u16((((ts and 0x7FFF).toInt()) shl 1) or 1)
    }

    private fun writePes(out: ByteWriter, pes: ByteArray, pcr: Long, randomAccess: Boolean) {
        var position = 0
        var first = true
        while (position < pes.size) {
            val remaining = pes.size - position
            // Adaptation field length (bytes after its length byte), or -1 for none.
            var adaptationLength = if (first) 1 + 6 else -1
            val capacity = PACKET_SIZE - 4 - (if (adaptationLength >= 0) adaptationLength + 1 else 0)
            val payloadLength: Int
            if (remaining >= capacity) {
                payloadLength = capacity
            } else {
                payloadLength = remaining
                val padding = capacity - remaining
                adaptationLength = if (adaptationLength >= 0) adaptationLength + padding else padding - 1
            }
            val control = if (adaptationLength >= 0) 0x30 else 0x10
            out.u8(SYNC_BYTE).u16((if (first) 0x4000 else 0) or videoPid).u8(control or nextContinuity(videoPid))
            if (adaptationLength >= 0) {
                out.u8(adaptationLength)
                var written = 0
                if (adaptationLength > 0) {
                    var flags = 0
                    if (first) flags = flags or 0x10 // PCR
                    if (first && randomAccess) flags = flags or 0x40
                    out.u8(flags)
                    written = 1
                    if (first) {
                        writePcr(out, pcr)
                        written += 6
                    }
                }
                out.fill(0xFF, adaptationLength - written)
            }
            out.bytes(pes, position, payloadLength)
            position += payloadLength
            first = false
        }
    }

    private fun writePcr(out: ByteWriter, pcr90k: Long) {
        val base = pcr90k and MAX_33_BITS
        out.u32(base ushr 1)
        out.u8((((base and 1).toInt()) shl 7) or 0x7E)
        out.u8(0)
    }

    companion object {
        const val PACKET_SIZE = 188
        const val SYNC_BYTE = 0x47
        const val PAT_PID = 0
        const val DEFAULT_PMT_PID = 0x1000
        const val DEFAULT_VIDEO_PID = 0x100
        const val STREAM_TYPE_H264 = 0x1B
        const val STREAM_ID_VIDEO = 0xE0
        private const val TRANSPORT_STREAM_ID = 1
        private const val PROGRAM_NUMBER = 1
        private const val MAX_33_BITS = (1L shl 33) - 1
    }
}

/** CRC-32/MPEG-2: polynomial 0x04C11DB7, initial value 0xFFFFFFFF, no reflection or final XOR. */
object Crc32Mpeg2 {
    private val table = LongArray(256) { index ->
        var crc = index.toLong() shl 24
        repeat(8) {
            crc = if (crc and 0x8000_0000L != 0L) ((crc shl 1) xor 0x04C1_1DB7L) else (crc shl 1)
            crc = crc and 0xFFFF_FFFFL
        }
        crc
    }

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Long {
        var crc = 0xFFFF_FFFFL
        for (index in offset until offset + length) {
            val lookup = ((crc ushr 24) xor (data[index].toLong() and 0xFF)).toInt()
            crc = ((crc shl 8) and 0xFFFF_FFFFL) xor table[lookup]
        }
        return crc
    }
}
