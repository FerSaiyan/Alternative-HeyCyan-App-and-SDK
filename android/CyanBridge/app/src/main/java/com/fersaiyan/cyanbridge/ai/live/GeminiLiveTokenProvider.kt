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
            return LiveTokenConfig(
                token = json.getString("token"),
                model = json.getString("model"),
                websocketUrl = json.getString("websocket_url"),
                expiresAtMs = expiresAt,
                reservationId = json.getString("reservation_id"),
            )
        }
    }
}
