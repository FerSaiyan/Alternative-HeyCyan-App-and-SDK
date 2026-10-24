package com.fersaiyan.cyanbridge.plugins.errandbrain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.plugins.settings.COMMUNITY_PLUGIN_PREFS
import com.fersaiyan.cyanbridge.plugins.settings.assertServiceIntent
import com.fersaiyan.cyanbridge.plugins.settings.clearPreferenceFiles
import com.fersaiyan.cyanbridge.plugins.settings.createSettingsActivity
import com.fersaiyan.cyanbridge.plugins.settings.nextStartedServiceIntent
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
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
        assertEquals(expected, ErrandBrainSettingsState.load(context))
        assertEquals(expected, createSettingsActivity<ErrandBrainSettingsActivity>().state)
    }

    @Test
    fun disablingPersistsBothFlagsAndSendsStopToService() {
        ErrandBrainPreferences.setEnabled(context, true)
        CommunityPluginPrefs.setNativePluginEnabled(context, NativePluginIds.ERRAND_BRAIN, true)
        val activity = createSettingsActivity<ErrandBrainSettingsActivity>()

        activity.onEnabledChange(false)

        assertFalse(activity.state.enabled)
        assertFalse(ErrandBrainPreferences.isEnabled(context))
        assertFalse(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.ERRAND_BRAIN))
        assertServiceIntent(activity.nextStartedServiceIntent(), ErrandBrainService::class.java, ErrandBrainService.ACTION_STOP)
        assertNull(activity.nextStartedServiceIntent())
    }

    @Test
    fun enablingPersistsBothFlagsWithoutStartingService() {
        val activity = createSettingsActivity<ErrandBrainSettingsActivity>()

        activity.onEnabledChange(true)

        assertTrue(ErrandBrainPreferences.isEnabled(context))
        assertTrue(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.ERRAND_BRAIN))
        assertNull(activity.nextStartedServiceIntent())
    }

    @Test
    fun optionActionsPersist() {
        val activity = createSettingsActivity<ErrandBrainSettingsActivity>()

        activity.onAutoCreateTasksChange(false)
        activity.onVoiceCommandsChange(false)
        activity.onReminderEnabledChange(false)
        activity.onDefaultPrioritySelected("low")
        activity.onDefaultCategorySelected("finance")
        activity.onMaxHistoryChange(950)

        assertFalse(ErrandBrainPreferences.isAutoCreateTasks(context))
        assertFalse(ErrandBrainPreferences.isVoiceCommands(context))
        assertFalse(ErrandBrainPreferences.isReminderEnabled(context))
        assertEquals("low", ErrandBrainPreferences.getDefaultPriority(context))
        assertEquals("finance", ErrandBrainPreferences.getDefaultCategory(context))
        assertEquals(950, ErrandBrainPreferences.getMaxHistory(context))
        assertEquals(ErrandBrainSettingsState.load(context), activity.state)
    }

    @Test
    fun customPromptOverTheLimitIsIgnored() {
        val activity = createSettingsActivity<ErrandBrainSettingsActivity>()

        activity.onCustomPromptChange("x".repeat(1000))
        activity.onCustomPromptChange("y".repeat(1001))

        assertEquals("x".repeat(1000), activity.state.customPrompt)
        assertEquals("x".repeat(1000), ErrandBrainPreferences.getCustomPrompt(context))
    }
}
