package com.fersaiyan.cyanbridge.ai.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveImageTurnsTest {
    private val capabilities = GeminiLiveServerEvent.ProxyCapabilities(clientImageTurns = true, maxImageBytes = 1_000, maxImagesPerTurn = 4)

    @Test
    fun `single turn only after the server announced it, with a prompt and within the size`() {
        val turns = GeminiLiveImageTurns(GeminiLiveImageTurns.Rejection())
        turns.onConnecting()
        assertTrue(turns.requestCapabilities)
        assertFalse("unknown server keeps frame + wait + text", turns.useSingleTurn(500, hasPrompt = true))

        turns.onCapabilities(capabilities)
        assertTrue(turns.useSingleTurn(500, hasPrompt = true))
        assertFalse("a photo without a question stays a realtime frame", turns.useSingleTurn(500, hasPrompt = false))
        assertFalse("larger than the announced cap", turns.useSingleTurn(1_001, hasPrompt = true))
        assertFalse(turns.useSingleTurn(0, hasPrompt = true))

        turns.onConnecting()
        assertFalse("each connection must announce it again", turns.useSingleTurn(500, hasPrompt = true))
        turns.onCapabilities(capabilities.copy(clientImageTurns = false))
        assertFalse(turns.useSingleTurn(500, hasPrompt = true))
    }

    @Test
    fun `a refused single turn switches back to the wait path for the process`() {
        val rejection = GeminiLiveImageTurns.Rejection()
        val turns = GeminiLiveImageTurns(rejection)
        turns.onConnecting()
        turns.onCapabilities(capabilities)
        turns.onSingleTurnSent()

        assertTrue(turns.onSocketClosed(1008, "Unsupported Live message"))
        assertTrue(rejection.rejected)
        assertFalse(turns.requestCapabilities)
        turns.onConnecting()
        turns.onCapabilities(capabilities)
        assertFalse(turns.useSingleTurn(500, hasPrompt = true))
        // Shared by every client in the process.
        val next = GeminiLiveImageTurns(rejection)
        next.onCapabilities(capabilities)
        assertFalse(next.useSingleTurn(500, hasPrompt = true))
    }

    @Test
    fun `answered turns and unrelated closes do not count as a refusal`() {
        val rejection = GeminiLiveImageTurns.Rejection()
        val turns = GeminiLiveImageTurns(rejection)
        turns.onCapabilities(capabilities)
        turns.onSingleTurnSent()
        turns.onModelResponse()
        assertFalse(turns.onSocketClosed(1008, "Unsupported Live message"))

        turns.onCapabilities(capabilities)
        turns.onSingleTurnSent()
        assertFalse(turns.onSocketClosed(1000, "user_stopped"))
        assertFalse(turns.onSocketClosed(1008, "Live image limit reached"))
        assertFalse(rejection.rejected)

        turns.onCapabilities(capabilities)
        turns.onSingleTurnSent()
        assertTrue("Google invalid argument", turns.onSocketClosed(1007, "Request contains an invalid argument."))
    }
}
