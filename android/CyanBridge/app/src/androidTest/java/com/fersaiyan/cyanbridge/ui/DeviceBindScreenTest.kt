package com.fersaiyan.cyanbridge.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
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
        // The screen shows what the caller passes back, as DeviceBindViewModel does; a constant here kept
        // HeyCyan selected, so Connect opened the manual protocol picker instead of confirming Meta.
        var selectedState by mutableStateOf(DeviceClass.HEY_CYAN)
        composeRule.setContent {
            CyanBridgeTheme {
                DeviceBindScreen(
                    devices = listOf(device),
                    isScanning = false,
                    connectingDevice = device,
                    selectedClass = selectedState,
                    onScan = {},
                    onPairMetaGlasses = {},
                    onSelectDevice = {},
                    onSelectedClassChange = { selected = it; selectedState = it },
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

        // The device row has its own "Connect"; press the confirmation dialog's button.
        composeRule.onNode(hasText("Connect") and hasAnyAncestor(isDialog())).performClick()
        composeRule.runOnIdle { assertTrue(confirmed) }
    }
}
