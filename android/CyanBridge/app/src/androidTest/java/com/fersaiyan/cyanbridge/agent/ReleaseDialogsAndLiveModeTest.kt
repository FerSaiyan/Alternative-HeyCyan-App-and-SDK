package com.fersaiyan.cyanbridge.agent

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import com.fersaiyan.cyanbridge.shared.billing.ProSubscriptionSettingsUiState
import com.fersaiyan.cyanbridge.shared.ui.pro.ProSubscriptionSettingsScreen
import com.fersaiyan.cyanbridge.ui.OfficialHeyCyanWarningDialog
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReleaseDialogsAndLiveModeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun officialAppWarningOffersSafeAndExplicitSyncActions() {
        var openedAppInfo = false
        var syncAnyway = false
        var warningSuppressed = false
        composeRule.setContent {
            CyanBridgeTheme {
                OfficialHeyCyanWarningDialog(
                    onDismissRequest = {},
                    onOpenAppInfo = { openedAppInfo = true },
                    onSyncAnyway = { suppress ->
                        syncAnyway = true
                        warningSuppressed = suppress
                    },
                )
            }
        }

        composeRule.onNodeWithText("HeyCyan may interrupt sync").assertExists()
        composeRule.onNodeWithText("Open App info").performClick()
        composeRule.runOnIdle { assertTrue(openedAppInfo) }

        composeRule.onNodeWithText("Don't remind me again").performClick()
        composeRule.onNodeWithText("Sync anyway").performClick()
        composeRule.runOnIdle {
            assertTrue(syncAnyway)
            assertTrue(warningSuppressed)
        }
    }

    @Test
    fun emailVerificationAcceptsAndSubmitsSixDigits() {
        var submittedCode = ""
        composeRule.setContent {
            CyanBridgeTheme {
                EmailVerificationCodeDialog(
                    email = "subscriber@example.com",
                    initialMessage = "Check your inbox.",
                    verificationLinkAvailable = true,
                    verifying = false,
                    errorMessage = null,
                    onCodeChanged = {},
                    onVerify = { submittedCode = it },
                    onResend = {},
                    onOpenLink = {},
                    onDismissRequest = {},
                )
            }
        }

        composeRule.onNodeWithText("Verify your email").assertExists()
        composeRule.onNodeWithText("subscriber@example.com").assertExists()
        composeRule.onNodeWithTag("email_verification_code").performTextInput("123456")
        composeRule.onNodeWithText("Verify").performClick()
        composeRule.runOnIdle { assertEquals("123456", submittedCode) }
    }

    @Test
    fun proSettingsOffersEachGeminiLiveModeAsAQuestionsModel() {
        var pickedQuestionsModel = ""
        composeRule.setContent {
            CyanBridgeTheme {
                ProSubscriptionSettingsScreen(
                    state = ProSubscriptionSettingsUiState(
                        modelOptions = listOf("Cheap models router (1x)", "MiMo V2.6 Flash (1x)"),
                        questionsModelOptions = listOf(
                            "Cheap models router (1x)",
                            "Gemini 3.8 Live · Economy (7x)",
                            "Gemini 3.8 Live · Private (36x)",
                            "MiMo V2.6 Flash (1x)",
                        ),
                        questionsModel = "Gemini 3.8 Live · Economy (7x)",
                    ),
                    onRefreshPlan = {},
                    onChangePlan = {},
                    onCancelSubscription = {},
                    onRefreshAccount = {},
                    onRefreshQuota = {},
                    onRefreshModels = {},
                    onJoinBeta = {},
                    onStartGeminiLive = {},
                    onCloudSyncChange = {},
                    onPrioritySupportChange = {},
                    onPluginRewardsChange = {},
                    onEarlyAccessDevicesChange = {},
                    onBackupFrequencyChange = {},
                    onSupportChannelChange = {},
                    onRequestsModelChange = {},
                    onQuestionsModelChange = { pickedQuestionsModel = it },
                    onTasksModelChange = {},
                    onSystemPromptChange = {},
                    onResetSystemPrompt = {},
                    onBack = {},
                    liveEconomy = true,
                    liveModeLabel = "Gemini Live Economy · 7×",
                )
            }
        }

        // The Live card names the current mode; the chips are gone.
        composeRule.onNodeWithTag("pro_settings_list").performScrollToIndex(5)
        composeRule.onNodeWithText("Gemini Live Economy · 7×").assertExists()
        composeRule.onNodeWithText("Gemini Live Private · 36×").assertDoesNotExist()

        composeRule.onNodeWithTag("pro_settings_list").performScrollToIndex(2)
        composeRule.onNodeWithText("Photo & voice questions model: Gemini 3.8 Live · Economy (7x)").performClick()
        composeRule.onNodeWithText("Gemini 3.8 Live · Private (36x)").assertExists().performClick()
        composeRule.runOnIdle { assertEquals("Gemini 3.8 Live · Private (36x)", pickedQuestionsModel) }
    }
}
