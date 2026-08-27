package com.fersaiyan.cyanbridge.ui

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsumerProtocolProbeOrderTest {
    @Test
    fun scanHintIsTriedFirstWithAllConsumerFallbacks() {
        assertEquals(
            listOf(DeviceClass.EYEVUE, DeviceClass.TUNEBUDS, DeviceClass.HEY_CYAN),
            consumerProtocolProbeOrder(DeviceClass.EYEVUE),
        )
        assertEquals(
            listOf(DeviceClass.TUNEBUDS, DeviceClass.EYEVUE, DeviceClass.HEY_CYAN),
            consumerProtocolProbeOrder(DeviceClass.TUNEBUDS),
        )
        assertEquals(
            listOf(DeviceClass.HEY_CYAN, DeviceClass.EYEVUE, DeviceClass.TUNEBUDS),
            consumerProtocolProbeOrder(DeviceClass.HEY_CYAN),
        )
    }
}
