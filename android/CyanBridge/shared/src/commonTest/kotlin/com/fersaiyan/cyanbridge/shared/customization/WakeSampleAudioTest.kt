package com.fersaiyan.cyanbridge.shared.customization

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class WakeSampleAudioTest {
    @Test
    fun wavHeaderIsCanonical16kMonoPcm16() {
        val wav = WakeSampleAudio.toWav(ByteArray(32_000))
        assertEquals("RIFF", wav.copyOfRange(0, 4).decodeToString())
        assertEquals("WAVEfmt ", wav.copyOfRange(8, 16).decodeToString())
        assertContentEquals(byteArrayOf(16, 0, 0, 0, 1, 0, 1, 0, 0x80.toByte(), 0x3E, 0, 0, 0, 0x7D, 0, 0, 2, 0, 16, 0), wav.copyOfRange(16, 36))
        assertEquals("data", wav.copyOfRange(36, 40).decodeToString())
        assertEquals(32_044, wav.size)
        assertEquals(1_000L, WakeSampleAudio.durationMs(WakeSampleAudio.trimHead(ByteArray(41_600))))
    }

    @Test
    fun eachFamilysTransportIsSplitIntoOpusPackets() {
        // HeyCyan: 6-byte BigData header, then 40-byte packets.
        assertEquals(listOf(40, 40), GlassesMicFraming.heyCyanPackets(ByteArray(6 + 80)).map { it.size })
        // EyeVue: N x 40, ragged tail dropped like the vendor app.
        assertEquals(2, GlassesMicFraming.eyevuePackets(ByteArray(95)).size)
        // TuneBuds: 80-byte packets across notify boundaries.
        val repacketizer = OpusRepacketizer(80)
        val stream = ByteArray(240) { it.toByte() }
        val packets = listOf(117, 117, 6).runningFold(0, Int::plus).zipWithNext()
            .flatMap { (from, to) -> repacketizer.append(stream.copyOfRange(from, to)) }
        assertEquals(3, packets.size)
        assertContentEquals(stream.copyOfRange(80, 160), packets[1])
    }
}
