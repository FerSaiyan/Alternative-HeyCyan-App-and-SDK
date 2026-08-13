package com.fersaiyan.cyanbridge.plugins.localagent

import android.content.Context
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderType
import com.fersaiyan.cyanbridge.localagent.LocalAgentController
import com.fersaiyan.cyanbridge.localagent.LocalAgentNotificationSpeaker
import com.fersaiyan.cyanbridge.localagent.LocalAgentPrefs as RuntimePrefs
import com.fersaiyan.cyanbridge.localagent.LocalAgentTelegramService
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.fersaiyan.cyanbridge.ui.CommunityPluginPrefs

/**
 * Native-plugin facade for the existing Local Agent runtime.
 *
 * The automation preference remains the source of truth so upgrading users keep
 * their existing phone-control setting. The native-plugin flag mirrors it for
 * the plugin registry and shortcut surfaces.
 */
object LocalAgentPlugin {

    fun isEnabled(context: Context): Boolean =
        AutomationPrefs.isLocalAgentAutomationEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) {
        AutomationPrefs.setLocalAgentAutomationEnabled(context, enabled)
        CommunityPluginPrefs.setNativePluginEnabled(context, NativePluginIds.LOCAL_AGENT, enabled)
        if (!enabled) {
            LocalAgentController.stop(context)
            LocalAgentTelegramService.stop(context)
            LocalAgentNotificationSpeaker.stop()
        } else if (RuntimePrefs.isTelegramRemoteControlEnabled(context)) {
            // Remote control was explicitly configured earlier; restoring phone control can
            // resume only that already allowlisted Telegram listener.
            LocalAgentTelegramService.start(context)
        }
    }

    fun start(context: Context, goal: String): LocalAgentController.CommandResult {
        val trimmedGoal = goal.trim()
        if (trimmedGoal.isBlank()) {
            return LocalAgentController.CommandResult(
                ok = false,
                userMessage = "No agent goal was provided.",
                error = "missing_goal",
            )
        }
        if (!isEnabled(context)) {
            setEnabled(context, true)
        }
        return LocalAgentController.start(context, trimmedGoal)
    }

    fun stop(context: Context) {
        setEnabled(context, false)
    }

    fun syncNativePluginState(context: Context) {
        CommunityPluginPrefs.setNativePluginEnabled(
            context,
            NativePluginIds.LOCAL_AGENT,
            isEnabled(context),
        )
    }

    fun setPlanningProvider(context: Context, type: AgentProviderType) {
        AutomationPrefs.setProviderType(context, type)
        AiProviderPrefs.setProvider(
            context,
            when (type) {
                AgentProviderType.PRO_SUBSCRIPTION -> AiProviderType.CLI_RELAY
                AgentProviderType.LOCAL_AGENT -> AiProviderType.LOCAL_MODELS
                AgentProviderType.TASKER -> AiProviderType.MOCK
            },
        )
    }
}
