package com.fersaiyan.cyanbridge.shared.ai

import com.fersaiyan.cyanbridge.shared.json.optBoolean
import com.fersaiyan.cyanbridge.shared.json.optDouble
import com.fersaiyan.cyanbridge.shared.json.optInt
import com.fersaiyan.cyanbridge.shared.json.optJsonObject
import com.fersaiyan.cyanbridge.shared.json.optLong
import com.fersaiyan.cyanbridge.shared.json.optString
import com.fersaiyan.cyanbridge.shared.json.parseJsonObject
import com.fersaiyan.cyanbridge.shared.platform.HttpRequest
import com.fersaiyan.cyanbridge.shared.platform.HttpTransport
import com.fersaiyan.cyanbridge.shared.platform.platformCurrentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** What the relay client reads on every call (Android: AiProviderPrefs and the Pro prefs). */
interface RelaySettings {
    /** Relay base URL, already trimmed and defaulted. */
    val baseUrl: String

    /** Backend sent with each request; null leaves the field out. */
    val backend: CliRelayBackend?

    /** System prompt sent with each request; null leaves it out. */
    val systemPrompt: String?

    /** Extra request headers (Accept-Language, Authorization). */
    val headers: Map<String, String>
}

data class RelaySettingsSnapshot(
    override val baseUrl: String = DEFAULT_RELAY_URL,
    override val backend: CliRelayBackend? = null,
    override val systemPrompt: String? = null,
    override val headers: Map<String, String> = emptyMap(),
) : RelaySettings

/** A media file sent to the relay. [read] validates and loads it when the request is built. */
class RelayMedia(val filename: String, val read: () -> ByteArray)

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

data class RelayServerCapabilities(
    val raw: JsonObject,
    val chat: Boolean,
    val voiceQuery: Boolean,
    val imageQuery: Boolean,
    val openAiChatCompletions: Boolean,
    val subscriptionVerify: Boolean,
    val transcriptionHttp: Boolean,
) {
    companion object {
        fun parse(payload: JsonObject): RelayServerCapabilities {
            val caps = payload.optJsonObject("capabilities") ?: payload
            return RelayServerCapabilities(
                raw = payload,
                chat = caps.optBoolean("chat", true),
                voiceQuery = caps.optBoolean("voice_query", caps.optBoolean("chat", true)),
                imageQuery = caps.optBoolean("image_query", true),
                openAiChatCompletions = caps.optBoolean("openai_chat_completions", true),
                subscriptionVerify = caps.optBoolean("subscription_verify", false),
                transcriptionHttp = caps.optBoolean("transcription_http", false),
            )
        }
    }
}

/** GET /capabilities, cached for a minute per relay URL. */
class RelayCapabilitiesCache(
    private val transport: HttpTransport,
    private val clock: () -> Long = ::platformCurrentTimeMillis,
) {
    private val mutex = Mutex()
    private var cachedAtMs: Long = 0L
    private var cachedBaseUrl: String = ""
    private var cachedCapabilities: RelayServerCapabilities? = null

    suspend fun get(relayBaseUrl: String, forceRefresh: Boolean = false): Result<RelayServerCapabilities> = mutex.withLock {
        val baseUrl = relayBaseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            return Result.failure(IllegalStateException("Relay base URL is blank"))
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            return Result.failure(IllegalStateException("Relay base URL must start with http:// or https://"))
        }

        val now = clock()
        val cached = cachedCapabilities
        if (!forceRefresh && cached != null && cachedBaseUrl == baseUrl && now - cachedAtMs <= CACHE_TTL_MS) {
            return Result.success(cached)
        }

        runCatching {
            val response = transport.execute(
                HttpRequest(
                    method = "GET",
                    url = "$baseUrl/capabilities",
                    connectTimeoutMs = CONNECT_TIMEOUT_MS,
                    readTimeoutMs = READ_TIMEOUT_MS,
                ),
            )
            if (!response.isSuccessful) {
                throw IllegalStateException("Capabilities HTTP ${response.statusCode}: ${response.body}")
            }
            RelayServerCapabilities.parse(parseJsonObject(response.body)).also { parsed ->
                cachedBaseUrl = baseUrl
                cachedAtMs = now
                cachedCapabilities = parsed
            }
        }
    }

    suspend fun clear() = mutex.withLock {
        cachedCapabilities = null
        cachedAtMs = 0L
        cachedBaseUrl = ""
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5000
        const val READ_TIMEOUT_MS = 10000
        const val CACHE_TTL_MS = 60_000L
    }
}

/**
 * Client for the CyanBridge relay: /health, /chat, /voice-query, /image-query and
 * /audio-query. Failures come back as [Result.failure]; HTTP errors carry the relay's
 * `error`/`message` (see [RelayErrorClassifier] for the user-facing text).
 */
