package com.fersaiyan.cyanbridge.shared.media.rtmp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal object FlvSamples {
    val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1F, 0x11)
    val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
    val idr = byteArrayOf(0x65, 0x11, 0x22, 0x33)
    val slice = byteArrayOf(0x41, 0x44, 0x55)

    fun record(): ByteArray = ByteWriter().u8(1).u8(0x42).u8(0xC0).u8(0x1F).u8(0xFF)
        .u8(0xE1).u16(sps.size).bytes(sps).u8(1).u16(pps.size).bytes(pps).toByteArray()

    fun avcc(vararg units: ByteArray): ByteArray {
        val writer = ByteWriter()
        units.forEach { writer.u32(it.size.toLong()).bytes(it) }
        return writer.toByteArray()
    }

    fun legacySequenceHeader(): ByteArray = ByteWriter().u8(0x17).u8(0).u24(0).bytes(record()).toByteArray()

    fun legacyFrame(keyframe: Boolean, compositionTime: Int, vararg units: ByteArray): ByteArray =
        ByteWriter().u8(if (keyframe) 0x17 else 0x27).u8(1).u24(compositionTime and 0xFFFFFF).bytes(avcc(*units)).toByteArray()
}

class FlvVideoTest {
    private val startCode = byteArrayOf(0, 0, 0, 1)

    @Test
    fun legacyAvcTagsParseIntoConfigurationAndFrames() {
        val header = assertIs<FlvVideoPacket.AvcSequenceHeader>(FlvVideoTag.parse(FlvSamples.legacySequenceHeader()))
        assertEquals(4, header.config.nalLengthSize)
        assertContentEquals(FlvSamples.sps, header.config.sps.single())
        assertContentEquals(FlvSamples.pps, header.config.pps.single())
        assertEquals("avc1.42c01f", header.config.codecString)

        val frame = assertIs<FlvVideoPacket.AvcFrame>(FlvVideoTag.parse(FlvSamples.legacyFrame(true, 66, FlvSamples.idr)))
        assertTrue(frame.keyframe)
        assertEquals(66, frame.compositionTimeMs)
        val negative = assertIs<FlvVideoPacket.AvcFrame>(FlvVideoTag.parse(FlvSamples.legacyFrame(false, -33, FlvSamples.slice)))
        assertEquals(-33, negative.compositionTimeMs)
    }

    @Test
    fun enhancedRtmpAvcAndOtherCodecsAreRecognized() {
        val start = ByteWriter().u8(0x90).bytes("avc1".encodeToByteArray()).bytes(FlvSamples.record()).toByteArray()
        assertIs<FlvVideoPacket.AvcSequenceHeader>(FlvVideoTag.parse(start))
        val framesX = ByteWriter().u8(0x93).bytes("avc1".encodeToByteArray()).bytes(FlvSamples.avcc(FlvSamples.idr)).toByteArray()
        val frame = assertIs<FlvVideoPacket.AvcFrame>(FlvVideoTag.parse(framesX))
        assertTrue(frame.keyframe)
        assertEquals(0, frame.compositionTimeMs)
        val hevc = ByteWriter().u8(0x90).bytes("hvc1".encodeToByteArray()).u8(1).toByteArray()
        assertEquals(FlvVideoPacket.Unsupported("enhanced codec hvc1"), FlvVideoTag.parse(hevc))
        assertEquals(FlvVideoPacket.Unsupported("FLV codec id 12"), FlvVideoTag.parse(byteArrayOf(0x1C, 0, 0, 0, 0)))
        assertFailsWith<RtmpProtocolException> { FlvVideoTag.parse(byteArrayOf(0x17)) }
    }

    @Test
    fun keyframesGetDelimiterAndParameterSetsInAnnexB() {
        val config = AvcDecoderConfig.parse(FlvSamples.record())
        val units = AvcNal.splitAvcc(FlvSamples.avcc(FlvSamples.idr), config.nalLengthSize)
        val expected = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte()) + startCode + FlvSamples.sps + startCode +
            FlvSamples.pps + startCode + FlvSamples.idr
        assertContentEquals(expected, AvcNal.accessUnit(units, config))

        val inter = AvcNal.accessUnit(listOf(FlvSamples.slice), config)
        assertContentEquals(byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte()) + startCode + FlvSamples.slice, inter)

        // A keyframe that already carries its SPS/PPS is passed through unchanged.
        val own = AvcNal.accessUnit(listOf(FlvSamples.sps, FlvSamples.pps, FlvSamples.idr), config)
        assertEquals(6 + 3 * 4 + FlvSamples.sps.size + FlvSamples.pps.size + FlvSamples.idr.size, own.size)
    }

    @Test
    fun annexBInsideRtmpIsSplitOnStartCodes() {
        val annexB = startCode + FlvSamples.sps + byteArrayOf(0, 0, 1) + FlvSamples.idr
        val units = AvcNal.splitAvcc(annexB, 4)
        assertEquals(2, units.size)
        assertContentEquals(FlvSamples.sps, units[0])
        assertContentEquals(FlvSamples.idr, units[1])
        // A length prefix that happens to start with 00 00 01 stays length-prefixed.
        val longUnit = ByteArray(300) { if (it == 0) 0x41 else 7 }
        assertEquals(300, AvcNal.splitAvcc(FlvSamples.avcc(longUnit), 4).single().size)
        assertFailsWith<RtmpProtocolException> { AvcNal.splitAvcc(byteArrayOf(0, 0, 0, 9, 1), 4) }
    }

    @Test
    fun remuxerWaitsForAKeyframeAndKeepsTimeMonotonicAcrossReconnects() {
        val remuxer = RtmpToTsRemuxer()
        assertNull(remuxer.onVideo(0, FlvSamples.legacyFrame(true, 0, FlvSamples.idr)), "no configuration yet")
        assertNull(remuxer.onVideo(0, FlvSamples.legacySequenceHeader()))
        assertNull(remuxer.onVideo(5000, FlvSamples.legacyFrame(false, 0, FlvSamples.slice)), "inter frame before keyframe")
        val first = remuxer.onVideo(5066, FlvSamples.legacyFrame(true, 0, FlvSamples.idr))!!
        assertTrue(first.keyframe)
        assertEquals(0L, first.dtsMs)
        assertEquals(66L, remuxer.onVideo(5132, FlvSamples.legacyFrame(false, 0, FlvSamples.slice))!!.dtsMs)
        assertEquals(0, first.bytes.size % 188)

        remuxer.onPublishStarted()
        assertNull(remuxer.onVideo(0, FlvSamples.legacyFrame(false, 0, FlvSamples.slice)))
        val resumed = remuxer.onVideo(10, FlvSamples.legacyFrame(true, 0, FlvSamples.idr))!!
        assertEquals(166L, resumed.dtsMs)
        assertEquals(176L, remuxer.onVideo(20, FlvSamples.legacyFrame(false, 0, FlvSamples.slice))!!.dtsMs)

        remuxer.onVideo(30, ByteWriter().u8(0x90).bytes("hvc1".encodeToByteArray()).toByteArray())
        assertEquals("enhanced codec hvc1", remuxer.unsupportedCodec)
    }
}
