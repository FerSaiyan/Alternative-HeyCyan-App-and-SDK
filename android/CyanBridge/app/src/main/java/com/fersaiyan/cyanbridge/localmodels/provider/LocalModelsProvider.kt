package com.fersaiyan.cyanbridge.localmodels.provider

import android.content.Context
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogRepository
import com.fersaiyan.cyanbridge.localmodels.session.LocalChatSessionManager
import com.fersaiyan.cyanbridge.localmodels.session.LocalModelLoadDetails
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelRuntime
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelProjectorStore
import com.fersaiyan.cyanbridge.localmodels.templates.PromptMessage
import com.fersaiyan.cyanbridge.localmodels.templates.PromptTemplateRegistry
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiClient
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class LocalModelRequestPriority {
    HIGH,
    LOW,
}

/** LiteRT accepts one prompt alongside media, so preserve all system instructions in that prompt. */
internal fun buildMultimodalPrompt(
    configuredSystemPrompt: String,
    messages: List<PromptMessage>,
): String {
    val systemInstructions = buildList {
        configuredSystemPrompt.trim().takeIf { it.isNotBlank() }?.let(::add)
        messages
            .filter { it.role.equals("system", ignoreCase = true) }
            .map { it.content.trim() }
            .filter { it.isNotBlank() }
            .forEach(::add)
    }.distinct()
    val userRequest = messages.lastOrNull { it.role.equals("user", ignoreCase = true) }
        ?.content
        ?: messages.lastOrNull()?.content.orEmpty()

    return buildString {
        if (systemInstructions.isNotEmpty()) {
            appendLine("System instructions:")
            appendLine(systemInstructions.joinToString("\n\n"))
            appendLine()
        }
        append("User request: ")
        append(userRequest)
    }
}

class LocalModelsProvider {
    companion object {
        const val STATUS_MAX_TOKENS_REACHED = "__MAX_TOKENS_REACHED__"
    }

    /**
     * Loads the currently selected local model without starting generation. Glasses voice/image
     * flows call this as soon as their foreground session begins so model initialization overlaps
     * image transfer, the listening cue, and the user's question instead of sitting on the critical
     * path after those steps finish.
     *
     * Returns null when the request would not use a local model (remote backend, no selection, or a
     * remote-only runtime). It deliberately does not run a warm-up generation because that could
     * occupy the generation mutex when the real user request arrives.
     */
    suspend fun prepareSelectedModel(
        context: Context,
        onStatus: ((String) -> Unit)? = null,
    ): LocalModelLoadDetails? {
        return withContext(Dispatchers.IO) {
            if (RemoteOpenAiPrefs.isActive(context)) return@withContext null

            LocalModelStorageRepository.cleanupMissingModels(context)
            val selected = LocalModelStorageRepository.resolveSelectedModel(context) ?: return@withContext null
            val catalogEntry = LocalModelCatalogRepository.findById(selected.catalogId)
            val settings = LocalModelSettingsRepository.getForModel(context, selected.id)
            if (settings.modelRuntime == LocalModelRuntime.REMOTE_OPENAI) return@withContext null

            onStatus?.invoke("Preparing ${selected.displayName}...")
            LocalChatSessionManager.ensureModelLoaded(
                context = context,
                model = selected,
                catalogEntry = catalogEntry,
                settings = settings,
            )
        }
    }

