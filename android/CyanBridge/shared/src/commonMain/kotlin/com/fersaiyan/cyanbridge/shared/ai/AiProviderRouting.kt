package com.fersaiyan.cyanbridge.shared.ai

import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import com.fersaiyan.cyanbridge.shared.platform.platformSupportsTasker
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType

/** Backend behind the Tasker/automation provider. [wire] is the stored value. */
enum class AiProviderType(val wire: String, val label: String) {
    MOCK("mock", "Mock (local demo)"),
    COMPANY_BACKEND("company_backend", "Company Backend (stub)"),
    CLI_RELAY("cli_relay", "CLI Relay (Codex/Gemini)"),
    LOCAL_MODELS("local_models", "Local Models (on-device)");

    companion object {
        fun fromWire(value: String?): AiProviderType =
            entries.firstOrNull { it.wire == value } ?: MOCK
    }
}

/** CLI the relay runs a request with. [wire] is the stored and sent value. */
enum class CliRelayBackend(val wire: String, val label: String) {
    GEMINI("gemini", "Gemini CLI"),
    CODEX("codex", "Codex CLI");

    companion object {
        fun fromWire(value: String?): CliRelayBackend =
            entries.firstOrNull { it.wire == value } ?: GEMINI
    }
}

/** The custom providers a platform offers: iOS has no Tasker. */
object AgentProviderAvailability {
    fun available(supportsTasker: Boolean = platformSupportsTasker): List<AgentProviderType> =
        AgentProviderType.entries.filter { supportsTasker || it != AgentProviderType.TASKER }

    /** The provider in use; a saved choice the platform lacks (Tasker on iOS) runs as Local. */
    fun effective(saved: AgentProviderType, available: List<AgentProviderType> = available()): AgentProviderType =
        if (saved in available) saved else AgentProviderType.LOCAL_AGENT
}

/** Fixed replies of the providers that do not call a model. */
object AiProviderReplies {
    const val COMPANY_BACKEND = "Company backend is not configured yet in this build."

    fun mock(prompt: String): String = "Demo mode reply: $prompt"
}

/** Which backend answers a chat, given the user's provider choice. */
object AiProviderRouting {
    /** [provider] is the chosen provider, or the media route when there are attachments. */
    fun chatBackend(provider: AgentProviderType, taskerBackend: () -> AiProviderType): AiProviderType =
        when (provider) {
            AgentProviderType.PRO_SUBSCRIPTION -> AiProviderType.CLI_RELAY
            AgentProviderType.LOCAL_AGENT -> AiProviderType.LOCAL_MODELS
            AgentProviderType.TASKER -> taskerBackend()
        }
}

object MediaInferenceRouting {
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
}

enum class GlassesAssistantRoute {
    PHONE_ASSISTANT,
    LOCAL,
    PRO,
    TASKER_EXTERNAL_UI,
}

object GlassesAssistantRoutingPolicy {
    fun resolve(
        mode: GlassesAssistantMode,
        customProvider: AgentProviderType,
    ): GlassesAssistantRoute {
        if (mode == GlassesAssistantMode.PHONE_ASSISTANT) {
            return GlassesAssistantRoute.PHONE_ASSISTANT
        }
        return when (customProvider) {
            AgentProviderType.LOCAL_AGENT -> GlassesAssistantRoute.LOCAL
            AgentProviderType.PRO_SUBSCRIPTION -> GlassesAssistantRoute.PRO
            AgentProviderType.TASKER -> GlassesAssistantRoute.TASKER_EXTERNAL_UI
        }
    }
}

object AssistantSpeechPolicy {
    private const val DEFAULT_CLARIFICATION = "Please say exactly what you want me to do."

    fun clarification(raw: String?): String {
        val clean = raw.orEmpty()
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (clean.isBlank() || clean.length > 160) return DEFAULT_CLARIFICATION
        if (SUSPICIOUS_TOOL_TEXT.containsMatchIn(clean)) return DEFAULT_CLARIFICATION
        return clean
    }

    // (?s): '.' also matches line breaks.
    private val SUSPICIOUS_TOOL_TEXT = Regex(
        "(?s)(?:\\{.*\\}|click_coord|click_text|type_text|tool_call|reasoning|bounds:|center:|action\\s*:)",
        RegexOption.IGNORE_CASE,
    )
}
