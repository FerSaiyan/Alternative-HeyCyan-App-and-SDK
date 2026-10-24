package com.fersaiyan.cyanbridge.media

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Byte-exact characterization of the Ogg/Opus packaging used for synced glasses audio.
 * Expected bytes were produced by an independent reference implementation of RFC 3533
 * paging with the same packet-per-page policy.
 */
class OggOpusPackagerTest {
    private fun packet(size: Int, seed: Int): ByteArray =
        ByteArray(size) { k -> ((seed * 31 + k * 7) and 0xFF).toByte() }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).hex()

    private fun serialOf(ogg: ByteArray): Int =
        (ogg[14].toInt() and 0xFF) or
            ((ogg[15].toInt() and 0xFF) shl 8) or
            ((ogg[16].toInt() and 0xFF) shl 16) or
            ((ogg[17].toInt() and 0xFF) shl 24)

    private fun lengthPrefixed(packets: List<ByteArray>, encode: (Int) -> ByteArray): ByteArray =
        packets.fold(ByteArray(0)) { acc, p -> acc + encode(p.size) + p }

    @Test
    fun `ogg crc matches the unreflected crc32 without final xor`() {
        assertEquals(0x89a1897f.toInt(), OggOpusPackager.oggCrc("123456789".toByteArray(Charsets.US_ASCII)))
        assertEquals(0x5fb0a94f, OggOpusPackager.oggCrc("OggS".toByteArray(Charsets.US_ASCII)))
        assertEquals(0, OggOpusPackager.oggCrc(ByteArray(0)))
    }

    @Test
    fun `builds byte exact header and audio pages`() {
        val ogg = OggOpusPackager.buildOggOpusFromPackets(
            listOf(packet(3, 1), packet(5, 2), packet(4, 3)),
            packetDurationMs = 40,
            serial = 0x12345678,
        )
        assertEquals(
            "4f676753000200000000000000007856341200000000a5e2ec4601134f707573486561640101000080bb" +
                "00000000004f6767530000000000000000000078563412010000002da5e291011a4f707573546167730a" +
                "0000004379616e427269646765000000004f676753000480160000000000007856341202000000913f60" +
                "78030305041f262d3e454c535a5d646b72",
            ogg.hex(),
        )
    }

    @Test
    fun `lacing boundaries and negative serials are byte exact`() {
        val ogg = OggOpusPackager.buildOggOpusFromPackets(
            listOf(packet(255, 4), packet(510, 5), packet(1, 6), packet(256, 7)),
            packetDurationMs = 20,
            serial = 0xDEADBEEF.toInt(),
        )
        assertEquals(1158, ogg.size)
        assertEquals("334d62a13ab93f14dce57baf7cb05c97b53bf78c3d373d3dbe3257e870abccd2", ogg.sha256())
    }

    @Test
    fun `splits long recordings across pages at the segment limit`() {
        val ogg = OggOpusPackager.buildOggOpusFromPackets(
            List(600) { packet(40, it) },
            packetDurationMs = 40,
            serial = 42,
        )
        assertEquals(24782, ogg.size)
        assertEquals("1bdb667c954f797ec27ebdb02e3858152220f7a8779be99830eebac5422883de", ogg.sha256())
    }

    @Test
    fun `keeps existing ogg payloads untouched`() {
        val raw = "OggS".toByteArray(Charsets.US_ASCII) + packet(20, 9)
        val (out, note) = OggOpusPackager.wrapOpusIfNeeded(raw)
        assertSame(raw, out)
        assertEquals("ogg-already", note)
    }

    @Test
    fun `wraps little endian length prefixed packets`() {
        val packets = listOf(packet(3, 1), packet(5, 2), packet(4, 3))
        val raw = lengthPrefixed(packets) { byteArrayOf((it and 0xFF).toByte(), (it shr 8).toByte()) }
        val (out, note) = OggOpusPackager.wrapOpusIfNeeded(raw)
        assertEquals("wrapped packets=3", note)
        assertArrayEquals(
            OggOpusPackager.buildOggOpusFromPackets(packets, packetDurationMs = 40, serial = serialOf(out)),
            out,
        )
    }

    @Test
    fun `wraps big endian length prefixed packets`() {
        val packets = listOf(packet(3, 1), packet(5, 2), packet(4, 3))
        val raw = lengthPrefixed(packets) { byteArrayOf((it shr 8).toByte(), (it and 0xFF).toByte()) }
        assertNull(OggOpusPackager.parseLengthPrefixedPackets(raw, littleEndian = true))
        val (out, note) = OggOpusPackager.wrapOpusIfNeeded(raw)
        assertEquals("wrapped packets=3", note)
        assertArrayEquals(
            OggOpusPackager.buildOggOpusFromPackets(packets, packetDurationMs = 40, serial = serialOf(out)),
            out,
        )
    }

    @Test
    fun `wraps single byte length prefixed packets`() {
        val packets = listOf(packet(3, 1), packet(5, 2), packet(4, 3))
        val raw = lengthPrefixed(packets) { byteArrayOf(it.toByte()) }
        assertNull(OggOpusPackager.parseLengthPrefixedPackets(raw, littleEndian = true))
        assertNull(OggOpusPackager.parseLengthPrefixedPackets(raw, littleEndian = false))
        val (out, note) = OggOpusPackager.wrapOpusIfNeeded(raw)
        assertEquals("wrapped packets=3", note)
        assertArrayEquals(
            OggOpusPackager.buildOggOpusFromPackets(packets, packetDurationMs = 40, serial = serialOf(out)),
            out,
        )
    }

    @Test
    fun `falls back to fixed forty byte packets`() {
        val raw = ByteArray(200) { 0xFF.toByte() }
        val (out, note) = OggOpusPackager.wrapOpusIfNeeded(raw)
        assertEquals("wrapped packets=5", note)
        assertArrayEquals(
            OggOpusPackager.buildOggOpusFromPackets(
                List(5) { ByteArray(40) { 0xFF.toByte() } },
                packetDurationMs = 40,
                serial = serialOf(out),
            ),
            out,
        )
    }

    @Test
    fun `leaves unknown layouts unwrapped`() {
        val odd = ByteArray(7) { 0xFF.toByte() }
        val (out, note) = OggOpusPackager.wrapOpusIfNeeded(odd)
        assertSame(odd, out)
        assertEquals("raw-unwrapped", note)

        val empty = ByteArray(0)
        val (emptyOut, emptyNote) = OggOpusPackager.wrapOpusIfNeeded(empty)
        assertSame(empty, emptyOut)
        assertEquals("raw-unwrapped", emptyNote)
    }
}
