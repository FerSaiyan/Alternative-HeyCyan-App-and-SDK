package com.fersaiyan.cyanbridge.ai.router

import android.content.Context
import android.util.Base64
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionServerPrefs
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.delay

private data class RelayRequest(
    val type: String,
    val payload: String,
    val createdAtMs: Long,
)

object CliRelayQueue {
    private const val PREFS_NAME = "cli_relay_queue"
    private const val KEY_ITEMS = "items"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    private fun read(context: Context): MutableList<RelayRequest> {
        val raw = prefs(context).getString(KEY_ITEMS, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return MutableList(arr.length()) { idx ->
            val obj = arr.optJSONObject(idx) ?: JSONObject()
            RelayRequest(
                type = obj.optString("type"),
                payload = obj.optString("payload"),
                createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis()),
            )
        }
    }

    @Synchronized
    private fun write(context: Context, items: List<RelayRequest>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("type", it.type)
                    .put("payload", it.payload)
                    .put("createdAtMs", it.createdAtMs)
            )
        }
        prefs(context).edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    fun enqueueVoice(context: Context, prompt: String) {
        val items = read(context)
        items += RelayRequest("voice", prompt, System.currentTimeMillis())
        write(context, items)
    }

    fun enqueueImage(context: Context, imagePath: String) {
        val items = read(context)
        items += RelayRequest("image", imagePath, System.currentTimeMillis())
        write(context, items)
    }

    fun size(context: Context): Int = read(context).size

    suspend fun flush(context: Context): Int {
        val pending = read(context)
        if (pending.isEmpty()) return 0
        val remaining = mutableListOf<RelayRequest>()
        var delivered = 0
        pending.forEach { req ->
            val success = when (req.type) {
                "voice" -> CliRelayClient.voiceQuery(context, req.payload).isSuccess
                "image" -> CliRelayClient.imageQuery(context, req.payload).isSuccess
                else -> true
            }
            if (success) {
                delivered++
            } else {
                remaining += req
            }
        }
        write(context, remaining)
        return delivered
    }
}

object CliRelayClient {
    private const val CONNECT_TIMEOUT_MS = 7000
    private const val READ_TIMEOUT_MS = 120000

    data class VoiceQueryTelemetry(
        val inputTokens: Int,
        val outputTokens: Int,
        val promptTokensPerSec: Double,
        val generationTokensPerSec: Double,
        val totalMs: Long,
    )

    data class VoiceQueryDetails(
        val reply: String,
        val telemetry: VoiceQueryTelemetry,
    )

    suspend fun healthCheck(context: Context): Result<String> = runCatching {
        retry(times = 2) {
            val response = postJson(
                context,
                endpoint(context, "/health"),
                JSONObject().put("backend", AiProviderPrefs.getRelayBackend(context).wire)
            )
            val status = response.optString("status", "unknown")
            val backend = response.optString("backend", "unknown")
            "$status ($backend)"
        }
    }

    suspend fun chat(
        context: Context,
        chatId: String,
        prompt: String,
        messages: List<Map<String, String>>,
        modelOverride: String? = null,
    ): Result<String> = runCatching {
        RelayServerCapabilitiesClient.get(context).getOrNull()?.let { caps ->
            if (!caps.chat) {
                throw IllegalStateException("Server capability unavailable: chat")
            }
        }

        retry {
            val messagesArray = JSONArray()
            val systemPrompt = ProSubscriptionAiPrefs.getSystemPrompt(context)
            messagesArray.put(JSONObject().put("role", "system").put("content", systemPrompt))
            for (m in messages) {
                messagesArray.put(JSONObject().put("role", m["role"]).put("content", m["content"]))
            }
            val response = postJson(
                context,
                endpoint(context, "/chat"),
                JSONObject()
                    .put("backend", AiProviderPrefs.getRelayBackend(context).wire)
                    .put("chatId", chatId)
                    .put("prompt", prompt)
                    .put("messages", messagesArray)
                    .put("system_prompt", systemPrompt)
                    .apply {
                        val model = modelOverride?.trim().orEmpty()
                        if (model.isNotBlank()) put("model", model)
                    }
            )
            response.optString("reply").ifBlank {
                throw IllegalStateException("Relay returned empty chat reply")
            }
        }
    }

