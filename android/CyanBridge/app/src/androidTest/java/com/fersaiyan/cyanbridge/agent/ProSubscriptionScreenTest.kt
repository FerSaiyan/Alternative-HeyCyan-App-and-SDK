package com.fersaiyan.cyanbridge.agent

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.billing.ProSubscriptionUiState
import com.fersaiyan.cyanbridge.shared.ui.pro.ProSubscriptionScreen
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProSubscriptionScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun restoreButtonAppearsBeforeDonationAndRoutesAction() {
        var restoreClicks = 0
        composeRule.setContent {
            CyanBridgeTheme {
                ProSubscriptionScreen(
                    state = ProSubscriptionUiState(webCheckoutAvailable = false),
                    restoreNotFoundEmail = null,
                    restoreLogsSending = false,
                    onPlanSelected = {},
                    onStartFreeTrial = {},
                    onSubscribeWithGooglePlay = {},
                    onSubscribeOnWebsite = {},
                    onCheckoutUnavailable = {},
                    onRestoreExistingSubscription = { restoreClicks++ },
                    onDismissRestoreNotFound = {},
                    onSendRestoreFailureLogs = {},
                    onDonate = {},
                    onCancelSubscription = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("pro_subscription_list").performScrollToIndex(6)
        composeRule.onNodeWithTag("restore_existing_pro")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle { assertEquals(1, restoreClicks) }
    }

    @Test
    fun missingSubscriptionDialogCanSendLogsForManualReview() {
        var sendLogClicks = 0
        composeRule.setContent {
            CyanBridgeTheme {
                ProSubscriptionScreen(
                    state = ProSubscriptionUiState(),
                    restoreNotFoundEmail = "subscriber@example.com",
                    restoreLogsSending = false,
                    onPlanSelected = {},
                    onStartFreeTrial = {},
                    onSubscribeWithGooglePlay = {},
                    onSubscribeOnWebsite = {},
                    onCheckoutUnavailable = {},
                    onRestoreExistingSubscription = {},
                    onDismissRestoreNotFound = {},
                    onSendRestoreFailureLogs = { sendLogClicks++ },
                    onDonate = {},
                    onCancelSubscription = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Pro subscription not found").assertExists()
        composeRule.onNodeWithText(
            "We couldn't find an active Pro subscription for subscriber@example.com. Check that you entered the same email used for your purchase, or send logs to the developer for manual review.",
        ).assertExists()
        composeRule.onNodeWithText("Send logs to developer")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle { assertEquals(1, sendLogClicks) }
    }
}
