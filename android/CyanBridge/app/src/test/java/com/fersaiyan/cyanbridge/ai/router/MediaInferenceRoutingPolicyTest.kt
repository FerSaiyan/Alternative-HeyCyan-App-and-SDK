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

    @Test
    fun explicitProSelectionKeepsFreeGeminiLiveRoute() {
        assertEquals(
            AgentProviderType.PRO_SUBSCRIPTION,
            MediaInferenceRoutingPolicy.resolve(
                preferred = AgentProviderType.PRO_SUBSCRIPTION,
                localMediaAvailable = false,
                proAvailable = false,
            ),
        )
        assertEquals(
            AgentProviderType.PRO_SUBSCRIPTION,
            MediaInferenceRoutingPolicy.resolve(
                preferred = AgentProviderType.PRO_SUBSCRIPTION,
                localMediaAvailable = true,
                proAvailable = false,
            ),
        )
    }
    @Test
    fun configuredRemoteDoesNotFallBackToPublicRelayWithoutLocalModel() {
        for (proAvailable in listOf(false, true)) {
            assertEquals(
                AgentProviderType.LOCAL_AGENT,
                MediaInferenceRoutingPolicy.resolve(
                    preferred = AgentProviderType.LOCAL_AGENT,
                    localMediaAvailable = false,
                    proAvailable = proAvailable,
                    remoteBackendActive = true,
                ),
            )
        }
    }

    @Test
    fun taskerLocalBackendUsesConfiguredRemoteForImages() {
        assertEquals(
            AgentProviderType.LOCAL_AGENT,
            MediaInferenceRoutingPolicy.resolve(
                preferred = AgentProviderType.TASKER,
                localMediaAvailable = false,
                proAvailable = false,
                taskerUsesLocalModels = true,
                remoteBackendActive = true,
            ),
        )
    }

    @Test
    fun remoteConfigurationDoesNotOverrideExplicitProSelection() {
        assertEquals(
            AgentProviderType.PRO_SUBSCRIPTION,
            MediaInferenceRoutingPolicy.resolve(
                preferred = AgentProviderType.PRO_SUBSCRIPTION,
                localMediaAvailable = false,
                proAvailable = false,
                remoteBackendActive = true,
            ),
        )
    }
}
