package com.fersaiyan.cyanbridge.shared.media

import kotlin.random.Random

/**
 * Wraps the glasses' raw Opus recordings into an Ogg/Opus container so standard
 * players can open them. Platform-neutral port of the Android MainActivity logic:
 * the glasses often store bare Opus packets (commonly fixed 40-byte packets).
 */
object OggOpusWrapper {
    private const val PACKET_DURATION_MS = 40

    /** Returns the wrapped bytes, or the input unchanged when the layout is unknown. */
    fun wrapIfNeeded(raw: ByteArray): ByteArray {
        if (raw.size >= 4 && raw[0] == 'O'.code.toByte() && raw[1] == 'g'.code.toByte() &&
            raw[2] == 'g'.code.toByte() && raw[3] == 'S'.code.toByte()
        ) {
            return raw
        }
        val packets = parseLengthPrefixed(raw, littleEndian = true)
            ?: parseLengthPrefixed(raw, littleEndian = false)
            ?: parseLengthPrefixed1B(raw)
            ?: guessFixedSizePackets(raw)
            ?: return raw
        if (packets.isEmpty()) return raw
        return runCatching { buildOggOpus(packets) }.getOrDefault(raw)
    }

    private fun parseLengthPrefixed(raw: ByteArray, littleEndian: Boolean): List<ByteArray>? {
        var i = 0
        val out = ArrayList<ByteArray>()
        while (i + 2 <= raw.size) {
            val b0 = raw[i].toInt() and 0xFF
            val b1 = raw[i + 1].toInt() and 0xFF
            val len = if (littleEndian) (b0 or (b1 shl 8)) else ((b0 shl 8) or b1)
            i += 2
            if (len <= 0 || len > 2000 || i + len > raw.size) return null
            out.add(raw.copyOfRange(i, i + len))
            i += len
        }
        if (i != raw.size) return null
        return if (out.size >= 3) out else null
    }

    private fun parseLengthPrefixed1B(raw: ByteArray): List<ByteArray>? {
        var i = 0
        val out = ArrayList<ByteArray>()
        while (i + 1 <= raw.size) {
            val len = raw[i].toInt() and 0xFF
            i += 1
            if (len <= 0 || i + len > raw.size) return null
            out.add(raw.copyOfRange(i, i + len))
            i += len
        }
        if (i != raw.size) return null
        return if (out.size >= 3) out else null
    }

    private fun guessFixedSizePackets(raw: ByteArray): List<ByteArray>? {
        if (raw.isEmpty()) return null
        // 40 bytes matches the official app's packetSize.
        for (size in intArrayOf(40, 60, 80, 100, 120, 160, 200, 240, 320)) {
            if (raw.size % size != 0) continue
            val count = raw.size / size
            if (count < 5) continue
            return List(count) { index -> raw.copyOfRange(index * size, (index + 1) * size) }
        }
        return null
    }

    private fun buildOggOpus(packets: List<ByteArray>): ByteArray {
        val serial = Random.nextInt()
        var sequence = 0
        var granulePosition = 0L
        val out = ByteBuilder()

        writePage(out, serial, sequence++, 0L, headerType = 0x02, packets = listOf(opusHead()))
        writePage(out, serial, sequence++, 0L, headerType = 0x00, packets = listOf(opusTags("CyanBridge")))

        val samplesPerPacket = PACKET_DURATION_MS * 48_000L / 1000L
        var index = 0
        while (index < packets.size) {
            val pagePackets = ArrayList<ByteArray>()
            var segmentCount = 0
            while (index < packets.size) {
                val packet = packets[index]
                var needed = (packet.size + 254) / 255
                if (packet.size % 255 == 0) needed += 1
                if (segmentCount + needed > 255) break
                pagePackets.add(packet)
                segmentCount += needed
                granulePosition += samplesPerPacket
                index++
            }
            val headerType = if (index >= packets.size) 0x04 else 0x00
            writePage(out, serial, sequence++, granulePosition, headerType, pagePackets)
        }
        return out.toByteArray()
    }

    private fun opusHead(): ByteArray = ByteBuilder().apply {
        ascii("OpusHead")
        byte(1) // version
        byte(1) // mono
        le16(0) // pre-skip
        le32(48_000)
        le16(0) // output gain
        byte(0) // channel mapping family
    }.toByteArray()

    private fun opusTags(vendor: String): ByteArray = ByteBuilder().apply {
        val vendorBytes = vendor.encodeToByteArray()
        ascii("OpusTags")
        le32(vendorBytes.size)
        bytes(vendorBytes)
        le32(0)
    }.toByteArray()

    private fun writePage(
        out: ByteBuilder,
        serial: Int,
        sequence: Int,
        granulePosition: Long,
        headerType: Int,
        packets: List<ByteArray>,
    ) {
        val segments = ByteBuilder()
        val payload = ByteBuilder()
        for (packet in packets) {
            var offset = 0
            var remaining = packet.size
            while (remaining > 0) {
                val segment = minOf(255, remaining)
                segments.byte(segment)
                payload.bytes(packet, offset, segment)
                offset += segment
                remaining -= segment
            }
            // A packet ending exactly on a 255 boundary needs a terminating zero lace.
            if (packet.size % 255 == 0) segments.byte(0)
        }
        val segmentTable = segments.toByteArray()
        check(segmentTable.size <= 255) { "Ogg page has too many segments: ${segmentTable.size}" }

        val page = ByteBuilder().apply {
            ascii("OggS")
            byte(0)
            byte(headerType)
            le64(granulePosition)
            le32(serial)
            le32(sequence)
            le32(0) // checksum placeholder
            byte(segmentTable.size)
            bytes(segmentTable)
            bytes(payload.toByteArray())
        }.toByteArray()
        val crc = crc(page)
        page[22] = (crc and 0xFF).toByte()
        page[23] = ((crc shr 8) and 0xFF).toByte()
        page[24] = ((crc shr 16) and 0xFF).toByte()
        page[25] = ((crc shr 24) and 0xFF).toByte()
        out.bytes(page)
    }

    private val crcTable: IntArray = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    private fun crc(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = (crc shl 8) xor crcTable[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        }
        return crc
    }

    private class ByteBuilder {
        private var buffer = ByteArray(256)
        private var size = 0

        private fun ensure(extra: Int) {
            if (size + extra <= buffer.size) return
            var capacity = buffer.size * 2
            while (capacity < size + extra) capacity *= 2
            buffer = buffer.copyOf(capacity)
        }

        fun byte(value: Int) {
            ensure(1)
            buffer[size++] = value.toByte()
        }

        fun bytes(value: ByteArray, offset: Int = 0, length: Int = value.size) {
            ensure(length)
            value.copyInto(buffer, size, offset, offset + length)
            size += length
        }

        fun ascii(value: String) = bytes(value.encodeToByteArray())

        fun le16(value: Int) {
            byte(value and 0xFF)
            byte((value shr 8) and 0xFF)
        }

        fun le32(value: Int) {
            le16(value and 0xFFFF)
            le16((value ushr 16) and 0xFFFF)
        }

        fun le64(value: Long) {
            le32((value and 0xFFFFFFFFL).toInt())
            le32((value ushr 32).toInt())
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)
    }
}
