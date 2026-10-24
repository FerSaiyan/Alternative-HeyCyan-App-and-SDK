package com.fersaiyan.cyanbridge.shared.platform

import com.fersaiyan.cyanbridge.shared.ai.ChatAiService
import com.fersaiyan.cyanbridge.shared.ai.ChatMessage
import com.fersaiyan.cyanbridge.shared.ai.ChatResponse
import com.fersaiyan.cyanbridge.shared.ai.ImageAiService
import com.fersaiyan.cyanbridge.shared.ai.OpenAiCompatible
import com.fersaiyan.cyanbridge.shared.ai.VoiceAiService
import com.fersaiyan.cyanbridge.shared.localmodels.RemoteInferenceUiState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocale
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.preferredLanguages
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.Speech.SFSpeechURLRecognitionRequest
import kotlin.coroutines.resume
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Remote OpenAI-compatible server behind the "Local" AI provider (Android:
 * RemoteOpenAiPrefs): Ollama, LM Studio, llama.cpp server, vLLM, OpenRouter...
 * The API key lives in the Keychain. When enabled it takes priority over
 * on-device models, matching Android's remote toggle.
 */
object IosRemoteModelSettings {
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_MODEL = "model"
    private const val KEYCHAIN_API_KEY = "remote_openai_api_key"

    private val preferences = createPlatformPreferences("cyanbridge_remote_openai")

    val enabled: Boolean get() = preferences.getBoolean(KEY_ENABLED, false)
    val baseUrl: String get() = preferences.getString(KEY_BASE_URL, "").trim()
    val model: String get() = preferences.getString(KEY_MODEL, "").trim()
    val apiKey: String
        get() = IosSecurityRegistry.bridge?.keychainGet(KEYCHAIN_API_KEY)?.toKotlinBytes()?.decodeToString().orEmpty()

    /** True when chats should go to the configured server. */
    val isActive: Boolean get() = enabled && baseUrl.isNotBlank() && model.isNotBlank()

    fun savedDraft(status: String = "") = RemoteInferenceUiState(
        enabled = enabled,
        baseUrl = baseUrl,
        modelName = model,
        apiKey = apiKey,
        status = status,
    )

    /** Saves the draft and returns the status line to show. */
    fun save(draft: RemoteInferenceUiState): String {
        val problem = IosRemoteOpenAiClient.validate(draft.baseUrl, draft.apiKey)
            ?: if (draft.enabled && draft.modelName.isBlank()) "Enter the model name (for example llama3.2)." else null
        if (problem != null && (draft.enabled || draft.baseUrl.isNotBlank())) return problem
        preferences.putBoolean(KEY_ENABLED, draft.enabled)
        preferences.putString(KEY_BASE_URL, draft.baseUrl.trim())
        preferences.putString(KEY_MODEL, draft.modelName.trim())
        IosSecurityRegistry.bridge?.let { bridge ->
            if (draft.apiKey.isBlank()) {
                bridge.keychainDelete(KEYCHAIN_API_KEY)
            } else {
                bridge.keychainSet(KEYCHAIN_API_KEY, draft.apiKey.encodeToByteArray().toNSData())
            }
        }
        PlatformLogger.i(TAG, "Remote model server saved (enabled=${draft.enabled}, model=${draft.modelName.trim()})")
        return if (draft.enabled) "Saved. Chats, image questions and plugins now use this server." else "Saved (disabled)."
    }

    private const val TAG = "IosRemoteModel"
}

/** Minimal OpenAI chat-completions client (Android: RemoteOpenAiClient). */
internal object IosRemoteOpenAiClient {
    private val httpClient = PlatformHttpClient()

    /** Returns a user-facing problem with the URL/key pair, or null when it is usable. */
    fun validate(baseUrl: String, apiKey: String): String? {
        val clean = baseUrl.trim().trimEnd('/')
        if (clean.isEmpty()) return "Enter the server base URL (for example http://192.168.1.20:11434)."
        val scheme = clean.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return "The base URL must start with http:// or https://"
        val host = clean.substringAfter("://").substringBefore('/').substringBefore(':')
        if (host.isBlank()) return "The base URL must include a host."
        if ('?' in clean || '#' in clean) return "The base URL must not include a query string or fragment."
        if (apiKey.isNotBlank() && scheme == "http" && !isPrivateHost(host)) {
            return "Refusing to send an API key over plain http to a public server. Use https://"
        }
        return null
    }

    suspend fun healthCheck(baseUrl: String, apiKey: String): String {
        validate(baseUrl, apiKey)?.let { return it }
        return runCatching {
            val response = httpClient.get(OpenAiCompatible.modelsUrl(baseUrl), authHeaders(apiKey))
            OpenAiCompatible.healthSummary(response.statusCode, response.body)
        }.getOrElse { "Unreachable: ${it.message}" }
    }

