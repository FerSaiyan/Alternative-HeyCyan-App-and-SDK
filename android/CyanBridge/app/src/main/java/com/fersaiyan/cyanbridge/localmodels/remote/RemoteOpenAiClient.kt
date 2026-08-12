package com.fersaiyan.cyanbridge.localmodels.remote

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Base64
import java.util.Locale

/**
 * Lightweight client for OpenAI-compatible chat/completions endpoints.
 *
 * Supports both non-streaming and streaming (SSE) responses.
 * Works with Ollama (/v1/chat/completions), llama.cpp server, vLLM, TGI
 * with the OpenAI compatibility layer, and any other server that speaks
 * the same protocol.
 */
object RemoteOpenAiClient {
    private const val TAG = "RemoteOpenAiClient"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 120_000

    /**
     * Non-streaming chat completion.
     */
    suspend fun chatCompletion(
        context: Context,
        messages: List<Map<String, String>>,
        maxTokens: Int = 2048,
        temperature: Double = 0.7,
        imagePaths: List<String> = emptyList(),
        audioPath: String? = null,
    ): String {
        val baseUrl = RemoteOpenAiPrefs.getBaseUrl(context)
        val apiKey = RemoteOpenAiPrefs.getApiKey(context)
        val model = RemoteOpenAiPrefs.getModel(context)

        require(baseUrl.isNotBlank()) { "Remote server base URL is not configured" }
        require(model.isNotBlank()) { "Remote server model name is not configured" }
        require(apiKey.isBlank() || RemoteOpenAiPrefs.isCredentialTransportAllowed(baseUrl)) {
            "Refusing to send an API key over a public cleartext URL"
        }

        val payload = buildChatCompletionPayload(
            model = model,
            messages = messages,
            maxTokens = maxTokens,
            temperature = temperature,
            imagePaths = imagePaths,
            audioPath = audioPath,
        )

        val url = buildChatCompletionsUrl(baseUrl)
        Log.i(TAG, "chatCompletion -> $url model=$model")

        return postJson(url, apiKey, payload)
            .let { response ->
                val choices = response.optJSONArray("choices")
                    ?: throw IllegalStateException("No choices in remote response")
                if (choices.length() == 0) throw IllegalStateException("Empty choices array")
                val message = choices.getJSONObject(0).optJSONObject("message")
                message?.optString("content")?.trim()?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Empty content in remote response")
            }
    }

    /**
     * Streaming chat completion. Calls [onToken] for each chunk of text as it arrives.
     * Returns the full assembled response.
     */
    suspend fun chatCompletionStreaming(
        context: Context,
        messages: List<Map<String, String>>,
        maxTokens: Int = 2048,
        temperature: Double = 0.7,
        onToken: ((String) -> Unit)? = null,
        imagePaths: List<String> = emptyList(),
        audioPath: String? = null,
    ): String {
        val baseUrl = RemoteOpenAiPrefs.getBaseUrl(context)
        val apiKey = RemoteOpenAiPrefs.getApiKey(context)
        val model = RemoteOpenAiPrefs.getModel(context)

        require(baseUrl.isNotBlank()) { "Remote server base URL is not configured" }
        require(model.isNotBlank()) { "Remote server model name is not configured" }
        require(apiKey.isBlank() || RemoteOpenAiPrefs.isCredentialTransportAllowed(baseUrl)) {
            "Refusing to send an API key over a public cleartext URL"
        }

        val payload = buildChatCompletionPayload(
            model = model,
            messages = messages,
            maxTokens = maxTokens,
            temperature = temperature,
            stream = true,
            imagePaths = imagePaths,
            audioPath = audioPath,
        )

        val url = buildChatCompletionsUrl(baseUrl)
        Log.i(TAG, "chatCompletionStreaming -> $url model=$model")

        return postJsonStreaming(url, apiKey, payload, onToken)
    }

