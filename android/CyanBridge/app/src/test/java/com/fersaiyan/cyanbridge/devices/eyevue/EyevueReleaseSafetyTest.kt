package com.fersaiyan.cyanbridge.devices.eyevue

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EyevueReleaseSafetyTest {
    private val mainSource = File("src/main/java/com/fersaiyan/cyanbridge/MainActivity.kt").readText()
    private val managerSource = File(
        "src/main/java/com/fersaiyan/cyanbridge/devices/eyevue/EyevueManager.kt",
    ).readText()
    private val liveSource = File(
        "src/main/java/com/fersaiyan/cyanbridge/devices/eyevue/EyevueLivePreviewManager.kt",
    ).readText()

    @Test
    fun eyevueLivePreviewIsReleaseEnabledWithoutExtraWifiCommands() {
        assertTrue(
            mainSource.contains(
                "isAvailable = EyevueLivePreviewPolicy.isSupported(Build.VERSION.SDK_INT)",
            ),
        )
        assertFalse(mainSource.contains("Eyevue live preview is available in debug builds only"))
        assertTrue(mainSource.contains("eyevueLivePreviewManager?.isActive == true"))

        val liveCommandBlock = managerSource.substringAfter("suspend fun startLiveAndAwaitSsid")
            .substringBefore("suspend fun stopLiveBlocking")
        assertFalse(liveCommandBlock.contains("requestWifiInfo"))
        assertEquals(1, Regex("eyevueManager\\.stopLiveBlocking\\(\\)").findAll(liveSource).count())
    }
}
