package com.fersaiyan.cyanbridge.ui.chat

import android.content.Context
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderType
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.shared.chat.ChatModelInfo
import com.fersaiyan.cyanbridge.shared.chat.ChatProviderKind
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType

/** The chat provider and model selected in Settings. */
internal fun chatModelInfo(context: Context): ChatModelInfo {
    val provider = LocalAgentPrefs.getProviderType(context)
    val taskerProvider = if (provider == AgentProviderType.TASKER) AiProviderPrefs.getProvider(context) else null
    val local = provider == AgentProviderType.LOCAL_AGENT || taskerProvider == AiProviderType.LOCAL_MODELS
    val relay = provider == AgentProviderType.PRO_SUBSCRIPTION || taskerProvider == AiProviderType.CLI_RELAY
    return when {
        local && RemoteOpenAiPrefs.isActive(context) ->
            ChatModelInfo(ChatProviderKind.REMOTE_OPENAI, RemoteOpenAiPrefs.getModel(context))
        local -> ChatModelInfo(ChatProviderKind.LOCAL, LocalModelStorageRepository.resolveSelectedModel(context)?.displayName)
        relay -> ChatModelInfo(ChatProviderKind.RELAY, ProSubscriptionAiPrefs.getRequestsModel(context))
        else -> ChatModelInfo(ChatProviderKind.OTHER)
    }
}