    /**
     * Health check: tries to reach the server and list models.
     * Returns a human-readable status string.
     */
    suspend fun healthCheck(context: Context): String {
        val baseUrl = RemoteOpenAiPrefs.getBaseUrl(context)
        if (baseUrl.isBlank()) return "No base URL configured"

        return try {
            val modelsUrl = buildModelsUrl(baseUrl)
            val conn = (URL(modelsUrl).openConnection() as HttpURLConnection)
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/json")
            val apiKey = RemoteOpenAiPrefs.getApiKey(context)
            if (apiKey.isNotBlank() && !RemoteOpenAiPrefs.isCredentialTransportAllowed(baseUrl)) {
                return "Refusing public cleartext transport for API key"
            }
            if (apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            val code = conn.responseCode
            val body = BufferedReader(InputStreamReader(
                if (code in 200..299) conn.inputStream else conn.errorStream
            )).use { it.readText() }
            conn.disconnect()

            if (code !in 200..299) {
                "HTTP $code: ${body.take(200)}"
            } else {
                val obj = runCatching { JSONObject(body) }.getOrNull()
                val models = obj?.optJSONArray("data")
                val count = models?.length() ?: 0
                if (count > 0) {
                    val names = (0 until count).mapNotNull { i ->
                        models?.optJSONObject(i)?.optString("id")
                    }.take(5).joinToString(", ")
                    "OK ($count models: $names)"
                } else {
                    "OK (server reachable)"
                }
            }
        } catch (e: Exception) {
            "Unreachable: ${e.message}"
        }
    }

    internal fun buildChatCompletionsUrl(baseUrl: String): String {
        val (clean, path) = normalizeBaseUrl(baseUrl)
        return when {
            clean.endsWith("/chat/completions") -> clean
            clean.endsWith("/v1") -> "$clean/chat/completions"
            path.isBlank() || path == "/" -> "$clean/v1/chat/completions"
            else -> "$clean/chat/completions"
        }
    }

    internal fun buildModelsUrl(baseUrl: String): String {
        val (normalized, _) = normalizeBaseUrl(baseUrl)
        val clean = normalized.removeSuffix("/chat/completions")
        val path = URI(clean).path.orEmpty()
        return when {
            clean.endsWith("/v1") -> "$clean/models"
            path.isBlank() || path == "/" -> "$clean/v1/models"
            else -> "$clean/models"
        }
    }

    private fun normalizeBaseUrl(baseUrl: String): Pair<String, String> {
        val clean = baseUrl.trim().trimEnd('/')
        val uri = runCatching { URI(clean) }.getOrElse {
            throw IllegalArgumentException("Remote server base URL is invalid", it)
        }
        require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
            "Remote server base URL must use http:// or https://"
        }
        require(!uri.host.isNullOrBlank()) { "Remote server base URL must include a host" }
        require(uri.rawQuery == null && uri.rawFragment == null) {
            "Remote server base URL must not include a query string or fragment"
        }
        return clean to uri.path.orEmpty()
    }

