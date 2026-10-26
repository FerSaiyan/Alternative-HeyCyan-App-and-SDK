package com.fersaiyan.cyanbridge.plugins.localagent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.plugins.settings.COMMUNITY_PLUGIN_PREFS
import com.fersaiyan.cyanbridge.plugins.settings.clearPreferenceFiles
import com.fersaiyan.cyanbridge.plugins.settings.createSettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as RuntimePrefs

@RunWith(RobolectricTestRunner::class)
class LocalAgentSettingsActivityTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.clearPreferenceFiles("local_agent_prefs", COMMUNITY_PLUGIN_PREFS)
    }

    @Test
    fun firstFrameStateComesFromPreferences() {
        AutomationPrefs.setMaxSteps(context, 17)
        RuntimePrefs.setRequireActionConfirmationEnabled(context, false)
        RuntimePrefs.setStatus(context, "Idle")

        val state = createSettingsActivity<LocalAgentSettingsActivity>().state

        assertEquals(LocalAgentPlugin.isEnabled(context), state.enabled)
        assertEquals(17, state.maxSteps)
        assertFalse(state.requireActionConfirmation)
        assertEquals("Idle", state.status)
    }

    @Test
    fun preferenceActionsWriteAndRefreshState() {
        val activity = createSettingsActivity<LocalAgentSettingsActivity>()

        activity.onMaxStepsChange(42)
        activity.onRequireActionConfirmationChange(false)
        activity.onAutoExecuteLowRiskChange(false)
        activity.onWhatsAppReadAloudChange(true)

        assertEquals(42, AutomationPrefs.getMaxSteps(context))
        assertFalse(AutomationPrefs.isRequireConfirmationEnabled(context))
        assertFalse(RuntimePrefs.isRequireActionConfirmationEnabled(context))
        assertFalse(RuntimePrefs.isAutoExecuteLowRiskEnabled(context))
        assertTrue(RuntimePrefs.isWhatsAppNotificationReadAloudEnabled(context))

        val state = activity.state
        assertEquals(42, state.maxSteps)
        assertFalse(state.requireActionConfirmation)
        assertFalse(state.autoExecuteLowRisk)
        assertTrue(state.whatsappNotificationReadAloud)
    }
}
