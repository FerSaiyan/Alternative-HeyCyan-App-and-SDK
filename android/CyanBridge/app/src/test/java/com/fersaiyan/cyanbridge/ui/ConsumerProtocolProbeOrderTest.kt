package com.fersaiyan.cyanbridge.ui

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsumerProtocolProbeOrderTest {
    @Test
    fun scanHintIsTriedFirstWithAllConsumerFallbacks() {
        assertEquals(
            listOf(DeviceClass.EYEVUE, DeviceClass.TUNEBUDS, DeviceClass.MOYOUNG_W620, DeviceClass.HEY_CYAN),
            consumerProtocolProbeOrder(DeviceClass.EYEVUE),
        )
        assertEquals(
            listOf(DeviceClass.TUNEBUDS, DeviceClass.EYEVUE, DeviceClass.MOYOUNG_W620, DeviceClass.HEY_CYAN),
            consumerProtocolProbeOrder(DeviceClass.TUNEBUDS),
        )
        assertEquals(
            listOf(DeviceClass.HEY_CYAN, DeviceClass.EYEVUE, DeviceClass.TUNEBUDS, DeviceClass.MOYOUNG_W620),
            consumerProtocolProbeOrder(DeviceClass.HEY_CYAN),
        )
        assertEquals(
            listOf(DeviceClass.MOYOUNG_W620, DeviceClass.EYEVUE, DeviceClass.TUNEBUDS, DeviceClass.HEY_CYAN),
            consumerProtocolProbeOrder(DeviceClass.MOYOUNG_W620),
        )
    }
}
