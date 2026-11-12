package com.fersaiyan.cyanbridge.ai.live

import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** One reaction-worthy item of a Gemini Live server message, in the order it must be handled. */
internal sealed interface GeminiLiveServerEvent {
    /** A top-level `error`; when present it is the only event of the message. */
    data class Error(val quotaExhausted: Boolean) : GeminiLiveServerEvent

    object SetupComplete : GeminiLiveServerEvent

    data class SessionResumptionUpdate(val newHandle: String) : GeminiLiveServerEvent

    data class GoAway(val timeLeft: String) : GeminiLiveServerEvent

    /** Billed tokens for the turn, forwarded verbatim to the relay. */
    class UsageMetadata(val usage: JSONObject) : GeminiLiveServerEvent

    data class Transcription(val input: Boolean, val text: String) : GeminiLiveServerEvent

    object Interrupted : GeminiLiveServerEvent

    /** Decoded 24 kHz mono PCM16 model audio from one `modelTurn` part. */
    class ModelAudio(val pcm: ByteArray) : GeminiLiveServerEvent

    object TurnComplete : GeminiLiveServerEvent
}

/**
 * Pure Gemini Live WebSocket codec: builds every outgoing frame and decodes incoming frames into
 * [GeminiLiveServerEvent]s. It owns no socket, audio or session state.
 */
internal object GeminiLiveProtocol {
    const val INPUT_SAMPLE_RATE_HZ = 16_000
    const val OUTPUT_SAMPLE_RATE_HZ = 24_000
    const val INPUT_AUDIO_MIME_TYPE = "audio/pcm;rate=$INPUT_SAMPLE_RATE_HZ"
    const val IMAGE_MIME_TYPE = "image/jpeg"
    const val REALTIME_AUDIO_FIELD = "audio"
    const val REALTIME_VIDEO_FIELD = "video"

    data class Setup(val json: String, val serverProvided: Boolean)

