package com.fersaiyan.cyanbridge.shared.devices

import com.fersaiyan.cyanbridge.shared.devices.GlassesManagerGating.Action
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the dashboard sections each device class shows. Any change here changes what users see
 * on the glasses dashboard and must be tested on that hardware.
 */
class GlassesManagerGatingMatrixTest {
    private val expected: Map<DeviceClass, Set<Action>> = mapOf(
        DeviceClass.HEY_CYAN to setOf(
            Action.MEETING_CAPTURE,
            Action.HEY_CYAN_EXTRAS,
            Action.STATUS_BATTERY,
            Action.STATUS_STORAGE,
            Action.ADVANCED_CONTROLS,
            Action.ADVANCED_DEVICE_INFO,
            Action.ADVANCED_DEVICE_VOLUME,
            Action.ADVANCED_IMAGE_QUALITY,
            Action.ADVANCED_DEVELOPER_TOOLS,
            Action.ADVANCED_OTA,
            Action.CAPTURE_SETTINGS,
            Action.AI_WAKE_WORD_ROUTING,
            Action.WIFI_ADB_DEBUG,
        ),
        DeviceClass.EYEVUE to setOf(
            Action.MEETING_CAPTURE,
            Action.EYEVUE_CONTROLS,
            Action.STATUS_BATTERY,
            Action.STATUS_STORAGE,
            Action.ADVANCED_CONTROLS,
            Action.ADVANCED_DEVICE_INFO,
            Action.ADVANCED_DEVICE_VOLUME,
            Action.ADVANCED_DEVELOPER_TOOLS,
            Action.ADVANCED_OTA,
            Action.CAPTURE_SETTINGS,
            Action.AI_WAKE_WORD_ROUTING,
        ),
        DeviceClass.TUNEBUDS to setOf(
            Action.MEETING_CAPTURE,
            Action.TUNEBUDS_CONTROLS,
            Action.STATUS_BATTERY,
            Action.STATUS_STORAGE,
            Action.ADVANCED_CONTROLS,
            Action.ADVANCED_DEVICE_INFO,
            Action.ADVANCED_IMAGE_QUALITY,
        ),
        DeviceClass.MOYOUNG_W620 to setOf(
            Action.MEETING_CAPTURE,
            Action.STATUS_BATTERY,
            Action.STATUS_STORAGE,
        ),
        DeviceClass.META_RAYBAN to setOf(
            Action.MEETING_CAPTURE,
            Action.META_RAYBAN_CONTROLS,
            Action.META_RAYBAN_REGISTRATION,
        ),
        DeviceClass.MENTRA_LIVE to setOf(
            Action.MEETING_CAPTURE,
            Action.STATUS_BATTERY,
            Action.ADVANCED_CONTROLS,
            Action.ADVANCED_DEVICE_INFO,
            Action.ADVANCED_DEVICE_VOLUME,
            Action.ADVANCED_SDK_FIRMWARE_UPDATE,
        ),
        DeviceClass.MEIZU_MYVU to setOf(
            Action.MEETING_CAPTURE,
            Action.MEIZU_MYVU_CONTROLS,
            Action.STATUS_BATTERY,
        ),
        DeviceClass.MEMOMIND to setOf(
            Action.MEETING_CAPTURE,
            Action.MEMOMIND_CONTROLS,
            Action.STATUS_BATTERY,
        ),
        DeviceClass.EVEN_G1 to setOf(Action.MEETING_CAPTURE, Action.STATUS_BATTERY),
        DeviceClass.EVEN_G2 to setOf(Action.MEETING_CAPTURE, Action.STATUS_BATTERY),
        DeviceClass.MENTRA_DISPLAY to setOf(Action.MEETING_CAPTURE, Action.STATUS_BATTERY),
        DeviceClass.NIMO to setOf(Action.MEETING_CAPTURE, Action.STATUS_BATTERY),
        DeviceClass.XINGYI_AR99 to setOf(Action.MEETING_CAPTURE, Action.STATUS_BATTERY),
        DeviceClass.GENERIC_AUDIO to setOf(Action.MEETING_CAPTURE),
        DeviceClass.UNKNOWN to setOf(Action.MEETING_CAPTURE),
    )

    @Test
    fun everyDeviceClassIsPinned() {
        assertEquals(DeviceClass.entries.toSet(), expected.keys)
    }

    @Test
    fun visibleActionsMatchTheShippedDashboardForEachDeviceClass() {
        DeviceClass.entries.forEach { deviceClass ->
            assertEquals(
                expected.getValue(deviceClass),
                GlassesManagerGating.visibleActions(deviceClass),
                "visible dashboard actions for $deviceClass",
            )
        }
    }

    @Test
    fun missingProfileFallsBackToUnknown() {
        assertEquals(expected.getValue(DeviceClass.UNKNOWN), GlassesManagerGating.visibleActions(null as DeviceProfile?))
    }
}
