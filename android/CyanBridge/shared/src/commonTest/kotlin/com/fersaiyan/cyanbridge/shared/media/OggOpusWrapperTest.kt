package com.fersaiyan.cyanbridge.shared.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Byte-exact characterization of the Ogg/Opus packaging used for synced glasses audio.
 * The two SHA-256 cases live in OggOpusWrapperDigestTest (portabilityTest).
 * Expected bytes were produced by an independent reference implementation of RFC 3533
 * paging with the same packet-per-page policy.
 */
class OggOpusWrapperTest {
    private fun packet(size: Int, seed: Int): ByteArray =
        ByteArray(size) { k -> ((seed * 31 + k * 7) and 0xFF).toByte() }

    private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun serialOf(ogg: ByteArray): Int =
        (ogg[14].toInt() and 0xFF) or
            ((ogg[15].toInt() and 0xFF) shl 8) or
            ((ogg[16].toInt() and 0xFF) shl 16) or
            ((ogg[17].toInt() and 0xFF) shl 24)

    private fun lengthPrefixed(packets: List<ByteArray>, encode: (Int) -> ByteArray): ByteArray =
        packets.fold(ByteArray(0)) { acc, p -> acc + encode(p.size) + p }

    @Test
    fun oggCrcMatchesTheUnreflectedCrc32WithoutFinalXor() {
        assertEquals(0x89a1897f.toInt(), OggOpusWrapper.oggCrc("123456789".encodeToByteArray()))
        assertEquals(0x5fb0a94f, OggOpusWrapper.oggCrc("OggS".encodeToByteArray()))
        assertEquals(0, OggOpusWrapper.oggCrc(ByteArray(0)))
    }

    @Test
    fun buildsByteExactHeaderAndAudioPages() {
        val ogg = OggOpusWrapper.buildOggOpusFromPackets(
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
    fun keepsExistingOggPayloadsUntouched() {
        val raw = "OggS".encodeToByteArray() + packet(20, 9)
        val (out, note) = OggOpusWrapper.wrapOpusIfNeeded(raw)
        assertSame(raw, out)
        assertEquals("ogg-already", note)
    }

    @Test
    fun wrapsLittleEndianLengthPrefixedPackets() {
        val packets = listOf(packet(3, 1), packet(5, 2), packet(4, 3))
        val raw = lengthPrefixed(packets) { byteArrayOf((it and 0xFF).toByte(), (it shr 8).toByte()) }
        val (out, note) = OggOpusWrapper.wrapOpusIfNeeded(raw)
        assertEquals("wrapped packets=3", note)
        assertContentEquals(
            OggOpusWrapper.buildOggOpusFromPackets(packets, packetDurationMs = 40, serial = serialOf(out)),
            out,
        )
    }

    @Test
    fun wrapsBigEndianLengthPrefixedPackets() {
        val packets = listOf(packet(3, 1), packet(5, 2), packet(4, 3))
        val raw = lengthPrefixed(packets) { byteArrayOf((it shr 8).toByte(), (it and 0xFF).toByte()) }
        assertNull(OggOpusWrapper.parseLengthPrefixedPackets(raw, littleEndian = true))
        val (out, note) = OggOpusWrapper.wrapOpusIfNeeded(raw)
        assertEquals("wrapped packets=3", note)
        assertContentEquals(
            OggOpusWrapper.buildOggOpusFromPackets(packets, packetDurationMs = 40, serial = serialOf(out)),
            out,
        )
    }

    @Test
    fun wrapsSingleByteLengthPrefixedPackets() {
        val packets = listOf(packet(3, 1), packet(5, 2), packet(4, 3))
        val raw = lengthPrefixed(packets) { byteArrayOf(it.toByte()) }
        assertNull(OggOpusWrapper.parseLengthPrefixedPackets(raw, littleEndian = true))
        assertNull(OggOpusWrapper.parseLengthPrefixedPackets(raw, littleEndian = false))
        val (out, note) = OggOpusWrapper.wrapOpusIfNeeded(raw)
        assertEquals("wrapped packets=3", note)
        assertContentEquals(
            OggOpusWrapper.buildOggOpusFromPackets(packets, packetDurationMs = 40, serial = serialOf(out)),
            out,
        )
    }

    @Test
    fun fallsBackToFixedFortyBytePackets() {
        val raw = ByteArray(200) { 0xFF.toByte() }
        val (out, note) = OggOpusWrapper.wrapOpusIfNeeded(raw)
        assertEquals("wrapped packets=5", note)
        assertContentEquals(
            OggOpusWrapper.buildOggOpusFromPackets(
                List(5) { ByteArray(40) { 0xFF.toByte() } },
                packetDurationMs = 40,
                serial = serialOf(out),
            ),
            out,
        )
    }

    @Test
    fun leavesUnknownLayoutsUnwrapped() {
        val odd = ByteArray(7) { 0xFF.toByte() }
        val (out, note) = OggOpusWrapper.wrapOpusIfNeeded(odd)
        assertSame(odd, out)
        assertEquals("raw-unwrapped", note)

        val empty = ByteArray(0)
        val (emptyOut, emptyNote) = OggOpusWrapper.wrapOpusIfNeeded(empty)
        assertSame(empty, emptyOut)
        assertEquals("raw-unwrapped", emptyNote)
    }
}
