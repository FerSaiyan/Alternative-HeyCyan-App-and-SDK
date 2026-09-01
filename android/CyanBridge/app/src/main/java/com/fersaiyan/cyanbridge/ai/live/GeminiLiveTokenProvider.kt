package com.fersaiyan.cyanbridge.ai.live

import android.content.Context
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionServerPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import java.time.Instant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class LiveTokenConfig(
    val token: String,
    val model: String,
    val websocketUrl: String,
    val expiresAtMs: Long,
    val reservationId: String,
    /** Optional API key for direct Live websocket auth (free-tier requires x-goog-api-key alongside Token). */
    val apiKey: String? = null,
)

interface GeminiLiveTokenProvider {
    suspend fun requestToken(language: String, imagePrompt: String): LiveTokenConfig
}

class DefaultGeminiLiveTokenProvider(
    private val appContext: Context,
    private val http: OkHttpClient = OkHttpClient(),
) : GeminiLiveTokenProvider {
    override suspend fun requestToken(language: String, imagePrompt: String): LiveTokenConfig {
        val authToken = ProSubscriptionServerPrefs.getApiToken(appContext).trim()
        check(authToken.isNotBlank()) { "Sign in to CyanBridge before starting Gemini Live" }
        val base = AiProviderPrefs.getRelayBaseUrl(appContext).trim().trimEnd('/')
        check(base.startsWith("https://")) { "Gemini Live requires a secure relay URL" }
        val body = JSONObject()
            .put("language", language)
            .put("image_prompt", imagePrompt)
            .put("system_prompt", ProSubscriptionAiPrefs.getSystemPrompt(appContext))
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$base/api/pro/live/token")
            .header("Authorization", "Bearer $authToken")
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = JSONObject(raw.ifBlank { "{}" })
            if (!response.isSuccessful) {
                throw IllegalStateException(json.optString("error", "Gemini Live token request failed"))
            }
            val expiresAt = Instant.parse(json.getString("expire_time")).toEpochMilli()
            // Debug override for end-to-end testing with a direct Gemini API key.
            // Set via: adb shell am broadcast -a com.fersaiyan.cyanbridge.SET_GEMINI_KEY --es key "AQ...."
            // or via SharedPreferences "debug_gemini_api_key". Not used in production relay flow.
            val debugApiKey = appContext.getSharedPreferences("debug", Context.MODE_PRIVATE)
                .getString("debug_gemini_api_key", null)?.trim()?.takeIf { it.isNotBlank() }
            return LiveTokenConfig(
                token = json.getString("token"),
                model = json.getString("model"),
                websocketUrl = json.getString("websocket_url"),
                expiresAtMs = expiresAt,
                reservationId = json.getString("reservation_id"),
                apiKey = debugApiKey,
            )
        }
    }
}

/** Direct provider for local end-to-end testing with a Gemini API key (bypasses Vercel relay). */
class DirectGeminiApiKeyLiveTokenProvider(
    private val apiKey: String,
    private val http: OkHttpClient = OkHttpClient(),
) : GeminiLiveTokenProvider {
    override suspend fun requestToken(language: String, imagePrompt: String): LiveTokenConfig {
        check(apiKey.isNotBlank()) { "Gemini API key required" }
        val now = System.currentTimeMillis()
        val expireTime = java.time.Instant.ofEpochMilli(now + 15 * 60 * 1000).toString()
        val newSessionExpireTime = java.time.Instant.ofEpochMilli(now + 60 * 1000).toString()
        val body = JSONObject()
            .put("uses", 1)
            .put("expireTime", expireTime)
            .put("newSessionExpireTime", newSessionExpireTime)
            .put("bidiGenerateContentSetup", JSONObject()
                .put("model", "models/gemini-3.1-flash-live-preview")
                .put("generationConfig", JSONObject().put("responseModalities", org.json.JSONArray().put("AUDIO")))
                .put("sessionResumption", JSONObject())
                .put("systemInstruction", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", "You are Gemini Live in CyanBridge smart glasses. Reply in $language."))))
                .put("realtimeInputConfig", JSONObject()
                    .put("automaticActivityDetection", JSONObject()
                        .put("disabled", false)
                        .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                        .put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                        .put("prefixPaddingMs", 40)
                        .put("silenceDurationMs", 500))
                    .put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
                    .put("turnCoverage", "TURN_INCLUDES_AUDIO_ACTIVITY_AND_ALL_VIDEO"))
                .put("inputAudioTranscription", JSONObject())
                .put("outputAudioTranscription", JSONObject()))
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/auth_tokens")
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        http.newCall(req).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = JSONObject(raw.ifBlank { "{}" })
            if (!response.isSuccessful) throw IllegalStateException("auth_tokens failed: $raw")
            val name = json.getString("name")
            val exp = json.optString("expireTime", expireTime)
            val expiresAt = java.time.Instant.parse(exp).toEpochMilli()
            return LiveTokenConfig(
                token = name,
                model = "models/gemini-3.1-flash-live-preview",
                websocketUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
                expiresAtMs = expiresAt,
                reservationId = "direct",
                apiKey = apiKey,
            )
        }
    }
}
