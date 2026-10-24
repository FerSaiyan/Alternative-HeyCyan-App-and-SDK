package com.fersaiyan.cyanbridge.plugins.autodiary

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyBulletsSettings
import com.fersaiyan.cyanbridge.localagent.userfacts.ChatMemoryPrefs
import com.fersaiyan.cyanbridge.memoryvault.MemoryModeManager
import com.fersaiyan.cyanbridge.plugins.settings.COMMUNITY_PLUGIN_PREFS
import com.fersaiyan.cyanbridge.plugins.settings.clearPreferenceFiles
import com.fersaiyan.cyanbridge.plugins.settings.createSettingsActivity
import com.fersaiyan.cyanbridge.plugins.settings.nextStartedActivityIntent
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.ui.CommunityPluginPrefs
import com.fersaiyan.cyanbridge.ui.localagent.DailyFactsActivity
import com.fersaiyan.cyanbridge.ui.localagent.DailySummaryActivity
import com.fersaiyan.cyanbridge.ui.localagent.ScreenCapturesActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoDiarySettingsActivityTest {
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
        ChatMemoryPrefs.setAutoSaveDailyFactsEnabled(context, false)
        ChatMemoryPrefs.setExtractUserFactCandidatesEnabled(context, false)
        DailyBulletsSettings.setMaxTokensPerBullet(context, 64)
        MemoryModeManager.setScreenOcrRetentionDays(context, 9)
        DailyBulletsSettings.setCustomBulletPrompt(context, "One short bullet")

        val state = createSettingsActivity<AutoDiarySettingsActivity>().state

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
        val activity = createSettingsActivity<AutoDiarySettingsActivity>()
        val app = context as Application

        activity.onEnabledChange(true)

        assertTrue(activity.state.enabled)
        assertTrue(AutoDiaryService.isEnabled(context))
        assertTrue(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.AUTO_DIARY))
        assertTrue(shadowOf(app).broadcastIntents.any { it.action == AutoDiaryService.ACTION_TASKER_ENABLE })

        activity.onEnabledChange(false)

        assertFalse(activity.state.enabled)
        assertFalse(AutoDiaryService.isEnabled(context))
        assertFalse(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.AUTO_DIARY))
        assertEquals(AutoDiaryService.ACTION_TASKER_DISABLE, shadowOf(app).broadcastIntents.last().action)
    }

    @Test
    fun preferenceActionsPersist() {
        val activity = createSettingsActivity<AutoDiarySettingsActivity>()

        activity.onScreenOcrRetentionDaysChange(30)
        activity.onDailyFactsReminderChange(true)
        activity.onAutoSaveDailyFactsChange(false)
        activity.onExtractUserFactCandidatesChange(false)
        activity.onMaxTokensPerBulletChange(128)
        activity.onBulletPromptChange("Custom prompt")

        assertEquals(30, MemoryModeManager.getScreenOcrRetentionDays(context))
        assertTrue(LocalAgentPrefs.isDailyFactsReminderEnabled(context))
        assertFalse(ChatMemoryPrefs.isAutoSaveDailyFactsEnabled(context))
        assertFalse(ChatMemoryPrefs.isExtractUserFactCandidatesEnabled(context))
        assertEquals(128, DailyBulletsSettings.getMaxTokensPerBullet(context))
        assertEquals("Custom prompt", DailyBulletsSettings.getBulletPrompt(context))
        assertEquals(AutoDiarySettingsState.load(context), activity.state)
    }

    @Test
    fun restoreDefaultPromptWritesAndShowsDefault() {
        DailyBulletsSettings.setCustomBulletPrompt(context, "Custom prompt")
        val activity = createSettingsActivity<AutoDiarySettingsActivity>()

        activity.onRestoreDefaultBulletPrompt()

        assertEquals(DailyBulletsSettings.DEFAULT_BULLET_PROMPT, activity.state.bulletPrompt)
        assertEquals(DailyBulletsSettings.DEFAULT_BULLET_PROMPT, DailyBulletsSettings.getBulletPrompt(context))
    }

    @Test
    fun navigationActionsOpenTheSameActivities() {
        val activity = createSettingsActivity<AutoDiarySettingsActivity>()

        activity.onOpenCaptures()
        assertEquals(ScreenCapturesActivity::class.java.name, activity.nextStartedActivityIntent()?.component?.className)

        activity.onOpenSummary()
        assertEquals(DailySummaryActivity::class.java.name, activity.nextStartedActivityIntent()?.component?.className)

        activity.onOpenDailyFactsDraft()
        activity.nextStartedActivityIntent().let { intent ->
            assertEquals(DailyFactsActivity::class.java.name, intent?.component?.className)
            assertEquals(DailyFactsActivity.MODE_DRAFT, intent?.getStringExtra(DailyFactsActivity.EXTRA_MODE))
        }

        activity.onOpenConfirmedDailyFacts()
        activity.nextStartedActivityIntent().let { intent ->
            assertEquals(DailyFactsActivity::class.java.name, intent?.component?.className)
            assertEquals(DailyFactsActivity.MODE_CONFIRMED, intent?.getStringExtra(DailyFactsActivity.EXTRA_MODE))
        }

        // Tasker is not installed under Robolectric, so nothing is launched.
        activity.onOpenTasker()
        assertNull(activity.nextStartedActivityIntent())

        activity.onBack()
        assertTrue(activity.isFinishing)
    }
}
