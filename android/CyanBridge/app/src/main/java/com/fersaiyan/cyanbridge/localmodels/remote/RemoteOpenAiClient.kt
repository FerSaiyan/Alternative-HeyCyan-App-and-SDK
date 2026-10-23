package com.fersaiyan.cyanbridge.localmodels.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
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
    private const val MAX_IMAGE_DIMENSION = 1024
    private const val JPEG_QUALITY = 85
    private const val MAX_ERROR_BODY_LENGTH = 4_000

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

        return postWithImageFallback(payload) { attempt ->
            postJson(url, apiKey, attempt)
        }
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

        return postWithImageFallback(payload) { attempt ->
            postJsonStreaming(url, apiKey, attempt, onToken)
        }
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

    internal fun payloadForLogging(payload: JSONObject): String {
        val redactedPayload = JSONObject(payload.toString())
        val imageBase64Lengths = mutableListOf<Int>()
        val topLevelImage = redactedPayload.optString("image")
        if (topLevelImage.isNotBlank()) {
            imageBase64Lengths += topLevelImage.length
            redactedPayload.put("image", "<redacted>")
        }
        val messages = redactedPayload.optJSONArray("messages") ?: JSONArray()
        for (messageIndex in 0 until messages.length()) {
            val message = messages.optJSONObject(messageIndex) ?: continue
            val rawContent = message.opt("content")
            if (rawContent is String) {
                message.put("content", "<redacted>")
                continue
            }
            val content = rawContent as? JSONArray ?: continue
            for (partIndex in 0 until content.length()) {
                val part = content.optJSONObject(partIndex) ?: continue
                if (part.optString("type") == "text") {
                    part.put("text", "<redacted>")
                    continue
                }
                if (part.optString("type") != "image_url") continue

                val imageUrl = part.optJSONObject("image_url") ?: continue
                val dataUrl = imageUrl.optString("url")
                val commaIndex = dataUrl.indexOf(',')
                val base64Length = if (
                    dataUrl.startsWith("data:image/") &&
                    dataUrl.substringBefore(',').endsWith(";base64") &&
                    commaIndex >= 0
                ) {
                    dataUrl.length - commaIndex - 1
                } else {
                    null
                }
                imageBase64Lengths += base64Length ?: 0
                val redactedUrl = if (commaIndex >= 0) {
                    "${dataUrl.substring(0, commaIndex + 1)}<redacted>"
                } else {
                    "<redacted>"
                }
                imageUrl.put("url", redactedUrl)
            }
        }

        Log.i(TAG, "Outgoing image attachment Base64 lengths=$imageBase64Lengths")
        return redactedPayload.toString()
    }

    internal fun imageFallbackPayloads(payload: JSONObject): List<JSONObject> {
        val messages = payload.optJSONArray("messages") ?: return emptyList()
        val userMessageIndex = (messages.length() - 1 downTo 0)
            .firstOrNull { messages.optJSONObject(it)?.optString("role") == "user" }
            ?: return emptyList()
        val content = messages.optJSONObject(userMessageIndex)?.optJSONArray("content")
            ?: return emptyList()
        if ((0 until content.length()).any {
                content.optJSONObject(it)?.optString("type") == "input_audio"
            }
        ) {
            return emptyList()
        }

        val text = mutableListOf<String>()
        var base64Image: String? = null
        for (index in 0 until content.length()) {
            val part = content.optJSONObject(index) ?: continue
            when (part.optString("type")) {
                "text" -> part.optString("text").takeIf { it.isNotBlank() }?.let(text::add)
                "image_url" -> {
                    if (base64Image != null) return emptyList()
                    val dataUrl = part.optJSONObject("image_url")?.optString("url").orEmpty()
                    val commaIndex = dataUrl.indexOf(',')
                    if (!dataUrl.startsWith("data:image/") ||
                        !dataUrl.substringBefore(',').endsWith(";base64") ||
                        commaIndex < 0
                    ) {
                        return emptyList()
                    }
                    base64Image = dataUrl.substring(commaIndex + 1)
                }
                else -> return emptyList()
            }
        }
        val image = base64Image?.takeIf { it.isNotBlank() } ?: return emptyList()
        val prompt = text.joinToString("\n")

        val separateImagePayload = JSONObject(payload.toString())
        separateImagePayload.optJSONArray("messages")
            ?.getJSONObject(userMessageIndex)
            ?.put("content", prompt)
        separateImagePayload.put("image", image)

        val imageInPromptPayload = JSONObject(payload.toString())
        imageInPromptPayload.optJSONArray("messages")
            ?.getJSONObject(userMessageIndex)
            ?.put("content", "$prompt\n\nImage as base64 (JPEG):\n$image")

        return listOf(separateImagePayload, imageInPromptPayload)
    }

    private fun <T> postWithImageFallback(
        payload: JSONObject,
        postRequest: (JSONObject) -> T,
    ): T {
        val fallbacks = imageFallbackPayloads(payload)
        try {
            return postRequest(payload)
        } catch (error: RemoteOpenAiHttpException) {
            if (!error.shouldTryImageFallback() || fallbacks.isEmpty()) throw error
            Log.w(TAG, "HTTP ${error.statusCode}; retrying image request with alternate payloads")
        }

        var lastError: RemoteOpenAiHttpException? = null
        for ((index, fallback) in fallbacks.withIndex()) {
            try {
                Log.i(TAG, "Retrying image request with alternate payload ${index + 1}/${fallbacks.size}")
                return postRequest(fallback)
            } catch (error: RemoteOpenAiHttpException) {
                lastError = error
                if (!error.shouldTryImageFallback() || index == fallbacks.lastIndex) throw error
                Log.w(TAG, "HTTP ${error.statusCode}; trying the next image payload format")
            }
        }
        throw checkNotNull(lastError) { "Image fallback failed without an HTTP response" }
    }

    private fun RemoteOpenAiHttpException.shouldTryImageFallback(): Boolean {
        return statusCode == 400 || statusCode == 415 || statusCode == 422 || statusCode in 500..599
    }

    private fun logOutgoingPayload(requestType: String, payload: JSONObject) {
        val safePayload = payloadForLogging(payload)
        val chunkSize = 3_000
        val chunkCount = (safePayload.length + chunkSize - 1) / chunkSize
        for (chunkIndex in 0 until chunkCount) {
            val start = chunkIndex * chunkSize
            val end = minOf(start + chunkSize, safePayload.length)
            Log.d(
                TAG,
                "$requestType payload JSON (${chunkIndex + 1}/$chunkCount): " +
                    safePayload.substring(start, end),
            )
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

        if (kind == "image") {
            return readImageAttachment(file)
        }

        val extension = file.extension.lowercase(Locale.US)
        val mimeType = when (kind) {
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

    private fun readImageAttachment(file: File): EncodedAttachment {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Cannot decode image attachment: ${file.path}"
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateImageSampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IllegalArgumentException("Cannot decode image attachment: ${file.path}")
        val resized = resizeImageIfNeeded(decoded)
        val jpegBytes = try {
            ByteArrayOutputStream().use { output ->
                check(resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    "Cannot encode image attachment as JPEG: ${file.path}"
                }
                output.toByteArray()
            }
        } finally {
            if (resized !== decoded) resized.recycle()
            decoded.recycle()
        }

        return EncodedAttachment(
            base64 = Base64.getEncoder().encodeToString(jpegBytes),
            mimeType = "image/jpeg",
        )
    }

    private fun calculateImageSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while (maxOf(width, height) / (sampleSize * 2) >= MAX_IMAGE_DIMENSION) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun resizeImageIfNeeded(bitmap: Bitmap): Bitmap {
        val largestDimension = maxOf(bitmap.width, bitmap.height)
        if (largestDimension <= MAX_IMAGE_DIMENSION) return bitmap

        val scale = MAX_IMAGE_DIMENSION.toFloat() / largestDimension
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
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
        logOutgoingPayload("Non-streaming", payload)
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = readResponseBody(stream)
        conn.disconnect()
        if (code !in 200..299) {
            Log.e(TAG, "Remote server HTTP $code; response body: ${body.take(MAX_ERROR_BODY_LENGTH)}")
            throw RemoteOpenAiHttpException(code, body.take(MAX_ERROR_BODY_LENGTH))
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
        logOutgoingPayload("Streaming", payload)
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }

        val code = conn.responseCode
        if (code !in 200..299) {
            val errBody = readResponseBody(conn.errorStream)
            conn.disconnect()
            Log.e(TAG, "Remote server HTTP $code; response body: ${errBody.take(MAX_ERROR_BODY_LENGTH)}")
            throw RemoteOpenAiHttpException(code, errBody.take(MAX_ERROR_BODY_LENGTH))
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

internal class RemoteOpenAiHttpException(
    val statusCode: Int,
    val responseBody: String,
) : IOException("Remote server HTTP $statusCode: $responseBody")

private fun readResponseBody(stream: java.io.InputStream?): String {
    if (stream == null) return ""
    val maxLength = 4_000
    val body = stream.use { input ->
        BufferedReader(InputStreamReader(input)).use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(1024)
            while (result.length <= maxLength) {
                val count = reader.read(buffer, 0, minOf(buffer.size, maxLength + 1 - result.length))
                if (count < 0) break
                result.append(buffer, 0, count)
            }
            result.toString()
        }
    }
    return if (body.length > maxLength) {
        "${body.take(maxLength)} …(response body truncated)"
    } else {
        body
    }
}
