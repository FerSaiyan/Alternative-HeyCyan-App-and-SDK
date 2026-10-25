package com.fersaiyan.cyanbridge.shared.billing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProSubscriptionSettingsDraftTest {

    @Test
    fun quotaLoadingInfoAndErrorMatchTheFormerIndicator() {
        val draft = ProSubscriptionSettingsDraft()
        val models = ProSubscriptionModelLabels()

        draft.showQuotaLoading("openrouter/free")
        var state = draft.toUiState(models, "")
        assertEquals("Quota: loading for model 'openrouter/free'...", state.quotaStatus)
        assertNull(state.quotaProgress)

        draft.showQuotaInfo(ProQuota(used = 1_200, limit = 5_000, remaining = 3_800, resetAtMs = 0L, model = "openrouter/free"))
        state = draft.toUiState(models, "")
        assertEquals("Quota (openrouter): 24% used", state.quotaStatus)
        assertEquals("3.8k left · 1.2k/5k used", state.quotaBreakdown)
        assertEquals(24, state.quotaProgress)

        draft.showQuotaInfo(ProQuota(used = 7, limit = 0, remaining = 2_000_000, resetAtMs = 0L, model = "deepseek/deepseek-v4-flash"))
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
}
