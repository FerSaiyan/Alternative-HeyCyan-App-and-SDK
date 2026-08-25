package com.fersaiyan.cyanbridge.localmodels.provider

import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType

/** Pure policy for deciding whether an AI-question session should wake the local model early. */
object LocalModelPreloadPolicy {
    fun shouldPreload(
        assistantMode: GlassesAssistantMode,
        providerType: AgentProviderType,
        remoteOpenAiActive: Boolean,
        hasSelectedModel: Boolean,
    ): Boolean {
        return assistantMode == GlassesAssistantMode.CUSTOM_AI_PROVIDER &&
            providerType == AgentProviderType.LOCAL_AGENT &&
            !remoteOpenAiActive &&
            hasSelectedModel
    }
}
