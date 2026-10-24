package com.fersaiyan.cyanbridge.network

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps the glasses network lifecycle in one owner. */
class GlassesNetworkOwnershipSourceTest {
    private val sourceRoots = listOf(File("src/main/java"), File("src/debug/java"))
    private val sources: Map<String, String> = sourceRoots
        .flatMap { root -> root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList() }
        .associate { it.invariantSeparatorsPath to it.readText() }
    private val coordinatorPath =
        "src/main/java/com/fersaiyan/cyanbridge/network/GlassesNetworkSessionCoordinator.kt"
    private val coordinatorSource = File(coordinatorPath).readText()

    private fun filesCalling(regex: Regex): Set<String> =
        sources.filterValues { regex.containsMatchIn(it) }.keys

    @Test
    fun onlyTheSessionCoordinatorBindsTheProcessNetwork() {
        // Direct ConnectivityManager calls; delegations through the coordinator are allowed.
        val direct = Regex("""(?<!GlassesNetworkSessionCoordinator|p2pNetwork)\.bindProcessToNetwork\(""")
        assertEquals(setOf(coordinatorPath), filesCalling(direct))
    }

    @Test
    fun onlyTheSessionAndItsDriversRemoveWifiDirectGroups() {
        assertEquals(
            setOf(
                coordinatorPath,
                "src/main/java/com/fersaiyan/cyanbridge/network/WifiP2pChannelGroupDriver.kt",
                "src/main/java/com/fersaiyan/cyanbridge/ui/wifi/p2p/WifiP2pManagerSingleton.kt",
            ),
            filesCalling(Regex("""\.removeGroup\(""")),
        )
    }

    @Test
    fun heyCyanWorkflowsRegisterTheP2pReceiverOnlyThroughTheSession() {
        val registering = filesCalling(Regex("""manager\.registerReceiver\(\)|wifiP2pManager\.registerReceiver\(\)"""))
        assertEquals(setOf(coordinatorPath), registering)
    }

    @Test
    fun sessionTeardownNeverResetsTheGlassesOrRestartsDiscovery() {
        // Live preview must never trigger the V821 0x0F reset, and teardown must not rearm discovery.
        assertFalse(coordinatorSource.contains("resetDeviceP2p"))
        assertFalse(coordinatorSource.contains("0x0F", ignoreCase = true))
        assertFalse(coordinatorSource.contains("startPeerDiscovery"))
        assertTrue(coordinatorSource.contains("manager.stopP2pOperations()"))
        assertTrue(coordinatorSource.contains("manager.cancelP2pConnection()"))
    }

    @Test
    fun everyGlassesLinkWorkflowClaimsTheLinkFromTheSession() {
        val expected = mapOf(
            "src/main/java/com/fersaiyan/cyanbridge/ota/OtaManager.kt" to "GlassesLinkClient.HEYCYAN_OTA",
            "src/main/java/com/fersaiyan/cyanbridge/ota/LivePreviewManager.kt" to "GlassesLinkClient.HEYCYAN_LIVE_PREVIEW",
            "src/main/java/com/fersaiyan/cyanbridge/media/sync/GlassesMediaSyncCoordinator.kt" to
                "GlassesLinkClient.HEYCYAN_MEDIA_SYNC",
            "src/main/java/com/fersaiyan/cyanbridge/devices/eyevue/EyevueWifiTransport.kt" to
                "GlassesLinkClient.EYEVUE_WIFI",
            "src/main/java/com/fersaiyan/cyanbridge/devices/tunebuds/TuneBudsP2pTransport.kt" to
                "GlassesLinkClient.TUNEBUDS_WIFI_DIRECT",
            "src/debug/java/com/fersaiyan/cyanbridge/wifiadb/DefaultWifiAdbDebugControllerFactory.kt" to
                "GlassesLinkClient.HEYCYAN_WIFI_ADB",
        )
        expected.forEach { (path, client) ->
            assertTrue("$path must claim $client", sources.getValue(path).contains(client))
        }
    }
}
