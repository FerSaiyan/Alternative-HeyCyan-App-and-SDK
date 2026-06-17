package com.fersaiyan.cyanbridge.agent

import android.content.Context
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderType

object ProSubscriptionRoutingPolicy {
    enum class Action {
        NO_CHANGE,
        KEPT_LOCAL_MODELS,
        ENSURED_PRO_RELAY,
        SWITCHED_TASKER_TO_PRO,
    }

    fun applyAfterActivation(context: Context): Action {
        return when (LocalAgentPrefs.getProviderType(context)) {
            AgentProviderType.LOCAL_AGENT -> {
                if (AiProviderPrefs.getProvider(context) != AiProviderType.LOCAL_MODELS) {
                    AiProviderPrefs.setProvider(context, AiProviderType.LOCAL_MODELS)
                }
                Action.KEPT_LOCAL_MODELS
            }

            AgentProviderType.PRO_SUBSCRIPTION -> {
                if (AiProviderPrefs.getProvider(context) != AiProviderType.CLI_RELAY) {
                    AiProviderPrefs.setProvider(context, AiProviderType.CLI_RELAY)
                    Action.ENSURED_PRO_RELAY
                } else {
                    Action.NO_CHANGE
                }
            }

            AgentProviderType.TASKER -> {
                LocalAgentPrefs.setProviderType(context, AgentProviderType.PRO_SUBSCRIPTION)
                AiProviderPrefs.setProvider(context, AiProviderType.CLI_RELAY)
                Action.SWITCHED_TASKER_TO_PRO
            }
        }
    }

    fun actionNote(action: Action): String {
        return when (action) {
            Action.NO_CHANGE -> ""
            Action.KEPT_LOCAL_MODELS -> "Keeping Local Models selected"
            Action.ENSURED_PRO_RELAY -> "Using Pro relay for AI features"
            Action.SWITCHED_TASKER_TO_PRO -> "Switched provider to Pro Subscription"
        }
    }
}