    suspend fun voiceQuery(
        context: Context,
        prompt: String,
        backendOverride: CliRelayBackend? = null,
        modelOverride: String? = null,
    ): Result<String> = runCatching {
        voiceQueryDetailed(
            context = context,
            prompt = prompt,
            backendOverride = backendOverride,
            modelOverride = modelOverride,
        ).getOrThrow().reply
    }

    suspend fun voiceQueryDetailed(
        context: Context,
        prompt: String,
        backendOverride: CliRelayBackend? = null,
        modelOverride: String? = null,
    ): Result<VoiceQueryDetails> = runCatching {
        RelayServerCapabilitiesClient.get(context).getOrNull()?.let { caps ->
            if (!caps.voiceQuery) {
                throw IllegalStateException("Server capability unavailable: voice_query")
            }
        }

        retry {
            val started = System.currentTimeMillis()
            val response = postJson(
                context,
                endpoint(context, "/voice-query"),
                JSONObject()
                    .put("backend", (backendOverride ?: AiProviderPrefs.getRelayBackend(context)).wire)
                    .put("prompt", prompt)
                    .put("system_prompt", ProSubscriptionAiPrefs.getSystemPrompt(context))
                    .apply {
                        val model = modelOverride?.trim().orEmpty()
                        if (model.isNotBlank()) put("model", model)
                    }
            )
            val elapsedMs = (System.currentTimeMillis() - started).coerceAtLeast(1L)
            val reply = response.optString("reply").ifBlank {
                throw IllegalStateException("Relay returned empty voice reply")
            }

            VoiceQueryDetails(
                reply = reply,
                telemetry = parseVoiceTelemetry(
                    prompt = prompt,
                    reply = reply,
                    response = response,
                    elapsedMs = elapsedMs,
                ),
            )
        }
    }

    suspend fun imageQuery(
        context: Context,
        imagePath: String,
        prompt: String? = null,
        backendOverride: CliRelayBackend? = null,
        modelOverride: String? = null,
    ): Result<String> = runCatching {
        RelayServerCapabilitiesClient.get(context).getOrNull()?.let { caps ->
            if (!caps.imageQuery) {
                throw IllegalStateException("Server capability unavailable: image_query")
            }
        }

        val file = File(imagePath)
        require(file.exists()) { "Image file not found: $imagePath" }
        require(file.length() > 1000) { "Image file too small (${file.length()} bytes), likely corrupted" }
        
        val imageBase64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        
        val response = postJson(
            context,
            endpoint(context, "/image-query"),
            JSONObject()
                .put("backend", (backendOverride ?: AiProviderPrefs.getRelayBackend(context)).wire)
                .put("filename", file.name)
                    .put("imageBase64", imageBase64)
                    .put("system_prompt", ProSubscriptionAiPrefs.getSystemPrompt(context))
                .apply {
                    val requestPrompt = prompt?.trim().orEmpty()
                    if (requestPrompt.isNotBlank()) put("prompt", requestPrompt)
                    val model = modelOverride?.trim().orEmpty()
                    if (model.isNotBlank()) put("model", model)
                }
        )
        response.optString("reply").ifBlank {
            throw IllegalStateException("Relay returned empty image reply")
        }
    }

