package com.fersaiyan.cyanbridge.shared.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GattServiceClassificationTest {
    private val deviceInformation = "0000180a-0000-1000-8000-00805f9b34fb"
    private val jieliRcsp = "0000ae00-0000-1000-8000-00805f9b34fb"

    @Test
    fun heyCyanServiceWins() {
        assertEquals(
            DeviceClass.HEY_CYAN,
            BleDeviceClassifier.classifyByGattServices(
                listOf(deviceInformation, jieliRcsp, "DE5BF728-D711-4E47-AF26-65E3012A5DC7"),
            ),
        )
    }

    @Test
    fun eyevueByItsServiceNotByTheSharedJieliOne() {
        assertEquals(DeviceClass.EYEVUE, BleDeviceClassifier.classifyByGattServices(listOf(jieliRcsp, "aa12")))
        assertNull(BleDeviceClassifier.classifyByGattServices(listOf(jieliRcsp, deviceInformation)))
    }

    @Test
    fun otherFamiliesByTheirServices() {
        assertEquals(DeviceClass.TUNEBUDS, BleDeviceClassifier.classifyByGattServices(listOf("fdb3")))
        assertEquals(DeviceClass.MEIZU_MYVU, BleDeviceClassifier.classifyByGattServices(listOf("00000bd1")))
        assertEquals(DeviceClass.MOYOUNG_W620, BleDeviceClassifier.classifyByGattServices(listOf("0000fea8-0000-1000-8000-00805f9b34fb")))
        assertEquals(
            DeviceClass.MEMOMIND,
            BleDeviceClassifier.classifyByGattServices(listOf("fb349b5f-8000-0080-0010-002020000000")),
        )
    }

    @Test
    fun unknownGlassesHaveNoFamily() {
        assertNull(BleDeviceClassifier.classifyByGattServices(listOf(deviceInformation, "6e40fff0-b5a3-f393-e0a9-e50e24dcca9e")))
        assertNull(BleDeviceClassifier.classifyByGattServices(emptyList()))
    }
}
