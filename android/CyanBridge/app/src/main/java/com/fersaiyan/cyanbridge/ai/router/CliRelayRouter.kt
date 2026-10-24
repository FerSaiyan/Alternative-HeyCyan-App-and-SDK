package com.fersaiyan.cyanbridge.ai.router

import android.content.Context
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionServerPrefs
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.shared.ai.AiProviderReplies
import com.fersaiyan.cyanbridge.shared.ai.AiProviderRouting
import com.fersaiyan.cyanbridge.shared.ai.CliRelayBackend
import com.fersaiyan.cyanbridge.shared.ai.RelayClient
import com.fersaiyan.cyanbridge.shared.ai.RelayMedia
import com.fersaiyan.cyanbridge.shared.ai.RelaySettings
import com.fersaiyan.cyanbridge.shared.ai.VoiceQueryDetails
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.fersaiyan.cyanbridge.ui.localization.AppLanguagePreferences
import java.io.File
import java.util.Locale

/** Relay settings read from the app's preferences on every request. */
private class AndroidRelaySettings(private val context: Context) : RelaySettings {
    override val baseUrl: String get() = AiProviderPrefs.getRelayBaseUrl(context)
    override val backend: CliRelayBackend get() = AiProviderPrefs.getRelayBackend(context)
    override val systemPrompt: String get() = ProSubscriptionAiPrefs.getSystemPrompt(context)

    override val headers: Map<String, String>
        get() = buildMap {
            // Send app language so relay can return localized quota/subscription messages.
            runCatching {
                val lang = runCatching { AppLanguagePreferences.selected(context).languageTag }.getOrDefault("")
                val tag = lang.ifBlank {
                    runCatching { ImageQuestionPreferences.get(context).appLanguageTag }.getOrDefault("")
                }.ifBlank { Locale.getDefault().toLanguageTag() }
                if (tag.isNotBlank()) put("Accept-Language", tag)
            }
            val serverToken = ProSubscriptionServerPrefs.getApiToken(context)
            if (serverToken.isNotBlank()) put("Authorization", "Bearer $serverToken")
        }
}

/** The relay client ([RelayClient]) with the app's settings, files and HTTP transport. */
object CliRelayClient {
    private fun relay(context: Context) = RelayClient(
        settings = AndroidRelaySettings(context),
        transport = UrlConnectionHttpTransport,
        capabilities = RelayServerCapabilitiesClient.cache,
    )

    suspend fun healthCheck(context: Context): Result<String> = relay(context).healthCheck()

    suspend fun chat(
        context: Context,
        chatId: String,
        prompt: String,
        messages: List<Map<String, String>>,
        modelOverride: String? = null,
    ): Result<String> = relay(context).chat(chatId, prompt, messages, modelOverride)

    suspend fun voiceQuery(
        context: Context,
        prompt: String,
        backendOverride: CliRelayBackend? = null,
        modelOverride: String? = null,
    ): Result<String> = relay(context).voiceQuery(prompt, backendOverride, modelOverride)

    suspend fun voiceQueryDetailed(
        context: Context,
        prompt: String,
        backendOverride: CliRelayBackend? = null,
        modelOverride: String? = null,
    ): Result<VoiceQueryDetails> = relay(context).voiceQueryDetailed(prompt, backendOverride, modelOverride)

    suspend fun imageQuery(
        context: Context,
        imagePath: String,
        prompt: String? = null,
        backendOverride: CliRelayBackend? = null,
        modelOverride: String? = null,
    ): Result<String> = relay(context).imageQuery(imageMedia(imagePath), prompt, backendOverride, modelOverride)

    suspend fun audioQuery(
        context: Context,
        audioPath: String,
        prompt: String? = null,
        modelOverride: String? = null,
    ): Result<String> = relay(context).audioQuery(audioMedia(audioPath), prompt, modelOverride)

    internal suspend fun chatWithMedia(
        context: Context,
        chatId: String,
        userPrompt: String,
        messages: List<Map<String, String>>,
        imagePaths: List<String>,
        audioPath: String?,
        textModel: String?,
        mediaModel: String?,
    ): Result<String> = relay(context).chatWithMedia(
        chatId = chatId,
        userPrompt = userPrompt,
        messages = messages,
        images = imagePaths.map(::imageMedia),
        audio = audioPath?.takeIf { it.isNotBlank() }?.let(::audioMedia),
        textModel = textModel,
        mediaModel = mediaModel,
    )