    suspend fun audioQuery(
        context: Context,
        audioPath: String,
        prompt: String? = null,
        modelOverride: String? = null,
    ): Result<String> = runCatching {
        val file = File(audioPath)
        require(file.exists()) { "Audio file not found: $audioPath" }
        require(file.length() > 44L) { "Audio file is empty or corrupted" }

        val response = postJson(
            context,
            endpoint(context, "/audio-query"),
            JSONObject()
                .put("filename", file.name)
                    .put("audioBase64", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
                    .put("system_prompt", ProSubscriptionAiPrefs.getSystemPrompt(context))
                .apply {
                    val requestPrompt = prompt?.trim().orEmpty()
                    if (requestPrompt.isNotBlank()) put("prompt", requestPrompt)
                    val model = modelOverride?.trim().orEmpty()
                    if (model.isNotBlank()) put("model", model)
                },
        )
        response.optString("reply").ifBlank {
            throw IllegalStateException("Relay returned empty audio reply")
        }
    }

    private suspend fun <T> retry(
        times: Int = 3,
        delayMs: Long = 800,
        block: () -> T,
    ): T {
        var lastError: Throwable? = null
        repeat(times) { index ->
            try {
                return block()
            } catch (t: Throwable) {
                lastError = t
                if (index < times - 1) {
                    delay(delayMs * (index + 1))
                }
            }
        }
        throw lastError ?: IllegalStateException("retry failed")
    }

    private fun endpoint(context: Context, path: String): String {
        val base = AiProviderPrefs.getRelayBaseUrl(context).trimEnd('/')
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "Relay URL must start with http:// or https://"
        }
        return "$base$path"
    }

    private fun postJson(context: Context, url: String, payload: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        val serverToken = ProSubscriptionServerPrefs.getApiToken(context)
        if (serverToken.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $serverToken")
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = BufferedReader(InputStreamReader(stream ?: conn.inputStream)).use { it.readText() }
        conn.disconnect()
        if (code !in 200..299) {
            val message = runCatching { JSONObject(body).optString("message").trim() }
                .getOrNull()
                .orEmpty()
            throw IllegalStateException(message.ifBlank { "Relay HTTP $code" })
        }
        return JSONObject(body)
    }

    private fun parseVoiceTelemetry(
        prompt: String,
        reply: String,
        response: JSONObject,
        elapsedMs: Long,
    ): VoiceQueryTelemetry {
        val usage = response.optJSONObject("usage") ?: JSONObject()
        val perf = response.optJSONObject("performance")
            ?: response.optJSONObject("metrics")
            ?: JSONObject()

        val inputTokens = firstPositiveInt(
            usage.optInt("prompt_tokens", -1),
            usage.optInt("input_tokens", -1),
            response.optInt("prompt_tokens", -1),
            response.optInt("input_tokens", -1),
            roughTokenEstimate(prompt),
        )

        val outputTokens = firstPositiveInt(
            usage.optInt("completion_tokens", -1),
            usage.optInt("output_tokens", -1),
            response.optInt("completion_tokens", -1),
            response.optInt("output_tokens", -1),
            roughTokenEstimate(reply),
        )

        val promptMs = firstPositiveLong(
            perf.optLong("prompt_eval_ms", -1L),
            perf.optLong("prompt_ms", -1L),
            perf.optLong("prefill_ms", -1L),
            response.optLong("prompt_eval_ms", -1L),
        )

        val generationMs = firstPositiveLong(
            perf.optLong("completion_eval_ms", -1L),
            perf.optLong("generation_ms", -1L),
            perf.optLong("decode_ms", -1L),
            response.optLong("completion_eval_ms", -1L),
        )

        val promptTpsFromServer = firstPositiveDouble(
            perf.optDouble("prompt_tokens_per_sec", -1.0),
            perf.optDouble("prompt_tps", -1.0),
            response.optDouble("prompt_tokens_per_sec", -1.0),
        )

        val genTpsFromServer = firstPositiveDouble(
            perf.optDouble("generation_tokens_per_sec", -1.0),
            perf.optDouble("completion_tokens_per_sec", -1.0),
            perf.optDouble("gen_tps", -1.0),
            response.optDouble("generation_tokens_per_sec", -1.0),
        )

        val promptTps = when {
            promptTpsFromServer > 0.0 -> promptTpsFromServer
            promptMs > 0L -> inputTokens / (promptMs / 1000.0)
            else -> inputTokens / ((elapsedMs * 0.35) / 1000.0)
        }.coerceAtLeast(1.0)

        val generationTps = when {
            genTpsFromServer > 0.0 -> genTpsFromServer
            generationMs > 0L -> outputTokens / (generationMs / 1000.0)
            else -> outputTokens / ((elapsedMs * 0.65) / 1000.0)
        }.coerceAtLeast(1.0)

        return VoiceQueryTelemetry(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            promptTokensPerSec = promptTps,
            generationTokensPerSec = generationTps,
            totalMs = elapsedMs,
        )
    }

    private fun roughTokenEstimate(text: String): Int {
        val cleaned = text.trim()
        if (cleaned.isBlank()) return 1
        val words = cleaned.split(Regex("\\s+")).count { it.isNotBlank() }
        return (words * 1.35).toInt().coerceAtLeast(1)
    }

    private fun firstPositiveInt(vararg values: Int): Int {
        return values.firstOrNull { it > 0 } ?: 1
    }

    private fun firstPositiveLong(vararg values: Long): Long {
        return values.firstOrNull { it > 0L } ?: -1L
    }

    private fun firstPositiveDouble(vararg values: Double): Double {
        return values.firstOrNull { it.isFinite() && it > 0.0 } ?: -1.0
    }
}

/**
 * Direct API client for calling OpenAI-compatible APIs.
 * Supports OpenAI, Anthropic, Gemini, and custom endpoints.
 */
object DirectApiClient {
    private const val CONNECT_TIMEOUT_MS = 10000
    private const val READ_TIMEOUT_MS = 120000
    private const val MAX_RETRIES = 3
    private const val BASE_DELAY_MS = 2000L

