package com.fersaiyan.cyanbridge.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.runtime.mutableStateOf
import com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager
import com.fersaiyan.cyanbridge.devices.metarayban.MetaAccessState
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MetaPairingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun setupPopupsGuidePermissionsRegistrationAndSeparateCameraAccess() {
        val state = mutableStateOf(MetaPairingScreenState())
        var primaryCalls = 0
        composeRule.setContent {
            CyanBridgeTheme {
                MetaPairingScreen(state.value, {}, {}, { primaryCalls++ }, {}, {})
            }
        }
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.onNodeWithText("Allow Camera and Nearby devices").assertIsDisplayed()
        composeRule.onNodeWithTag("meta_setup_next_step").assertIsDisplayed()
        // The dialog and the underlying screen both have the action label.
        composeRule.onNodeWithText("Later").performClick()
        composeRule.onNodeWithTag("meta_pairing_screen").performScrollToNode(hasText("Grant required permissions"))
        composeRule.onNodeWithText("Grant required permissions").performClick()
        composeRule.runOnIdle {
            assertTrue(primaryCalls == 1)
            state.value = state.value.copy(androidCameraGranted = true, nearbyDevicesGranted = true, initialized = true,
                registrationState = MetaRaybanManager.RegistrationState.AVAILABLE)
        }
        composeRule.onNodeWithText("Authorize CyanBridge in Meta AI").assertIsDisplayed()
        composeRule.onNodeWithText("Later").performClick()
        composeRule.runOnIdle {
            state.value = state.value.copy(registrationState = MetaRaybanManager.RegistrationState.REGISTERED, availableDeviceCount = 1)
        }
        composeRule.onNodeWithText("Allow glasses camera access").assertIsDisplayed()
        composeRule.onNodeWithText("Later").performClick()
        composeRule.runOnIdle { state.value = state.value.copy(glassesCameraGranted = true) }
        composeRule.onNodeWithTag("meta_pairing_screen").performScrollToNode(hasText("Test AI image question"))
        composeRule.onNodeWithText("Test AI image question").performClick()
        composeRule.runOnIdle { assertTrue(primaryCalls == 2) }
    }

    @Test
    fun permanentlyDeniedAndroidPermissionShowsSettingsPopup() {
        composeRule.setContent {
            CyanBridgeTheme {
                MetaPairingScreen(MetaPairingScreenState(
                    androidPermissionPermanentlyDenied = true,
                    lastError = "Android permission denied: CAMERA",
                ), {}, {}, {}, {}, {})
            }
        }
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.onNodeWithText("Allow permissions in Android settings").assertIsDisplayed()
    }

    @Test
    fun logDisconnectedErrorOpensMetaAiInsteadOfRequestingAnotherPermission() {
        var opened = false
        composeRule.setContent {
            CyanBridgeTheme {
                MetaPairingScreen(
                    MetaPairingScreenState(lastError = "cameraPermission: All discovered devices are powered off or disconnected"),
                    {}, { opened = true }, {}, {}, {},
                )
            }
        }
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.onNodeWithText("Reconnect your Meta glasses").assertIsDisplayed()
        // Select the dialog button rather than the card's Open Meta AI button.
        composeRule.onNode(hasText("Open Meta AI") and hasAnyAncestor(isDialog())).performClick()
        composeRule.runOnIdle { assertTrue(opened) }
    }

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
            "Some users are experiencing issues with reliable Meta Glasses pairing. If you encounter an issue and get stuck, please send the logs with an available email for the developer to better understand and fix the issue, since I am having difficulties reproducing the error on my device.",
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
        composeRule.onNodeWithTag("meta_pairing_screen").assertExists()
        composeRule.onNodeWithTag("meta_pairing_screen").performScrollToNode(hasText("Ray-Ban Meta"))
        composeRule.onNodeWithText("Ray-Ban Meta").assertIsDisplayed()
        composeRule.onNodeWithTag("meta_pairing_screen").performScrollToNode(hasText("Test AI image question"))
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
        composeRule.onNodeWithText("No Meta glasses paired").assertIsDisplayed()
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

    @Test
    fun releaseChannelGateOpensAccessRequest() {
        var requestAccessClicked = false
        composeRule.setContent {
            CyanBridgeTheme {
                MetaPairingScreen(
                    state = MetaPairingScreenState(
                        androidCameraGranted = true,
                        nearbyDevicesGranted = true,
                        initialized = true,
                        metaAccessState = MetaAccessState.NEEDS_META_INVITE,
                    ),
                    onBack = {},
                    onOpenMetaAi = {},
                    onPrimaryAction = {},
                    onRetryPairing = {},
                    onSendDiagnostics = {},
                    onRequestAccess = { requestAccessClicked = true },
                )
            }
        }

        composeRule.onNodeWithText("Continue").performClick()
        composeRule.onNodeWithText("Meta access required").assertIsDisplayed()
        composeRule.onNodeWithText("Request access").performClick()
        composeRule.runOnIdle { assertTrue(requestAccessClicked) }
    }
}
