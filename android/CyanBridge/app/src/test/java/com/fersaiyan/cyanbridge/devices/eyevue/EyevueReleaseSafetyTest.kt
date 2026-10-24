package com.fersaiyan.cyanbridge.devices.eyevue

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EyevueReleaseSafetyTest {
    private val mainSource = File("src/main/java/com/fersaiyan/cyanbridge/MainActivity.kt").readText()
    private val connectionCoordinatorSource = File(
        "src/main/java/com/fersaiyan/cyanbridge/ui/main/DeviceConnectionCoordinator.kt",
    ).readText()
    private val dashboardControllerSource = File(
        "src/main/java/com/fersaiyan/cyanbridge/ui/main/DashboardController.kt",
    ).readText()
    private val mainScreenSources = mainSource + File("src/main/java/com/fersaiyan/cyanbridge/ui/main")
        .listFiles { file -> file.extension == "kt" }!!
        .sortedBy { it.name }
        .joinToString("\n") { it.readText() }
    private val managerSource = File(
        "src/main/java/com/fersaiyan/cyanbridge/devices/eyevue/EyevueManager.kt",
    ).readText()
    private val connectionSource = File(
        "src/main/java/com/fersaiyan/cyanbridge/devices/eyevue/EyevueLiveConnection.kt",
    ).readText()
    private val mediaSource = File(
        "src/main/java/com/fersaiyan/cyanbridge/devices/eyevue/EyevueMediaSync.kt",
    ).readText()

    @Test
    fun eyevueMediaSyncUsesVerifiedWifiActivationCommand() {
        assertTrue(mediaSource.contains("manager.awaitWifiSsid("))
        assertFalse(mediaSource.contains("manager.startLiveAndAwaitSsid"))
    }

    @Test
    fun eyevueLivePreviewIsReleaseEnabledWithoutExtraWifiCommands() {
        assertTrue(
            connectionCoordinatorSource.contains(
                "isAvailable = EyevueLivePreviewPolicy.isSupported(Build.VERSION.SDK_INT)",
            ),
        )
        assertFalse(mainScreenSources.contains("Eyevue live preview is available in debug builds only"))
        assertTrue(dashboardControllerSource.contains("eyevueLivePreviewManager?.isActive == true"))

        val liveCommandBlock = managerSource.substringAfter("suspend fun startLiveAndAwaitSsid")
            .substringBefore("suspend fun stopLiveBlocking")
        assertFalse(liveCommandBlock.contains("requestWifiInfo"))
        assertEquals(1, Regex("manager\\.stopLiveBlocking\\(\\)").findAll(connectionSource).count())
    }
}
