package com.fersaiyan.cyanbridge.ai.live

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeminiLiveRelayPayloadsTest {
    @Test
    fun `reservation end body`() {
        assertEquals(
            """{"reservation_id":"res-1","input_audio_ms":1200,"output_audio_ms":3400,"image_count":2}""",
            GeminiLiveRelayPayloads.reservationEnd("res-1", 1_200L, 3_400L, 2),
        )
    }

    @Test
    fun `usage body forwards usageMetadata verbatim or audio deltas`() {
        assertEquals(
            """{"reservation_id":"res-1","usage_metadata":{"totalTokenCount":42,"promptTokenCount":40}}""",
            GeminiLiveRelayPayloads.usage("res-1", JSONObject("""{"totalTokenCount":42,"promptTokenCount":40}"""), 0, 0, 0),
        )
        assertEquals(
            """{"reservation_id":"res-1","delta_input_audio_ms":120,"delta_output_audio_ms":340,"delta_image_count":1,""" +
                """"input_audio_ms":120,"output_audio_ms":340,"image_count":1}""",
            GeminiLiveRelayPayloads.usage("res-1", null, 120L, 340L, 1),
        )
    }

    @Test
    fun `vision diagnostics body per mode`() {
        val snapshot = GeminiLiveVisionDiagnostics.Snapshot(9, 3, 2, 1, 4, 5, 6, 7)
        val counters = """"revision":9,"capture_attempts":3,"captures_completed":2,"capture_failures":1,""" +
            """"images_queued":4,"send_failures":5,"photo_ready_timeouts":6,"transfer_failures":7}"""
        assertEquals(
            """{"report_id":"rep","session_id":"res-1","mode":"private","device_class":"UNKNOWN","refresh_mode":"initial_only",""" + counters,
            GeminiLiveRelayPayloads.visionDiagnostics(config("res-1"), "rep", "UNKNOWN", true, snapshot),
        )
        assertEquals(
            """{"report_id":"rep","session_id":"rep","mode":"free","device_class":"MENTRA_LIVE","refresh_mode":"every_turn",""" + counters,
            GeminiLiveRelayPayloads.visionDiagnostics(config("free-proxy", freeTier = true), "rep", "MENTRA_LIVE", false, snapshot),
        )
        assertEquals(
            """{"report_id":"rep","session_id":"rep","mode":"economy","device_class":"UNKNOWN","refresh_mode":"every_turn",""" + counters,
            GeminiLiveRelayPayloads.visionDiagnostics(config("free-proxy", freeTier = true, economy = true), "rep", "UNKNOWN", false, snapshot),
        )
    }

    private fun config(reservationId: String, freeTier: Boolean = false, economy: Boolean = false) =
        LiveTokenConfig("", "m", "wss://x", Long.MAX_VALUE, reservationId, economy = economy, freeTier = freeTier)
}
