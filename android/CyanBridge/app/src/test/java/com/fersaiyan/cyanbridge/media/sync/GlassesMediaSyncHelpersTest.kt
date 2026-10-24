package com.fersaiyan.cyanbridge.media.sync

import com.fersaiyan.cyanbridge.shared.network.GlassesNetworkAddressing
import com.fersaiyan.cyanbridge.shared.network.HeyCyanP2pPeerMatching
import java.io.ByteArrayInputStream
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class GlassesMediaSyncHelpersTest {
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun pinTimeZone() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun `parses capture time from glasses file names`() {
        // 2026-01-27T09:51:59Z = 1769507519000
        assertEquals(1769507519018L, GlassesMediaLibrary.parseTakenTimeMillisFromFilename("20260127095159018.jpg"))
        assertEquals(1769507519000L, GlassesMediaLibrary.parseTakenTimeMillisFromFilename("20260127095159.mp4"))
        assertEquals(1769507519001L, GlassesMediaLibrary.parseTakenTimeMillisFromFilename("202601270951590012345.opus"))
        assertEquals(1769507519000L, GlassesMediaLibrary.parseTakenTimeMillisFromFilename("20260127095159_x.jpg"))
        assertNull(GlassesMediaLibrary.parseTakenTimeMillisFromFilename("2026012709515.jpg"))
        assertNull(GlassesMediaLibrary.parseTakenTimeMillisFromFilename("IMG_20260127095159.jpg"))
    }

    @Test
    fun `reads whole streams and hex-dumps payload heads`() {
        val bytes = ByteArray(70_000) { (it % 251).toByte() }
        assertArrayEquals(bytes, GlassesMediaLibrary.readAllBytes(ByteArrayInputStream(bytes)))
        assertEquals("", GlassesMediaLibrary.bytesToHex(ByteArray(0), 24))
        assertEquals("00ff7f80", GlassesMediaLibrary.bytesToHex(byteArrayOf(0, -1, 127, -128), 4))
        assertEquals("00ff...", GlassesMediaLibrary.bytesToHex(byteArrayOf(0, -1, 127, -128), 2))
    }

    @Test
    fun `formats transfer sizes like the progress card`() {
        assertEquals("0 B", TransferProgressPresenter.formatTransferBytes(0L))
        assertEquals("0 B", TransferProgressPresenter.formatTransferBytes(-5L))
        assertEquals("1023 B", TransferProgressPresenter.formatTransferBytes(1023L))
        assertEquals("1.0 KB", TransferProgressPresenter.formatTransferBytes(1024L))
        assertEquals("99.9 KB", TransferProgressPresenter.formatTransferBytes(102_297L))
        assertEquals("100 KB", TransferProgressPresenter.formatTransferBytes(102_400L))
        assertEquals("1.5 MB", TransferProgressPresenter.formatTransferBytes(1_572_864L))
        assertEquals("2048 GB", TransferProgressPresenter.formatTransferBytes(2_199_023_255_552L))
    }

    @Test
    fun `derives the ipv4 slash 24 prefix`() {
        assertEquals("192.168.49.", GlassesNetworkAddressing.prefix24("192.168.49.79"))
        assertNull(GlassesNetworkAddressing.prefix24(null))
        assertNull(GlassesNetworkAddressing.prefix24(" "))
        assertNull(GlassesNetworkAddressing.prefix24("fe80::1"))
        assertNull(GlassesNetworkAddressing.prefix24("10.0.0"))
    }

    @Test
    fun `scores likely glasses peer names`() {
        val score = HeyCyanP2pPeerMatching::likelyGlassesPeerNameStrength
        assertEquals(-1, score(null, "C4E3BFC3A402"))
        assertEquals(-1, score("", null))
        assertEquals(100, score("m01_c4e3bfc3a402", "C4E3BFC3A402"))
        assertEquals(80, score("HeyCyan", null))
        assertEquals(80, score("o_glasses", null))
        assertEquals(80, score("Q_1234", null))
        assertEquals(70, score("AIMB-12", null))
        assertEquals(70, score("My Smart Glass", null))
        assertEquals(70, score("Ray SG2", null))
        assertEquals(30, score("DIRECT-AB C4E3BFC3A402", null))
        assertEquals(-1, score("[TV] Living room", null))
    }
}
