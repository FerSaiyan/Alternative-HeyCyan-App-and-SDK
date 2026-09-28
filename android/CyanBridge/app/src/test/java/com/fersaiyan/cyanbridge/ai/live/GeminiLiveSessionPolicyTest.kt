package com.fersaiyan.cyanbridge.ai.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveSessionPolicyTest {
    @Test fun idleTimeoutBeginsAfterResponseAndLastsThirtySeconds() {
        assertEquals(30_000L, GeminiLiveSessionPolicy.POST_RESPONSE_IDLE_MS)
    }

    @Test fun freeAndEconomyEndWithoutAutomaticRestartAtFiveMinutes() {
        assertEquals(300_000L, GeminiLiveSessionPolicy.METERED_SESSION_LIMIT_MS)
        assertTrue(GeminiLiveSessionPolicy.requiresExplicitRestart(freeTier = true, economy = false))
        assertTrue(GeminiLiveSessionPolicy.requiresExplicitRestart(freeTier = false, economy = true))
        assertTrue(GeminiLiveSessionPolicy.requiresExplicitRestart(freeTier = true, economy = true))
    }

    @Test fun privateLivePreservesExistingSessionResumption() {
        assertFalse(GeminiLiveSessionPolicy.requiresExplicitRestart(freeTier = false, economy = false))
    }
}
