package com.fersaiyan.cyanbridge.ota

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePreviewSourceSafetyTest {
    @Test
    fun `live preview starts the glasses stream and owns its local transport`() {
        val source = File(
            "src/main/java/com/fersaiyan/cyanbridge/ota/LivePreviewManager.kt",
        ).readText()
        val p2pSource = File(
            "src/main/java/com/fersaiyan/cyanbridge/ui/wifi/p2p/WifiP2pManagerSingleton.kt",
        ).readText()

        assertTrue(source.contains("glassesControl(byteArrayOf(0x02, 0x01, 0x14, 0x01)"))
        assertTrue(source.contains("glassesControl(byteArrayOf(0x02, 0x01, 0x15, 0x01)"))
        assertTrue(source.contains("syncHeartBeat(4)"))
        assertTrue(source.contains("RtspFrames(glassesIp"))
        assertTrue(source.contains("MjpegServer(MJPEG_PORT)"))
        assertTrue(source.contains("connectToDevice(target, groupOwnerIntent = 15)"))
        assertFalse(source.contains("byteArrayOf(0x09, 0x0a, 0x0d, 0x0e)"))
        assertTrue(p2pSource.contains("groupOwnerIntent?.let { this.groupOwnerIntent = it }"))
        assertTrue(source.contains("manager.startPeerDiscovery(allowDeviceResetOnTimeout = false)"))
    }
}
