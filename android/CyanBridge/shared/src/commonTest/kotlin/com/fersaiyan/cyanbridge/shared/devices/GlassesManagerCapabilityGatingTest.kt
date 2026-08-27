package com.fersaiyan.cyanbridge.shared.devices

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlassesManagerCapabilityGatingTest {
    @Test
    fun tuneBudsAdvancedIncludesOnlyValidatedSharedSections() {
        val model = GlassesManagerGating.uiModel(
            DeviceProfile(
                macAddress = "FA:00:11:15:A1:7B",
                advertisedName = "xk one Pro",
                detectedClass = DeviceClass.TUNEBUDS,
                selectedClass = DeviceClass.TUNEBUDS,
                userOverridden = false,
            ),
        )

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
    }

    @Test
    fun heyCyanRetainsCompleteAdvancedPresentation() {
        val model = GlassesManagerGating.uiModel(
            DeviceProfile(
                macAddress = "AA:BB:CC:DD:EE:FF",
                advertisedName = "HeyCyan",
                detectedClass = DeviceClass.HEY_CYAN,
                selectedClass = DeviceClass.HEY_CYAN,
                userOverridden = false,
            ),
        )

        assertTrue(model.isVisible(GlassesManagerGating.Action.ADVANCED_CONTROLS))
        assertFalse(model.isVisible(GlassesManagerGating.Action.ADVANCED_LOCAL_AGENT))
        assertTrue(model.isVisible(GlassesManagerGating.Action.ADVANCED_DEVICE_INFO))
        assertTrue(model.isVisible(GlassesManagerGating.Action.ADVANCED_DEVICE_VOLUME))
        assertTrue(model.isVisible(GlassesManagerGating.Action.ADVANCED_IMAGE_QUALITY))
        assertTrue(model.isVisible(GlassesManagerGating.Action.ADVANCED_DEVELOPER_TOOLS))
        assertTrue(model.isVisible(GlassesManagerGating.Action.ADVANCED_OTA))
        assertTrue(model.isVisible(GlassesManagerGating.Action.CAPTURE_SETTINGS))
        assertTrue(model.isVisible(GlassesManagerGating.Action.AI_WAKE_WORD_ROUTING))
        assertTrue(model.isVisible(GlassesManagerGating.Action.WIFI_ADB_DEBUG))
    }
}
