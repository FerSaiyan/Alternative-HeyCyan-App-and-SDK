package com.fersaiyan.cyanbridge.shared.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlassesWifiNotifyTest {
    private fun frame(type: Int, vararg payload: Int): ByteArray =
        ByteArray(6) + byteArrayOf(type.toByte()) + ByteArray(payload.size) { payload[it].toByte() }

    @Test
    fun parsesTheGlassesIpAsUnsignedOctets() {
        val notify = GlassesWifiNotify.parse(frame(0x08, 192, 168, 49, 226))
        assertEquals("192.168.49.226", (notify as GlassesWifiNotify.GlassesIp).ip)
        assertEquals(GlassesWifiNotify.GlassesIpTooShort(10), GlassesWifiNotify.parse(frame(0x08, 192, 168, 49)))
    }

    @Test
    fun classifiesWifiErrorsAndKeepsTheRawByte() {
        val noisy = GlassesWifiNotify.parse(frame(0x09, 255)) as GlassesWifiNotify.WifiError
        assertEquals(255, noisy.code)
        assertEquals((-1).toByte(), noisy.raw)
        assertTrue(noisy.isNoisyVendorError)

        val missing = GlassesWifiNotify.parse(frame(0x09)) as GlassesWifiNotify.WifiError
        assertEquals(0, missing.code)
        assertNull(missing.raw)
        assertFalse(missing.isNoisyVendorError)
    }

    @Test
    fun parsesOtaProgressAndResult() {
        assertEquals(GlassesWifiNotify.OtaProgress(42, 2, 10), GlassesWifiNotify.parse(frame(0x04, 42, 2, 0)))
        assertTrue((GlassesWifiNotify.parse(frame(0x07, 1)) as GlassesWifiNotify.OtaResult).succeeded)
        assertFalse((GlassesWifiNotify.parse(frame(0x07, 0)) as GlassesWifiNotify.OtaResult).succeeded)
        assertEquals(GlassesWifiNotify.OtaResult(null), GlassesWifiNotify.parse(frame(0x07)))
    }

    @Test
    fun ignoresShortFramesAndKeepsUnknownTypesUnsigned() {
        assertNull(GlassesWifiNotify.parse(ByteArray(6)))
        assertEquals(GlassesWifiNotify.Other(0xF0, 7), GlassesWifiNotify.parse(frame(0xF0)))
    }
}
