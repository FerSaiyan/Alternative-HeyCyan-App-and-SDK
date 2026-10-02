package com.fersaiyan.cyanbridge.ota

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePreviewSourceSafetyTest {
    @Test
    fun `live preview uses only the validated realtime control path`() {
        val source = File(
            "src/main/java/com/fersaiyan/cyanbridge/ota/LivePreviewManager.kt",
        ).readText()

        assertTrue(source.contains("START_LIVE_COMMAND = byteArrayOf(0x02, 0x01, 0x14, 0x01)"))
        assertTrue(source.contains("STOP_LIVE_COMMAND = byteArrayOf(0x02, 0x01, 0x15, 0x01)"))
        assertTrue(source.contains("RTSP_PORTS = intArrayOf(8554, 554)"))
        assertTrue(source.contains("\"ch0\","))
        assertTrue(source.contains("\"testH264VideoStreamer\","))
        assertTrue(source.contains("manager.startPeerDiscovery(allowDeviceResetOnTimeout = false)"))
        assertFalse(source.contains("byteArrayOf(0x02, 0x01, 0x0A)"))
        assertFalse(source.contains("byteArrayOf(0x02, 0x01, 0x0F)"))
        assertFalse(source.contains("PASSIVE MODE"))
    }
}
