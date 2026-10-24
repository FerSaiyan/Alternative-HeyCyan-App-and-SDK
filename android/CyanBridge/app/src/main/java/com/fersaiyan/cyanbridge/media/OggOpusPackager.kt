package com.fersaiyan.cyanbridge.media

import android.util.Log
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * Packages raw Opus payloads downloaded from the glasses into an Ogg/Opus container
 * so standard players can open them.
 */
internal object OggOpusPackager {

    fun wrapOpusIfNeeded(raw: ByteArray): Pair<ByteArray, String> {
        if (raw.size >= 4 && raw[0].toInt() == 'O'.code && raw[1].toInt() == 'g'.code && raw[2].toInt() == 'g'.code && raw[3].toInt() == 'S'.code) {
            return raw to "ogg-already"
        }

        // Try to interpret the file as a sequence of length-prefixed Opus packets and wrap
        // them into a proper Ogg/Opus container so standard players (VLC) can open it.
        val packets = parseLengthPrefixedPackets(raw, littleEndian = true)
            ?: parseLengthPrefixedPackets(raw, littleEndian = false)
            ?: parseLengthPrefixedPackets1B(raw)
            ?: guessFixedSizePackets(raw)

        if (packets == null || packets.isEmpty()) {
            // Unknown/proprietary layout (the official app decodes these with jl_opus).
            return raw to "raw-unwrapped"
        }

        return try {
            val ogg = buildOggOpusFromPackets(packets, packetDurationMs = 40)
            ogg to "wrapped packets=${packets.size}"
        } catch (e: Exception) {
            Log.w("DataDownload", "Failed to wrap opus into ogg: ${e.message}")
            raw to "raw-unwrapped"
        }
    }

    fun parseLengthPrefixedPackets(raw: ByteArray, littleEndian: Boolean): List<ByteArray>? {
        // Heuristic: repeated [u16 len][len bytes]...
        var i = 0
        val out = ArrayList<ByteArray>()
        while (i + 2 <= raw.size) {
            val b0 = raw[i].toInt() and 0xFF
            val b1 = raw[i + 1].toInt() and 0xFF
            val len = if (littleEndian) (b0 or (b1 shl 8)) else ((b0 shl 8) or b1)
            i += 2
            if (len <= 0 || len > 2000) return null
            if (i + len > raw.size) return null
            out.add(raw.copyOfRange(i, i + len))
            i += len
        }
        if (i != raw.size) return null
        // Require a few packets so we don't mis-detect.
        return if (out.size >= 3) out else null
    }

    fun parseLengthPrefixedPackets1B(raw: ByteArray): List<ByteArray>? {
        // Heuristic: repeated [u8 len][len bytes]...
        var i = 0
        val out = ArrayList<ByteArray>()
        while (i + 1 <= raw.size) {
            val len = raw[i].toInt() and 0xFF
            i += 1
            if (len <= 0 || len > 255) return null
            if (i + len > raw.size) return null
            out.add(raw.copyOfRange(i, i + len))
            i += len
        }
        if (i != raw.size) return null
        return if (out.size >= 3) out else null
    }

    fun guessFixedSizePackets(raw: ByteArray): List<ByteArray>? {
        // Last-resort heuristic: some devices store raw Opus packets back-to-back with a
        // fixed packet byte size. Try a few common sizes.
        if (raw.isEmpty()) return null
        // 40 bytes is especially common for these glasses (official app uses packetSize=40).
        val candidates = intArrayOf(40, 60, 80, 100, 120, 160, 200, 240, 320)
        for (size in candidates) {
            if (size <= 0) continue
            if (raw.size % size != 0) continue
            val count = raw.size / size
            if (count < 5) continue
            val out = ArrayList<ByteArray>(count)
            var i = 0
            while (i < raw.size) {
                out.add(raw.copyOfRange(i, i + size))
                i += size
            }
            return out
        }
        return null
    }

    fun buildOggOpusFromPackets(
        packets: List<ByteArray>,
        packetDurationMs: Int,
        serial: Int = SecureRandom().nextInt(),
    ): ByteArray {
        // Ogg/Opus expects OpusHead + OpusTags packets before audio packets.
        var seq = 0
        var granulePos: Long = 0

        val out = ByteArrayOutputStream()

        val opusHead = buildOpusHead(channels = 1, preSkip = 0)
        val opusTags = buildOpusTags(vendor = "CyanBridge")

        // Header pages
        writeOggPage(out, serial, seq++, granulePosition = 0, headerType = 0x02, packets = listOf(opusHead))
        writeOggPage(out, serial, seq++, granulePosition = 0, headerType = 0x00, packets = listOf(opusTags))

        // Audio pages
        val samplesPerPacket48k = (packetDurationMs * 48_000L) / 1000L
        val maxSegments = 255
        var idx = 0
        while (idx < packets.size) {
            val pagePackets = ArrayList<ByteArray>()
            var segCount = 0
            var localGranule = granulePos

            while (idx < packets.size) {
                val p = packets[idx]
                var neededSeg = (p.size + 254) / 255
                if (p.size % 255 == 0) neededSeg += 1
                if (segCount + neededSeg > maxSegments) break
                pagePackets.add(p)
                segCount += neededSeg
                localGranule += samplesPerPacket48k
                idx++
            }

            granulePos = localGranule
            val isLast = idx >= packets.size
            val headerType = if (isLast) 0x04 else 0x00
            writeOggPage(out, serial, seq++, granulePosition = granulePos, headerType = headerType, packets = pagePackets)
        }

        return out.toByteArray()
    }