    private fun imageMedia(imagePath: String): RelayMedia {
        val file = File(imagePath)
        return RelayMedia(file.name) {
            require(file.exists()) { "Image file not found: $imagePath" }
            require(file.length() > 1000) { "Image file too small (${file.length()} bytes), likely corrupted" }
            file.readBytes()
        }
    }

    private fun audioMedia(audioPath: String): RelayMedia {
        val file = File(audioPath)
        return RelayMedia(file.name) {
            require(file.exists()) { "Audio file not found: $audioPath" }
            require(file.length() > 44L) { "Audio file is empty or corrupted" }
            file.readBytes()
        }
    }
}

object AiAssistantRouter {
    interface ChatStreamCallbacks {
        fun onStatus(status: String) {}
        fun onToken(token: String) {}
    }

    private val localModelsProvider = LocalModelsProvider()

    suspend fun chatReplyStreaming(
        context: Context,
        chatId: String,
        userPrompt: String,
        messages: List<Map<String, String>>,
        imagePaths: List<String>,
        audioPath: String?,
        callbacks: ChatStreamCallbacks?,
    ): String {
        val effectiveProvider = if (imagePaths.isNotEmpty() || !audioPath.isNullOrBlank()) {
            MediaInferenceRoutingPolicy.resolve(context)
        } else {
            AutomationPrefs.getProviderType(context)
        }
        val providerType = AiProviderRouting.chatBackend(effectiveProvider) { AiProviderPrefs.getProvider(context) }

        return when (providerType) {
            AiProviderType.MOCK -> AiProviderReplies.mock(userPrompt)
            AiProviderType.COMPANY_BACKEND -> AiProviderReplies.COMPANY_BACKEND
            AiProviderType.CLI_RELAY -> {
                val isPro = effectiveProvider == AgentProviderType.PRO_SUBSCRIPTION
                val textModel = if (isPro) ProSubscriptionAiPrefs.getRequestsModel(context) else null
                val mediaModel = if (isPro) ProSubscriptionAiPrefs.getQuestionsModel(context) else textModel
                CliRelayClient.chatWithMedia(
                    context = context,
                    chatId = chatId,
                    userPrompt = userPrompt,
                    messages = messages,
                    imagePaths = imagePaths,
                    audioPath = audioPath,
                    textModel = textModel,
                    mediaModel = mediaModel,
                ).getOrElse { RelayErrorLocalizer.localizedMessage(context, it) }
            }

            AiProviderType.LOCAL_MODELS -> {
                localModelsProvider.streamChat(
                    context = context,
                    messages = messages,
                    onStatus = { callbacks?.onStatus(it) },
                    onToken = { callbacks?.onToken(it) },
                    imagePaths = imagePaths,
                    audioPath = audioPath,
                )
            }
        }
    }

    /**
     * Non-chat, single-shot text prompt helper.
     */
    suspend fun textReply(context: Context, prompt: String): String {
        val providerType = AiProviderPrefs.getProvider(context)

        return when (providerType) {
            AiProviderType.MOCK -> AiProviderReplies.mock(prompt)
            AiProviderType.COMPANY_BACKEND -> AiProviderReplies.COMPANY_BACKEND
            AiProviderType.CLI_RELAY -> {
                val modelOverride = if (AutomationPrefs.getProviderType(context) == AgentProviderType.PRO_SUBSCRIPTION) {
                    ProSubscriptionAiPrefs.getTasksModel(context)
                } else {
                    null
                }
                val result = CliRelayClient.voiceQuery(context, prompt, modelOverride = modelOverride)
                result.getOrElse { RelayErrorLocalizer.localizedMessage(context, it) }
            }

            AiProviderType.LOCAL_MODELS -> {
                localModelsProvider.streamChat(
                    context = context,
                    messages = listOf(mapOf("role" to "User", "content" to prompt)),
                )
            }
        }
    }

    suspend fun cancelCurrentGeneration(context: Context) {
        if (AiProviderPrefs.getProvider(context) == AiProviderType.LOCAL_MODELS) {
            localModelsProvider.cancelGeneration()
        }
    }
}
