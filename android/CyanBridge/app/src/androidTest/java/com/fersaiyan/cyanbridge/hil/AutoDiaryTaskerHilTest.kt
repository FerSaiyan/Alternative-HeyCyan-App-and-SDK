package com.fersaiyan.cyanbridge.hil

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.plugins.autodiary.AutoDiaryService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AutoDiaryTaskerHilTest {
    @Test
    fun periodicHandlerStoresAllowedScreenAndSkipsExcludedPackage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        HilTestSupport.requireTaskerStack(context)

        ActivityScenario.launch(HilFixtureActivity::class.java).use {
            AutoDiaryService.enable(context)
            Thread.sleep(900)

            try {
                setExcludedPackages(context, "")
                Thread.sleep(500)

                val beforeAllowed = markerCount(context)
                triggerPeriodicCapture(context)
                assertTrue(
                    "Tasker periodic AutoDiary handler did not store the fixture through the existing Memory Vault pipeline",
                    waitUntil(timeoutMs = 12_000L) { markerCount(context) > beforeAllowed },
                )

                setExcludedPackages(context, context.packageName)
                Thread.sleep(800)
                val beforeBlocked = markerCount(context)
                triggerPeriodicCapture(context)
                Thread.sleep(3_000)
                val afterBlocked = markerCount(context)
                assertEquals(
                    "AutoDiary exclusion variable did not block the current package before Tasker sent screen contents",
                    beforeBlocked,
                    afterBlocked,
                )
            } finally {
                // Never leave the shared lab phone with the fixture app excluded after a failed test.
                setExcludedPackages(context, "")
            }
        }
    }

    private fun triggerPeriodicCapture(context: android.content.Context) {
        context.sendBroadcast(
            Intent(ACTION_HIL_AUTODIARY_NOW)
                .setPackage(HilTestSupport.TASKER_PACKAGE),
        )
    }

    private fun setExcludedPackages(context: android.content.Context, packages: String) {
        context.sendBroadcast(
            Intent(ACTION_HIL_SET_AUTODIARY_EXCLUDED)
                .setPackage(HilTestSupport.TASKER_PACKAGE)
                .putExtra("packages", packages),
        )
    }

    private fun markerCount(context: android.content.Context): Int =
        LocalAgentMemoryStore.readScreenCaptureLines(context, today(), maxLines = 500)
            .count { it.contains(HilFixtureActivity.MARKER_TEXT) }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(250)
        }
        return condition()
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    companion object {
        private const val ACTION_HIL_AUTODIARY_NOW = "com.fersaiyan.cyanbridge.HIL_AUTODIARY_NOW"
        private const val ACTION_HIL_SET_AUTODIARY_EXCLUDED =
            "com.fersaiyan.cyanbridge.HIL_SET_AUTODIARY_EXCLUDED"
    }
}