    private fun buildOpusHead(channels: Int, preSkip: Int): ByteArray {
        // OpusHead (19 bytes)
        val b = ByteArrayOutputStream()
        b.write("OpusHead".toByteArray(Charsets.US_ASCII))
        b.write(1) // version
        b.write(channels and 0xFF)
        // pre-skip LE16
        b.write(preSkip and 0xFF)
        b.write((preSkip shr 8) and 0xFF)
        // input sample rate LE32 (Opus is coded at 48k internally)
        val sr = 48_000
        b.write(sr and 0xFF)
        b.write((sr shr 8) and 0xFF)
        b.write((sr shr 16) and 0xFF)
        b.write((sr shr 24) and 0xFF)
        // output gain LE16
        b.write(0)
        b.write(0)
        // channel mapping family (0 = mono/stereo)
        b.write(0)
        return b.toByteArray()
    }

    private fun buildOpusTags(vendor: String): ByteArray {
        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        val b = ByteArrayOutputStream()
        b.write("OpusTags".toByteArray(Charsets.US_ASCII))
        writeLe32(b, vendorBytes.size)
        b.write(vendorBytes)
        // user comment list length = 0
        writeLe32(b, 0)
        return b.toByteArray()
    }

    private fun writeLe32(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }

    private fun writeOggPage(
        out: ByteArrayOutputStream,
        serial: Int,
        seq: Int,
        granulePosition: Long,
        headerType: Int,
        packets: List<ByteArray>,
    ) {
        val segmentTable = ByteArrayOutputStream()
        val payload = ByteArrayOutputStream()

        for (p in packets) {
            var remaining = p.size
            var offset = 0
            while (remaining > 0) {
                val seg = minOf(255, remaining)
                segmentTable.write(seg)
                payload.write(p, offset, seg)
                offset += seg
                remaining -= seg
            }
            if (p.size % 255 == 0) {
                // Lacing: 255 indicates continuation; add 0 to terminate packet exactly on boundary.
                segmentTable.write(0)
            }
        }

        val segBytes = segmentTable.toByteArray()
        if (segBytes.size > 255) {
            throw IllegalStateException("Ogg page has too many segments: ${segBytes.size}")
        }
        val payloadBytes = payload.toByteArray()

        val header = ByteArrayOutputStream()
        header.write("OggS".toByteArray(Charsets.US_ASCII))
        header.write(0) // version
        header.write(headerType and 0xFF)
        writeLe64(header, granulePosition)
        writeLe32(header, serial)
        writeLe32(header, seq)
        // checksum placeholder
        writeLe32(header, 0)
        header.write(segBytes.size)
        header.write(segBytes)

        val pageBytes = header.toByteArray() + payloadBytes
        val crc = oggCrc(pageBytes)

        // Patch checksum at byte offset 22 (from start of OggS)
        pageBytes[22] = (crc and 0xFF).toByte()
        pageBytes[23] = ((crc shr 8) and 0xFF).toByte()
        pageBytes[24] = ((crc shr 16) and 0xFF).toByte()
        pageBytes[25] = ((crc shr 24) and 0xFF).toByte()

        out.write(pageBytes)
    }

    private fun writeLe64(out: ByteArrayOutputStream, v: Long) {
        out.write((v and 0xFF).toInt())
        out.write(((v shr 8) and 0xFF).toInt())
        out.write(((v shr 16) and 0xFF).toInt())
        out.write(((v shr 24) and 0xFF).toInt())
        out.write(((v shr 32) and 0xFF).toInt())
        out.write(((v shr 40) and 0xFF).toInt())
        out.write(((v shr 48) and 0xFF).toInt())
        out.write(((v shr 56) and 0xFF).toInt())
    }

    private val oggCrcTable: IntArray = run {
        val table = IntArray(256)
        for (i in 0 until 256) {
            var r = i shl 24
            for (j in 0 until 8) {
                r = if ((r and 0x80000000.toInt()) != 0) {
                    (r shl 1) xor 0x04C11DB7
                } else {
                    r shl 1
                }
            }
            table[i] = r
        }
        table
    }

    fun oggCrc(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            val idx = ((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF
            crc = (crc shl 8) xor oggCrcTable[idx]
        }
        return crc
    }
}
