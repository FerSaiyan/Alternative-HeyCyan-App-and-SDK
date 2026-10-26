package com.fersaiyan.cyanbridge.shared.media.ts

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MpegTsMuxerTest {
    private fun hex(text: String): ByteArray =
        text.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun packets(stream: ByteArray): List<ByteArray> {
        assertEquals(0, stream.size % MpegTsMuxer.PACKET_SIZE)
        return stream.toList().chunked(MpegTsMuxer.PACKET_SIZE).map { it.toByteArray() }
    }

    private fun pid(packet: ByteArray) = ((packet[1].toInt() and 0x1F) shl 8) or (packet[2].toInt() and 0xFF)

    private fun u(byte: Byte) = byte.toInt() and 0xFF

    /** The payload bytes of a packet, after its adaptation field. */
    private fun payload(packet: ByteArray): ByteArray {
        val control = (u(packet[3]) shr 4) and 0x3
        var start = 4
        if (control and 0x2 != 0) start += 1 + u(packet[4])
        return if (control and 0x1 != 0) packet.copyOfRange(start, packet.size) else ByteArray(0)
    }

    private fun readTimestamp(bytes: ByteArray, at: Int): Long =
        ((u(bytes[at]).toLong() shr 1) and 0x7 shl 30) or
            ((u(bytes[at + 1]).toLong() shl 7 or (u(bytes[at + 2]).toLong() shr 1)) shl 15) or
            (u(bytes[at + 3]).toLong() shl 7 or (u(bytes[at + 4]).toLong() shr 1))

    @Test
    fun crcMatchesTheMpeg2Definition() {
        assertEquals(0x2AB104B2L, Crc32Mpeg2.compute(hex("00b00d0001c100000001f000")))
        assertEquals(0x15BD4D56L, Crc32Mpeg2.compute(hex("02b0120001c10000e100f0001be100f000")))
    }

    @Test
    fun keyframesStartWithPatAndPmt() {
        val stream = MpegTsMuxer().muxVideo(ByteArray(10) { 1 }, ptsMs = 0, dtsMs = 0, keyframe = true)
        val list = packets(stream)
        assertContentEquals(
            hex("4740001000 00b00d0001c100000001f0002ab104b2"),
            list[0].copyOfRange(0, 21),
        )
        assertTrue(list[0].copyOfRange(21, 188).all { it == 0xFF.toByte() })
        assertContentEquals(hex("4750001000 02b0120001c10000e100f0001be100f00015bd4d56"), list[1].copyOfRange(0, 26))
        assertEquals(0x100, pid(list[2]))
        // Adaptation field with PCR and random access on the first video packet.
        assertEquals(0x50, u(list[2][5]))
        val inter = MpegTsMuxer().muxVideo(ByteArray(10), ptsMs = 0, dtsMs = 0, keyframe = false)
        assertEquals(1, packets(inter).size)
    }

    @Test
    fun pesPayloadSurvivesPacketizationAtEverySize() {
        val muxer = MpegTsMuxer()
        var expectedContinuity = 0
        for (size in listOf(1, 2, 150, 166, 167, 168, 175, 176, 177, 183, 184, 185, 350, 361, 362, 1000, 70_000)) {
            val unit = ByteArray(size) { (it * 7 + size).toByte() }
            val video = packets(muxer.muxVideo(unit, ptsMs = 1000, dtsMs = 1000, keyframe = false))
            video.forEach { packet ->
                assertEquals(0x47, u(packet[0]))
                assertEquals(0x100, pid(packet))
                assertEquals(expectedContinuity, u(packet[3]) and 0x0F, "size $size")
                expectedContinuity = (expectedContinuity + 1) and 0x0F
            }
            assertTrue(u(video[0][1]) and 0x40 != 0, "payload unit start")
            val pes = video.map(::payload).reduce { a, b -> a + b }
            assertContentEquals(byteArrayOf(0, 0, 1, 0xE0.toByte()), pes.copyOfRange(0, 4))
            val headerLength = u(pes[8])
            assertContentEquals(unit, pes.copyOfRange(9 + headerLength, pes.size), "size $size")
        }
    }

    @Test
    fun timestampsAndPcrUseThe90KhzClock() {
        val packet = packets(MpegTsMuxer().muxVideo(ByteArray(20), ptsMs = 1_066, dtsMs = 1_000, keyframe = false)).single()
        // PCR base from the adaptation field.
        val pcrBase = (u(packet[6]).toLong() shl 25) or (u(packet[7]).toLong() shl 17) or
            (u(packet[8]).toLong() shl 9) or (u(packet[9]).toLong() shl 1) or (u(packet[10]).toLong() shr 7)
        assertEquals(90_000L, pcrBase)
        val pes = payload(packet)
        assertEquals(0xC0, u(pes[7]))
        assertEquals(10, u(pes[8]))
        assertEquals(1_066L * 90, readTimestamp(pes, 9))
        assertEquals(1_000L * 90, readTimestamp(pes, 14))
        assertEquals(0x31, u(pes[9]) and 0xF1)
    }
}
