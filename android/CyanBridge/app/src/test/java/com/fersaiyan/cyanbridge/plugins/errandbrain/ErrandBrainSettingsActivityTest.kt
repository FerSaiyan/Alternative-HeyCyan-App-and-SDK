package com.fersaiyan.cyanbridge.plugins.errandbrain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.plugins.settings.COMMUNITY_PLUGIN_PREFS
import com.fersaiyan.cyanbridge.plugins.settings.assertServiceIntent
import com.fersaiyan.cyanbridge.plugins.settings.clearPreferenceFiles
import com.fersaiyan.cyanbridge.plugins.settings.createSettingsActivity
import com.fersaiyan.cyanbridge.plugins.settings.nextStartedServiceIntent
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.plugins.settings.ErrandBrainSettingsState
import com.fersaiyan.cyanbridge.ui.CommunityPluginPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ErrandBrainSettingsActivityTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.clearPreferenceFiles("errand_brain_prefs", "errand_brain_store", COMMUNITY_PLUGIN_PREFS)
    }

    @Test
    fun firstFrameStateComesFromPreferences() {
        ErrandBrainPreferences.setEnabled(context, true)
        ErrandBrainPreferences.setAutoCreateTasks(context, false)
        ErrandBrainPreferences.setVoiceCommands(context, false)
        ErrandBrainPreferences.setReminderEnabled(context, false)
        ErrandBrainPreferences.setDefaultPriority(context, "urgent")
        ErrandBrainPreferences.setDefaultCategory(context, "health")
        ErrandBrainPreferences.setMaxHistory(context, 600)
        ErrandBrainPreferences.setCustomPrompt(context, "Group by store")

        val expected = ErrandBrainSettingsState(
            enabled = true,
            autoCreateTasks = false,
            voiceCommands = false,
            reminderEnabled = false,
            defaultPriority = "urgent",
            defaultCategory = "health",
            maxHistory = 600,
            customPrompt = "Group by store",
        )
        assertEquals(expected, createSettingsActivity<ErrandBrainSettingsActivity>().viewModel.state)
    }

    @Test
    fun disablingPersistsBothFlagsAndSendsStopToService() {
        ErrandBrainPreferences.setEnabled(context, true)
        CommunityPluginPrefs.setNativePluginEnabled(context, NativePluginIds.ERRAND_BRAIN, true)
        val activity = createSettingsActivity<ErrandBrainSettingsActivity>()

        activity.viewModel.onEnabledChange(false)

        assertFalse(activity.viewModel.state.enabled)
        assertFalse(ErrandBrainPreferences.isEnabled(context))
        assertFalse(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.ERRAND_BRAIN))
        assertServiceIntent(activity.nextStartedServiceIntent(), ErrandBrainService::class.java, ErrandBrainService.ACTION_STOP)
        assertNull(activity.nextStartedServiceIntent())
    }

    @Test
    fun enablingPersistsBothFlagsWithoutStartingService() {
        val activity = createSettingsActivity<ErrandBrainSettingsActivity>()

        activity.viewModel.onEnabledChange(true)

        assertTrue(ErrandBrainPreferences.isEnabled(context))
        assertTrue(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.ERRAND_BRAIN))
        assertNull(activity.nextStartedServiceIntent())
    }

    @Test
    fun optionActionsPersist() {
        val activity = createSettingsActivity<ErrandBrainSettingsActivity>()

        activity.viewModel.onAutoCreateTasksChange(false)
        activity.viewModel.onVoiceCommandsChange(false)
        activity.viewModel.onReminderEnabledChange(false)
        activity.viewModel.onDefaultPrioritySelected("low")
        activity.viewModel.onDefaultCategorySelected("finance")
        activity.viewModel.onMaxHistoryChange(950)

        assertFalse(ErrandBrainPreferences.isAutoCreateTasks(context))
        assertFalse(ErrandBrainPreferences.isVoiceCommands(context))
        assertFalse(ErrandBrainPreferences.isReminderEnabled(context))
        assertEquals("low", ErrandBrainPreferences.getDefaultPriority(context))
        assertEquals("finance", ErrandBrainPreferences.getDefaultCategory(context))
        assertEquals(950, ErrandBrainPreferences.getMaxHistory(context))
        assertEquals(createSettingsActivity<ErrandBrainSettingsActivity>().viewModel.state, activity.viewModel.state)
    }

    @Test
    fun customPromptOverTheLimitIsIgnored() {
        val activity = createSettingsActivity<ErrandBrainSettingsActivity>()

        activity.viewModel.onCustomPromptChange("x".repeat(1000))
        activity.viewModel.onCustomPromptChange("y".repeat(1001))

        assertEquals("x".repeat(1000), activity.viewModel.state.customPrompt)
        assertEquals("x".repeat(1000), ErrandBrainPreferences.getCustomPrompt(context))
    }
}