    /**
     * Builds the `setup` frame. If the token was minted with an exact bidiGenerateContentSetup
     * (Pro Live), it is echoed with the token model and current resumption handle; otherwise the
     * client setup mirrors the server's buildLiveSystemInstruction.
     */
    fun buildSetup(
        config: LiveTokenConfig,
        sessionResumptionHandle: String?,
        language: String,
        imagePrompt: String,
        systemPrompt: String,
        storedSystemPrompt: () -> String,
    ): Setup {
        // If the token was minted with an exact bidiGenerateContentSetup (Pro Live), echo it exactly
        // to avoid hanging in CONNECTING (server waits for matching setup). Free proxy does
        // upstream.send({setup: provisioningRequest.bidiGenerateContentSetup}) — we do the same for Pro.
        val serverSetup = config.setupJson?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()
        }
        val setup: JSONObject = if (serverSetup != null) {
            // Inject sessionResumption handle if present, keep everything else from server
            val resumption = JSONObject().apply {
                sessionResumptionHandle?.takeIf { it.isNotBlank() }?.let { put("handle", it) }
            }
            // Ensure sessionResumption is updated, keep other fields from server
            serverSetup.put("sessionResumption", resumption)
            // Ensure model matches token (in case server setup had different)
            serverSetup.put("model", config.model)
            serverSetup
        } else {
            val resumption = JSONObject().apply {
                sessionResumptionHandle?.takeIf { it.isNotBlank() }?.let { put("handle", it) }
            }
            val automaticActivityDetection = JSONObject()
                .put("disabled", false)
                .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                .put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                .put("prefixPaddingMs", 40)
                .put("silenceDurationMs", 500)
            val realtimeInputConfig = JSONObject()
                .put("automaticActivityDetection", automaticActivityDetection)
                .put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
                .put("turnCoverage", "TURN_INCLUDES_AUDIO_ACTIVITY_AND_ALL_VIDEO")
            // Build systemInstruction to match token's bidiGenerateContentSetup (see lib/gemini-live.ts buildLiveSystemInstruction)
            // The token's setup is built server-side with the same language/imagePrompt/systemPrompt, and the Live setup we send
            // must include the same systemInstruction + contextWindowCompression to avoid hanging in CONNECTING
            // (seen when setup omitted these and server waited for matching setup).
            val cleanPrompt = imagePrompt.trim().replace(Regex("\\s+"), " ").take(400)
                .ifBlank { "Describe what you can see and answer the user's question." }
            val resolvedSystemPrompt = systemPrompt.ifBlank { storedSystemPrompt() }
            // Replicate resolveAssistantSystemPrompt + buildLiveSystemInstruction
            val assistantPrompt = resolvedSystemPrompt.trim().take(4000).ifBlank {
                "You are CyanBridge's assistant for smart glasses. Answer the user's request directly. Give the most useful answer first in one clear sentence, then stop when the request is fully answered. For simple spoken requests, usually use 1-3 short sentences; for complex requests, include the important explanation, steps, caveats, and safety information needed. Use the shortest complete answer. Avoid filler, long preambles, repetition, and unnecessary formatting unless the user asks for them. Use the latest glasses image as visual context when the user refers to what they see."
            }
            val systemInstructionText = listOf(
                assistantPrompt,
                "You are Gemini Live in CyanBridge smart glasses.",
                "You support 97 languages. Respond in the language the user is currently speaking, defaulting to ${language} only when unclear. Switch immediately when the user asks to speak another language.",
                "Keep spoken answers concise, helpful, and safe for a hands-free conversation.",
                "Default image question when the user gives no specific question and you have a fresh glasses image: $cleanPrompt"
            ).joinToString(" ")
            val systemInstruction = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstructionText)))
            val contextWindowCompression = JSONObject()
                .put("triggerTokens", 25000)
                .put("slidingWindow", JSONObject().put("targetTokens", 8000))
            JSONObject()
                .put("model", config.model)
                .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")))
                .put("realtimeInputConfig", realtimeInputConfig)
                .put("inputAudioTranscription", JSONObject())
                .put("outputAudioTranscription", JSONObject())
                .put("sessionResumption", resumption)
                .put("contextWindowCompression", contextWindowCompression)
                .put("systemInstruction", systemInstruction)
        }
        return Setup(
            json = JSONObject().put("setup", setup).toString(),
            serverProvided = serverSetup != null,
        )
    }

    /** A completed user text turn (`clientContent`), capped at 2,000 characters. */
    fun buildClientTextTurn(text: String): String {
        val content = JSONObject()
            .put(
                "turns",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", text.take(2_000)))),
                ),
            )
            .put("turnComplete", true)
        return JSONObject().put("clientContent", content).toString()
    }

    /** A `realtimeInput` media blob; [field] is the current `audio` / `video` field name. */
    fun buildRealtimeBlob(field: String, mimeType: String, bytes: ByteArray): String {
        val realtimeInput = JSONObject().put(
            field,
            JSONObject()
                .put("mimeType", mimeType)
                .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)),
        )
        return JSONObject().put("realtimeInput", realtimeInput).toString()
    }

    /** Explicit flush of cached server-side audio when the input stream is actually paused. */
    fun buildAudioStreamEnd(): String {
        val realtimeInput = JSONObject().put("audioStreamEnd", true)
        return JSONObject().put("realtimeInput", realtimeInput).toString()
    }

    /**
     * Builds the WebSocket upgrade request. [languageTag] is resolved lazily; any failure while
     * resolving or applying it only omits the Accept-Language header.
     */
    fun buildConnectRequest(
        config: LiveTokenConfig,
        visionReportId: String,
        languageTag: () -> String,
    ): Request {
        val liveUrl = if (config.freeTier || config.economy) {
            config.websocketUrl.replaceFirst("wss://", "https://").toHttpUrl().newBuilder()
                .addQueryParameter("vision_session_id", visionReportId).build().toString()
                .replaceFirst("https://", "wss://")
        } else config.websocketUrl
        val builder = Request.Builder().url(liveUrl)
        // A constrained Google URL already carries its ephemeral access_token.
        // Do not send the same credential in a second Authorization header.
        // Free/Economy proxies still require their own CyanBridge Bearer header.
        config.authorizationHeader?.takeIf { it.isNotBlank() }?.let {
            builder.header("Authorization", it)
        } ?: config.token.takeIf { it.isNotBlank() && !config.websocketUrl.contains("access_token=") }?.let {
            builder.header("Authorization", "Token $it")
        }
        // Free-tier Live requires x-goog-api-key alongside the ephemeral token (or alone).
        // DefaultGeminiLiveTokenProvider populates apiKey from debug prefs; Direct provider always does.
        config.apiKey?.takeIf { it.isNotBlank() }?.let { builder.header("x-goog-api-key", it) }
        // Send app language so free queue 429 can be localized (too many free users)
        runCatching {
            val langTag = languageTag()
            if (langTag.isNotBlank()) builder.header("Accept-Language", langTag)
        }
        return builder.build()
    }

    /** Decodes one server frame; `null` when it is not a JSON object. */
    fun parseServerMessage(raw: String): List<GeminiLiveServerEvent>? {
        val message = runCatching { JSONObject(raw) }.getOrElse { return null }
        message.optJSONObject("error")?.let { error ->
            return listOf(GeminiLiveServerEvent.Error(quotaExhausted = error.optString("message") == "live_quota_exhausted"))
        }
        val events = mutableListOf<GeminiLiveServerEvent>()
        if (message.has("setupComplete")) events += GeminiLiveServerEvent.SetupComplete

        message.optJSONObject("sessionResumptionUpdate")
            ?.optString("newHandle")
            ?.takeIf { it.isNotBlank() }
            ?.let { events += GeminiLiveServerEvent.SessionResumptionUpdate(it) }

        message.optJSONObject("goAway")?.let { goAway ->
            events += GeminiLiveServerEvent.GoAway(goAway.optString("timeLeft"))
        }

        message.optJSONObject("usageMetadata")?.let { usage ->
            events += GeminiLiveServerEvent.UsageMetadata(usage)
        }

        val serverContent = message.optJSONObject("serverContent") ?: return events
        serverContent.optJSONObject("inputTranscription")
            ?.optString("text")
            ?.takeIf { it.isNotBlank() }
            ?.let { events += GeminiLiveServerEvent.Transcription(input = true, text = it) }
        serverContent.optJSONObject("outputTranscription")
            ?.optString("text")
            ?.takeIf { it.isNotBlank() }
            ?.let { events += GeminiLiveServerEvent.Transcription(input = false, text = it) }

        if (serverContent.optBoolean("interrupted", false)) events += GeminiLiveServerEvent.Interrupted

        val parts = serverContent.optJSONObject("modelTurn")?.optJSONArray("parts")
        if (parts != null) {
            for (index in 0 until parts.length()) {
                val encoded = parts.optJSONObject(index)
                    ?.optJSONObject("inlineData")
                    ?.optString("data")
                    .orEmpty()
                if (encoded.isNotBlank()) {
                    runCatching { Base64.decode(encoded, Base64.DEFAULT) }
                        .getOrNull()
                        ?.let { events += GeminiLiveServerEvent.ModelAudio(it) }
                }
            }
        }

        if (serverContent.optBoolean("turnComplete", false)) events += GeminiLiveServerEvent.TurnComplete
        return events
    }
}
