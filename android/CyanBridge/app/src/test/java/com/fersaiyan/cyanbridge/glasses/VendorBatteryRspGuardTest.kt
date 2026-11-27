package com.fersaiyan.cyanbridge.glasses

import com.oudmon.ble.base.communication.rsp.BatteryRsp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The bundled vendor SDK's rsp-channel battery parser (patched in glasses_sdk_20250723_v01.aar).
 *
 * The original BatteryRsp.acceptData read data[0] and data[1] with no length check, and the vendor's
 * QCDataParser calls it outside its try/catch, so a short battery reply was an uncaught
 * ArrayIndexOutOfBoundsException (the same rsp-form crash class as the one fixed for cmd-65).
 */
@RunWith(RobolectricTestRunner::class)
class VendorBatteryRspGuardTest {

    @Test
    fun `full reply parses battery and charging`() {
        val rsp = BatteryRsp()
        rsp.acceptData(byteArrayOf(71, 1))
        assertEquals(71, rsp.batteryValue)
        assertTrue(rsp.isCharging)
    }

    @Test
    fun `battery byte only keeps the battery and reports not charging`() {
        val rsp = BatteryRsp()
        rsp.acceptData(byteArrayOf(42))
        assertEquals(42, rsp.batteryValue)
        assertFalse(rsp.isCharging)
    }

    @Test
    fun `empty and null replies do not throw`() {
        val empty = BatteryRsp()
        empty.acceptData(ByteArray(0))
        assertEquals(0, empty.batteryValue)
        BatteryRsp().acceptData(null)
    }
}
