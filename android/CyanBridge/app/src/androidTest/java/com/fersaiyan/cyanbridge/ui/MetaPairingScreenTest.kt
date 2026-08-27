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
    fun initialPairingNoticeIsShownAndCanSendLogs() {
        var diagnosticsClicked = false
        composeRule.setContent {
            CyanBridgeTheme {
                MetaPairingScreen(
                    state = MetaPairingScreenState(),
                    onBack = {},
                    onOpenMetaAi = {},
                    onPrimaryAction = {},
                    onRetryPairing = {},
                    onSendDiagnostics = { diagnosticsClicked = true },
                )
            }
        }

        composeRule.onNodeWithText("Meta pairing reliability notice").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Some users are experiencing issues with reliable Meta Glasses pairing, if you encounter an issue and get stuckz please send the logs with an available email for the dev to better understand and fix the issue, since I'm having difficulties reproducing the error on my device",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Send logs").performClick()
        composeRule.runOnIdle { assertTrue(diagnosticsClicked) }
    }

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

        composeRule.onNodeWithText("Continue").performClick()
        composeRule.onNodeWithTag("meta_pairing_screen").assertIsDisplayed()
        composeRule.onNodeWithText("Ray-Ban Meta").assertIsDisplayed()
        composeRule.onNodeWithText("Test AI image question").performClick()
        composeRule.runOnIdle { assertTrue(clicked) }
    }

    @Test
    fun missingMetaAiShowsInstallDialogAfterNotice() {
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

        composeRule.onNodeWithText("Continue").performClick()
        composeRule.onNodeWithText("Meta AI is required").assertIsDisplayed()
        composeRule.onNodeWithText("Install Meta AI").performClick()
        composeRule.runOnIdle { assertTrue(installClicked) }
    }

    @Test
    fun registeredWithoutDatDeviceShowsActionableDialog() {
        var diagnosticsClicked = false
        composeRule.setContent {
            CyanBridgeTheme {
                MetaPairingScreen(
                    state = MetaPairingScreenState(
                        androidCameraGranted = true,
                        nearbyDevicesGranted = true,
                        initialized = true,
                        registrationState = MetaRaybanManager.RegistrationState.REGISTERED,
                        availableDeviceCount = 0,
                        guidance = "Registration is complete, but DAT has not exposed a device yet.",
                    ),
                    onBack = {},
                    onOpenMetaAi = {},
                    onPrimaryAction = {},
                    onRetryPairing = {},
                    onSendDiagnostics = { diagnosticsClicked = true },
                )
            }
        }

        composeRule.onNodeWithText("Continue").performClick()
        composeRule.onNodeWithText("Meta glasses are not ready").assertIsDisplayed()
        composeRule.onNodeWithText("Send logs").performClick()
        composeRule.runOnIdle { assertTrue(diagnosticsClicked) }
    }

    @Test
    fun unknownFailureShowsRetryDialogAfterNotice() {
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

        composeRule.onNodeWithText("Continue").performClick()
        composeRule.onNodeWithText("We could not finish Meta pairing").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        composeRule.runOnIdle { assertTrue(retryClicked) }
    }
}
