package com.fersaiyan.cyanbridge.shared.ai

import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import kotlin.test.assertEquals
import kotlin.test.Test

class MediaInferenceRoutingTest {
    @Test
    fun localNeverImplicitlyRoutesToNetworkWhenMediaIsUnavailable() {
        assertEquals(
            AgentProviderType.LOCAL_AGENT,
            MediaInferenceRouting.resolve(AgentProviderType.LOCAL_AGENT, true, false),
        )
        assertEquals(
            AgentProviderType.LOCAL_AGENT,
            MediaInferenceRouting.resolve(AgentProviderType.LOCAL_AGENT, false, true),
        )
        assertEquals(
            AgentProviderType.LOCAL_AGENT,
            MediaInferenceRouting.resolve(AgentProviderType.LOCAL_AGENT, false, false),
        )
    }

    @Test
    fun taskerLocalModelsUsesCapableLocalRuntime() {
        assertEquals(
            AgentProviderType.LOCAL_AGENT,
            MediaInferenceRouting.resolve(
                preferred = AgentProviderType.TASKER,
                localMediaAvailable = true,
                proAvailable = false,
                taskerUsesLocalModels = true,
            ),
        )
    }

    @Test
    fun explicitProSelectionKeepsFreeGeminiLiveRoute() {
        assertEquals(
            AgentProviderType.PRO_SUBSCRIPTION,
            MediaInferenceRouting.resolve(
                preferred = AgentProviderType.PRO_SUBSCRIPTION,
                localMediaAvailable = false,
                proAvailable = false,
            ),
        )
        assertEquals(
            AgentProviderType.PRO_SUBSCRIPTION,
            MediaInferenceRouting.resolve(
                preferred = AgentProviderType.PRO_SUBSCRIPTION,
                localMediaAvailable = true,
                proAvailable = false,
            ),
        )
    }
}
