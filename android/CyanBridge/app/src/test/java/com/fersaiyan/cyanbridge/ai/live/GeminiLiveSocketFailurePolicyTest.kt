package com.fersaiyan.cyanbridge.ai.live

import com.fersaiyan.cyanbridge.ai.live.GeminiLiveSocketFailurePolicy.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiLiveSocketFailurePolicyTest {
    private val free = config(reservationId = "free-proxy", freeTier = true)
    private val economy = config(reservationId = "free-proxy", economy = true)
    private val private = config(reservationId = "res-1")

    @Test
    fun `only 429 bodies are read`() {
        assertTrue(GeminiLiveSocketFailurePolicy.shouldReadBody(429))
        assertFalse(GeminiLiveSocketFailurePolicy.shouldReadBody(402))
        assertFalse(GeminiLiveSocketFailurePolicy.shouldReadBody(null))
    }

    @Test
    fun `free daily limit uses server message or default text`() {
        assertEquals(
            Outcome.FreeDailyLimit("Server says stop"),
            GeminiLiveSocketFailurePolicy.classify(free, 429, """{"error":"live_free_daily_limit","message":"Server says stop"}""", null),
        )
        assertEquals(
            Outcome.FreeDailyLimit("You've used your 25 free Live minutes for today. Come back tomorrow, or use Pro quota for more access."),
            GeminiLiveSocketFailurePolicy.classify(free, 429, "live_free_daily_limit", null),
        )
    }

    @Test
    fun `economy 4xx is terminal with code specific text`() {
        assertEquals(
            Outcome.EconomyRejected("Live quota exhausted. Please wait for quota reset or upgrade."),
            GeminiLiveSocketFailurePolicy.classify(economy, 402, "", null),
        )
        assertEquals(
            Outcome.EconomyRejected("Live is busy or another session is active. Please try again shortly."),
            GeminiLiveSocketFailurePolicy.classify(economy, 429, """{"message":"queued"}""", null),
        )
        assertEquals(
            Outcome.EconomyRejected("Unable to start Economy Live. Check your Pro subscription and try again."),
            GeminiLiveSocketFailurePolicy.classify(economy, 401, "", null),
        )
        assertEquals(Outcome.Retryable, GeminiLiveSocketFailurePolicy.classify(economy, 500, "", null))
        assertEquals(Outcome.Retryable, GeminiLiveSocketFailurePolicy.classify(economy, null, "", "reset"))
    }

    @Test
    fun `free proxy 429 is always queued and only queued bodies supply detail`() {
        assertEquals(
            Outcome.FreeQueued("Fila cheia"),
            GeminiLiveSocketFailurePolicy.classify(free, 429, """{"error":"live_free_queued","message":"Fila cheia"}""", null),
        )
        assertEquals(Outcome.FreeQueued(null), GeminiLiveSocketFailurePolicy.classify(free, 429, "live_rate_limited", null))
        assertEquals(Outcome.FreeQueued(null), GeminiLiveSocketFailurePolicy.classify(free, 429, """{"message":"other"}""", null))
        assertEquals(
            Outcome.FreeQueued("other"),
            GeminiLiveSocketFailurePolicy.classify(free, 429, """{"message":"other"}""", "Expected HTTP 101 but was 429"),
        )
    }

    @Test
    fun `other failures are retryable`() {
        assertEquals(Outcome.Retryable, GeminiLiveSocketFailurePolicy.classify(private, 429, "live_free_queued", null))
        assertEquals(Outcome.Retryable, GeminiLiveSocketFailurePolicy.classify(free, 503, "", null))
        assertEquals(Outcome.Retryable, GeminiLiveSocketFailurePolicy.classify(private, null, "", "timeout"))
    }

    @Test
    fun `free queue message is localized by language prefix`() {
        assertEquals(
            "Muitos usuários gratuitos no modo Live agora. Você está na fila — tente novamente em instantes.",
            GeminiLiveSocketFailurePolicy.freeQueueMessage("PT-BR"),
        )
        assertEquals(
            "Too many free users on Live mode right now, you are in queue. Please try again in a moment.",
            GeminiLiveSocketFailurePolicy.freeQueueMessage("ja"),
        )
    }

    private fun config(reservationId: String, freeTier: Boolean = false, economy: Boolean = false) =
        LiveTokenConfig("", "m", "wss://x", Long.MAX_VALUE, reservationId, economy = economy, freeTier = freeTier)
}
