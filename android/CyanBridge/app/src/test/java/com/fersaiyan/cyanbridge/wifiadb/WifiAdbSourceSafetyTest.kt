package com.fersaiyan.cyanbridge.wifiadb

import com.fersaiyan.cyanbridge.bridge.core.GlassesCapability
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
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
        val coordinatorSource = File(
            "src/main/java/com/fersaiyan/cyanbridge/wifiadb/WifiAdbDebugCoordinator.kt",
        ).readText()
        assertTrue(mainSource.contains("wifiAdbDebug = WifiAdbDebugUiState(isAvailable = false)"))
        val dashboardControllerSource = File(
            "src/main/java/com/fersaiyan/cyanbridge/ui/main/DashboardController.kt",
        ).readText()
        val connectionCoordinatorSource = File(
            "src/main/java/com/fersaiyan/cyanbridge/ui/main/DeviceConnectionCoordinator.kt",
        ).readText()
        assertTrue(
            dashboardControllerSource.contains(
                "if (BuildConfig.DEBUG && family.supports(GlassesCapability.WIFI_ADB_DEBUG)) actions.startWifiAdbDebug()",
            ),
        )
        // Only HeyCyan declares the Wi-Fi ADB capability.
        assertEquals(
            setOf(DeviceClass.HEY_CYAN),
            GlassesFamilyProfiles.all
                .filter { it.supports(GlassesCapability.WIFI_ADB_DEBUG) }
                .map { it.deviceClass }
                .toSet(),
        )
        assertTrue(mainSource.contains("private fun startWifiAdbDebug() = wifiAdbDebugCoordinator.startWifiAdbDebug()"))
        assertTrue(coordinatorSource.contains("if (!BuildConfig.DEBUG || wifiAdbDebugController.isActive) return"))
        assertTrue(
            coordinatorSource.contains("isAvailable = BuildConfig.DEBUG && isHeyCyanSelected() && runtime.isAvailable"),
        )
        assertTrue(coordinatorSource.contains("private fun isHeyCyanSelected(): Boolean = host.isHeyCyanSelected()"))
        // The dashboard gating lives in :shared; the coordinator passes the build type in.
        val dashboardGatingSource = File(
            "../shared/src/commonMain/kotlin/com/fersaiyan/cyanbridge/shared/glasses/GlassesDashboardPresentation.kt",
        ).readText()
        assertTrue(connectionCoordinatorSource.contains("isDebugBuild = BuildConfig.DEBUG,"))
        assertTrue(
            dashboardGatingSource.contains(
                "isAvailable = isDebugBuild &&\n                model.isVisible(Action.WIFI_ADB_DEBUG),",
            ),
        )
        assertFalse(mainSource.contains("isAvailable = BuildConfig.DEBUG && showBattery"))
        assertFalse(coordinatorSource.contains("isAvailable = BuildConfig.DEBUG && showBattery"))
        assertFalse(connectionCoordinatorSource.contains("isAvailable = BuildConfig.DEBUG && showBattery"))
        assertFalse(dashboardGatingSource.contains("isAvailable = isDebugBuild && showBattery"))
    }
}
