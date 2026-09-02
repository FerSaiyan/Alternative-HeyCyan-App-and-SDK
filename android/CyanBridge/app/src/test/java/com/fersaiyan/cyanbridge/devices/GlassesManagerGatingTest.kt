package com.fersaiyan.cyanbridge.devices

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.DeviceProfile
import com.fersaiyan.cyanbridge.shared.devices.GlassesManagerGating
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesManagerGatingTest {

    @Test
    fun heyCyan_showsExtrasAndStatusPlaceholders() {
        val profile = DeviceProfile(
            macAddress = "AA:BB:CC:DD:EE:FF",
            advertisedName = "HeyCyan_123",
            detectedClass = DeviceClass.HEY_CYAN,
            selectedClass = DeviceClass.HEY_CYAN,
            userOverridden = false,
        )

        val model = GlassesManagerGating.uiModel(profile)
        assertTrue(model.isVisible(GlassesManagerGating.Action.MEETING_CAPTURE))
        assertTrue(model.isVisible(GlassesManagerGating.Action.HEY_CYAN_EXTRAS))
        assertTrue(model.isVisible(GlassesManagerGating.Action.STATUS_BATTERY))
        assertTrue(model.isVisible(GlassesManagerGating.Action.STATUS_STORAGE))
        assertTrue(model.isVisible(GlassesManagerGating.Action.CAPTURE_SETTINGS))
        assertTrue(model.isVisible(GlassesManagerGating.Action.AI_WAKE_WORD_ROUTING))
        assertTrue(model.isVisible(GlassesManagerGating.Action.ADVANCED_CONTROLS))
        assertFalse(model.isVisible(GlassesManagerGating.Action.ADVANCED_LOCAL_AGENT))
        assertTrue(model.isVisible(GlassesManagerGating.Action.ADVANCED_DEVICE_INFO))
        assertTrue(model.isVisible(GlassesManagerGating.Action.ADVANCED_DEVICE_VOLUME))
        assertTrue(model.isVisible(GlassesManagerGating.Action.ADVANCED_IMAGE_QUALITY))
        assertTrue(model.isVisible(GlassesManagerGating.Action.ADVANCED_DEVELOPER_TOOLS))
        assertTrue(model.isVisible(GlassesManagerGating.Action.ADVANCED_OTA))
        assertTrue(model.isVisible(GlassesManagerGating.Action.WIFI_ADB_DEBUG))
    }

    @Test
    fun metaRayban_hidesHeyCyanExtras() {
        val profile = DeviceProfile(
            macAddress = "00:11:22:33:44:55",
            advertisedName = "Ray-Ban Meta",
            detectedClass = DeviceClass.META_RAYBAN,
            selectedClass = DeviceClass.META_RAYBAN,
            userOverridden = false,
        )

        val model = GlassesManagerGating.uiModel(profile)
        assertTrue(model.isVisible(GlassesManagerGating.Action.MEETING_CAPTURE))
        assertTrue(model.isVisible(GlassesManagerGating.Action.META_RAYBAN_CONTROLS))
        assertTrue(model.isVisible(GlassesManagerGating.Action.META_RAYBAN_REGISTRATION))
        assertFalse(model.isVisible(GlassesManagerGating.Action.HEY_CYAN_EXTRAS))
        assertFalse(model.isVisible(GlassesManagerGating.Action.STATUS_BATTERY))
        assertFalse(model.isVisible(GlassesManagerGating.Action.STATUS_STORAGE))
    }

    @Test
    fun meizuMyvu_showsDisplayControlsAndBatteryOnly() {
        val profile = DeviceProfile(
            macAddress = "00:11:22:33:44:55",
            advertisedName = "MYVU Star Air",
            detectedClass = DeviceClass.MEIZU_MYVU,
            selectedClass = DeviceClass.MEIZU_MYVU,
            userOverridden = false,
        )

        val model = GlassesManagerGating.uiModel(profile)
        assertTrue(model.isVisible(GlassesManagerGating.Action.MEIZU_MYVU_CONTROLS))
        assertTrue(model.isVisible(GlassesManagerGating.Action.STATUS_BATTERY))
        assertFalse(model.isVisible(GlassesManagerGating.Action.STATUS_STORAGE))
        assertFalse(model.isVisible(GlassesManagerGating.Action.HEY_CYAN_EXTRAS))
    }

    @Test
    fun eyevue_showsEyevueControlsAndStatus() {
        val profile = DeviceProfile(
            macAddress = "00:11:22:33:44:55",
            advertisedName = "Eyevue",
            detectedClass = DeviceClass.EYEVUE,
            selectedClass = DeviceClass.EYEVUE,
            userOverridden = false,
        )

        val model = GlassesManagerGating.uiModel(profile)
        assertTrue(model.isVisible(GlassesManagerGating.Action.EYEVUE_CONTROLS))
        assertTrue(model.isVisible(GlassesManagerGating.Action.STATUS_BATTERY))
        assertTrue(model.isVisible(GlassesManagerGating.Action.STATUS_STORAGE))
        assertTrue(model.isVisible(GlassesManagerGating.Action.ADVANCED_CONTROLS))
        assertFalse(model.isVisible(GlassesManagerGating.Action.ADVANCED_LOCAL_AGENT))
        assertTrue(model.isVisible(GlassesManagerGating.Action.ADVANCED_DEVICE_INFO))
        assertTrue(model.isVisible(GlassesManagerGating.Action.ADVANCED_DEVICE_VOLUME))
        assertFalse(model.isVisible(GlassesManagerGating.Action.ADVANCED_IMAGE_QUALITY))
        assertTrue(model.isVisible(GlassesManagerGating.Action.ADVANCED_DEVELOPER_TOOLS))
        assertTrue(model.isVisible(GlassesManagerGating.Action.ADVANCED_OTA))
        assertTrue(model.isVisible(GlassesManagerGating.Action.CAPTURE_SETTINGS))
        assertTrue(model.isVisible(GlassesManagerGating.Action.AI_WAKE_WORD_ROUTING))
        assertFalse(model.isVisible(GlassesManagerGating.Action.WIFI_ADB_DEBUG))
        assertFalse(model.isVisible(GlassesManagerGating.Action.HEY_CYAN_EXTRAS))
    }

    @Test
    fun tuneBuds_showsCameraControlsAndStatusWithoutHeyCyanExtras() {
        val profile = DeviceProfile(
            macAddress = "FA:00:11:15:A1:7B",
            advertisedName = "xk one Pro",
            detectedClass = DeviceClass.TUNEBUDS,
            selectedClass = DeviceClass.TUNEBUDS,
            userOverridden = false,
        )

        val model = GlassesManagerGating.uiModel(profile)
        assertTrue(model.isVisible(GlassesManagerGating.Action.TUNEBUDS_CONTROLS))
        assertTrue(model.isVisible(GlassesManagerGating.Action.STATUS_BATTERY))
        assertTrue(model.isVisible(GlassesManagerGating.Action.STATUS_STORAGE))
        assertTrue(model.isVisible(GlassesManagerGating.Action.ADVANCED_CONTROLS))
        assertFalse(model.isVisible(GlassesManagerGating.Action.ADVANCED_LOCAL_AGENT))
        assertTrue(model.isVisible(GlassesManagerGating.Action.ADVANCED_DEVICE_INFO))
        assertFalse(model.isVisible(GlassesManagerGating.Action.ADVANCED_DEVICE_VOLUME))
        assertTrue(model.isVisible(GlassesManagerGating.Action.ADVANCED_IMAGE_QUALITY))
        assertFalse(model.isVisible(GlassesManagerGating.Action.ADVANCED_DEVELOPER_TOOLS))
        assertFalse(model.isVisible(GlassesManagerGating.Action.ADVANCED_OTA))
        assertFalse(model.isVisible(GlassesManagerGating.Action.CAPTURE_SETTINGS))
        assertFalse(model.isVisible(GlassesManagerGating.Action.AI_WAKE_WORD_ROUTING))
        assertFalse(model.isVisible(GlassesManagerGating.Action.WIFI_ADB_DEBUG))
        assertFalse(model.isVisible(GlassesManagerGating.Action.HEY_CYAN_EXTRAS))
        assertFalse(model.isVisible(GlassesManagerGating.Action.EYEVUE_CONTROLS))
        assertFalse(model.isVisible(GlassesManagerGating.Action.MEIZU_MYVU_CONTROLS))
    }

    @Test
    fun moyoungW620_showsOnlyValidatedStatusCapabilities() {
        val model = GlassesManagerGating.uiModel(
            DeviceProfile(
                macAddress = "DB:3E:33:4C:9D:A2",
                advertisedName = "W620",
                detectedClass = DeviceClass.MOYOUNG_W620,
                selectedClass = DeviceClass.MOYOUNG_W620,
                userOverridden = false,
            ),
        )

        assertTrue(model.isVisible(GlassesManagerGating.Action.STATUS_BATTERY))
        assertTrue(model.isVisible(GlassesManagerGating.Action.STATUS_STORAGE))
        assertFalse(model.isVisible(GlassesManagerGating.Action.HEY_CYAN_EXTRAS))
        assertFalse(model.isVisible(GlassesManagerGating.Action.EYEVUE_CONTROLS))
        assertFalse(model.isVisible(GlassesManagerGating.Action.TUNEBUDS_CONTROLS))
        assertFalse(model.isVisible(GlassesManagerGating.Action.ADVANCED_CONTROLS))
    }

    @Test
    fun genericAudio_hidesHeyCyanExtras() {
        val profile = DeviceProfile(
            macAddress = "10:20:30:40:50:60",
            advertisedName = "BT Headset",
            detectedClass = DeviceClass.GENERIC_AUDIO,
            selectedClass = DeviceClass.GENERIC_AUDIO,
            userOverridden = true,
        )

        val model = GlassesManagerGating.uiModel(profile)
        assertTrue(model.isVisible(GlassesManagerGating.Action.MEETING_CAPTURE))
        assertFalse(model.isVisible(GlassesManagerGating.Action.HEY_CYAN_EXTRAS))
    }

    @Test
    fun nullProfile_defaultsToMeetingOnly() {
        val model = GlassesManagerGating.uiModel(null)
        assertTrue(model.isVisible(GlassesManagerGating.Action.MEETING_CAPTURE))
        assertFalse(model.isVisible(GlassesManagerGating.Action.HEY_CYAN_EXTRAS))
    }
}