    /**
     * Builds the OpenAI chat-completions payload. Media is encoded as the
     * protocol's multimodal content parts on the final user message instead
     * of being silently discarded by remote routing.
     */
    internal fun buildChatCompletionPayload(
        model: String,
        messages: List<Map<String, String>>,
        maxTokens: Int,
        temperature: Double,
        stream: Boolean = false,
        imagePaths: List<String> = emptyList(),
        audioPath: String? = null,
    ): JSONObject {
        require(model.isNotBlank()) { "Remote server model name is not configured" }
        require(maxTokens > 0) { "maxTokens must be greater than zero" }

        val hasAudio = !audioPath.isNullOrBlank()
        val hasMedia = imagePaths.isNotEmpty() || hasAudio
        val userMessageIndex = messages.indexOfLast {
            it["role"]?.trim()?.lowercase(Locale.US).let { role ->
                role.isNullOrBlank() || role == "user"
            }
        }
        if (hasMedia && userMessageIndex < 0) {
            throw IllegalArgumentException("Media attachments require at least one user message")
        }

        val messagesArray = JSONArray()
        messages.forEachIndexed { index, message ->
            val role = message["role"]?.trim()?.lowercase(Locale.US).orEmpty().ifBlank { "user" }
            val text = message["content"].orEmpty()
            val jsonMessage = JSONObject().put("role", role)
            if (index != userMessageIndex || !hasMedia) {
                jsonMessage.put("content", text)
            } else {
                val contentParts = JSONArray()
                if (text.isNotBlank()) {
                    contentParts.put(JSONObject().put("type", "text").put("text", text))
                }
                imagePaths.forEach { path ->
                    val image = readAttachment(File(requireAttachmentPath(path, "image")), "image")
                    contentParts.put(
                        JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", "data:${image.mimeType};base64,${image.base64}")),
                    )
                }
                if (hasAudio) {
                    val audioFile = File(requireAttachmentPath(audioPath.orEmpty(), "audio"))
                    val audio = readAttachment(audioFile, "audio")
                    contentParts.put(
                        JSONObject()
                            .put("type", "input_audio")
                            .put(
                                "input_audio",
                                JSONObject()
                                    .put("data", audio.base64)
                                    .put("format", audio.mimeType),
                            ),
                    )
                }
                jsonMessage.put("content", contentParts)
            }
            messagesArray.put(jsonMessage)
        }

        return JSONObject()
            .put("model", model.trim())
            .put("messages", messagesArray)
            .put("max_tokens", maxTokens)
            .put("temperature", temperature)
            .apply {
                if (stream) put("stream", true)
            }
    }

    private data class EncodedAttachment(
        val base64: String,
        val mimeType: String,
    )

    private fun requireAttachmentPath(path: String, kind: String): String {
        return path.trim().takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("$kind attachment path is blank")
    }

    private fun readAttachment(file: File, kind: String): EncodedAttachment {
        require(file.isFile && file.canRead()) {
            "Cannot read $kind attachment: ${file.path}"
        }
        require(file.length() > 0L) {
            "$kind attachment is empty: ${file.path}"
        }

        val extension = file.extension.lowercase(Locale.US)
        val mimeType = when (kind) {
            "image" -> when (extension) {
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
            "audio" -> when (extension) {
                "wav", "wave" -> "wav"
                "mp3", "mpeg" -> "mp3"
                else -> throw IllegalArgumentException(
                    "Unsupported remote audio format '.$extension'. OpenAI-compatible chat audio requires WAV or MP3.",
                )
            }
            else -> error("Unknown attachment kind: $kind")
        }

        return EncodedAttachment(
            base64 = Base64.getEncoder().encodeToString(file.readBytes()),
            mimeType = mimeType,
        )
    }

    private fun postJson(url: String, apiKey: String, payload: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (apiKey.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = BufferedReader(InputStreamReader(stream ?: conn.inputStream)).use { it.readText() }
        conn.disconnect()
        if (code !in 200..299) {
            throw IllegalStateException("Remote server HTTP $code: ${body.take(500)}")
        }
        return JSONObject(body)
    }

    /**
     * Streaming POST: reads SSE lines (`data: {...}`) and extracts content deltas.
     */
    private fun postJsonStreaming(
        url: String,
        apiKey: String,
        payload: JSONObject,
        onToken: ((String) -> Unit)?,
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Accept", "text/event-stream")
        if (apiKey.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }

        val code = conn.responseCode
        if (code !in 200..299) {
            val errBody = BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream))
                .use { it.readText() }
            conn.disconnect()
            throw IllegalStateException("Remote server HTTP $code: ${errBody.take(500)}")
        }

        val result = StringBuilder()
        BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data:")) continue
                val data = l.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isBlank()) continue

                val chunk = runCatching {
                    val obj = JSONObject(data)
                    val choices = obj.optJSONArray("choices") ?: return@runCatching ""
                    val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: return@runCatching ""
                    delta.optString("content", "")
                }.getOrDefault("")

                if (chunk.isNotBlank()) {
                    result.append(chunk)
                    onToken?.invoke(chunk)
                }
            }
        }
        conn.disconnect()
        return result.toString()
    }
}
