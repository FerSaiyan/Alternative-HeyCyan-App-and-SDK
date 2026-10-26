package com.fersaiyan.cyanbridge.plugins.autodiary

import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyBulletsSettings
import com.fersaiyan.cyanbridge.memoryvault.MemoryModeManager
import com.fersaiyan.cyanbridge.plugins.settings.COMMUNITY_PLUGIN_PREFS
import com.fersaiyan.cyanbridge.plugins.settings.clearPreferenceFiles
import com.fersaiyan.cyanbridge.plugins.settings.createSettingsScreen
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.prefs.MemoryPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.fersaiyan.cyanbridge.ui.CommunityPluginPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AutoDiarySettingsDestinationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.clearPreferenceFiles(
            "local_agent_prefs",
            "memory_mode_prefs",
            "chat_memory_prefs",
            "daily_bullets_settings",
            COMMUNITY_PLUGIN_PREFS,
        )
    }

    @Test
    fun firstFrameStateComesFromPreferences() {
        LocalAgentPrefs.setDailyFactsReminderEnabled(context, true)
        MemoryPrefs.ChatMemory.autoSaveDailyFacts[context] = false
        MemoryPrefs.ChatMemory.extractUserFactCandidates[context] = false
        DailyBulletsSettings.setMaxTokensPerBullet(context, 64)
        MemoryModeManager.setScreenOcrRetentionDays(context, 9)
        DailyBulletsSettings.setCustomBulletPrompt(context, "One short bullet")

        val state = createSettingsScreen<AutoDiarySettingsDestination>(AppRoute.AUTO_DIARY_SETTINGS).state

        assertEquals(AutoDiarySettingsState.load(context), state)
        assertFalse(state.enabled)
        assertFalse(state.taskerObserverAvailable)
        assertTrue(state.dailyFactsReminder)
        assertFalse(state.autoSaveDailyFacts)
        assertFalse(state.extractUserFactCandidates)
        assertEquals(64, state.maxTokensPerBullet)
        assertEquals(9, state.screenOcrRetentionDays)
        assertEquals("One short bullet", state.bulletPrompt)
    }

    @Test
    fun enableAndDisableGoThroughServiceAndRefreshState() {
        val screen = createSettingsScreen<AutoDiarySettingsDestination>(AppRoute.AUTO_DIARY_SETTINGS)
        val app = context as Application

        screen.onEnabledChange(true)

        assertTrue(screen.state.enabled)
        assertTrue(AutoDiaryService.isEnabled(context))
        assertTrue(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.AUTO_DIARY))
        assertTrue(shadowOf(app).broadcastIntents.any { it.action == AutoDiaryService.ACTION_TASKER_ENABLE })

        screen.onEnabledChange(false)

        assertFalse(screen.state.enabled)
        assertFalse(AutoDiaryService.isEnabled(context))
        assertFalse(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.AUTO_DIARY))
        assertEquals(AutoDiaryService.ACTION_TASKER_DISABLE, shadowOf(app).broadcastIntents.last().action)
    }

    @Test
    fun preferenceActionsPersist() {
        val screen = createSettingsScreen<AutoDiarySettingsDestination>(AppRoute.AUTO_DIARY_SETTINGS)

        screen.onScreenOcrRetentionDaysChange(30)
        screen.onDailyFactsReminderChange(true)
        screen.onAutoSaveDailyFactsChange(false)
        screen.onExtractUserFactCandidatesChange(false)
        screen.onMaxTokensPerBulletChange(128)
        screen.onBulletPromptChange("Custom prompt")

        assertEquals(30, MemoryModeManager.getScreenOcrRetentionDays(context))
        assertTrue(LocalAgentPrefs.isDailyFactsReminderEnabled(context))
        assertFalse(MemoryPrefs.ChatMemory.autoSaveDailyFacts[context])
        assertFalse(MemoryPrefs.ChatMemory.extractUserFactCandidates[context])
        assertEquals(128, DailyBulletsSettings.getMaxTokensPerBullet(context))
        assertEquals("Custom prompt", DailyBulletsSettings.getBulletPrompt(context))
        assertEquals(AutoDiarySettingsState.load(context), screen.state)
    }

    @Test
    fun restoreDefaultPromptWritesAndShowsDefault() {
        DailyBulletsSettings.setCustomBulletPrompt(context, "Custom prompt")
        val screen = createSettingsScreen<AutoDiarySettingsDestination>(AppRoute.AUTO_DIARY_SETTINGS)

        screen.onRestoreDefaultBulletPrompt()

        assertEquals(DailyBulletsSettings.DEFAULT_BULLET_PROMPT, screen.state.bulletPrompt)
        assertEquals(DailyBulletsSettings.DEFAULT_BULLET_PROMPT, DailyBulletsSettings.getBulletPrompt(context))
    }
}
