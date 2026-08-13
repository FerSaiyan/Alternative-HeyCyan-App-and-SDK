package com.fersaiyan.cyanbridge.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.ui.DeviceBindScreen
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DeviceBindScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

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
}
