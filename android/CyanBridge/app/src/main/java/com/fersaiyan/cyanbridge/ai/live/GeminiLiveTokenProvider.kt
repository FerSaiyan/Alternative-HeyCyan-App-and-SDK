package com.fersaiyan.cyanbridge.ai.live

import android.content.Context
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionRelayClient
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import java.time.Instant
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    val authorizationHeader: String? = null,
    /** Optional API key reserved for explicit local/debug providers. Production Pro never receives one. */
    val apiKey: String? = null,
    /** Optional setup override retained for provider/test compatibility. */
    val setupJson: String? = null,
)

interface GeminiLiveTokenProvider {
    suspend fun requestToken(language: String, imagePrompt: String): LiveTokenConfig
}

class DefaultGeminiLiveTokenProvider(
    private val appContext: Context,
    private val http: OkHttpClient = OkHttpClient(),
) : GeminiLiveTokenProvider {
    override suspend fun requestToken(language: String, imagePrompt: String): LiveTokenConfig {
        val authToken = ProSubscriptionRelayClient.fetchAccountInfo(appContext)
            .getOrThrow()
            .apiToken
            .trim()
        check(authToken.isNotBlank()) { "Sign in to CyanBridge before starting Gemini Live" }
        val base = AiProviderPrefs.getRelayBaseUrl(appContext).trim().trimEnd('/')
        check(base.startsWith("https://")) { "Gemini Live requires a secure relay URL" }
        val paidPlan = ProSubscriptionPrefs.isActiveLocally(appContext) &&
            ProSubscriptionPrefs.getPlan(appContext).lowercase() in setOf("cheap", "standard", "max")
        if (!paidPlan) {
            val httpUrl = base.toHttpUrl().newBuilder()
                .addPathSegments("api/pro/live/free")
                .addQueryParameter("language", language)
                .addQueryParameter("image_prompt", imagePrompt.take(400))
                .build()
                .toString()
            val websocketUrl = httpUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
            return LiveTokenConfig(
                token = "",
                model = "models/gemini-3.1-flash-live-preview",
                websocketUrl = websocketUrl,
                expiresAtMs = System.currentTimeMillis() + 13 * 60 * 1000L,
                reservationId = "free-proxy",
                authorizationHeader = "Bearer $authToken",
            )
        }
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
                val error = json.optString("error", "Gemini Live token request failed")
                // Surface quota details for live_quota_exhausted so the UI can show remaining vs required.
                if (error == "live_quota_exhausted" || raw.contains("live_quota_exhausted")) {
                    val quota = json.optJSONObject("quota")
                    val plan = quota?.optString("plan") ?: json.optString("plan").takeIf { it.isNotBlank() } ?: ""
                    val remaining = quota?.optInt("remaining", -1)?.takeIf { it >= 0 }?.toString()
                        ?: json.optString("remaining", "")
                    val required = json.optInt("required_reference_tokens", -1).takeIf { it >= 0 }?.toString()
                        ?: json.optString("required", "")
                    val detail = buildString {
                        append(error)
                        if (plan.isNotBlank()) append(" plan $plan")
                        if (remaining?.isNotBlank() == true) append(" remaining $remaining")
                        if (required?.isNotBlank() == true) append(" required $required")
                        // Fall back to raw quota dump for debugging if fields are missing
                        if (plan.isBlank() && quota != null) append(" quota $quota")
                    }
                    throw IllegalStateException(detail)
                }
                throw IllegalStateException(error)
            }
            val expiresAt = Instant.parse(json.getString("expire_time")).toEpochMilli()
            // Production Pro uses Google\'s client-to-server ephemeral-token flow.
            // The server returns a BidiGenerateContentConstrained URL containing only
            // the short-lived access_token. No long-lived Google API key is sent to Android.
            return LiveTokenConfig(
                token = json.getString("token"),
                model = json.getString("model"),
                websocketUrl = json.getString("websocket_url"),
                expiresAtMs = expiresAt,
                reservationId = json.getString("reservation_id"),
                authorizationHeader = null,
                apiKey = null,
                setupJson = null,
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
            val websocketUrl = "https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("access_token", name)
                .build()
                .toString()
                .replaceFirst("https://", "wss://")
            return LiveTokenConfig(
                token = name,
                model = "models/gemini-3.1-flash-live-preview",
                websocketUrl = websocketUrl,
                expiresAtMs = expiresAt,
                reservationId = "direct",
                authorizationHeader = null,
                apiKey = null,
            )
        }
    }
}
