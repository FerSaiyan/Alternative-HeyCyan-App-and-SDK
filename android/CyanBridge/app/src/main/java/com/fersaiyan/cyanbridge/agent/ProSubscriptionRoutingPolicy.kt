package com.fersaiyan.cyanbridge.agent

import android.content.Context
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderType
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType

object ProSubscriptionRoutingPolicy {
    enum class Action {
        NO_CHANGE,
        SWITCHED_TO_PRO,
    }

    fun applyAfterActivation(context: Context): Action {
        val alreadyUsingPro = LocalAgentPrefs.getProviderType(context) == AgentProviderType.PRO_SUBSCRIPTION &&
            AiProviderPrefs.getProvider(context) == AiProviderType.CLI_RELAY
        LocalAgentPrefs.setProviderType(context, AgentProviderType.PRO_SUBSCRIPTION)
        AiProviderPrefs.setProvider(context, AiProviderType.CLI_RELAY)
        return if (alreadyUsingPro) Action.NO_CHANGE else Action.SWITCHED_TO_PRO
    }

    /**
     * Applies the activation default only when a subscription becomes active. Routine status
     * refreshes must not call this after the user deliberately chooses Local or Tasker.
     */
    fun actionAfterActivationTransition(
        context: Context,
        wasActive: Boolean,
    ): Action {
        return if (wasActive) Action.NO_CHANGE else applyAfterActivation(context)
    }

    fun actionNote(action: Action): String {
        return when (action) {
            Action.NO_CHANGE -> ""
            Action.SWITCHED_TO_PRO -> "Switched provider to Pro Subscription"
        }
    }
}
