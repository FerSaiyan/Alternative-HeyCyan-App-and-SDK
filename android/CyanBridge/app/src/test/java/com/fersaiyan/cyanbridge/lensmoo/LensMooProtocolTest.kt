package com.fersaiyan.cyanbridge.lensmoo

import org.junit.Assert.*
import org.junit.Test

class LensMooProtocolTest {
    @Test fun fragmentedAndCoalesced() {
        val frame=LensMooProtocol.node(3,14,"1001",byteArrayOf(1,2),1,0)
        for(split in 1 until frame.size) {
            val parser=LensMooProtocol.Parser()
            assertTrue(parser.feed(frame.copyOfRange(0,split)).isEmpty())
            val frames=parser.feed(frame.copyOfRange(split,frame.size)+frame)
            assertEquals(2,frames.size)
            val node=LensMooProtocol.nodes(frames[0]).single()
            assertEquals(14,node.pkg); assertEquals("1001",node.urn)
            assertArrayEquals(byteArrayOf(1,2),node.data)
        }
    }
    @Test fun clfsrRecoveredReferenceVector() {
        val actual=LensMooProtocol.crypt(0x1234,"0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toByteArray())
        assertEquals("297dd3b84ae60236e301e5adbf4fdbc8253a09ec9ea19e8204d446b8c7d697dcd061d4fd",actual.joinToString("") { "%02x".format(it.toInt() and 255) })
    }
    @Test fun crcGolden() { assertEquals(0x29b1,LensMooProtocol.crc("123456789".toByteArray())) }
    @Test fun corruptPayloadRejected() {
        val frame=LensMooProtocol.envelope(0x30,1,1,byteArrayOf(1,2));frame[17]=3
        assertThrows(IllegalArgumentException::class.java) { LensMooProtocol.Parser().feed(frame) }
    }
    @Test fun unknownHeaderRejected() {
        assertThrows(IllegalArgumentException::class.java) { LensMooProtocol.Parser().feed(ByteArray(16)) }
    }
    @Test fun malformedNodeRejected() {
        val f=LensMooProtocol.node(1,1,"1001",byteArrayOf(1),1,0)
        assertThrows(IllegalArgumentException::class.java) { LensMooProtocol.nodes(f.copyOf(f.size-1)) }
    }
    @Test fun handshakeRejectsBadLengthAndTampering() {
        assertFalse(LensMooProtocol.verify(ByteArray(63),ByteArray(64)))
        val req=ByteArray(64) { it.toByte() }; val challenge=LensMooProtocol.crypt(0x0e0f,req.copyOfRange(16,48))
        val masked=ByteArray(32) { (challenge[it].toInt() xor req[48+it%16].toInt()).toByte() }
        val response=ByteArray(16)+LensMooProtocol.crypt(req.copyOfRange(16,48).sumOf { it.toInt() and 255 },masked)+ByteArray(16)
        assertTrue(LensMooProtocol.verify(req,response));response[20]=(response[20].toInt() xor 1).toByte();assertFalse(LensMooProtocol.verify(req,response))
    }
}
