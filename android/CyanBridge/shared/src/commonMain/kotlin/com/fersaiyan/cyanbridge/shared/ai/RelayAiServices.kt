package com.fersaiyan.cyanbridge.shared.ai

import com.fersaiyan.cyanbridge.shared.platform.PlatformHttpClient
import com.fersaiyan.cyanbridge.shared.platform.PlatformLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** The public CyanBridge relay (Android: AiProviderPrefs.DEFAULT_PUBLIC_RELAY_URL). */
const val DEFAULT_RELAY_URL = "https://cyanbridge.vercel.app"

private val relayJson = Json { ignoreUnknownKeys = true }
private val relayHeaders = mapOf("Content-Type" to "application/json; charset=UTF-8")

/** Chat through the relay: POST /chat. Errors come back as an "Error: …" reply. */
class RelayChatAiService(
    private val baseUrl: String = DEFAULT_RELAY_URL,
    private val httpClient: PlatformHttpClient = PlatformHttpClient(),
) : ChatAiService {
    override suspend fun chat(messages: List<ChatMessage>, model: String?): ChatResponse {
        val reply = try {
            val body = buildJsonObject {
                putJsonArray("messages") {
                    messages.forEach { message ->
                        addJsonObject {
                            put("role", message.role)
                            put("content", message.content)
                        }
                    }
                }
                put("prompt", messages.lastOrNull { it.role.equals("user", ignoreCase = true) }?.content.orEmpty())
                model?.trim()?.takeIf { it.isNotBlank() }?.let { put("model", it) }
            }
            val response = httpClient.post("$baseUrl/chat", body.toString(), relayHeaders)
            if (response.isSuccessful) {
                relayText(response.body, "reply", "response", "message")
            } else {
                PlatformLogger.e(TAG, "Chat request failed: ${response.statusCode}")
                "Error: Server returned ${response.statusCode}"
            }
        } catch (e: Exception) {
            PlatformLogger.e(TAG, "Chat request error", e)
            "Error: ${e.message ?: "Unknown error"}"
        }
        return ChatResponse(message = ChatMessage("assistant", reply))
    }

    private companion object {
        const val TAG = "RelayChatAi"
    }
}

/** Transcription through the relay: POST /transcribe. Empty text on failure. */
class RelayVoiceAiService(
    private val baseUrl: String = DEFAULT_RELAY_URL,
    private val httpClient: PlatformHttpClient = PlatformHttpClient(),
) : VoiceAiService {
    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun transcribe(audioData: ByteArray, mimeType: String): String = try {
        val body = buildJsonObject {
            put("audio", Base64.encode(audioData))
            put("mime_type", mimeType)
        }
        val response = httpClient.post("$baseUrl/transcribe", body.toString(), relayHeaders)
        if (response.isSuccessful) {
            relayText(response.body, "text", "transcript", "reply")
        } else {
            PlatformLogger.e(TAG, "Voice transcription failed: ${response.statusCode}")
            ""
        }
    } catch (e: Exception) {
        PlatformLogger.e(TAG, "Voice transcription error", e)
        ""
    }

    private companion object {
        const val TAG = "RelayVoiceAi"
    }
}

/** Image questions through the relay: POST /image-query. Errors come back as "Error: …". */
class RelayImageAiService(
    private val baseUrl: String = DEFAULT_RELAY_URL,
    private val httpClient: PlatformHttpClient = PlatformHttpClient(),
) : ImageAiService {
    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun analyzeImage(imageData: ByteArray, prompt: String, mimeType: String): String = try {
        val body = buildJsonObject {
            put("imageBase64", Base64.encode(imageData))
            put("filename", if (mimeType.equals("image/png", ignoreCase = true)) "image.png" else "image.jpg")
            put("prompt", prompt)
        }
        val response = httpClient.post("$baseUrl/image-query", body.toString(), relayHeaders)
        if (response.isSuccessful) {
            relayText(response.body, "reply", "response", "text")
        } else {
            PlatformLogger.e(TAG, "Image analysis failed: ${response.statusCode}")
            "Error: Server returned ${response.statusCode}"
        }
    } catch (e: Exception) {
        PlatformLogger.e(TAG, "Image analysis error", e)
        "Error: ${e.message ?: "Unknown error"}"
    }

    private companion object {
        const val TAG = "RelayImageAi"
    }
}

/** Models offered by the relay: GET /models, cached; the two relay defaults when it fails. */
class RelayAiModelRegistry(
    private val baseUrl: String = DEFAULT_RELAY_URL,
    private val httpClient: PlatformHttpClient = PlatformHttpClient(),
) : AiModelRegistry {
    private var cachedModels: List<AiModel>? = null

    override suspend fun listModels(): List<AiModel> {
        cachedModels?.let { return it }
        return try {
            val response = httpClient.get("$baseUrl/models")
            if (response.isSuccessful) {
                parseModels(response.body).also { cachedModels = it }
            } else {
                PlatformLogger.e(TAG, "Failed to fetch models: ${response.statusCode}")
                DEFAULT_MODELS
            }
        } catch (e: Exception) {
            PlatformLogger.e(TAG, "Failed to fetch models", e)
            DEFAULT_MODELS
        }
    }

    override fun getDefaultModelId(): String = "relay-chat"

    private fun parseModels(body: String): List<AiModel> {
        val models = mutableListOf<AiModel>()
        fun collect(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    val id = (element["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val name = (element["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (id != null && name != null) {
                        models += AiModel(id = id, name = name, provider = "cyanbridge")
                    } else {
                        element.values.forEach(::collect)
                    }
                }
                is JsonArray -> element.forEach(::collect)
                else -> Unit
            }
        }
        runCatching { collect(relayJson.parseToJsonElement(body)) }
        return models.ifEmpty { DEFAULT_MODELS }
    }

    private companion object {
        const val TAG = "RelayModelRegistry"
        val DEFAULT_MODELS = listOf(
            AiModel("relay-chat", "Relay Chat", "cyanbridge"),
            AiModel("relay-vision", "Relay Vision", "cyanbridge"),
        )
    }
}

/** First string field among [keys] in the relay's JSON reply; the raw body when there is none. */
internal fun relayText(body: String, vararg keys: String): String {
    val root = runCatching { relayJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return body
    return keys.firstNotNullOfOrNull { key ->
        (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    } ?: body
}