    suspend fun streamChat(
        context: Context,
        messages: List<Map<String, String>>,
        onStatus: ((String) -> Unit)? = null,
        onToken: ((String) -> Unit)? = null,
        imagePaths: List<String> = emptyList(),
        audioPath: String? = null,
        requestPriority: LocalModelRequestPriority = LocalModelRequestPriority.HIGH,
        maxTokens: Int? = null,
    ): String {
        return withContext(Dispatchers.IO) {
            // Check if remote OpenAI server is enabled.
            if (RemoteOpenAiPrefs.isActive(context)) {
                return@withContext streamChatRemote(
                    context = context,
                    messages = messages,
                    onStatus = onStatus,
                    onToken = onToken,
                    imagePaths = imagePaths,
                    audioPath = audioPath,
                    maxTokens = maxTokens,
                )
            }

            LocalModelStorageRepository.cleanupMissingModels(context)
            val hasMediaAttachments = imagePaths.isNotEmpty() || !audioPath.isNullOrBlank()
            val preferred = LocalModelStorageRepository.resolveSelectedModel(context)
            val selected = (if (hasMediaAttachments) {
                val candidates = LocalModelStorageRepository.listInstalled(context)
                fun mediaReady(model: com.fersaiyan.cyanbridge.localmodels.storage.InstalledLocalModel): Boolean {
                    val runtime = LocalModelSettingsRepository.getForModel(context, model.id).modelRuntime
                    return runtime == LocalModelRuntime.LITERT || (runtime == LocalModelRuntime.LLAMA_CPP && LocalModelProjectorStore.get(context, model) != null)
                }
                // Failed STT prefers a local LiteRT audio package. A GGUF with a projector can
                // consume the same audio when no LiteRT package is installed (native caps checked).
                if (!audioPath.isNullOrBlank()) {
                    preferred?.takeIf { LocalModelSettingsRepository.getForModel(context, it.id).modelRuntime == LocalModelRuntime.LITERT }
                        ?: candidates.firstOrNull { LocalModelSettingsRepository.getForModel(context, it.id).modelRuntime == LocalModelRuntime.LITERT }
                        ?: preferred?.takeIf(::mediaReady) ?: candidates.firstOrNull(::mediaReady)
                } else preferred?.takeIf(::mediaReady) ?: candidates.firstOrNull(::mediaReady)
            } else preferred)
                ?: throw IllegalStateException(
                    "No local model is installed. Open Configure Local Models to download or import a GGUF model first.",
                )

            val catalogEntry = LocalModelCatalogRepository.findById(selected.catalogId)
            val settings = LocalModelSettingsRepository.getForModel(context, selected.id)
            val templateId = settings.templateOverrideId
                ?: selected.promptTemplateId
                ?: catalogEntry?.promptTemplateId
                ?: "generic_chatml"

            val systemPrompt = buildString {
                val settingsSystem = settings.systemPromptOverride.trim()
                if (settingsSystem.isNotBlank()) {
                    append(settingsSystem)
                }
            }

            val chatMessages = messages
                .mapNotNull { m ->
                    val role = m["role"]?.trim().orEmpty()
                    val content = m["content"]?.trim().orEmpty()
                    if (role.isBlank() || content.isBlank()) null else PromptMessage(role = role, content = content)
                }

            // LiteRT accepts one prompt alongside image/audio attachments. Preserve system instructions
            // instead of dropping them when moving from the chat template to the media API.
            val effectivePrompt = if (hasMediaAttachments && settings.modelRuntime == LocalModelRuntime.LITERT) {
                buildMultimodalPrompt(systemPrompt, chatMessages)
            } else if (hasMediaAttachments) {
                val lastUser = chatMessages.indexOfLast { it.role.equals("user", true) }
                require(lastUser >= 0) { "Media request needs a user message" }
                val withMedia = chatMessages.mapIndexed { index, message ->
                    if (index == lastUser) message.copy(content = "<cyanbridge_media>\n${message.content}") else message
                }
                PromptTemplateRegistry.renderPrompt(templateId, systemPrompt, withMedia)
            } else {
                // For text-only, use the full template-rendered prompt
                PromptTemplateRegistry.renderPrompt(
                    templateId = templateId,
                    systemPrompt = systemPrompt,
                    messages = chatMessages,
                )
            }

            onStatus?.invoke("Loading ${selected.displayName}...")
            val loadDetails = LocalChatSessionManager.ensureModelLoaded(
                context = context,
                model = selected,
                catalogEntry = catalogEntry,
                settings = settings,
            )
            onStatus?.invoke(generationStatus(loadDetails.activeBackend))
            if (!loadDetails.fallbackReason.isNullOrBlank()) {
                if (loadDetails.activeBackend == LocalComputeBackend.CPU) {
                    onStatus?.invoke("GPU unavailable, using CPU")
                } else {
                    onStatus?.invoke("GPU active (audio/vision backend disabled)")
                }
            }

            val firstReply = LocalChatSessionManager.streamGenerate(
                settings = settings,
                prompt = effectivePrompt,
                onToken = { token -> onToken?.invoke(token) },
                imagePaths = imagePaths,
                audioPath = audioPath,
                requestPriority = requestPriority,
                maxTokensOverride = maxTokens,
            )

            val firstCapped = LocalChatSessionManager.consumeLastGenerationCappedFlag()
            if (firstCapped) {
                onStatus?.invoke(STATUS_MAX_TOKENS_REACHED)
            }

            if (firstReply.isNotBlank()) {
                return@withContext firstReply
            }

            onStatus?.invoke("Loading model and retrying...")
            runCatching { LocalChatSessionManager.unload() }

            onStatus?.invoke("Reloading ${selected.displayName}...")
            val reloadDetails = LocalChatSessionManager.ensureModelLoaded(
                context = context,
                model = selected,
                catalogEntry = catalogEntry,
                settings = settings,
            )
            onStatus?.invoke(generationStatus(reloadDetails.activeBackend))
            if (!reloadDetails.fallbackReason.isNullOrBlank()) {
                if (reloadDetails.activeBackend == LocalComputeBackend.CPU) {
                    onStatus?.invoke("GPU unavailable, using CPU")
                } else {
                    onStatus?.invoke("GPU active (audio/vision backend disabled)")
                }
            }

            val retryReply = LocalChatSessionManager.streamGenerate(
                settings = settings,
                prompt = effectivePrompt,
                onToken = { token -> onToken?.invoke(token) },
                imagePaths = imagePaths,
                audioPath = audioPath,
                requestPriority = requestPriority,
                maxTokensOverride = maxTokens,
            )

            val retryCapped = LocalChatSessionManager.consumeLastGenerationCappedFlag()
            if (retryCapped) {
                onStatus?.invoke(STATUS_MAX_TOKENS_REACHED)
            }

            if (retryReply.isNotBlank()) {
                retryReply
            } else {
                "I couldn't generate a reply yet. The local model was reloaded, please try once more."
            }
        }
    }

