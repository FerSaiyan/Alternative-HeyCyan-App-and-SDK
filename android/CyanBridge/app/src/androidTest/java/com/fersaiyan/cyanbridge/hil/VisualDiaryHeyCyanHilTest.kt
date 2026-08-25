package com.fersaiyan.cyanbridge.hil

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.localagent.userfacts.CandidateUserFactsStorage
import com.fersaiyan.cyanbridge.plugins.visualdiary.VisualDiaryPreferences
import com.fersaiyan.cyanbridge.plugins.visualdiary.VisualDiaryService
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.oudmon.ble.base.bluetooth.BleOperateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class VisualDiaryHeyCyanHilTest {
    @Test
    fun taskerPeriodicTriggerCapturesRealHeyCyanThumbnail() {
        assumeTrue(
            "Real glasses HIL is disabled. Set CYANBRIDGE_HIL_ENABLE_GLASSES=true on the lab workflow when the dedicated phone and HeyCyan glasses are ready.",
            HilTestSupport.glassesRequired,
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        HilTestSupport.requireTaskerStack(context)
        assertEquals(
            "The dedicated HIL phone must have HeyCyan selected before enabling the glasses suite",
            DeviceClass.HEY_CYAN,
            DeviceProfileStore.selectedClass(context),
        )
        assertTrue(
            "HeyCyan BLE did not reconnect after installing the branch build",
            waitUntil(timeoutMs = 35_000L) { BleOperateManager.getInstance().isConnected },
        )

        val wasEnabled = VisualDiaryPreferences.isEnabled(context)
        val beforeCandidates = CandidateUserFactsStorage.load(context, today()).toSet()
        val beforeFiles = heyCyanThumbnails(context).associateBy { it.absolutePath }

        try {
            assertTrue("Visual Diary could not be enabled for the selected HeyCyan device", VisualDiaryService.enable(context))
            Thread.sleep(900)

            context.sendBroadcast(
                Intent(ACTION_HIL_VISUALDIARY_NOW)
                    .setPackage(HilTestSupport.TASKER_PACKAGE),
            )

            val captured = waitForNewThumbnail(context, beforeFiles.keys, timeoutMs = 40_000L)
            assertNotNull(
                "Tasker triggered Visual Diary, but no new HeyCyan AUTO_LOOP_THUMB image arrived",
                captured,
            )
            assertTrue(
                "The new HeyCyan thumbnail is too small to be a usable capture: ${captured?.length() ?: 0L} bytes",
                (captured?.length() ?: 0L) >= MIN_IMAGE_BYTES,
            )

            if (HilTestSupport.expectVisualFact) {
                assertTrue(
                    "A real image was captured, but the configured visual model did not append a new 'Glasses scene' candidate fact",
                    waitUntil(timeoutMs = 90_000L) {
                        CandidateUserFactsStorage.load(context, today())
                            .filterNot { it in beforeCandidates }
                            .any { it.startsWith("Glasses scene ") }
                    },
                )
            }
        } finally {
            if (!wasEnabled) VisualDiaryService.disable(context)
        }
    }

    private fun waitForNewThumbnail(
        context: android.content.Context,
        previousPaths: Set<String>,
        timeoutMs: Long,
    ): File? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val candidate = heyCyanThumbnails(context)
                .filterNot { it.absolutePath in previousPaths }
                .filter { it.length() >= MIN_IMAGE_BYTES }
                .maxByOrNull { it.lastModified() }
            if (candidate != null) return candidate
            Thread.sleep(500)
        }
        return heyCyanThumbnails(context)
            .filterNot { it.absolutePath in previousPaths }
            .filter { it.length() >= MIN_IMAGE_BYTES }
            .maxByOrNull { it.lastModified() }
    }

    private fun heyCyanThumbnails(context: android.content.Context): List<File> {
        val dir = context.getExternalFilesDir("DCIM") ?: return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("AUTO_LOOP_THUMB_") && it.name.endsWith(".jpg") }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(500)
        }
        return condition()
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    companion object {
        private const val ACTION_HIL_VISUALDIARY_NOW = "com.fersaiyan.cyanbridge.HIL_VISUALDIARY_NOW"
        private const val MIN_IMAGE_BYTES = 1_024L
    }
}
