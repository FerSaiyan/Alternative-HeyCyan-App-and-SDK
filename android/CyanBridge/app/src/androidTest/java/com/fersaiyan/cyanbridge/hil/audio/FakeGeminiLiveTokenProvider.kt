package com.fersaiyan.cyanbridge.hil.audio

import com.fersaiyan.cyanbridge.ai.live.GeminiLiveTokenProvider
import com.fersaiyan.cyanbridge.ai.live.LiveTokenConfig

class FakeGeminiLiveTokenProvider(
    private val token: String = "fake-token",
    private val model: String = "gemini-live-test",
    private val websocketUrl: String = "wss://example.invalid/gemini-live",
    private val expiresAtMs: Long = System.currentTimeMillis() + 10 * 60 * 1000L,
    private val reservationId: String = "test-reservation",
) : GeminiLiveTokenProvider {
    var lastLanguage: String? = null
    var lastImagePrompt: String? = null
    var requestCount = 0
        private set

    override suspend fun requestToken(language: String, imagePrompt: String): LiveTokenConfig {
        lastLanguage = language
        lastImagePrompt = imagePrompt
        requestCount++
        return LiveTokenConfig(token, model, websocketUrl, expiresAtMs, reservationId)
    }
}
