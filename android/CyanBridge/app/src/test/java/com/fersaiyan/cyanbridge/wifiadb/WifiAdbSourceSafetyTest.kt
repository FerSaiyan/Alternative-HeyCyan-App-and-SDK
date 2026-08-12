package com.fersaiyan.cyanbridge.wifiadb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiAdbSourceSafetyTest {
    private val debugSource = File(
        "src/debug/java/com/fersaiyan/cyanbridge/wifiadb/DefaultWifiAdbDebugControllerFactory.kt",
    ).readText()
    private val releaseSource = File(
        "src/release/java/com/fersaiyan/cyanbridge/wifiadb/DefaultWifiAdbDebugControllerFactory.kt",
    ).readText()
    private val relaySource = File(
        "src/debug/java/com/fersaiyan/cyanbridge/wifiadb/RawTcpRelay.kt",
    ).readText()
    private val p2pSource = File(
        "src/main/java/com/fersaiyan/cyanbridge/ui/wifi/p2p/WifiP2pManagerSingleton.kt",
    ).readText()

    @Test
    fun debugProtocolUsesOnlyTheConfirmedEntryAndSingleExitCommands() {
        val commandPayloads = Regex("""byteArrayOf\(([^)]*)\)""")
            .findAll(debugSource)
            .map { it.groupValues[1].replace(" ", "") }
            .toList()

        assertEquals(listOf("0x02,0x01,0x04", "0x02,0x01,0x09"), commandPayloads)
        assertEquals(2, Regex("""\bglassesControl\s*\(""").findAll(debugSource).count())
        assertTrue(debugSource.contains("addOutDeviceListener(2"))
        assertTrue(debugSource.contains("data[6].toInt() and 0xFF"))
        assertTrue(debugSource.contains("manager.startPeerDiscovery(allowDeviceResetOnTimeout = false)"))
        assertTrue(debugSource.contains("network.socketFactory.createSocket()"))
        assertFalse(debugSource.contains("bindProcessToNetwork"))
        assertFalse(debugSource.contains("0x0F", ignoreCase = true))
        assertFalse(debugSource.contains("candidate", ignoreCase = true))
        assertTrue(debugSource.contains("activeGeneration"))
        assertTrue(debugSource.contains("isCurrentGeneration(generation)"))
        assertTrue(debugSource.contains("if (!entryAcknowledged || exitSent) return"))
        assertTrue(debugSource.contains("if (!p2pTeardownConfirmed || !exitAcknowledged) return"))
        assertTrue(debugSource.contains("removeGlassesControlCallback()"))
        assertTrue(relaySource.contains("InetAddress.getByName(\"127.0.0.1\")"))
        assertFalse(relaySource.contains("bind(InetSocketAddress(listenPort))"))
        assertTrue(
            p2pSource.contains(
                "startPeerDiscovery(allowDeviceResetOnTimeout = allowDeviceResetOnDiscoveryTimeout)",
            ),
        )
    }

    @Test
    fun releaseFactoryIsInertAndMainHandlerIsDebugGated() {
        assertTrue(releaseSource.contains("InertWifiAdbDebugController"))
        assertTrue(releaseSource.contains("override val isActive: Boolean = false"))
        assertFalse(releaseSource.contains("LargeDataHandler"))
        assertFalse(releaseSource.contains("WifiP2pManager"))
        assertFalse(releaseSource.contains("ServerSocket"))
        assertFalse(releaseSource.contains("Socket("))

        val mainSource = File("src/main/java/com/fersaiyan/cyanbridge/MainActivity.kt").readText()
        assertTrue(mainSource.contains("wifiAdbDebug = WifiAdbDebugUiState(isAvailable = false)"))
        assertTrue(mainSource.contains("if (BuildConfig.DEBUG && isHeyCyanSelected()) startWifiAdbDebug()"))
        assertTrue(mainSource.contains("isAvailable = BuildConfig.DEBUG && isHeyCyanSelected() && runtime.isAvailable"))
        assertFalse(mainSource.contains("isAvailable = BuildConfig.DEBUG && showBattery"))
    }
}