    /**
     * Routes the request to a remote OpenAI-compatible server (Ollama, llama.cpp, vLLM, etc.).
     */
    private suspend fun streamChatRemote(
        context: Context,
        messages: List<Map<String, String>>,
        onStatus: ((String) -> Unit)?,
        onToken: ((String) -> Unit)?,
        imagePaths: List<String>,
        audioPath: String?,
        maxTokens: Int?,
    ): String {
        val model = RemoteOpenAiPrefs.getModel(context)
        val baseUrl = RemoteOpenAiPrefs.getBaseUrl(context)
        onStatus?.invoke("Remote: $model @ ${baseUrl.substringBefore("/v1")}")

        return try {
            RemoteOpenAiClient.chatCompletionStreaming(
                context = context,
                messages = messages,
                maxTokens = maxTokens ?: 2048,
                onToken = onToken,
                imagePaths = imagePaths,
                audioPath = audioPath,
            )
        } catch (e: Exception) {
            onStatus?.invoke("Remote error: ${e.message}")
            throw e
        }
    }

    suspend fun cancelGeneration() {
        LocalChatSessionManager.cancelActiveGeneration()
    }

    private fun generationStatus(backend: LocalComputeBackend): String {
        return when (backend) {
            LocalComputeBackend.NPU_EXPERIMENTAL -> "Generating (NPU)..."
            LocalComputeBackend.GPU -> "Generating (GPU)..."
            LocalComputeBackend.CPU -> "Generating (CPU)..."
        }
    }
}
