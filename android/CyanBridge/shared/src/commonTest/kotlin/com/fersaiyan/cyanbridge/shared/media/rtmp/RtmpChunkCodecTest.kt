package com.fersaiyan.cyanbridge.shared.media.rtmp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RtmpChunkCodecTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun payload(size: Int, seed: Int = 1) = ByteArray(size) { ((it * 31 + seed) and 0xFF).toByte() }

    @Test
    fun encoderAndDecoderRoundTripMultiChunkMessagesInAnySplit() {
        val encoder = RtmpChunkEncoder()
        val messages = listOf(
            RtmpMessage(RtmpMessage.VIDEO, 1, 40, payload(1000)),
            RtmpMessage(RtmpMessage.AUDIO, 1, 41, payload(3, seed = 7)),
            RtmpMessage(RtmpMessage.VIDEO, 1, 80, payload(128)),
            RtmpMessage(RtmpMessage.VIDEO, 1, 120, ByteArray(0)),
        )
        val wire = messages.map { encoder.encode(if (it.typeId == RtmpMessage.AUDIO) 4 else 6, it) }
            .reduce { a, b -> a + b }
        for (split in listOf(1, 7, 128, wire.size)) {
            val decoder = RtmpChunkDecoder()
            val decoded = wire.toList().chunked(split).flatMap { decoder.feed(it.toByteArray()) }
            assertEquals(messages.size, decoded.size, "split $split")
            messages.zip(decoded).forEach { (expected, actual) ->
                assertEquals(expected.typeId, actual.typeId)
                assertEquals(expected.streamId, actual.streamId)
                assertEquals(expected.timestamp, actual.timestamp)
                assertContentEquals(expected.payload, actual.payload)
            }
        }
    }

    @Test
    fun compressedHeadersReuseTheChunkStreamState() {
        val decoder = RtmpChunkDecoder()
        // fmt 0: ts 1000, length 2, type 9, stream 1.
        val full = bytes(0x06, 0x00, 0x03, 0xE8, 0x00, 0x00, 0x02, 0x09, 0x01, 0x00, 0x00, 0x00, 0xAA, 0xBB)
        // fmt 1: delta 33, length 1, type 8 (stream id kept).
        val medium = bytes(0x46, 0x00, 0x00, 0x21, 0x00, 0x00, 0x01, 0x08, 0xCC)
        // fmt 2: delta 40, same length and type.
        val small = bytes(0x86, 0x00, 0x00, 0x28, 0xDD)
        // fmt 3 starting a new message: the previous delta again.
        val minimal = bytes(0xC6, 0xEE)
        val decoded = decoder.feed(full + medium + small + minimal)
        assertEquals(listOf(1000L, 1033L, 1073L, 1113L), decoded.map { it.timestamp })
        assertEquals(listOf(9, 8, 8, 8), decoded.map { it.typeId })
        assertEquals(listOf(1, 1, 1, 1), decoded.map { it.streamId })
        assertContentEquals(bytes(0xEE), decoded.last().payload)
    }

    @Test
    fun extendedTimestampsAreReadOnFirstAndContinuationChunks() {
        val message = RtmpMessage(RtmpMessage.VIDEO, 1, 0x0100_0000L, payload(300))
        val wire = RtmpChunkEncoder().encode(6, message)
        // Header 12 + extended 4 + 128, then (1 + 4 + 128) and (1 + 4 + 44).
        assertEquals(12 + 4 + 300 + 2 * (1 + 4), wire.size)
        val decoded = RtmpChunkDecoder().feed(wire).single()
        assertEquals(0x0100_0000L, decoded.timestamp)
        assertContentEquals(message.payload, decoded.payload)
    }

    @Test
    fun setChunkSizeAppliesToTheFollowingChunks() {
        val sender = RtmpChunkEncoder()
        val control = sender.encode(2, RtmpControl.setChunkSize(4096))
        sender.chunkSize = 4096
        val big = RtmpMessage(RtmpMessage.VIDEO, 1, 0, payload(3000))
        val encoded = sender.encode(6, big)
        assertEquals(12 + 3000, encoded.size)
        val decoder = RtmpChunkDecoder()
        val decoded = decoder.feed(control + encoded)
        assertEquals(4096, decoder.chunkSize)
        assertContentEquals(big.payload, decoded.last().payload)
    }

    @Test
    fun twoAndThreeByteBasicHeadersAreSupported() {
        val twoByte = bytes(0x00, 0x0A, 0, 0, 5, 0, 0, 1, 9, 1, 0, 0, 0, 0x42) // csid 74
        val threeByte = bytes(0x01, 0x00, 0x01, 0, 0, 6, 0, 0, 1, 8, 1, 0, 0, 0, 0x43) // csid 320
        val decoded = RtmpChunkDecoder().feed(twoByte + threeByte)
        assertEquals(listOf(5L, 6L), decoded.map { it.timestamp })
        assertEquals(listOf(0x42.toByte(), 0x43.toByte()), decoded.map { it.payload.single() })
    }

    @Test
    fun abortDropsThePartialMessage() {
        val decoder = RtmpChunkDecoder()
        // A 200-byte message on csid 6, of which only the first 128-byte chunk arrives.
        val first = RtmpChunkEncoder().encode(6, RtmpMessage(RtmpMessage.VIDEO, 1, 0, payload(200))).copyOf(12 + 128)
        assertTrue(decoder.feed(first).isEmpty())
        val abort = RtmpChunkEncoder().encode(2, RtmpMessage(RtmpMessage.ABORT, 0, 0, ByteWriter().u32(6).toByteArray()))
        decoder.feed(abort)
        val next = RtmpChunkEncoder().encode(6, RtmpMessage(RtmpMessage.VIDEO, 1, 10, payload(5)))
        assertEquals(5, decoder.feed(next).single().payload.size)
    }

    @Test
    fun malformedStreamsAreRejected() {
        assertFailsWith<RtmpProtocolException> { RtmpChunkDecoder().feed(bytes(0xC6, 0x00)) }
        val huge = bytes(0x06, 0, 0, 0, 0xFF, 0xFF, 0xFF, 9, 1, 0, 0, 0)
        assertFailsWith<RtmpProtocolException> { RtmpChunkDecoder(maxMessageBytes = 1024).feed(huge) }
    }

    @Test
    fun handshakeEchoesC1AndUsesAZeroVersionField() {
        val c0c1 = ByteArray(RtmpHandshake.C0C1_SIZE) { (it % 251).toByte() }.also { it[0] = 3 }
        val response = RtmpHandshake.serverResponse(c0c1, serverTimeMs = 0x01020304, random = ByteArray(1528) { 9 })
        assertEquals(1 + 2 * 1536, response.size)
        assertEquals(3, response[0].toInt())
        assertContentEquals(bytes(1, 2, 3, 4, 0, 0, 0, 0), response.copyOfRange(1, 9))
        assertContentEquals(c0c1.copyOfRange(1, 1537), response.copyOfRange(1537, 3073))
        c0c1[0] = 6
        assertFailsWith<RtmpProtocolException> { RtmpHandshake.serverResponse(c0c1, 0, ByteArray(1528)) }
    }

    @Test
    fun amf0RoundTripsCommandValues() {
        val values = listOf(
            "connect", 1.0, linkedMapOf("app" to "live", "flashVer" to "FMLE/3.0", "objectEncoding" to 0.0),
            null, Amf0Undefined, true, listOf(1.0, "x"),
        )
        assertEquals(values, Amf0.decodeAll(Amf0.encode(values)))
    }

    @Test
    fun amf0ReadsEcmaArraysAndRejectsTruncatedData() {
        // ECMA array with one property "width" = 1280.0.
        val ecma = ByteWriter().u8(0x08).u32(1).u16(5).bytes("width".encodeToByteArray())
            .u8(0).u64(1280.0.toRawBits()).u16(0).u8(0x09).toByteArray()
        assertEquals(listOf(mapOf("width" to 1280.0)), Amf0.decodeAll(ecma))
        assertFailsWith<RtmpProtocolException> { Amf0.decodeAll(ecma.copyOf(ecma.size - 1)) }
        assertFailsWith<RtmpProtocolException> { Amf0.decodeAll(bytes(0x0D)) }
    }
}
