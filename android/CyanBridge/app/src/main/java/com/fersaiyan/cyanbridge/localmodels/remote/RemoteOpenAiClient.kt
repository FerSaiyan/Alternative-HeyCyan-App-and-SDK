package com.fersaiyan.cyanbridge.localmodels.remote

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.ai.router.UrlConnectionHttpTransport
import com.fersaiyan.cyanbridge.shared.ai.OpenAiCompatible
import com.fersaiyan.cyanbridge.shared.ai.OpenAiCompatible.EncodedAttachment
import com.fersaiyan.cyanbridge.shared.ai.OpenAiCompatible.StreamLine
import com.fersaiyan.cyanbridge.shared.platform.HttpRequest
import kotlinx.serialization.json.JsonObject
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
 * Android side of the OpenAI-compatible client ([OpenAiCompatible]): settings,
 * attachments from files, and HTTP including the streamed (SSE) reply.
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
        val (url, apiKey, payload) = prepare(context, messages, maxTokens, temperature, false, imagePaths, audioPath)
        Log.i(TAG, "chatCompletion -> $url model=${RemoteOpenAiPrefs.getModel(context)}")

        val response = UrlConnectionHttpTransport.execute(
            HttpRequest(
                method = "POST",
                url = url,
                headers = jsonHeaders(apiKey),
                body = payload.toString(),
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
            ),
        )
        if (!response.isSuccessful) {
            throw IllegalStateException("Remote server HTTP ${response.statusCode}: ${response.body.take(500)}")
        }
        return OpenAiCompatible.parseChatCompletion(response.body)
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
        val (url, apiKey, payload) = prepare(context, messages, maxTokens, temperature, true, imagePaths, audioPath)
        Log.i(TAG, "chatCompletionStreaming -> $url model=${RemoteOpenAiPrefs.getModel(context)}")

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
            val apiKey = RemoteOpenAiPrefs.getApiKey(context)
            if (apiKey.isNotBlank() && !RemoteOpenAiPrefs.isCredentialTransportAllowed(baseUrl)) {
                return "Refusing public cleartext transport for API key"
            }
            val headers = buildMap {
                put("Accept", "application/json")
                if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
            }
            val response = UrlConnectionHttpTransport.execute(
                HttpRequest("GET", modelsUrl, headers, connectTimeoutMs = CONNECT_TIMEOUT_MS, readTimeoutMs = 10_000),
            )
            OpenAiCompatible.healthSummary(response.statusCode, response.body)
        } catch (e: Exception) {
            "Unreachable: ${e.message}"
        }
    }

    internal fun buildChatCompletionsUrl(baseUrl: String): String =
        OpenAiCompatible.chatCompletionsUrl(normalizeBaseUrl(baseUrl))

    internal fun buildModelsUrl(baseUrl: String): String =
        OpenAiCompatible.modelsUrl(normalizeBaseUrl(baseUrl))

    private fun normalizeBaseUrl(baseUrl: String): String {
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
        return clean
    }

    private data class Prepared(val url: String, val apiKey: String, val payload: JsonObject)

    private fun prepare(
        context: Context,
        messages: List<Map<String, String>>,
        maxTokens: Int,
        temperature: Double,
        stream: Boolean,
        imagePaths: List<String>,
        audioPath: String?,
    ): Prepared {
        val baseUrl = RemoteOpenAiPrefs.getBaseUrl(context)
        val apiKey = RemoteOpenAiPrefs.getApiKey(context)
        val model = RemoteOpenAiPrefs.getModel(context)

        require(baseUrl.isNotBlank()) { "Remote server base URL is not configured" }
        require(model.isNotBlank()) { "Remote server model name is not configured" }
        require(apiKey.isBlank() || RemoteOpenAiPrefs.isCredentialTransportAllowed(baseUrl)) {
            "Refusing to send an API key over a public cleartext URL"
        }

        val payload = buildChatCompletionPayload(model, messages, maxTokens, temperature, stream, imagePaths, audioPath)
        return Prepared(buildChatCompletionsUrl(baseUrl), apiKey, payload)
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
    ): JsonObject = OpenAiCompatible.buildChatCompletionPayload(
        model = model,
        messages = messages,
        maxTokens = maxTokens,
        temperature = temperature,
        stream = stream,
        images = imagePaths.map { path -> { readAttachment(path, "image") } },
        audio = audioPath?.takeIf { it.isNotBlank() }?.let { path -> { readAttachment(path, "audio") } },
    )

    private fun readAttachment(path: String, kind: String): EncodedAttachment {
        val file = File(path.trim().takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("$kind attachment path is blank"))
        require(file.isFile && file.canRead()) {
            "Cannot read $kind attachment: ${file.path}"
        }
        require(file.length() > 0L) {
            "$kind attachment is empty: ${file.path}"
        }
        val extension = file.extension.lowercase(Locale.US)
        val mimeType = when (kind) {
            "image" -> OpenAiCompatible.imageMimeType(extension)
            else -> OpenAiCompatible.audioFormat(extension)
        }
        return EncodedAttachment(Base64.getEncoder().encodeToString(file.readBytes()), mimeType)
    }

    private fun jsonHeaders(apiKey: String): Map<String, String> = buildMap {
        put("Content-Type", "application/json; charset=utf-8")
        if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
    }

    /**
     * Streaming POST: reads SSE lines (`data: {...}`) and extracts content deltas.
     */
    private fun postJsonStreaming(
        url: String,
        apiKey: String,
        payload: JsonObject,
        onToken: ((String) -> Unit)?,
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.doOutput = true
        jsonHeaders(apiKey).forEach { (name, value) -> conn.setRequestProperty(name, value) }
        conn.setRequestProperty("Accept", "text/event-stream")
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
            while (true) {
                val line = reader.readLine() ?: break
                when (val parsed = OpenAiCompatible.parseStreamLine(line)) {
                    StreamLine.Done -> break
                    StreamLine.Skip -> Unit
                    is StreamLine.Token -> {
                        result.append(parsed.text)
                        onToken?.invoke(parsed.text)
                    }
                }
            }
        }
        conn.disconnect()
        return result.toString()
    }
}
