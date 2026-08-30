package com.fersaiyan.cyanbridge.devices

import android.os.ParcelUuid
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceClassifierTest {

    @Test
    fun heyCyan_detectedByName() {
        assertEquals(DeviceClass.HEY_CYAN, DeviceClassifier.guessDeviceClass("HeyCyan-123"))
        assertEquals(DeviceClass.HEY_CYAN, DeviceClassifier.guessDeviceClass("cyan glasses"))
        assertEquals(DeviceClass.HEY_CYAN, DeviceClassifier.guessDeviceClass("O_ABC"))
        assertEquals(DeviceClass.HEY_CYAN, DeviceClassifier.guessDeviceClass("Q_001"))
    }

    @Test
    fun metaRayban_detectedByName() {
        assertEquals(DeviceClass.META_RAYBAN, DeviceClassifier.guessDeviceClass("Ray-Ban Meta"))
        assertEquals(DeviceClass.META_RAYBAN, DeviceClassifier.guessDeviceClass("rayban"))
    }

    @Test
    fun meizuMyvu_detectedByName() {
        assertEquals(DeviceClass.MEIZU_MYVU, DeviceClassifier.guessDeviceClass("MYVU Star Air"))
        assertEquals(DeviceClass.MEIZU_MYVU, DeviceClassifier.guessDeviceClass("myvu_xga010c"))
    }

    @Test
    fun meizuMyvu_detectedByAdvertisedOrGattServiceWithoutName() {
        listOf(
            "00000bd3-0000-1000-8000-00805f9b34fb",
            "00000bd1-0000-1000-8000-00805f9b34fb",
        ).forEach { uuid ->
            assertEquals(
                DeviceClass.MEIZU_MYVU,
                DeviceClassifier.guessDeviceClass(null, listOf(ParcelUuid.fromString(uuid))),
            )
        }
    }

    @Test
    fun eyevue_detectedByName() {
        assertEquals(DeviceClass.EYEVUE, DeviceClassifier.guessDeviceClass("Eyevue T"))
    }

    @Test
    fun tuneBuds_detectedByNameOrManufacturer() {
        assertEquals(DeviceClass.TUNEBUDS, DeviceClassifier.guessDeviceClass("xk one Pro"))
        assertEquals(DeviceClass.TUNEBUDS, DeviceClassifier.guessDeviceClass("TuneBuds Glasses"))
        assertEquals(
            DeviceClass.TUNEBUDS,
            DeviceClassifier.guessDeviceClass(null, manufacturerCompanyIds = setOf(0x475A)),
        )
    }

    @Test
    fun moyoungW620_detectedByServiceThenNameThenReportedMacPrefix() {
        assertEquals(
            DeviceClass.MOYOUNG_W620,
            DeviceClassifier.guessDeviceClass(
                null,
                listOf(ParcelUuid.fromString("0000fea8-0000-1000-8000-00805f9b34fb")),
                manufacturerCompanyIds = setOf(0x475A),
            ),
        )
        assertEquals(DeviceClass.MOYOUNG_W620, DeviceClassifier.guessDeviceClass("W620"))
        assertEquals(DeviceClass.MOYOUNG_W620, DeviceClassifier.guessDeviceClass("MoYoung Glasses"))
        assertEquals(
            DeviceClass.MOYOUNG_W620,
            DeviceClassifier.guessDeviceClass(null, macAddress = "DB:3E:33:4C:9D:A2"),
        )
    }

    @Test
    fun genericAudio_detectedByName() {
        assertEquals(DeviceClass.GENERIC_AUDIO, DeviceClassifier.guessDeviceClass("AirPods Pro"))
        assertEquals(DeviceClass.GENERIC_AUDIO, DeviceClassifier.guessDeviceClass("BT Headset"))
    }

    @Test
    fun unknownWhenEmpty() {
        assertEquals(DeviceClass.UNKNOWN, DeviceClassifier.guessDeviceClass(null))
        assertEquals(DeviceClass.UNKNOWN, DeviceClassifier.guessDeviceClass(""))
        assertEquals(DeviceClass.UNKNOWN, DeviceClassifier.guessDeviceClass("   "))
    }
}
