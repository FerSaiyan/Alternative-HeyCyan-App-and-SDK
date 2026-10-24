package com.fersaiyan.cyanbridge.shared.ai

import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import kotlin.test.assertEquals
import kotlin.test.Test

class GlassesAssistantRoutingPolicyTest {
    @Test
    fun phoneAssistantIgnoresCustomProviderSetting() {
        AgentProviderType.entries.forEach { provider ->
            assertEquals(
                GlassesAssistantRoute.PHONE_ASSISTANT,
                GlassesAssistantRoutingPolicy.resolve(GlassesAssistantMode.PHONE_ASSISTANT, provider),
            )
        }
    }

    @Test
    fun customModeRoutesEachSettingsChoiceExplicitly() {
        assertEquals(
            GlassesAssistantRoute.LOCAL,
            GlassesAssistantRoutingPolicy.resolve(
                GlassesAssistantMode.CUSTOM_AI_PROVIDER,
                AgentProviderType.LOCAL_AGENT,
            ),
        )
        assertEquals(
            GlassesAssistantRoute.PRO,
            GlassesAssistantRoutingPolicy.resolve(
                GlassesAssistantMode.CUSTOM_AI_PROVIDER,
                AgentProviderType.PRO_SUBSCRIPTION,
            ),
        )
        assertEquals(
            GlassesAssistantRoute.TASKER_EXTERNAL_UI,
            GlassesAssistantRoutingPolicy.resolve(
                GlassesAssistantMode.CUSTOM_AI_PROVIDER,
                AgentProviderType.TASKER,
            ),
        )
    }
}
