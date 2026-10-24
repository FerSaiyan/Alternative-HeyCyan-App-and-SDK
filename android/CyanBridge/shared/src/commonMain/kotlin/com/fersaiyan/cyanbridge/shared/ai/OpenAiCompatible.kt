package com.fersaiyan.cyanbridge.shared.ai

import com.fersaiyan.cyanbridge.shared.json.optJsonArray
import com.fersaiyan.cyanbridge.shared.json.optJsonObject
import com.fersaiyan.cyanbridge.shared.json.optString
import com.fersaiyan.cyanbridge.shared.json.parseJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The OpenAI chat-completions protocol as spoken to Ollama, llama.cpp server, vLLM,
 * LM Studio and similar servers: URLs, request payload and reply parsing. The
 * platforms validate the base URL, read attachments and do the HTTP.
 */
object OpenAiCompatible {
    /** An attachment ready to send: base64 data plus the MIME type (image) or format (audio). */
    data class EncodedAttachment(val base64: String, val mimeType: String)

    /** One line of a streamed (SSE) reply. */
    sealed interface StreamLine {
        data object Skip : StreamLine
        data object Done : StreamLine
        data class Token(val text: String) : StreamLine
    }

    /** [baseUrl] must already be validated; a bare host gets the usual /v1 prefix. */
    fun chatCompletionsUrl(baseUrl: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        return when {
            clean.endsWith("/chat/completions") -> clean
            clean.endsWith("/v1") -> "$clean/chat/completions"
            pathOf(clean).isBlank() -> "$clean/v1/chat/completions"
            else -> "$clean/chat/completions"
        }
    }

    fun modelsUrl(baseUrl: String): String {
        val clean = baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions")
        return when {
            clean.endsWith("/v1") -> "$clean/models"
            pathOf(clean).isBlank() -> "$clean/v1/models"
            else -> "$clean/models"
        }
    }

    private fun pathOf(url: String): String =
        url.substringAfter("://").substringAfter('/', "").trimEnd('/')

    /** MIME type for a lowercase image file extension; throws for formats servers do not take. */
    fun imageMimeType(extension: String): String = when (extension) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        else -> throw IllegalArgumentException(
            "Unsupported image format '.$extension'. Use JPEG, PNG, WebP, GIF, BMP, or HEIC.",
        )
    }

    /** input_audio format for a lowercase audio file extension: only WAV and MP3 are accepted. */
    fun audioFormat(extension: String): String = when (extension) {
        "wav", "wave" -> "wav"
        "mp3", "mpeg" -> "mp3"
        else -> throw IllegalArgumentException(
            "Unsupported remote audio format '.$extension'. OpenAI-compatible chat audio requires WAV or MP3.",
        )
    }

    /**
     * The chat-completions payload. Media goes as multimodal content parts on the last
     * user message (or the last message without a role); attachments are loaded while
     * that message is built, so their errors surface in the same order as before.
     */
    fun buildChatCompletionPayload(
        model: String,
        messages: List<Map<String, String>>,
        maxTokens: Int,
        temperature: Double,
        stream: Boolean = false,
        images: List<() -> EncodedAttachment> = emptyList(),
        audio: (() -> EncodedAttachment)? = null,
    ): JsonObject {
        require(model.isNotBlank()) { "Remote server model name is not configured" }
        require(maxTokens > 0) { "maxTokens must be greater than zero" }

        val hasMedia = images.isNotEmpty() || audio != null
        val userMessageIndex = messages.indexOfLast {
            it["role"]?.trim()?.lowercase().let { role -> role.isNullOrBlank() || role == "user" }
        }
        if (hasMedia && userMessageIndex < 0) {
            throw IllegalArgumentException("Media attachments require at least one user message")
        }

        return buildJsonObject {
            put("model", model.trim())
            putJsonArray("messages") {
                messages.forEachIndexed { index, message ->
                    val role = message["role"]?.trim()?.lowercase().orEmpty().ifBlank { "user" }
                    val text = message["content"].orEmpty()
                    addJsonObject {
                        put("role", role)
                        if (index != userMessageIndex || !hasMedia) {
                            put("content", text)
                        } else {
                            putJsonArray("content") {
                                if (text.isNotBlank()) {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", text)
                                    }
                                }
                                images.forEach { load ->
                                    val image = load()
                                    addJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") { put("url", "data:${image.mimeType};base64,${image.base64}") }
                                    }
                                }
                                if (audio != null) {
                                    val encoded = audio()
                                    addJsonObject {
                                        put("type", "input_audio")
                                        putJsonObject("input_audio") {
                                            put("data", encoded.base64)
                                            put("format", encoded.mimeType)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            if (stream) put("stream", true)
        }
    }

    /** The reply text of a non-streamed completion; throws when there is none. */
    fun parseChatCompletion(body: String): String {
        val choices = parseJsonObject(body).optJsonArray("choices")
            ?: throw IllegalStateException("No choices in remote response")
        if (choices.isEmpty()) throw IllegalStateException("Empty choices array")
        val first = choices[0] as? JsonObject ?: throw IllegalStateException("Empty content in remote response")
        return first.optJsonObject("message")?.optString("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Empty content in remote response")
    }

    /** One SSE line (`data: {...}`) of a streamed completion. */
    fun parseStreamLine(line: String): StreamLine {
        if (!line.startsWith("data:")) return StreamLine.Skip
        val data = line.removePrefix("data:").trim()
        if (data == "[DONE]") return StreamLine.Done
        if (data.isBlank()) return StreamLine.Skip
        val chunk = runCatching {
            val delta = (parseJsonObject(data).optJsonArray("choices")?.getOrNull(0) as? JsonObject)
                ?.optJsonObject("delta")
                ?: return@runCatching ""
            delta.optString("content", "")
        }.getOrDefault("")
        return if (chunk.isNotBlank()) StreamLine.Token(chunk) else StreamLine.Skip
    }

    /** The status line for a GET /models probe. */
    fun healthSummary(statusCode: Int, body: String): String {
        if (statusCode !in 200..299) return "HTTP $statusCode: ${body.take(200)}"
        val models = runCatching { parseJsonObject(body) }.getOrNull()?.optJsonArray("data")
        val count = models?.size ?: 0
        if (count == 0) return "OK (server reachable)"
        val names = (0 until count).mapNotNull { i -> (models?.get(i) as? JsonObject)?.optString("id") }
            .take(5)
            .joinToString(", ")
        return "OK ($count models: $names)"
    }
}
