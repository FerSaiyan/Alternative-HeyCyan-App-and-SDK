package com.fersaiyan.cyanbridge.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the vendor cmd-65 (GlassModelControl) BLE notify parse against short/truncated frames.
 *
 * The 8-byte frame below is the field-crash vector pinned by add.217: routed through the real
 * dispatch (data[0]=0xBC magic, data[1]=65 command id) the vendor
 * GlassModelControlResponse.acceptData reads data[7] as dataType=6 and then
 * Arrays.copyOfRange(data, 9, 11) on an 8-byte array -> arraycopy length=-1 / AIOOBE.
 */
class GlassModelControlFrameGuardTest {

    private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    // The exact 8-byte truncated cmd-65 frame that reproduces the field crash (dataType 6).
    private val shortFrameDataType6 = b(0xBC, 65, 0x00, 0x00, 0x00, 0x00, 0x00, 0x06)

    // 8-byte truncated cmd-65 p2pIp frame (dataType 3) -> acceptData reads data[10..12].
    private val shortFrameDataType3 = b(0xBC, 65, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03)

    @Test
    fun `exact 8-byte crash frame is rejected and does not throw`() {
        // Must not throw for the frame that crashed the vendor parser in the field.
        assertFalse(GlassModelControlFrameGuard.isCompleteStatusFrame(shortFrameDataType6))
        assertTrue(GlassModelControlFrameGuard.shouldDropIncompleteFrame(shortFrameDataType6))
    }

    @Test
    fun `exact 8-byte p2pIp crash frame is rejected`() {
        assertFalse(GlassModelControlFrameGuard.isCompleteStatusFrame(shortFrameDataType3))
        assertTrue(GlassModelControlFrameGuard.shouldDropIncompleteFrame(shortFrameDataType3))
    }

    @Test
    fun `well-formed 14-byte cmd-65 frame is accepted`() {
        // dataType 4 needs 14 bytes (image/video/record counts). Full-length frame passes.
        val full = b(0xBC, 65, 0x00, 0x00, 0x00, 0x00, 0x00, 0x04, 1, 0, 2, 0, 3, 0)
        assertTrue(GlassModelControlFrameGuard.isCompleteStatusFrame(full))
        assertFalse(GlassModelControlFrameGuard.shouldDropIncompleteFrame(full))
    }

    @Test
    fun `minimum length table matches the vendor offset reads`() {
        assertEquals(10, GlassModelControlFrameGuard.minLengthForDataType(1))
        assertEquals(11, GlassModelControlFrameGuard.minLengthForDataType(2))
        assertEquals(13, GlassModelControlFrameGuard.minLengthForDataType(3))
        assertEquals(14, GlassModelControlFrameGuard.minLengthForDataType(4))
        assertEquals(11, GlassModelControlFrameGuard.minLengthForDataType(6))
    }

    @Test
    fun `each dataType rejects one-byte-short and accepts exact minimum`() {
        for (dataType in listOf(1, 2, 3, 4, 6)) {
            val min = GlassModelControlFrameGuard.minLengthForDataType(dataType)
            val short = ByteArray(min - 1).also { it[0] = 0xBC.toByte(); it[1] = 65; it[7] = dataType.toByte() }
            val exact = ByteArray(min).also { it[0] = 0xBC.toByte(); it[1] = 65; it[7] = dataType.toByte() }
            assertFalse("dataType $dataType len ${min - 1} should be incomplete", GlassModelControlFrameGuard.isCompleteStatusFrame(short))
            assertTrue("dataType $dataType len $min should be complete", GlassModelControlFrameGuard.isCompleteStatusFrame(exact))
        }
    }

    @Test
    fun `degenerate frames never throw and are treated as incomplete`() {
        for (frame in listOf(ByteArray(0), b(0xBC), b(0xBC, 65), b(0xBC, 65, 0, 0, 0, 0, 0))) {
            assertFalse(GlassModelControlFrameGuard.isCompleteStatusFrame(frame))
        }
    }

    @Test
    fun `non cmd-65 frames are left untouched`() {
        // Wrong magic or wrong command id: not this guard's concern, never dropped.
        val notBigData = b(0x01, 65, 0, 0, 0, 0, 0, 0x06)
        val otherCommand = b(0xBC, 0x10, 0, 0, 0, 0, 0, 0x06)
        assertFalse(GlassModelControlFrameGuard.shouldDropIncompleteFrame(notBigData))
        assertFalse(GlassModelControlFrameGuard.shouldDropIncompleteFrame(otherCommand))
    }
}
