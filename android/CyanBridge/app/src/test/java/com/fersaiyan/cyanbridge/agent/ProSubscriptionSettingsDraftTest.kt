package com.fersaiyan.cyanbridge.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProSubscriptionSettingsDraftTest {

    @Test
    fun untouchedDraftShowsTheScreenDefaults() {
        val state = ProSubscriptionSettingsDraft().toUiState(ProSubscriptionModelLabels(), systemPrompt = "Prompt")

        assertEquals("Quota: unknown", state.quotaStatus)
        assertEquals("", state.quotaBreakdown)
        assertNull(state.quotaProgress)
        assertEquals("Not signed up yet.", state.betaStatus)
        assertEquals(
            listOf(
                "Cheap models router (1x)",
                "Gemini 3.1 Flash Live (36x)",
                "Gemma 4 26B Vision (2x)",
                "DeepSeek V4 Flash (2x)",
            ),
            state.modelOptions,
        )
        assertEquals("Cheap models router (1x)", state.requestsModel)
        assertEquals("Prompt", state.systemPrompt)
    }

    @Test
    fun quotaLoadingInfoAndErrorMatchTheFormerIndicator() {
        val draft = ProSubscriptionSettingsDraft()
        val models = ProSubscriptionModelLabels()

        draft.showQuotaLoading("openrouter/free")
        var state = draft.toUiState(models, "")
        assertEquals("Quota: loading for model 'openrouter/free'...", state.quotaStatus)
        assertNull(state.quotaProgress)

        draft.showQuotaInfo(ProSubscriptionRelayClient.QuotaInfo(used = 1_200, limit = 5_000, remaining = 3_800, resetAtMs = 0L, model = "openrouter/free"))
        state = draft.toUiState(models, "")
        assertEquals("Quota (openrouter): 24% used", state.quotaStatus)
        assertEquals("3.8k left · 1.2k/5k used", state.quotaBreakdown)
        assertEquals(24, state.quotaProgress)

        draft.showQuotaInfo(ProSubscriptionRelayClient.QuotaInfo(used = 7, limit = 0, remaining = 2_000_000, resetAtMs = 0L, model = "deepseek/deepseek-v4-flash"))
        state = draft.toUiState(models, "")
        assertEquals("Quota (deepseek/deepseek-v4-flash): 2M left", state.quotaStatus)
        assertEquals("7 used", state.quotaBreakdown)
        assertNull(state.quotaProgress)

        draft.showQuotaError("Quota unavailable: offline")
        state = draft.toUiState(models, "")
        assertEquals("Quota unavailable: offline", state.quotaStatus)
        assertEquals("7 used", state.quotaBreakdown)
        assertNull(state.quotaProgress)
    }

    @Test
    fun modelLabelsKeepSavedIdsAndResolvePositionsLikeTheFormerPickers() {
        val models = ProSubscriptionModelLabels()
        models.includeSaved(listOf("custom/model", "", "OPENROUTER/FREE"))

        assertEquals("custom/model", models.labels.last())
        assertEquals(4, models.positionOf("custom/model"))
        assertEquals(0, models.positionOf("OpenRouter/Free"))
        assertEquals(3, models.positionOf("deepseek/deepseek-v4-flash"))
        assertEquals(0, models.positionOf("not/listed"))
        assertEquals("google/gemma-4-26b-a4b-it", models.modelAt(2))
        assertEquals("custom/model", models.modelAt(4))

        models.replaceAll(linkedMapOf("a/one" to "Same", "b/two" to "Same", "c/three" to " "))

        assertEquals(listOf("Same", "Same · b/two", "c/three"), models.labels)
        assertEquals(1, models.positionOf("b/two"))
        assertEquals("b/two", models.modelAt(1))
        assertEquals("c/three", models.modelAt(2))
    }

    @Test
    fun compactCountsAndMaskedTokens() {
        assertEquals("999", ProSubscriptionSettingsFormat.compactCount(999))
        assertEquals("1k", ProSubscriptionSettingsFormat.compactCount(1_000))
        assertEquals("1.5M", ProSubscriptionSettingsFormat.compactCount(1_500_000))
        assertEquals("2B", ProSubscriptionSettingsFormat.compactCount(2_000_000_000))
        assertEquals("-", ProSubscriptionSettingsFormat.maskToken(" "))
        assertEquals("short-token", ProSubscriptionSettingsFormat.maskToken("short-token"))
        assertEquals("abcdefgh...wxyz", ProSubscriptionSettingsFormat.maskToken("abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun inactivePlanClosesTheScreenWithToast() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ProSubscriptionPrefs.clearEntitlement(context)

        val activity = Robolectric.buildActivity(ProSubscriptionSettingsActivity::class.java).create().get()

        assertTrue(activity.isFinishing)
        assertEquals("No active Pro plan found.", ShadowToast.getTextOfLatestToast())
    }
}
