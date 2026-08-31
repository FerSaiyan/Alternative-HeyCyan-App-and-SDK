package com.fersaiyan.cyanbridge.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.ScannedDevice
import com.fersaiyan.cyanbridge.shared.ui.DeviceBindScreen
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DeviceBindScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun scanRowHidesMacAddress() {
        val mac = "AA:BB:CC:DD:EE:FF"
        composeRule.setContent {
            CyanBridgeTheme {
                DeviceBindScreen(
                    devices = listOf(
                        ScannedDevice(
                            macAddress = mac,
                            advertisedName = "Smart Glasses",
                            rssi = -54,
                            detectedClass = DeviceClass.HEY_CYAN,
                            selectedClass = null,
                            userOverridden = false,
                        ),
                    ),
                    isScanning = false,
                    connectingDevice = null,
                    selectedClass = DeviceClass.HEY_CYAN,
                    onScan = {},
                    onPairMetaGlasses = {},
                    onSelectDevice = {},
                    onSelectedClassChange = {},
                    onConfirmConnection = {},
                    onDismissConnection = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Smart Glasses").assertExists()
        composeRule.onNodeWithText("Signal: -54 dBm").assertExists()
        composeRule.onAllNodesWithText(mac).assertCountEquals(0)
        composeRule.onNodeWithText("Pair Meta Glasses").assertExists()
    }

    @Test
    fun pairMetaButtonLaunchesDedicatedFlow() {
        var clicked = false
        composeRule.setContent {
            CyanBridgeTheme {
                DeviceBindScreen(
                    devices = emptyList(),
                    isScanning = false,
                    connectingDevice = null,
                    selectedClass = DeviceClass.HEY_CYAN,
                    onScan = {},
                    onPairMetaGlasses = { clicked = true },
                    onSelectDevice = {},
                    onSelectedClassChange = {},
                    onConfirmConnection = {},
                    onDismissConnection = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Pair Meta Glasses").performClick()
        composeRule.runOnIdle { assertTrue(clicked) }
    }

    @Test
    fun typePickerUsesUnifiedConsumerGlassesChoiceAndKeepsMeta() {
        var selected: DeviceClass? = null
        var confirmed = false
        val device = ScannedDevice(
            macAddress = "AA:BB:CC:DD:EE:FF",
            advertisedName = "Smart Glasses",
            rssi = -50,
            detectedClass = DeviceClass.UNKNOWN,
            selectedClass = null,
            userOverridden = false,
        )
        composeRule.setContent {
            CyanBridgeTheme {
                DeviceBindScreen(
                    devices = listOf(device),
                    isScanning = false,
                    connectingDevice = device,
                    selectedClass = DeviceClass.HEY_CYAN,
                    onScan = {},
                    onPairMetaGlasses = {},
                    onSelectDevice = {},
                    onSelectedClassChange = { selected = it },
                    onConfirmConnection = { confirmed = true },
                    onDismissConnection = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("HeyCyan / EyeVue / TuneBuds / MoYoung").assertExists()
        composeRule.onNodeWithText("Choose the protocol manually on the next screen").assertExists()
        composeRule.onNodeWithText("Meta Ray-Ban").performClick()
        composeRule.runOnIdle { assertEquals(DeviceClass.META_RAYBAN, selected) }

        composeRule.onNodeWithText("Connect").performClick()
        composeRule.runOnIdle { assertTrue(confirmed) }
    }
}