    /** Same request and reply handling as Android's RemoteOpenAiClient (see [OpenAiCompatible]). */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun chat(messages: List<ChatMessage>, image: ByteArray? = null, imageMimeType: String = "image/jpeg"): String {
        val baseUrl = IosRemoteModelSettings.baseUrl
        val apiKey = IosRemoteModelSettings.apiKey
        validate(baseUrl, apiKey)?.let { error(it) }
        val payload = OpenAiCompatible.buildChatCompletionPayload(
            model = IosRemoteModelSettings.model,
            messages = messages.map { mapOf("role" to it.role, "content" to it.content) },
            maxTokens = 2048,
            temperature = 0.7,
            images = listOfNotNull(image?.let { bytes -> { OpenAiCompatible.EncodedAttachment(Base64.encode(bytes), imageMimeType) } }),
        )
        val url = OpenAiCompatible.chatCompletionsUrl(baseUrl)
        PlatformLogger.i(TAG, "chat -> $url model=${IosRemoteModelSettings.model} image=${image != null}")
        val response = httpClient.post(
            url,
            payload.toString(),
            authHeaders(apiKey) + ("Content-Type" to "application/json; charset=utf-8"),
        )
        check(response.isSuccessful) { "Remote server HTTP ${response.statusCode}: ${response.body.take(500)}" }
        return OpenAiCompatible.parseChatCompletion(response.body)
    }

    private fun authHeaders(apiKey: String): Map<String, String> =
        if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${apiKey.trim()}")

    private fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h.endsWith(".local") || !h.contains('.')) return true
        val octets = h.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return false
        return octets[0] == 10 || octets[0] == 127 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 100 && octets[1] in 64..127)
    }

    private const val TAG = "IosRemoteOpenAi"
}

/** Speech-to-text on the iPhone for recorded audio files (no relay account needed). */
@OptIn(ExperimentalForeignApi::class)
internal object IosOnDeviceTranscriber {
    suspend fun transcribe(audio: ByteArray, mimeType: String): String {
        if (audio.isEmpty() || !requestAuthorization()) return ""
        val extension = when {
            mimeType.contains("wav", ignoreCase = true) -> "wav"
            mimeType.contains("aac", ignoreCase = true) -> "aac"
            else -> "m4a"
        }
        val path = "${NSTemporaryDirectory()}cyanbridge-transcribe.$extension"
        if (!IosMediaPlatform.writeFile(path, audio)) return ""
        val language = (NSLocale.preferredLanguages.firstOrNull() as? String) ?: "en-US"
        val recognizer = SFSpeechRecognizer(locale = NSLocale(localeIdentifier = language))
        if (!recognizer.isAvailable()) return ""
        val request = SFSpeechURLRecognitionRequest(uRL = NSURL.fileURLWithPath(path))
        if (recognizer.supportsOnDeviceRecognition) request.requiresOnDeviceRecognition = true
        val text = withTimeoutOrNull(120_000L) {
            suspendCancellableCoroutine { continuation ->
                val task = recognizer.recognitionTaskWithRequest(request) { result, error ->
                    if (!continuation.isActive) return@recognitionTaskWithRequest
                    when {
                        result?.isFinal() == true -> continuation.resume(result.bestTranscription.formattedString)
                        error != null -> {
                            PlatformLogger.w(TAG, "On-device transcription failed: ${error.localizedDescription}")
                            continuation.resume(result?.bestTranscription?.formattedString.orEmpty())
                        }
                    }
                }
                continuation.invokeOnCancellation { task.cancel() }
            }
        }.orEmpty()
        NSFileManager.defaultManager.removeItemAtPath(path, null)
        return text.trim()
    }

    private suspend fun requestAuthorization(): Boolean = suspendCancellableCoroutine { continuation ->
        SFSpeechRecognizer.requestAuthorization { status ->
            if (continuation.isActive) {
                continuation.resume(status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized)
            }
        }
    }

    private const val TAG = "IosTranscriber"
}

/** Routing order (Android: AiProviderRouter): remote server, then on-device model, then the relay. */
internal class IosRoutedChatAiService(private val relay: ChatAiService) : ChatAiService {
    override suspend fun chat(messages: List<ChatMessage>, model: String?): ChatResponse {
        val reply = when {
            IosRemoteModelSettings.isActive -> runCatching { IosRemoteOpenAiClient.chat(messages) }
                .getOrElse { "Error: ${it.message ?: "remote server failed"}" }
            IosLocalModels.isActive -> runCatching { IosLocalModels.chat(messages) }
                .getOrElse { "Error: ${it.message ?: "on-device model failed"}" }
            else -> return relay.chat(messages, model)
        }
        return ChatResponse(ChatMessage("assistant", reply))
    }
}

internal class IosRoutedImageAiService(private val relay: ImageAiService) : ImageAiService {
    override suspend fun analyzeImage(imageData: ByteArray, prompt: String, mimeType: String): String {
        if (!IosRemoteModelSettings.isActive) {
            if (IosLocalModels.isActive) {
                return runCatching { IosLocalModels.describeImage(imageData, prompt) }
                    .getOrElse { "Error: ${it.message ?: "on-device model failed"}" }
            }
            return relay.analyzeImage(imageData, prompt, mimeType)
        }
        return runCatching { IosRemoteOpenAiClient.chat(listOf(ChatMessage("user", prompt)), imageData, mimeType) }
            .getOrElse { "Error: ${it.message ?: "remote server failed"}" }
    }
}

/** Custom provider: transcribe on the iPhone. Relay: fall back to the iPhone when it fails. */
internal class IosRoutedVoiceAiService(private val relay: VoiceAiService) : VoiceAiService {
    override suspend fun transcribe(audioData: ByteArray, mimeType: String): String {
        if (IosRemoteModelSettings.isActive || IosLocalModels.isActive) {
            return IosOnDeviceTranscriber.transcribe(audioData, mimeType)
        }
        return relay.transcribe(audioData, mimeType).ifBlank { IosOnDeviceTranscriber.transcribe(audioData, mimeType) }
    }
}
