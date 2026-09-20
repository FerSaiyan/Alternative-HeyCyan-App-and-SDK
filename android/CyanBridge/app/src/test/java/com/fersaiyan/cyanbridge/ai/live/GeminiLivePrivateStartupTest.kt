package com.fersaiyan.cyanbridge.ai.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLivePrivateStartupTest {
    @Test
    fun `active economy reservation is diagnosed instead of blaming Google`() {
        val failure = LiveTokenRequestException(
            errorCode = "live_concurrent_session",
            httpStatus = 429,
            retryAfterMs = 91_000,
            activeMode = "economy",
        )
        assertEquals(429, failure.httpStatus)
        assertEquals("live_concurrent_session", failure.errorCode)
        assertTrue(failure.message.orEmpty().contains("economy session is still active"))
        assertTrue(failure.message.orEmpty().contains("91 seconds"))
    }

    @Test
    fun `rate limit keeps retry-after duration visible to the user`() {
        val failure = LiveTokenRequestException(
            errorCode = "live_rate_limited",
            httpStatus = 429,
            retryAfterMs = 61_000,
        )
        assertTrue(failure.message.orEmpty().contains("61 seconds"))
        assertEquals("live_rate_limited", failure.errorCode)
    }
}
