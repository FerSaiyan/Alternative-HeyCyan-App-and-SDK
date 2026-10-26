package com.fersaiyan.cyanbridge.plugins.errandbrain

import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.plugins.settings.COMMUNITY_PLUGIN_PREFS
import com.fersaiyan.cyanbridge.plugins.settings.assertServiceIntent
import com.fersaiyan.cyanbridge.plugins.settings.clearPreferenceFiles
import com.fersaiyan.cyanbridge.plugins.settings.createSettingsScreen
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
class ErrandBrainSettingsDestinationTest {
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
        assertEquals(expected, createSettingsScreen<ErrandBrainSettingsDestination>(AppRoute.ERRAND_BRAIN_SETTINGS).viewModel.state)
    }

    @Test
    fun disablingPersistsBothFlagsAndSendsStopToService() {
        ErrandBrainPreferences.setEnabled(context, true)
        CommunityPluginPrefs.setNativePluginEnabled(context, NativePluginIds.ERRAND_BRAIN, true)
        val screen = createSettingsScreen<ErrandBrainSettingsDestination>(AppRoute.ERRAND_BRAIN_SETTINGS)

        screen.viewModel.onEnabledChange(false)

        assertFalse(screen.viewModel.state.enabled)
        assertFalse(ErrandBrainPreferences.isEnabled(context))
        assertFalse(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.ERRAND_BRAIN))
        assertServiceIntent(screen.nextStartedServiceIntent(), ErrandBrainService::class.java, ErrandBrainService.ACTION_STOP)
        assertNull(screen.nextStartedServiceIntent())
    }

    @Test
    fun enablingPersistsBothFlagsWithoutStartingService() {
        val screen = createSettingsScreen<ErrandBrainSettingsDestination>(AppRoute.ERRAND_BRAIN_SETTINGS)

        screen.viewModel.onEnabledChange(true)

        assertTrue(ErrandBrainPreferences.isEnabled(context))
        assertTrue(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.ERRAND_BRAIN))
        assertNull(screen.nextStartedServiceIntent())
    }

    @Test
    fun optionActionsPersist() {
        val screen = createSettingsScreen<ErrandBrainSettingsDestination>(AppRoute.ERRAND_BRAIN_SETTINGS)

        screen.viewModel.onAutoCreateTasksChange(false)
        screen.viewModel.onVoiceCommandsChange(false)
        screen.viewModel.onReminderEnabledChange(false)
        screen.viewModel.onDefaultPrioritySelected("low")
        screen.viewModel.onDefaultCategorySelected("finance")
        screen.viewModel.onMaxHistoryChange(950)

        assertFalse(ErrandBrainPreferences.isAutoCreateTasks(context))
        assertFalse(ErrandBrainPreferences.isVoiceCommands(context))
        assertFalse(ErrandBrainPreferences.isReminderEnabled(context))
        assertEquals("low", ErrandBrainPreferences.getDefaultPriority(context))
        assertEquals("finance", ErrandBrainPreferences.getDefaultCategory(context))
        assertEquals(950, ErrandBrainPreferences.getMaxHistory(context))
        assertEquals(createSettingsScreen<ErrandBrainSettingsDestination>(AppRoute.ERRAND_BRAIN_SETTINGS).viewModel.state, screen.viewModel.state)
    }

    @Test
    fun customPromptOverTheLimitIsIgnored() {
        val screen = createSettingsScreen<ErrandBrainSettingsDestination>(AppRoute.ERRAND_BRAIN_SETTINGS)

        screen.viewModel.onCustomPromptChange("x".repeat(1000))
        screen.viewModel.onCustomPromptChange("y".repeat(1001))

        assertEquals("x".repeat(1000), screen.viewModel.state.customPrompt)
        assertEquals("x".repeat(1000), ErrandBrainPreferences.getCustomPrompt(context))
    }
}
