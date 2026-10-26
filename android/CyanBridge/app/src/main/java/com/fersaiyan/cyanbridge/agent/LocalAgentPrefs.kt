package com.fersaiyan.cyanbridge.agent

import android.content.Context
import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import com.fersaiyan.cyanbridge.shared.prefs.AgentPrefs.LocalAgent
import com.fersaiyan.cyanbridge.shared.prefs.edit
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.isSet
import com.fersaiyan.cyanbridge.shared.prefs.remove
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType

/**
 * Local agent settings in `local_agent_prefs`: automation and routing (provider, glasses mode,
 * capture), runtime status and approvals, planner media status, Shizuku controls, and the
 * on-device runtime toggle.
 * Keys, types and defaults are declared in [LocalAgent].
 */
object LocalAgentPrefs {
    /** Opt-in fast path: EmbeddingGemma cosine gate ahead of the single-token LLM. Default off. */
    fun isEmbeddingDecisionEnabled(context: Context): Boolean = LocalAgent.embeddingDecisionEnabled[context]

    fun setEmbeddingDecisionEnabled(context: Context, enabled: Boolean) {
        LocalAgent.embeddingDecisionEnabled[context] = enabled
    }

    /** Absolute path to the embedding GGUF (HIL-provisioned; never bundled). Empty disables. */
    fun getEmbeddingModelPath(context: Context): String = LocalAgent.embeddingModelPath[context].trim()

    fun setEmbeddingModelPath(context: Context, path: String) {
        LocalAgent.embeddingModelPath[context] = path.trim()
    }

    /** Minimum cosine margin for the embedding gate; below abstains to the LLM path. */
    fun getEmbeddingMarginThreshold(context: Context): Float =
        LocalAgent.embeddingMarginThreshold[context].coerceIn(0f, 2f)

    fun setEmbeddingMarginThreshold(context: Context, margin: Float) {
        LocalAgent.embeddingMarginThreshold[context] = margin.coerceIn(0f, 2f)
    }

    fun getProviderType(context: Context): AgentProviderType =
        when (LocalAgent.providerType[context]?.trim()?.uppercase()) {
            AgentProviderType.LOCAL_AGENT.name -> AgentProviderType.LOCAL_AGENT
            "API_MODELS" -> AgentProviderType.PRO_SUBSCRIPTION
            AgentProviderType.PRO_SUBSCRIPTION.name -> AgentProviderType.PRO_SUBSCRIPTION
            AgentProviderType.TASKER.name -> AgentProviderType.TASKER
            null, "" -> AgentProviderType.LOCAL_AGENT
            else -> AgentProviderType.TASKER
        }

    fun setProviderType(context: Context, type: AgentProviderType) {
        LocalAgent.providerType[context] = type.name
    }

    fun getGlassesAssistantMode(context: Context): GlassesAssistantMode {
        val stored = LocalAgent.glassesAssistantMode[context].trim().uppercase()
        val mode = when (stored) {
            GlassesAssistantMode.CUSTOM_AI_PROVIDER.name,
            "CHOSEN_PROVIDER" -> GlassesAssistantMode.CUSTOM_AI_PROVIDER

            GlassesAssistantMode.PHONE_ASSISTANT.name,
            "GEMINI",
            "CHAT_GPT",
            "PHONE_DEFAULT" -> GlassesAssistantMode.PHONE_ASSISTANT

            "" -> GlassesAssistantMode.CUSTOM_AI_PROVIDER

            else -> GlassesAssistantMode.PHONE_ASSISTANT
        }
        if (stored != mode.name) setGlassesAssistantMode(context, mode)
        return mode
    }

    fun setGlassesAssistantMode(context: Context, mode: GlassesAssistantMode) {
        LocalAgent.glassesAssistantMode[context] = mode.name
    }

    fun isLocalAgentAutomationEnabled(context: Context): Boolean = LocalAgent.automationEnabled[context]

    fun setLocalAgentAutomationEnabled(context: Context, enabled: Boolean) {
        LocalAgent.automationEnabled[context] = enabled
    }

    fun isRequireConfirmationEnabled(context: Context): Boolean = LocalAgent.requireConfirmation[context]

    fun setRequireConfirmationEnabled(context: Context, enabled: Boolean) {
        LocalAgent.requireConfirmation[context] = enabled
    }

    fun getMaxSteps(context: Context): Int = LocalAgent.maxSteps[context]

    fun setMaxSteps(context: Context, steps: Int) {
        LocalAgent.maxSteps[context] = steps
    }

    /** Legacy CyanBridge Accessibility-based screen capture switch. */
    fun setAutoCaptureEnabled(context: Context, enabled: Boolean) {
        LocalAgent.autoCaptureEnabled[context] = enabled
    }

