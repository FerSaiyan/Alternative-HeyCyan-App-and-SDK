package com.fersaiyan.cyanbridge.plugins.localagent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.plugins.autodiary.AutoDiarySettingsActivity
import com.fersaiyan.cyanbridge.plugins.settings.COMMUNITY_PLUGIN_PREFS
import com.fersaiyan.cyanbridge.plugins.settings.clearPreferenceFiles
import com.fersaiyan.cyanbridge.plugins.settings.createSettingsActivity
import com.fersaiyan.cyanbridge.plugins.settings.nextStartedActivityIntent
import com.fersaiyan.cyanbridge.ui.SettingsActivity
import com.fersaiyan.cyanbridge.ui.localagent.PendingActionsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.localagent.LocalAgentPrefs as RuntimePrefs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
        RuntimePrefs.setScreenshotPlanningEnabled(context, true)
        RuntimePrefs.setRemoteScreenshotUploadEnabled(context, true)
        RuntimePrefs.setRequireActionConfirmationEnabled(context, false)
        RuntimePrefs.setStatus(context, "Idle")

        val state = createSettingsActivity<LocalAgentSettingsActivity>().state

        assertEquals(LocalAgentPlugin.isEnabled(context), state.enabled)
        assertEquals(17, state.maxSteps)
        assertTrue(state.screenshotPlanningEnabled)
        assertTrue(state.remoteScreenshotUploadEnabled)
        assertFalse(state.requireActionConfirmation)
        assertEquals("Idle", state.status)
        assertFalse(state.telegramConfigured)
    }

    @Test
    fun preferenceActionsWriteAndRefreshState() {
        val activity = createSettingsActivity<LocalAgentSettingsActivity>()

        activity.onScreenshotPlanningChange(true)
        activity.onRemoteScreenshotUploadChange(true)
        activity.onMaxStepsChange(42)
        activity.onRequireActionConfirmationChange(false)
        activity.onAutoExecuteLowRiskChange(false)
        activity.onWhatsAppReadAloudChange(true)

        assertTrue(RuntimePrefs.isScreenshotPlanningEnabled(context))
        assertTrue(RuntimePrefs.isRemoteScreenshotUploadEnabled(context))
        assertEquals(42, AutomationPrefs.getMaxSteps(context))
        assertFalse(AutomationPrefs.isRequireConfirmationEnabled(context))
        assertFalse(RuntimePrefs.isRequireActionConfirmationEnabled(context))
        assertFalse(RuntimePrefs.isAutoExecuteLowRiskEnabled(context))
        assertTrue(RuntimePrefs.isWhatsAppNotificationReadAloudEnabled(context))

        val state = activity.state
        assertTrue(state.screenshotPlanningEnabled)
        assertTrue(state.remoteScreenshotUploadEnabled)
        assertEquals(42, state.maxSteps)
        assertFalse(state.requireActionConfirmation)
        assertFalse(state.autoExecuteLowRisk)
        assertTrue(state.whatsappNotificationReadAloud)
    }

    @Test
    fun invalidTelegramChatIdIsRejectedWithToast() {
        val activity = createSettingsActivity<LocalAgentSettingsActivity>()

        assertFalse(activity.onSaveTelegramConfiguration(botToken = "", allowedChatId = "not a chat"))
        assertEquals(context.getString(R.string.compose_local_agent_valid_chat_id), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun remoteControlNeedsTheAgentEnabledFirst() {
        val activity = createSettingsActivity<LocalAgentSettingsActivity>()

        activity.onTelegramRemoteControlChange(true)

        assertFalse(RuntimePrefs.isTelegramRemoteControlEnabled(context))
        assertEquals(context.getString(R.string.compose_local_agent_enable_first), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun statusActionShowsNoGoalToast() {
        val activity = createSettingsActivity<LocalAgentSettingsActivity>()

        activity.onAgentStatus()

        assertEquals(context.getString(R.string.compose_local_agent_no_goal), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun navigationActionsOpenTheSameActivities() {
        val activity = createSettingsActivity<LocalAgentSettingsActivity>()

        activity.onOpenSettings()
        assertEquals(SettingsActivity::class.java.name, activity.nextStartedActivityIntent()?.component?.className)

        activity.onOpenPendingActions()
        assertEquals(PendingActionsActivity::class.java.name, activity.nextStartedActivityIntent()?.component?.className)

        activity.onOpenAutoDiarySettings()
        assertEquals(AutoDiarySettingsActivity::class.java.name, activity.nextStartedActivityIntent()?.component?.className)

        activity.onBack()
        assertTrue(activity.isFinishing)
    }
}