class RelayClient(
    private val settings: RelaySettings,
    private val transport: HttpTransport,
    private val capabilities: RelayCapabilitiesCache? = null,
    private val clock: () -> Long = ::platformCurrentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun healthCheck(): Result<String> = runCatching {
        retry(times = 2) {
            val response = postJson(
                endpoint("/health"),
                buildJsonObject { settings.backend?.let { put("backend", it.wire) } },
            )
            val status = response.optString("status", "unknown")
            val backend = response.optString("backend", "unknown")
            "$status ($backend)"
        }
    }

    /** [chatId] null leaves the field out (callers without a stored thread). */
    suspend fun chat(
        chatId: String?,
        prompt: String,
        messages: List<Map<String, String>>,
        modelOverride: String? = null,
    ): Result<String> = runCatching {
        requireCapability("chat") { it.chat }

        retry {
            val systemPrompt = settings.systemPrompt
            val response = postJson(
                endpoint("/chat"),
                buildJsonObject {
                    settings.backend?.let { put("backend", it.wire) }
                    chatId?.let { put("chatId", it) }
                    put("prompt", prompt)
                    putJsonArray("messages") {
                        if (systemPrompt != null) {
                            addJsonObject {
                                put("role", "system")
                                put("content", systemPrompt)
                            }
                        }
                        messages.forEach { message ->
                            addJsonObject {
                                message["role"]?.let { put("role", it) }
                                message["content"]?.let { put("content", it) }
                            }
                        }
                    }
                    systemPrompt?.let { put("system_prompt", it) }
                    putModel(modelOverride)
                },
            )
            response.optString("reply").ifBlank {
                throw IllegalStateException("Relay returned empty chat reply")
            }
        }
    }

    suspend fun voiceQuery(
        prompt: String,
        backendOverride: CliRelayBackend? = null,
        modelOverride: String? = null,
    ): Result<String> = voiceQueryDetailed(prompt, backendOverride, modelOverride).map { it.reply }

    suspend fun voiceQueryDetailed(
        prompt: String,
        backendOverride: CliRelayBackend? = null,
        modelOverride: String? = null,
    ): Result<VoiceQueryDetails> = runCatching {
        requireCapability("voice_query") { it.voiceQuery }

        retry {
            val started = clock()
            val response = postJson(
                endpoint("/voice-query"),
                buildJsonObject {
                    (backendOverride ?: settings.backend)?.let { put("backend", it.wire) }
                    put("prompt", prompt)
                    settings.systemPrompt?.let { put("system_prompt", it) }
                    putModel(modelOverride)
                },
            )
            val elapsedMs = (clock() - started).coerceAtLeast(1L)
            val reply = response.optString("reply").ifBlank {
                throw IllegalStateException("Relay returned empty voice reply")
            }

            VoiceQueryDetails(
                reply = reply,
                telemetry = parseVoiceTelemetry(prompt, reply, response, elapsedMs),
            )
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun imageQuery(
        image: RelayMedia,
        prompt: String? = null,
        backendOverride: CliRelayBackend? = null,
        modelOverride: String? = null,
        /** A spoken question recorded with the photo: sent as audio in the same request ([spokenQuestion]). */
        audio: RelayMedia? = null,
        spokenQuestion: Boolean = false,
        /** Appended by the relay to the user's system prompt (glasses spoken-question instructions). */
        systemContext: String? = null,
    ): Result<String> = runCatching {
        requireCapability("image_query") { it.imageQuery }

        val imageBase64 = Base64.encode(image.read())
        val audioBase64 = audio?.let { Base64.encode(it.read()) }
        val response = postJson(
            endpoint("/image-query"),
            buildJsonObject {
                (backendOverride ?: settings.backend)?.let { put("backend", it.wire) }
                put("filename", image.filename)
                put("imageBase64", imageBase64)
                settings.systemPrompt?.let { put("system_prompt", it) }
                prompt?.trim().orEmpty().takeIf { it.isNotBlank() }?.let { put("prompt", it) }
                putModel(modelOverride)
                if (audio != null && audioBase64 != null) {
                    put("audioBase64", audioBase64)
                    put("audioFilename", audio.filename)
                }
                if (spokenQuestion) put("spoken_question", true)
                systemContext?.takeIf { it.isNotBlank() }?.let { put("system_context", it) }
            },
        )
        response.optString("reply").ifBlank {
            throw IllegalStateException("Relay returned empty image reply")
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun audioQuery(
        audio: RelayMedia,
        prompt: String? = null,
        modelOverride: String? = null,
        /** The recording is the whole question: the relay sends it without a text part. */
        spokenQuestion: Boolean = false,
        /** Appended by the relay to the user's system prompt (glasses spoken-question instructions). */
        systemContext: String? = null,
    ): Result<String> = runCatching {
        val bytes = audio.read()
        val response = postJson(
            endpoint("/audio-query"),
            buildJsonObject {
                put("filename", audio.filename)
                put("audioBase64", Base64.encode(bytes))
                settings.systemPrompt?.let { put("system_prompt", it) }
                prompt?.trim().orEmpty().takeIf { it.isNotBlank() }?.let { put("prompt", it) }
                putModel(modelOverride)
                if (spokenQuestion) put("spoken_question", true)
                systemContext?.takeIf { it.isNotBlank() }?.let { put("system_context", it) }
            },
        )
        response.optString("reply").ifBlank {
            throw IllegalStateException("Relay returned empty audio reply")
        }
    }

    /**
     * Chat with attachments: each image (and the audio) is asked on its own; one reply is
     * returned as is, several are merged by a final /chat. [textModel] is used without
     * media, [mediaModel] for the media calls and the merge. A failed media call throws;
     * only the chat call comes back as a [Result].
     */
    suspend fun chatWithMedia(
        chatId: String?,
        userPrompt: String,
        messages: List<Map<String, String>>,
        images: List<RelayMedia>,
        audio: RelayMedia?,
        textModel: String?,
        mediaModel: String?,
    ): Result<String> {
        val mediaReplies = buildList {
            images.forEachIndexed { index, image ->
                val imagePrompt = if (images.size > 1) {
                    "Image ${index + 1} of ${images.size}. $userPrompt"
                } else {
                    userPrompt
                }
                add(imageQuery(image, imagePrompt, modelOverride = mediaModel).getOrThrow())
            }
            if (audio != null) {
                add(audioQuery(audio, userPrompt, modelOverride = mediaModel).getOrThrow())
            }
        }
        if (mediaReplies.size == 1) return Result.success(mediaReplies.single())
        if (mediaReplies.isEmpty()) return chat(chatId, userPrompt, messages, textModel)
        val synthesisPrompt = buildString {
            appendLine(userPrompt)
            appendLine()
            appendLine("Attached media analyses:")
            mediaReplies.forEachIndexed { index, reply ->
                appendLine("${index + 1}. $reply")
            }
            append("Give one coherent answer using all attached media.")
        }
        return chat(
            chatId = chatId,
            prompt = synthesisPrompt,
            messages = messages + mapOf("role" to "user", "content" to synthesisPrompt),
            modelOverride = mediaModel,
        )
    }

    private suspend fun requireCapability(name: String, supported: (RelayServerCapabilities) -> Boolean) {
        capabilities?.get(settings.baseUrl)?.getOrNull()?.let { caps ->
            if (!supported(caps)) {
                throw IllegalStateException("Server capability unavailable: $name")
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putModel(modelOverride: String?) {
        val model = modelOverride?.trim().orEmpty()
        if (model.isNotBlank()) put("model", model)
    }

    private suspend fun <T> retry(
        times: Int = 3,
        delayMs: Long = 800,
        block: suspend () -> T,
    ): T {
        var lastError: Throwable? = null
        repeat(times) { index ->
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t
                if (index < times - 1) {
                    sleep(delayMs * (index + 1))
                }
            }
        }
        throw lastError ?: IllegalStateException("retry failed")
    }

    private fun endpoint(path: String): String {
        val base = settings.baseUrl.trimEnd('/')
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "Relay URL must start with http:// or https://"
        }
        return "$base$path"
    }

    private suspend fun postJson(url: String, payload: JsonObject): JsonObject {
        val response = transport.execute(
            HttpRequest(
                method = "POST",
                url = url,
                headers = mapOf("Content-Type" to "application/json; charset=utf-8") + settings.headers,
                body = payload.toString(),
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
            ),
        )
        if (!response.isSuccessful) {
            throw IllegalStateException(relayHttpErrorMessage(response.statusCode, response.body))
        }
        return parseJsonObject(response.body)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 7000
        private const val READ_TIMEOUT_MS = 120000

        /** The exception text for a non-2xx relay reply: its `error` and/or `message`. */
        fun relayHttpErrorMessage(code: Int, body: String): String {
            val json = runCatching { parseJsonObject(body) }.getOrNull()
            val error = json?.optString("error")?.trim().orEmpty()
            val message = json?.optString("message")?.trim().orEmpty()
            return when {
                error.isNotBlank() && message.isNotBlank() -> "$error: $message"
                error.isNotBlank() -> "$error (HTTP $code)"
                message.isNotBlank() -> message
                else -> "Relay HTTP $code"
            }
        }

        fun parseVoiceTelemetry(
            prompt: String,
            reply: String,
            response: JsonObject,
            elapsedMs: Long,
        ): VoiceQueryTelemetry {
            val usage = response.optJsonObject("usage") ?: JsonObject(emptyMap())
            val perf = response.optJsonObject("performance")
                ?: response.optJsonObject("metrics")
                ?: JsonObject(emptyMap())

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

        private fun firstPositiveInt(vararg values: Int): Int = values.firstOrNull { it > 0 } ?: 1

        private fun firstPositiveLong(vararg values: Long): Long = values.firstOrNull { it > 0L } ?: -1L

        private fun firstPositiveDouble(vararg values: Double): Double =
            values.firstOrNull { it.isFinite() && it > 0.0 } ?: -1.0
    }
}
