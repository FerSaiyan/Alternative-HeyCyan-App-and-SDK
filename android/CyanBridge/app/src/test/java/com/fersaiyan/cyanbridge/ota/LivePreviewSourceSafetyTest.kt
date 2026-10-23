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
        val activitySource = File(
            "src/main/java/com/fersaiyan/cyanbridge/MainActivity.kt",
        ).readText()

        assertTrue(source.contains("glassesControl(byteArrayOf(0x02, 0x01, 0x14, 0x01)"))
        assertTrue(source.contains("glassesControl(byteArrayOf(0x02, 0x01, 0x15, 0x01)"))
        assertTrue(source.contains("syncHeartBeat(4)"))
        assertTrue(source.contains("RtspFrames(glassesIp"))
        assertTrue(source.contains("MjpegServer(MJPEG_PORT)"))
        assertTrue(source.contains("connectToDevice(target, groupOwnerIntent = 15)"))
        assertTrue(source.contains("peers.singleOrNull()"))
        assertTrue(source.contains("will not connect to an arbitrary device"))
        assertFalse(source.contains("byteArrayOf(0x09, 0x0a, 0x0d, 0x0e)"))
        assertTrue(p2pSource.contains("groupOwnerIntent?.let { this.groupOwnerIntent = it }"))
        assertTrue(p2pSource.contains("deviceAddress = peerAddress"))
        assertTrue(p2pSource.contains("wifiP2pManager.connect(wifiP2pChannel, config"))
        assertTrue(p2pSource.contains("private val connectTimeoutMs = 30_000L"))
        assertTrue(p2pSource.contains("ContextCompat.RECEIVER_EXPORTED"))
        assertTrue(p2pSource.contains("reason == WifiP2pManager.BUSY"))
        assertTrue(p2pSource.contains("maxBusyDiscoveryRetries"))
        assertTrue(p2pSource.contains("handler.postDelayed(delayedPeerDiscoveryRestart, busyDiscoveryRetryDelayMs)"))
        assertTrue(p2pSource.contains("WIFI_P2P_CONNECTION_CHANGED_ACTION"))
        assertFalse(source.contains("Settings.ACTION_WIFI_SETTINGS"))
        assertTrue(source.contains("manager.startPeerDiscovery(allowDeviceResetOnTimeout = false)"))
        assertTrue(source.contains("private const val P2P_CONNECT_TIMEOUT_MS = 30_000L"))
        assertTrue(activitySource.contains("if (lp.isPlaying && lp.streamUrl != null)"))
        assertTrue(activitySource.contains("showRtspPlayerDialog(lp.streamUrl)"))
        assertTrue(activitySource.contains("loadDataWithBaseURL("))
        assertTrue(activitySource.contains("img src=\\\"/mjpeg\\\""))
    }
}
