package com.fersaiyan.cyanbridge.ai.live

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeminiLiveSessionStateTest {
    private val session = GeminiLiveSessionState()

    @Test
    fun `audio bytes are metered as PCM16 milliseconds per packet`() {
        session.addInputAudioBytes(1_280)
        session.addInputAudioBytes(31) // rounds down to 0 ms per packet
        session.addOutputAudioBytes(4_800)
        session.addOutputAudioBytes(47)

        assertEquals(40L, session.inputAudioMs)
        assertEquals(100L, session.outputAudioMs)
    }

    @Test
    fun `usage metadata is deduplicated by total tokens`() {
        assertFalse(session.acceptUsageMetadata(JSONObject()))
        assertFalse(session.acceptUsageMetadata(JSONObject("""{"totalTokenCount":0,"promptTokenCount":0}""")))
        assertTrue(session.acceptUsageMetadata(JSONObject("""{"totalTokenCount":10}""")))
        assertFalse(session.acceptUsageMetadata(JSONObject("""{"totalTokenCount":10}""")))
        assertTrue(session.acceptUsageMetadata(JSONObject("""{"promptTokenCount":4,"responseTokenCount":3}""")))
        assertFalse(session.acceptUsageMetadata(JSONObject("""{"promptTokenCount":5,"responseTokenCount":2}""")))
        assertTrue(session.acceptUsageMetadata(JSONObject("""{"totalTokenCount":11}""")))
    }

    @Test
    fun `turn usage reports growth since the last billed turn`() {
        assertNull(session.pendingTurnUsage())
        session.addInputAudioBytes(3_200)
        session.addOutputAudioBytes(48_000)
        session.visionDiagnostics.send(ready = true, bytes = byteArrayOf(1), limit = 10) { true }

        assertEquals(GeminiLiveSessionState.TurnUsage(100L, 1_000L, 1), session.pendingTurnUsage())
        session.markTurnBilled()
        assertNull(session.pendingTurnUsage())

        session.addInputAudioBytes(32)
        assertEquals(GeminiLiveSessionState.TurnUsage(1L, 0L, 0), session.pendingTurnUsage())
    }

    @Test
    fun `reset for start clears metering, flags and rotates the vision report id`() {
        session.addInputAudioBytes(3_200)
        session.markTurnBilled()
        session.acceptUsageMetadata(JSONObject("""{"totalTokenCount":10}"""))
        session.reconnectAttempt = 3
        session.meteredConnectionAttempted.set(true)
        session.setupComplete.set(true)
        session.speechActive.set(true)
        session.modelSpeaking.set(true)
        session.visionReportConfig = LiveTokenConfig("", "m", "wss://x", 0L, "r")
        val previousReportId = session.visionReportId

        session.resetForStart()

        assertEquals(0L, session.inputAudioMs)
        assertEquals(0, session.reconnectAttempt)
        assertFalse(session.meteredConnectionAttempted.get())
        assertFalse(session.setupComplete.get())
        assertFalse(session.speechActive.get())
        assertFalse(session.modelSpeaking.get())
        assertNull(session.visionReportConfig)
        assertNotEquals(previousReportId, session.visionReportId)
        assertNull(session.pendingTurnUsage())
        assertTrue(session.acceptUsageMetadata(JSONObject("""{"totalTokenCount":10}""")))
    }

    @Test
    fun `listening duration is zero until setup completed`() {
        assertEquals(0L, session.listeningDurationMs(5_000L))
        session.socketListeningStartedAtMs = 1_000L
        assertEquals(4_000L, session.listeningDurationMs(5_000L))
    }
}