    /**
     * Tasker-backed AutoDiary feature switch. Before the first migration write, fall back
     * to the legacy capture bit so already-enabled users can be migrated without losing
     * their setting. AutoDiaryService persists this new key before clearing the legacy bit.
     */
    fun isTaskerAutoDiaryEnabled(context: Context): Boolean =
        if (LocalAgent.taskerAutoDiaryEnabled.isSet(context)) {
            LocalAgent.taskerAutoDiaryEnabled[context]
        } else {
            LocalAgent.autoCaptureEnabled[context]
        }

    fun setTaskerAutoDiaryEnabled(context: Context, enabled: Boolean) {
        LocalAgent.taskerAutoDiaryEnabled[context] = enabled
    }

    fun setCaptureBlacklistPackages(context: Context, packages: Set<String>) {
        // Use commit() for reliability: users may blacklist many apps at once and immediately
        // leave the screen; apply() is async and can be lost if the process is killed.
        LocalAgent.edit(context, commit = true) { this[LocalAgent.captureBlacklist] = cleanPackages(packages) }
    }

    private fun cleanPackages(packages: Set<String>): Set<String> =
        packages.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()

    fun isDailyFactsReminderEnabled(context: Context): Boolean = LocalAgent.dailyFactsReminderEnabled[context]

    fun setDailyFactsReminderEnabled(context: Context, enabled: Boolean) {
        LocalAgent.dailyFactsReminderEnabled[context] = enabled
    }

    fun isRequireActionConfirmationEnabled(context: Context): Boolean = LocalAgent.requireActionConfirmation[context]

    fun setRequireActionConfirmationEnabled(context: Context, enabled: Boolean) {
        LocalAgent.requireActionConfirmation[context] = enabled
    }

    fun isAutoExecuteLowRiskEnabled(context: Context): Boolean = LocalAgent.autoExecuteLowRisk[context]

    fun setAutoExecuteLowRiskEnabled(context: Context, enabled: Boolean) {
        LocalAgent.autoExecuteLowRisk[context] = enabled
    }

    fun isWhatsAppNotificationReadAloudEnabled(context: Context): Boolean =
        LocalAgent.whatsAppNotificationReadAloud[context]

    fun setWhatsAppNotificationReadAloudEnabled(context: Context, enabled: Boolean) {
        LocalAgent.whatsAppNotificationReadAloud[context] = enabled
    }

    fun getStatus(context: Context): String = LocalAgent.status[context]

    fun setStatus(context: Context, status: String) {
        LocalAgent.status[context] = status
    }

    fun getLastError(context: Context): String = LocalAgent.lastError[context]

    fun setLastError(context: Context, error: String) {
        LocalAgent.lastError[context] = error
    }

    fun clearLastError(context: Context) {
        setLastError(context, "(none)")
    }

    fun setLastApprovalVoicePrompt(context: Context, prompt: String) {
        LocalAgent.lastApprovalVoicePrompt[context] = prompt.take(2_000)
    }

    fun getLastApprovalVoicePrompt(context: Context): String = LocalAgent.lastApprovalVoicePrompt[context]

    fun clearLastApprovalVoicePrompt(context: Context) {
        LocalAgent.lastApprovalVoicePrompt.remove(context)
    }

    fun setLastApprovalVoiceReply(context: Context, reply: String) {
        LocalAgent.lastApprovalVoiceReply[context] = reply.take(500)
    }

    fun getLastApprovalVoiceReply(context: Context): String = LocalAgent.lastApprovalVoiceReply[context]

    fun clearLastApprovalVoiceReply(context: Context) {
        LocalAgent.lastApprovalVoiceReply.remove(context)
    }

    /** Debug: last context injection details (normal chat System prompt). */
    fun setLastContextInjectionDebug(context: Context, debugText: String) {
        LocalAgent.edit(context) {
            this[LocalAgent.lastContextInjectionDebug] = debugText
            this[LocalAgent.lastContextInjectionAtMs] = System.currentTimeMillis()
        }
    }

    fun getLastContextInjectionDebug(context: Context): String = LocalAgent.lastContextInjectionDebug[context]

    fun getLastContextInjectionAtMs(context: Context): Long = LocalAgent.lastContextInjectionAtMs[context]

    /** Last planner media status: text-only, or whether a Tasker screenshot was attached. */
    fun getScreenshotStatus(context: Context): String = LocalAgent.screenshotStatus[context]

    fun setScreenshotStatus(context: Context, status: String) {
        LocalAgent.screenshotStatus[context] = status
    }

    fun isShizukuFallbackEnabled(context: Context): Boolean = LocalAgent.shizukuFallbackEnabled[context]

    fun setShizukuFallbackEnabled(context: Context, enabled: Boolean) {
        LocalAgent.shizukuFallbackEnabled[context] = enabled
    }

    fun getShizukuStatus(context: Context): String = LocalAgent.shizukuStatus[context]

    fun setShizukuStatus(context: Context, status: String) {
        LocalAgent.shizukuStatus[context] = status
    }

    /** On-device runtime toggle. */
    fun isEnabled(context: Context): Boolean = LocalAgent.enabled[context]

    fun setEnabled(context: Context, enabled: Boolean) {
        LocalAgent.enabled[context] = enabled
    }
}