    suspend fun chatCompletion(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<Map<String, String>>,
    ): Result<String> = runCatching {
        val messagesArray = JSONArray()
        for (m in messages) {
            // Normalize role to lowercase for OpenAI API compatibility
            val role = m["role"]?.lowercase() ?: "user"
            messagesArray.put(JSONObject().put("role", role).put("content", m["content"]))
        }

        val payload = JSONObject()
            .put("model", model)
            .put("messages", messagesArray)
            .put("max_tokens", 2048)

        val url = if (baseUrl.endsWith("/chat/completions")) {
            baseUrl
        } else {
            baseUrl.trimEnd('/') + "/chat/completions"
        }

        // Retry with exponential backoff for rate limits
        var lastError: Throwable? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                val response = postJson(url, apiKey, payload)
                val choices = response.optJSONArray("choices")
                if (choices == null || choices.length() == 0) {
                    throw IllegalStateException("No choices in response")
                }
                val firstChoice = choices.getJSONObject(0)
                val message = firstChoice.optJSONObject("message")
                return@runCatching parseAssistantMessageContent(message)
                    ?: throw IllegalStateException("Empty response content")
            } catch (e: IllegalStateException) {
                lastError = e
                val msg = e.message ?: ""
                // Only retry on rate limit (429) or server errors (5xx)
                val isRetryable = msg.contains("429") || msg.contains("5") && msg.contains("HTTP")
                if (isRetryable && attempt < MAX_RETRIES - 1) {
                    val delayMs = BASE_DELAY_MS * (1 shl attempt) // 2s, 4s, 8s
                    delay(delayMs)
                } else {
                    throw e
                }
            }
        }
        throw lastError ?: IllegalStateException("Retry failed")
    }

    suspend fun imageCompletion(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        imagePath: String,
    ): Result<String> = runCatching {
        val file = File(imagePath)
        require(file.exists()) { "Image file not found: $imagePath" }

        val imageBase64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        val imageDataUrl = "data:image/jpeg;base64,$imageBase64"

        val messagesArray = JSONArray().apply {
            if (systemPrompt.isNotBlank()) {
                put(
                    JSONObject()
                        .put("role", "system")
                        .put("content", systemPrompt)
                )
            }

            put(
                JSONObject()
                    .put("role", "user")
                    .put(
                        "content",
                        JSONArray()
                            .put(JSONObject().put("type", "text").put("text", userPrompt))
                            .put(
                                JSONObject().put(
                                    "type",
                                    "image_url"
                                ).put(
                                    "image_url",
                                    JSONObject().put("url", imageDataUrl)
                                )
                            )
                    )
            )
        }

        val payload = JSONObject()
            .put("model", model)
            .put("messages", messagesArray)
            .put("max_tokens", 1200)

        val url = if (baseUrl.endsWith("/chat/completions")) {
            baseUrl
        } else {
            baseUrl.trimEnd('/') + "/chat/completions"
        }

        var lastError: Throwable? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                val response = postJson(url, apiKey, payload)
                val choices = response.optJSONArray("choices")
                if (choices == null || choices.length() == 0) {
                    throw IllegalStateException("No choices in multimodal response")
                }
                val firstChoice = choices.getJSONObject(0)
                val message = firstChoice.optJSONObject("message")
                return@runCatching parseAssistantMessageContent(message)
                    ?: throw IllegalStateException("Empty multimodal response content")
            } catch (e: IllegalStateException) {
                lastError = e
                val msg = e.message.orEmpty()
                val isRetryable = msg.contains("429") || msg.contains("5") && msg.contains("HTTP")
                if (isRetryable && attempt < MAX_RETRIES - 1) {
                    val delayMs = BASE_DELAY_MS * (1 shl attempt)
                    delay(delayMs)
                } else {
                    throw e
                }
            }
        }
        throw lastError ?: IllegalStateException("Retry failed")
    }

    private fun parseAssistantMessageContent(message: JSONObject?): String? {
        if (message == null) return null

        val rawContent = message.opt("content") ?: return null
        return when (rawContent) {
            is String -> rawContent.trim().takeIf { it.isNotBlank() }
            is JSONArray -> {
                val sb = StringBuilder()
                for (i in 0 until rawContent.length()) {
                    val part = rawContent.opt(i)
                    when (part) {
                        is String -> {
                            if (part.isNotBlank()) {
                                if (sb.isNotEmpty()) sb.append('\n')
                                sb.append(part.trim())
                            }
                        }
                        is JSONObject -> {
                            val text = part.optString("text").trim()
                            if (text.isNotBlank()) {
                                if (sb.isNotEmpty()) sb.append('\n')
                                sb.append(text)
                            }
                        }
                    }
                }
                sb.toString().trim().takeIf { it.isNotBlank() }
            }
            else -> rawContent.toString().trim().takeIf { it.isNotBlank() }
        }
    }

    private fun postJson(url: String, apiKey: String, payload: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = BufferedReader(InputStreamReader(stream ?: conn.inputStream)).use { it.readText() }
        conn.disconnect()
        if (code !in 200..299) {
            // Better error message for rate limits
            if (code == 429) {
                throw IllegalStateException("API rate limited. Please wait a moment and try again. (HTTP 429)")
            }
            throw IllegalStateException("API HTTP $code: $body")
        }
        return JSONObject(body)
    }
}

