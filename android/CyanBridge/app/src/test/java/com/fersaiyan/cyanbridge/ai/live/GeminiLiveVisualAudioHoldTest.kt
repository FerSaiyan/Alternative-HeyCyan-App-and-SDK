package com.fersaiyan.cyanbridge.ai.live

import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiLiveVisualAudioHoldTest {
    private val hold = GeminiLiveVisualAudioHold()
    private val sent = mutableListOf<Int>()
    private var speaking = false
    private var live = true

    private fun offer(vararg ids: Int) = ids.forEach { id ->
        hold.offer(byteArrayOf(id.toByte()), isSpeaking = { speaking }, send = { sent += it[0].toInt() })
    }

    private fun release(): Int? {
        var released: Int? = null
        hold.release(isLive = { live }, send = { sent += it[0].toInt() }) { released = it }
        return released
    }

    @Test
    fun `streams with a sixteen chunk delay when no hold is armed`() {
        offer(*(1..16).toList().toIntArray())
        assertEquals(emptyList<Int>(), sent)
        offer(17, 18)
        assertEquals(listOf(1, 2), sent)
    }

    @Test
    fun `offered packets are copied`() {
        val packet = byteArrayOf(5)
        hold.offer(packet, isSpeaking = { false }, send = {})
        packet[0] = 6
        hold.flush(isLive = { true }, send = { sent += it[0].toInt() })
        assertEquals(listOf(5), sent)
    }

    @Test
    fun `silent tail is held and later silence dropped until release`() {
        offer(1, 2, 3)
        var armed = 0
        hold.defer(isLive = { live }) { armed++ }
        hold.defer(isLive = { live }) { armed++ }
        assertEquals(1, armed)

        offer(4, 5, 6)
        assertEquals(emptyList<Int>(), sent)

        assertEquals(4, release())
        assertEquals(listOf(1, 2, 3, 4), sent)
        assertEquals(null, release())
    }

    @Test
    fun `speech during a hold drops the held silent tail and resumes streaming`() {
        offer(*(1..20).toList().toIntArray()) // streams 1..4, queue holds 5..20
        sent.clear()
        hold.defer(isLive = { true }) {}
        offer(21) // arms hold: queue 5..21 (17), held tail = 15 chunks
        speaking = true
        offer(22) // removes 15 newest (7..21), queue 5,6 + 22
        assertEquals(emptyList<Int>(), sent)

        hold.flush(isLive = { true }, send = { sent += it[0].toInt() })
        assertEquals(listOf(5, 6, 22), sent)
    }

    @Test
    fun `defer is ignored when not live and release discards when no longer live`() {
        var armed = 0
        hold.defer(isLive = { false }) { armed++ }
        assertEquals(0, armed)

        offer(1)
        hold.defer(isLive = { true }) { armed++ }
        live = false
        assertEquals(null, release())
        hold.flush(isLive = { true }, send = { sent += it[0].toInt() })
        assertEquals(emptyList<Int>(), sent)
    }

    @Test
    fun `flush when not live and clear both discard queued audio`() {
        offer(1, 2)
        hold.flush(isLive = { false }, send = { sent += it[0].toInt() })
        offer(3)
        hold.clear()
        hold.flush(isLive = { true }, send = { sent += it[0].toInt() })
        assertEquals(emptyList<Int>(), sent)
    }
}
