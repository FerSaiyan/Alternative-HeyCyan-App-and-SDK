package com.fersaiyan.cyanbridge.ai.router

import android.content.Context
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionPrefs
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelRuntime
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.shared.ai.MediaInferenceRouting
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType

object MediaInferenceRoutingPolicy {
    fun resolve(context: Context): AgentProviderType {
        return MediaInferenceRouting.resolve(
            preferred = LocalAgentPrefs.getProviderType(context),
            localMediaAvailable = hasLocalMultimodalModel(context),
            proAvailable = ProSubscriptionPrefs.isActiveLocally(context),
            taskerUsesLocalModels = AiProviderPrefs.getProvider(context) == AiProviderType.LOCAL_MODELS,
        )
    }

    fun hasLocalMultimodalModel(context: Context): Boolean {
        val selected = LocalModelStorageRepository.resolveSelectedModel(context) ?: return false
        val runtime = LocalModelSettingsRepository.getForModel(context, selected.id).modelRuntime
        return runtime == LocalModelRuntime.LITERT || (runtime == LocalModelRuntime.LLAMA_CPP &&
            com.fersaiyan.cyanbridge.localmodels.storage.LocalModelProjectorStore.get(context, selected) != null)
    }
}
