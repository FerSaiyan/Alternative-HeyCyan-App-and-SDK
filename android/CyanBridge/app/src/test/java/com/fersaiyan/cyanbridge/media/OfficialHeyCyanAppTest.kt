package com.fersaiyan.cyanbridge.media

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialHeyCyanAppTest {
    @Test
    fun `warns only for installed unsuppressed HeyCyan profile`() {
        assertTrue(
            OfficialHeyCyanApp.shouldWarn(
                selectedClass = DeviceClass.HEY_CYAN,
                installed = true,
                suppressed = false,
            ),
        )
        assertFalse(OfficialHeyCyanApp.shouldWarn(DeviceClass.HEY_CYAN, installed = false, suppressed = false))
        assertFalse(OfficialHeyCyanApp.shouldWarn(DeviceClass.HEY_CYAN, installed = true, suppressed = true))
        assertFalse(OfficialHeyCyanApp.shouldWarn(DeviceClass.EYEVUE, installed = true, suppressed = false))
        assertFalse(OfficialHeyCyanApp.shouldWarn(DeviceClass.TUNEBUDS, installed = true, suppressed = false))
        assertFalse(OfficialHeyCyanApp.shouldWarn(DeviceClass.MOYOUNG_W620, installed = true, suppressed = false))
    }
}
