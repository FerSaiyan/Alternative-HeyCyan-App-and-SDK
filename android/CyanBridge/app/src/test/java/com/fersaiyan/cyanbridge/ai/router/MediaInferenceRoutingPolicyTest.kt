package com.fersaiyan.cyanbridge.ai.router

import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaInferenceRoutingPolicyTest {
    @Test
    fun localFallsBackToProThenTasker() {
        assertEquals(
            AgentProviderType.LOCAL_AGENT,
            MediaInferenceRoutingPolicy.resolve(AgentProviderType.LOCAL_AGENT, true, false),
        )
        assertEquals(
            AgentProviderType.PRO_SUBSCRIPTION,
            MediaInferenceRoutingPolicy.resolve(AgentProviderType.LOCAL_AGENT, false, true),
        )
        assertEquals(
            AgentProviderType.TASKER,
            MediaInferenceRoutingPolicy.resolve(AgentProviderType.LOCAL_AGENT, false, false),
        )
    }

    @Test
    fun taskerLocalModelsUsesCapableLocalRuntime() {
        assertEquals(
            AgentProviderType.LOCAL_AGENT,
            MediaInferenceRoutingPolicy.resolve(
                preferred = AgentProviderType.TASKER,
                localMediaAvailable = true,
                proAvailable = false,
                taskerUsesLocalModels = true,
            ),
        )
    }
}
