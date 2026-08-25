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
            AgentProviderType.LOCAL_AGENT -> when {
                localMediaAvailable -> AgentProviderType.LOCAL_AGENT
                proAvailable -> AgentProviderType.PRO_SUBSCRIPTION
                else -> AgentProviderType.TASKER
            }
            AgentProviderType.PRO_SUBSCRIPTION -> when {
                proAvailable -> AgentProviderType.PRO_SUBSCRIPTION
                localMediaAvailable -> AgentProviderType.LOCAL_AGENT
                else -> AgentProviderType.TASKER
            }
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
        return LocalModelSettingsRepository.getForModel(context, selected.id).modelRuntime == LocalModelRuntime.LITERT
    }
}
