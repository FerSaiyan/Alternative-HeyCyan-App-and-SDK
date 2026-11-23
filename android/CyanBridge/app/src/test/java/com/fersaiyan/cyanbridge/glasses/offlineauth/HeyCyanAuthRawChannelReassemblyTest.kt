package com.fersaiyan.cyanbridge.glasses.offlineauth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Replies longer than the link's notification size arrive split. Seen on the add.222 rig (20-byte notifications):
 * the 21-byte cmd-0x47 capability reply came as 20 + 1 bytes, the app never matched it and the unlock timed out.
 */
class HeyCyanAuthRawChannelReassemblyTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val capReply = hex("bc470f0080230100000000000000000000000000d0")
    private val nak = hex("bc410100ff4103")

    @Before
    fun clear() {
        // A complete frame resets any buffered fragment.
        HeyCyanAuthRawChannel.reassemble(nak)
    }

    @Test
    fun splitCapabilityReplyIsReassembled() {
        assertNull(HeyCyanAuthRawChannel.reassemble(capReply.copyOfRange(0, 20)))
        assertArrayEquals(capReply, HeyCyanAuthRawChannel.reassemble(capReply.copyOfRange(20, capReply.size)))
    }

    @Test
    fun wholeFramesPassStraightThrough() {
        assertArrayEquals(nak, HeyCyanAuthRawChannel.reassemble(nak))
        assertArrayEquals(capReply, HeyCyanAuthRawChannel.reassemble(capReply))
    }

    @Test
    fun aNewFrameReplacesAnUnfinishedOne() {
        assertNull(HeyCyanAuthRawChannel.reassemble(capReply.copyOfRange(0, 20)))
        assertArrayEquals(nak, HeyCyanAuthRawChannel.reassemble(nak))
    }

    @Test
    fun rcspFramesAreNeverGluedToABufferedBcFragment() {
        val rcsp = hex("fedcba00") + ByteArray(16) { it.toByte() }
        assertNull(HeyCyanAuthRawChannel.reassemble(capReply.copyOfRange(0, 20)))
        assertArrayEquals(rcsp, HeyCyanAuthRawChannel.reassemble(rcsp))
    }
}
