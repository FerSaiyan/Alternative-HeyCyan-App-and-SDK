package com.fersaiyan.cyanbridge.ui.debug

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as RuntimePrefs
import com.fersaiyan.cyanbridge.diagnostics.DiagnosticsCollector
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DebugLogSupportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun resetAutomationState() {
        context.getSharedPreferences("local_agent_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("local_agent_task_history", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("remote_openai_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `automation diagnostics are present without recent automation logs`() {
        AutomationPrefs.setLocalAgentAutomationEnabled(context, true)
        RuntimePrefs.setStatus(context, "Waiting for accessibility")
        RuntimePrefs.setLastError(context, "accessibility_not_connected")

        val diagnostics = DebugLogSupport.buildAutomationDiagnostics(context)

        assertTrue(diagnostics.contains("Feature enabled: true"))
        assertTrue(diagnostics.contains("AutoInput accessibility enabled: false"))
        assertTrue(diagnostics.contains("Runtime status: Waiting for accessibility"))
        assertTrue(diagnostics.contains("Last error: accessibility_not_connected"))
        assertTrue(diagnostics.contains("Last task: none recorded"))
    }

    @Test
    fun `a Test Lab device never uploads a report`() {
        android.provider.Settings.System.putString(context.contentResolver, "firebase.test.lab", "true")
        try {
            val report = com.fersaiyan.cyanbridge.shared.diagnostics.DiagnosticReport(
                kotlinx.serialization.json.JsonObject(emptyMap()), "", 0,
            )
            // Port 9 has no listener: reaching the network would fail with a connection error instead.
            val result = DebugLogSupport.postReport(context, report, relayBaseUrl = "http://127.0.0.1:9")

            assertEquals("Not sent: this is a Firebase Test Lab device", result.exceptionOrNull()?.message)
        } finally {
            android.provider.Settings.System.putString(context.contentResolver, "firebase.test.lab", null)
        }
    }

    @Test
    fun `logcat caps every tag so Meta DAT cannot crowd out the app's own lines`() {
        val dat = (1..900).map { "11-17 10:00:00.000  42  42 D DAT:CORE:Stream$it: frame" }
        val app = (1..5).map { "11-17 10:00:01.000  42  42 W AIHijack: Spoken decision failed $it" }
        val capped = DiagnosticsCollector.capLogcat(app.take(2) + dat + app.drop(2), perTagCap = 100, keepLines = 1_000)

        assertEquals(105, capped.keptLines)
        assertEquals("DAT" to 900, capped.topTags.first())
        assertTrue(app.all { it in capped.text })
        assertEquals("DAT", DiagnosticsCollector.tagGroup(dat.first()))
    }
}
