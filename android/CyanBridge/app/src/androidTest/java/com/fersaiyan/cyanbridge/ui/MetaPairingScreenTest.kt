package com.fersaiyan.cyanbridge.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MetaPairingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun readySetupCanStartAiImageQuestion() {
        var clicked = false
        composeRule.setContent {
            CyanBridgeTheme {
                MetaPairingScreen(
                    state = MetaPairingScreenState(
                        androidCameraGranted = true,
                        nearbyDevicesGranted = true,
                        initialized = true,
                        registrationState = MetaRaybanManager.RegistrationState.REGISTERED,
                        availableDeviceCount = 1,
                        selectedDeviceName = "Ray-Ban Meta",
                        glassesCameraGranted = true,
                    ),
                    onBack = {},
                    onOpenMetaAi = {},
                    onPrimaryAction = { clicked = true },
                    onRetryPairing = {},
                    onSendDiagnostics = {},
                )
            }
        }

        composeRule.onNodeWithTag("meta_pairing_screen").assertIsDisplayed()
        composeRule.onNodeWithText("Ray-Ban Meta").assertIsDisplayed()
        composeRule.onNodeWithText("Test AI image question").performClick()
        composeRule.runOnIdle { assertTrue(clicked) }
    }

    @Test
    fun missingMetaAiShowsInstallDialog() {
        var installClicked = false
        composeRule.setContent {
            CyanBridgeTheme {
                MetaPairingScreen(
                    state = MetaPairingScreenState(metaAiInstalled = false),
                    onBack = {},
                    onOpenMetaAi = { installClicked = true },
                    onPrimaryAction = {},
                    onRetryPairing = {},
                    onSendDiagnostics = {},
                )
            }
        }

        composeRule.onNodeWithText("Meta AI is required").assertIsDisplayed()
        composeRule.onNodeWithText("Install Meta AI").performClick()
        composeRule.runOnIdle { assertTrue(installClicked) }
    }

    @Test
    fun unknownFailureShowsRetryDialog() {
        var retryClicked = false
        composeRule.setContent {
            CyanBridgeTheme {
                MetaPairingScreen(
                    state = MetaPairingScreenState(
                        lastError = "registration: opaque sdk failure 42",
                    ),
                    onBack = {},
                    onOpenMetaAi = {},
                    onPrimaryAction = {},
                    onRetryPairing = { retryClicked = true },
                    onSendDiagnostics = {},
                )
            }
        }

        composeRule.onNodeWithText("We could not finish Meta pairing").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        composeRule.runOnIdle { assertTrue(retryClicked) }
    }
}
