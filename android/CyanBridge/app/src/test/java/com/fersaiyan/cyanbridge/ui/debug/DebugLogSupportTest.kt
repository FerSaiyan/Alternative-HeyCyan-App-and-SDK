package com.fersaiyan.cyanbridge.ui.debug

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.localagent.LocalAgentPrefs as RuntimePrefs
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
        assertTrue(diagnostics.contains("Accessibility service connected: false"))
        assertTrue(diagnostics.contains("Runtime status: Waiting for accessibility"))
        assertTrue(diagnostics.contains("Last error: accessibility_not_connected"))
        assertTrue(diagnostics.contains("Last task: none recorded"))
    }

    @Test
    fun `collector includes exact automation log tags`() {
        assertTrue(DebugLogSupport.LOG_TAGS.contains("LocalAgentAccSvc"))
        assertTrue(DebugLogSupport.LOG_TAGS.contains("LocalAgentBridge"))
        assertTrue(DebugLogSupport.LOG_TAGS.contains("LocalAgentController"))
        assertTrue(DebugLogSupport.LOG_TAGS.contains("LocalAgentService"))
        assertTrue(DebugLogSupport.LOG_TAGS.contains("LocalAgentSteps"))
        assertTrue(DebugLogSupport.LOG_TAGS.contains("RemoteOpenAiClient"))
    }

    @Test
    fun `device info identifies the effective remote model backend`() {
        context.getSharedPreferences("remote_openai_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", true)
            .putString("base_url", "http://100.100.10.20:11434")
            .putString("model", "qwen2.5:7b")
            .commit()

        val deviceInfo = DebugLogSupport.buildDeviceInfo(context)

        assertTrue(deviceInfo.contains("Local-model backend: Remote OpenAI-compatible"))
        assertTrue(deviceInfo.contains("Remote model: qwen2.5:7b"))
        assertTrue(deviceInfo.contains("Remote base URL: http://100.100.10.20:11434"))
    }

    @Test
    fun `collector includes Meta registration and device detection tags`() {
        assertTrue(DebugLogSupport.LOG_TAGS.contains("MetaRaybanManager"))
        assertTrue(DebugLogSupport.LOG_TAGS.contains("DAT:CORE:RegistrationManager"))
        assertTrue(DebugLogSupport.LOG_TAGS.contains("DAT:CORE:BluetoothDeviceDetection"))
    }
}
