package com.fersaiyan.cyanbridge.ai.router

import android.content.Context
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionPrefs
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelRuntime
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType

object MediaInferenceRoutingPolicy {
    fun resolve(context: Context): AgentProviderType {
        return resolve(
            preferred = LocalAgentPrefs.getProviderType(context),
            localMediaAvailable = hasLocalMultimodalModel(context),
            proAvailable = ProSubscriptionPrefs.isActiveLocally(context),
            taskerUsesLocalModels = AiProviderPrefs.getProvider(context) == AiProviderType.LOCAL_MODELS,
        )
    }

    fun resolve(
        preferred: AgentProviderType,
        localMediaAvailable: Boolean,
        proAvailable: Boolean,
        taskerUsesLocalModels: Boolean = false,
    ): AgentProviderType {
        return when (preferred) {
            // LOCAL is an offline choice. Missing media support is reported locally;
            // it must not implicitly enable the Pro/Tasker relay or Gemini Live.
            AgentProviderType.LOCAL_AGENT -> AgentProviderType.LOCAL_AGENT
            // Explicit Pro selection also represents Free Gemini Live for image and voice
            // questions. Subscription status controls direct Pro access, not this route.
            AgentProviderType.PRO_SUBSCRIPTION -> AgentProviderType.PRO_SUBSCRIPTION
            AgentProviderType.TASKER -> when {
                taskerUsesLocalModels && localMediaAvailable -> {
                    AgentProviderType.LOCAL_AGENT
                }
                else -> AgentProviderType.TASKER
            }
        }
    }

    fun hasLocalMultimodalModel(context: Context): Boolean {
        val selected = LocalModelStorageRepository.resolveSelectedModel(context) ?: return false
        val runtime = LocalModelSettingsRepository.getForModel(context, selected.id).modelRuntime
        return runtime == LocalModelRuntime.LITERT || (runtime == LocalModelRuntime.LLAMA_CPP &&
            com.fersaiyan.cyanbridge.localmodels.storage.LocalModelProjectorStore.get(context, selected) != null)
    }
}
