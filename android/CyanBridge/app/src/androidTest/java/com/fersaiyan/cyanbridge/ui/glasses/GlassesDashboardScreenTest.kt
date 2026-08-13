package com.fersaiyan.cyanbridge.ui.glasses

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.fersaiyan.cyanbridge.shared.glasses.FirmwarePatchRequestUiState
import com.fersaiyan.cyanbridge.shared.glasses.AiWakeWordRoute
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.shared.glasses.OtaFirmwareSource
import com.fersaiyan.cyanbridge.shared.glasses.MetaRaybanUiState
import com.fersaiyan.cyanbridge.shared.glasses.WifiAdbDebugUiState
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginShortcutAction
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginShortcutButton
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginShortcutUiState
import com.fersaiyan.cyanbridge.shared.ui.glasses.GlassesDashboardScreen
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GlassesDashboardScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersCapabilityGatedControlsAndDispatchesActions() {
        var action: GlassesDashboardAction? = null
        composeRule.setContent {
            CyanBridgeTheme {
                GlassesDashboardScreen(
                    state = GlassesDashboardUiState(
                        connectionLabel = "Connected - Cyan",
                        deviceClassLabel = "HeyCyan",
                        showHeyCyanControls = true,
                    ),
                    onAction = { action = it },
                )
            }
        }

        composeRule.onNodeWithTag("glasses_dashboard").assertIsDisplayed()
        composeRule.onNodeWithText("Connected - Cyan").assertIsDisplayed()
        composeRule.onNodeWithText("Test voice").performClick()

        composeRule.runOnIdle {
            assertEquals(GlassesDashboardAction.TestVoiceQuestion, action)
        }

        composeRule.onNodeWithText("Test image AI description").performClick()
        composeRule.runOnIdle {
            assertEquals(GlassesDashboardAction.TestImageQuestion, action)
        }
    }

    @Test
    fun heyCyanImageRouteRequiresConfirmation() {
        var action: GlassesDashboardAction? = null
        composeRule.setContent {
            CyanBridgeTheme {
                GlassesDashboardScreen(
                    state = GlassesDashboardUiState(
                        showHeyCyanControls = true,
                        showAiWakeWordRouting = true,
                        aiWakeWordRoute = AiWakeWordRoute.VOICE_QUESTION,
                    ),
                    onAction = { action = it },
                )
            }
        }

        composeRule.onNodeWithTag("ai_wake_word_route_image_question").performClick()
        composeRule.onNodeWithTag("ai_wake_word_image_warning").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(null, action) }

        composeRule.onNodeWithTag("ai_wake_word_image_warning_confirm").performClick()
        composeRule.runOnIdle {
            assertEquals(
                GlassesDashboardAction.SetAiWakeWordRoute(AiWakeWordRoute.IMAGE_QUESTION),
                action,
            )
        }
    }

    @Test
    fun eyevueImageRouteDoesNotShowHeyCyanWarning() {
        var action: GlassesDashboardAction? = null
        composeRule.setContent {
            CyanBridgeTheme {
                GlassesDashboardScreen(
                    state = GlassesDashboardUiState(
                        showEyevueControls = true,
                        showAiWakeWordRouting = true,
                        aiWakeWordRoute = AiWakeWordRoute.VOICE_QUESTION,
                    ),
                    onAction = { action = it },
                )
            }
        }

        composeRule.onNodeWithTag("ai_wake_word_route_image_question").performClick()
        composeRule.onAllNodesWithText("Use image questions for HeyCyan?").assertCountEquals(0)
        composeRule.runOnIdle {
            assertEquals(
                GlassesDashboardAction.SetAiWakeWordRoute(AiWakeWordRoute.IMAGE_QUESTION),
                action,
            )
        }
    }

    @Test
    fun keepsCoreGlassesActionsVisibleOutsideAdvancedControls() {
        composeRule.setContent {
            CyanBridgeTheme {
                GlassesDashboardScreen(
                    state = GlassesDashboardUiState(
                        showHeyCyanControls = true,
                        showAdvancedControls = true,
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("Photo").assertIsDisplayed()
        composeRule.onNodeWithText("Video").assertIsDisplayed()
        composeRule.onNodeWithText("Audio").assertIsDisplayed()
        composeRule.onNodeWithText("Count").assertIsDisplayed()
        composeRule.onNodeWithText("Sync data (P2P)").assertIsDisplayed()
        composeRule.onNodeWithText("Test voice").assertIsDisplayed()
        composeRule.onNodeWithText("Test image AI description").assertIsDisplayed()
        composeRule.onNodeWithText("Show advanced controls").assertIsDisplayed()
        composeRule.onAllNodesWithText("Meeting capture").assertCountEquals(0)
    }

    @Test
    fun tuneBudsAdvancedShowsOnlyValidatedSharedSections() {
        composeRule.setContent {
            CyanBridgeTheme {
                GlassesDashboardScreen(
                    state = GlassesDashboardUiState(
                        showTuneBudsControls = true,
                        showAdvancedControls = true,
                        showAdvancedLocalAgent = true,
                        showAdvancedDeviceInfo = true,
                        advancedExpanded = true,
                        deviceInfoLabel = "Model: E1749  Firmware: 0.1.0.6  Coprocessor: 1.0.1.1.4",
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag("advanced_controls_toggle").assertIsDisplayed()
        composeRule.onAllNodesWithTag("advanced_local_agent").assertCountEquals(1)
        composeRule.onAllNodesWithTag("advanced_device_info").assertCountEquals(1)
        composeRule.onAllNodesWithText("Model: E1749  Firmware: 0.1.0.6  Coprocessor: 1.0.1.1.4")
            .assertCountEquals(1)
        composeRule.onAllNodesWithTag("glasses_recording_settings").assertCountEquals(0)
        composeRule.onAllNodesWithTag("ai_wake_word_route_controls").assertCountEquals(0)
        composeRule.onAllNodesWithTag("advanced_image_quality").assertCountEquals(0)
        composeRule.onAllNodesWithTag("advanced_developer_tools").assertCountEquals(0)
        composeRule.onAllNodesWithTag("advanced_ota").assertCountEquals(0)
        composeRule.onAllNodesWithText("Volume").assertCountEquals(0)
    }

    @Test
    fun metaKeepsAssistantActionsButHidesHeyCyanMediaControls() {
        composeRule.setContent {
            CyanBridgeTheme {
                GlassesDashboardScreen(
                    state = GlassesDashboardUiState(
                        connectionLabel = "Meta Ray-Ban ready",
                        deviceClassLabel = "Meta Rayban",
                        showMetaRaybanControls = true,
                        metaRayban = MetaRaybanUiState(canCapturePhoto = true),
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag("glasses_assistant_controls").assertIsDisplayed()
        composeRule.onNodeWithText("Test voice").assertIsDisplayed()
        composeRule.onAllNodesWithText("Register").assertCountEquals(0)
        composeRule.onAllNodesWithText("Unregister").assertCountEquals(0)
        composeRule.onNodeWithTag("meta_rayban_registration_status").assertIsDisplayed()
        composeRule.onAllNodesWithText("Video").assertCountEquals(0)
        composeRule.onAllNodesWithText("Sync data (P2P)").assertCountEquals(0)
    }

    @Test
    fun metaErrorKeepsDiagnosticsActionVisible() {
        var action: GlassesDashboardAction? = null
        composeRule.setContent {
            CyanBridgeTheme {
                GlassesDashboardScreen(
                    state = GlassesDashboardUiState(
                        showMetaRaybanControls = true,
                        metaRayban = MetaRaybanUiState(lastError = "startSession: registration required"),
                    ),
                    onAction = { action = it },
                )
            }
        }

        composeRule.onNodeWithTag("meta_rayban_last_error").assertIsDisplayed()
        composeRule.onNodeWithText("Send Meta diagnostics").performClick()
        composeRule.runOnIdle { assertEquals(GlassesDashboardAction.MetaSendDiagnostics, action) }
    }

    @Test
    fun metaUnavailableShowsActionableSetupGuidance() {
        composeRule.setContent {
            CyanBridgeTheme {
                GlassesDashboardScreen(
                    state = GlassesDashboardUiState(
                        showMetaRaybanControls = true,
                        metaRayban = MetaRaybanUiState(
                            registrationLabel = "UNAVAILABLE",
                            setupGuidance = "Pair supported glasses in Meta AI first.",
                            canRegister = true,
                        ),
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag("meta_rayban_setup_guidance").assertIsDisplayed()
        composeRule.onNodeWithText("Pair supported glasses in Meta AI first.").assertIsDisplayed()
    }

    @Test
    fun rendersSelectedNativePluginShortcutsAndDispatchesAction() {
        var action: GlassesDashboardAction? = null
        composeRule.setContent {
            CyanBridgeTheme {
                GlassesDashboardScreen(
                    state = GlassesDashboardUiState(
                        nativePluginShortcut = NativePluginShortcutUiState(
                            id = NativePluginIds.MEETING_SPARK_NOTES,
                            title = "Meeting Spark Notes",
                            description = "Capture meeting speech.",
                            isEnabled = false,
                            buttons = listOf(
                                NativePluginShortcutButton(NativePluginShortcutAction.START, "Start capture"),
                                NativePluginShortcutButton(NativePluginShortcutAction.STOP, "Stop capture"),
                                NativePluginShortcutButton(NativePluginShortcutAction.SUMMARIZE, "Summarize"),
                            ),
                        ),
                    ),
                    onAction = { action = it },
                )
            }
        }

        composeRule.onNodeWithText("Meeting Spark Notes shortcuts").assertIsDisplayed()
        composeRule.onNodeWithText("Start capture").performClick()
        composeRule.runOnIdle {
            assertEquals(
                GlassesDashboardAction.RunNativePluginShortcut(NativePluginShortcutAction.START),
                action,
            )
        }
    }

    @Test
    fun privilegedWifiAdbRequiresExplicitRiskAcknowledgement() {
        var action: GlassesDashboardAction? = null
        composeRule.setContent {
            CyanBridgeTheme {
                GlassesDashboardScreen(
                    state = GlassesDashboardUiState(
                        wifiAdbDebug = WifiAdbDebugUiState(isAvailable = true),
                    ),
                    onAction = { action = it },
                )
            }
        }

        composeRule.onNodeWithText("Start ADB relay").performClick()
        composeRule.onNodeWithText("Privileged ADB risk").assertIsDisplayed()
        composeRule.onNodeWithTag("wifi_adb_confirm_start").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(null, action) }

        composeRule.onNodeWithTag("wifi_adb_risk_acknowledgement").performClick()
        composeRule.onNodeWithTag("wifi_adb_confirm_start").assertIsEnabled().performClick()

        composeRule.onAllNodesWithText("Privileged ADB risk").assertCountEquals(0)
        composeRule.runOnIdle {
            assertEquals(GlassesDashboardAction.RequestStartWifiAdbDebug, action)
        }
    }

    @Test
    fun otaSourcePickerRequiresCompatibilityAcknowledgement() {
        var action: GlassesDashboardAction? = null
        composeRule.setContent {
            CyanBridgeTheme {
                GlassesDashboardScreen(
                    state = GlassesDashboardUiState(
                        showHeyCyanControls = true,
                        showAdvancedControls = true,
                        showAdvancedOta = true,
                        advancedExpanded = true,
                    ),
                    onAction = { action = it },
                )
            }
        }

        composeRule.onNodeWithText("Choose combined OTA files").performClick()
        composeRule.onNodeWithTag("ota_firmware_source_picker").assertIsDisplayed()
        composeRule.onNodeWithTag("ota_firmware_source_personal_file").assertIsNotEnabled()

        composeRule.onNodeWithTag("ota_firmware_risk_acknowledgement").performClick()
        composeRule.onNodeWithTag("ota_firmware_source_personal_file").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals(
                GlassesDashboardAction.RequestOtaFirmware(OtaFirmwareSource.PERSONAL_FILE),
                action,
            )
        }
    }

    @Test
    fun unavailableFirmwareCanSendAnExactVersionPatchRequest() {
        var action: GlassesDashboardAction? = null
        composeRule.setContent {
            CyanBridgeTheme {
                GlassesDashboardScreen(
                    state = GlassesDashboardUiState(
                        showAdvancedOta = true,
                        firmwarePatchRequest = FirmwarePatchRequestUiState(
                            source = OtaFirmwareSource.STEALTH_CATALOG,
                            target = com.fersaiyan.cyanbridge.shared.glasses.OtaTargetSelection.V821_WIFI,
                            targetHardwareVersion = "WIFIAM01G1_V9.2",
                            targetFirmwareVersion = "WIFIAM01G1_1.00.23_2510111600",
                            wifiHardwareVersion = "WIFIAM01G1_V9.2",
                            wifiFirmwareVersion = "WIFIAM01G1_1.00.23_2510111600",
                            bleHardwareVersion = "AM01G1_V9.2",
                            bleFirmwareVersion = "AM01G1_9.20.03_260112",
                            relayMessage = "No approved exact-base patch is available.",
                        ),
                    ),
                    onAction = { action = it },
                )
            }
        }

        composeRule.onNodeWithTag("firmware_patch_request_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("firmware_patch_request_send").assertIsNotEnabled()
        composeRule.onNodeWithTag("firmware_patch_request_email")
            .performTextInput("owner@example.com")
        composeRule.onNodeWithTag("firmware_patch_request_send").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals(
                GlassesDashboardAction.SubmitFirmwarePatchRequest("owner@example.com"),
                action,
            )
        }
    }

    @Test
    fun unavailableFirmwarePatchRequestCanBeCancelled() {
        var action: GlassesDashboardAction? = null
        composeRule.setContent {
            CyanBridgeTheme {
                GlassesDashboardScreen(
                    state = GlassesDashboardUiState(
                        showAdvancedOta = true,
                        firmwarePatchRequest = FirmwarePatchRequestUiState(
                            source = OtaFirmwareSource.DEBUG_CATALOG,
                            target = com.fersaiyan.cyanbridge.shared.glasses.OtaTargetSelection.JIELI_BLE,
                            targetHardwareVersion = "AM01G1_V9.2",
                            targetFirmwareVersion = "AM01G1_9.20.03_260112",
                            wifiHardwareVersion = "WIFIAM01G1_V9.2",
                            wifiFirmwareVersion = "WIFIAM01G1_1.00.23_2510111600",
                            bleHardwareVersion = "AM01G1_V9.2",
                            bleFirmwareVersion = "AM01G1_9.20.03_260112",
                            relayMessage = "No approved exact-base patch is available.",
                        ),
                    ),
                    onAction = { action = it },
                )
            }
        }

        composeRule.onNodeWithTag("firmware_patch_request_cancel").performClick()
        composeRule.runOnIdle {
            assertEquals(GlassesDashboardAction.DismissFirmwarePatchRequest, action)
        }
    }
}