object AiAssistantRouter {
    interface ChatStreamCallbacks {
        fun onStatus(status: String) {}
        fun onToken(token: String) {}
    }

    private val localModelsProvider = LocalModelsProvider()

    suspend fun chatReply(context: Context, chatId: String, userPrompt: String, messages: List<Map<String, String>>): String {
        return chatReplyStreaming(
            context = context,
            chatId = chatId,
            userPrompt = userPrompt,
            messages = messages,
            imagePaths = emptyList(),
            audioPath = null,
            callbacks = null,
        )
    }

    suspend fun chatReplyStreaming(
        context: Context,
        chatId: String,
        userPrompt: String,
        messages: List<Map<String, String>>,
        imagePaths: List<String>,
        audioPath: String?,
        callbacks: ChatStreamCallbacks?,
    ): String {
        val preferredProvider = AutomationPrefs.getProviderType(context)
        val effectiveProvider = if (imagePaths.isNotEmpty() || !audioPath.isNullOrBlank()) {
            MediaInferenceRoutingPolicy.resolve(context)
        } else {
            preferredProvider
        }
        val providerType = when (effectiveProvider) {
            AgentProviderType.PRO_SUBSCRIPTION -> AiProviderType.CLI_RELAY
            AgentProviderType.LOCAL_AGENT -> AiProviderType.LOCAL_MODELS
            AgentProviderType.TASKER -> AiProviderPrefs.getProvider(context)
        }

        return when (providerType) {
            AiProviderType.MOCK -> {
                "Demo mode reply: $userPrompt"
            }
            AiProviderType.COMPANY_BACKEND -> {
                "Company backend is not configured yet in this build."
            }
            AiProviderType.CLI_RELAY -> {
                val modelOverride = if (effectiveProvider == AgentProviderType.PRO_SUBSCRIPTION) {
                    ProSubscriptionAiPrefs.getRequestsModel(context)
                } else {
                    null
                }
                val mediaModelOverride = if (effectiveProvider == AgentProviderType.PRO_SUBSCRIPTION) {
                    ProSubscriptionAiPrefs.getQuestionsModel(context)
                } else {
                    modelOverride
                }
                val mediaReplies = buildList {
                    imagePaths.forEachIndexed { index, imagePath ->
                        val imagePrompt = if (imagePaths.size > 1) {
                            "Image ${index + 1} of ${imagePaths.size}. $userPrompt"
                        } else {
                            userPrompt
                        }
                        add(
                            CliRelayClient.imageQuery(
                                context = context,
                                imagePath = imagePath,
                                prompt = imagePrompt,
                                modelOverride = mediaModelOverride,
                            ).getOrThrow(),
                        )
                    }
                    if (!audioPath.isNullOrBlank()) {
                        add(
                            CliRelayClient.audioQuery(
                                context = context,
                                audioPath = audioPath,
                                prompt = userPrompt,
                                modelOverride = mediaModelOverride,
                            ).getOrThrow(),
                        )
                    }
                }
                val result = if (mediaReplies.size == 1) {
                    Result.success(mediaReplies.single())
                } else {
                    val synthesisPrompt = if (mediaReplies.isEmpty()) {
                        userPrompt
                    } else {
                        buildString {
                            appendLine(userPrompt)
                            appendLine()
                            appendLine("Attached media analyses:")
                            mediaReplies.forEachIndexed { index, reply ->
                                appendLine("${index + 1}. $reply")
                            }
                            append("Give one coherent answer using all attached media.")
                        }
                    }
                    CliRelayClient.chat(
                        context = context,
                        chatId = chatId,
                        prompt = synthesisPrompt,
                        messages = if (mediaReplies.isEmpty()) messages else messages + mapOf(
                            "role" to "user",
                            "content" to synthesisPrompt,
                        ),
                        modelOverride = if (mediaReplies.isEmpty()) modelOverride else mediaModelOverride,
                    )
                }
                result.getOrElse { "Relay unavailable (${it.message})." }
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
            AiProviderType.MOCK -> "Demo mode reply: $prompt"
            AiProviderType.COMPANY_BACKEND -> "Company backend is not configured yet in this build."
            AiProviderType.CLI_RELAY -> {
                val modelOverride = if (AutomationPrefs.getProviderType(context) == AgentProviderType.PRO_SUBSCRIPTION) {
                    ProSubscriptionAiPrefs.getTasksModel(context)
                } else {
                    null
                }
                val result = CliRelayClient.voiceQuery(context, prompt, modelOverride = modelOverride)
                result.getOrElse { "Relay unavailable (${it.message})." }
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
