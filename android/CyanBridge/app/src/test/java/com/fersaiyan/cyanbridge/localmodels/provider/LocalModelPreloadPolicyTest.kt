package com.fersaiyan.cyanbridge.localmodels.provider

import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelPreloadPolicyTest {
    @Test
    fun localCustomProviderWithInstalledModelPreloads() {
        assertTrue(
            LocalModelPreloadPolicy.shouldPreload(
                assistantMode = GlassesAssistantMode.CUSTOM_AI_PROVIDER,
                providerType = AgentProviderType.LOCAL_AGENT,
                remoteOpenAiActive = false,
                hasSelectedModel = true,
            ),
        )
    }

    @Test
    fun phoneAssistantDoesNotPreload() {
        assertFalse(
            LocalModelPreloadPolicy.shouldPreload(
                assistantMode = GlassesAssistantMode.PHONE_ASSISTANT,
                providerType = AgentProviderType.LOCAL_AGENT,
                remoteOpenAiActive = false,
                hasSelectedModel = true,
            ),
        )
    }

    @Test
    fun proAndTaskerProvidersDoNotPreload() {
        listOf(AgentProviderType.PRO_SUBSCRIPTION, AgentProviderType.TASKER).forEach { provider ->
            assertFalse(
                LocalModelPreloadPolicy.shouldPreload(
                    assistantMode = GlassesAssistantMode.CUSTOM_AI_PROVIDER,
                    providerType = provider,
                    remoteOpenAiActive = false,
                    hasSelectedModel = true,
                ),
            )
        }
    }

    @Test
    fun remoteBackendOrMissingModelDoesNotPreload() {
        assertFalse(
            LocalModelPreloadPolicy.shouldPreload(
                assistantMode = GlassesAssistantMode.CUSTOM_AI_PROVIDER,
                providerType = AgentProviderType.LOCAL_AGENT,
                remoteOpenAiActive = true,
                hasSelectedModel = true,
            ),
        )
        assertFalse(
            LocalModelPreloadPolicy.shouldPreload(
                assistantMode = GlassesAssistantMode.CUSTOM_AI_PROVIDER,
                providerType = AgentProviderType.LOCAL_AGENT,
                remoteOpenAiActive = false,
                hasSelectedModel = false,
            ),
        )
    }
}
