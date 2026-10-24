package com.fersaiyan.cyanbridge.plugins.meetingsparknotes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.plugins.settings.COMMUNITY_PLUGIN_PREFS
import com.fersaiyan.cyanbridge.plugins.settings.clearPreferenceFiles
import com.fersaiyan.cyanbridge.plugins.settings.createSettingsActivity
import com.fersaiyan.cyanbridge.plugins.settings.nextStartedServiceIntent
import com.fersaiyan.cyanbridge.plugins.settings.nextStoppedServiceIntent
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeetingSparkNotesSettingsActivityTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.clearPreferenceFiles("meeting_spark_notes_prefs", "meeting_spark_notes_store", COMMUNITY_PLUGIN_PREFS)
    }

    @Test
    fun defaultStateMatchesPreferenceDefaults() {
        assertEquals(
            MeetingSparkNotesSettingsState(
                enabled = false,
                summaryStyle = "concise",
                includeParticipants = true,
                includeActionItems = true,
                maxHistory = 50,
                customPrompt = "",
            ),
            MeetingSparkNotesSettingsState.load(context),
        )
    }

    @Test
    fun firstFrameStateComesFromPreferences() {
        MeetingSparkNotesPreferences.setEnabled(context, true)
        MeetingSparkNotesPreferences.setSummaryStyle(context, "detailed")
        MeetingSparkNotesPreferences.setIncludeParticipants(context, false)
        MeetingSparkNotesPreferences.setIncludeActionItems(context, false)
        MeetingSparkNotesPreferences.setMaxHistory(context, 120)
        MeetingSparkNotesPreferences.setCustomPrompt(context, "Focus on decisions")

        val expected = MeetingSparkNotesSettingsState(
            enabled = true,
            summaryStyle = "detailed",
            includeParticipants = false,
            includeActionItems = false,
            maxHistory = 120,
            customPrompt = "Focus on decisions",
        )
        assertEquals(expected, MeetingSparkNotesSettingsState.load(context))
        assertEquals(expected, createSettingsActivity<MeetingSparkNotesSettingsActivity>().state)
    }

    @Test
    fun disablingPersistsBothFlagsAndStopsService() {
        MeetingSparkNotesPreferences.setEnabled(context, true)
        CommunityPluginPrefs.setNativePluginEnabled(context, NativePluginIds.MEETING_SPARK_NOTES, true)
        val activity = createSettingsActivity<MeetingSparkNotesSettingsActivity>()

        activity.onEnabledChange(false)

        assertFalse(activity.state.enabled)
        assertFalse(MeetingSparkNotesPreferences.isEnabled(context))
        assertFalse(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.MEETING_SPARK_NOTES))
        val stopped = activity.nextStoppedServiceIntent()
        assertEquals(MeetingSparkNotesService::class.java.name, stopped?.component?.className)
        assertNull(stopped?.action)
        assertNull(activity.nextStartedServiceIntent())
    }

    @Test
    fun enablingPersistsBothFlagsWithoutTouchingService() {
        val activity = createSettingsActivity<MeetingSparkNotesSettingsActivity>()

        activity.onEnabledChange(true)

        assertTrue(activity.state.enabled)
        assertTrue(MeetingSparkNotesPreferences.isEnabled(context))
        assertTrue(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.MEETING_SPARK_NOTES))
        assertNull(activity.nextStartedServiceIntent())
        assertNull(activity.nextStoppedServiceIntent())
    }

    @Test
    fun styleContentAndHistoryActionsPersist() {
        val activity = createSettingsActivity<MeetingSparkNotesSettingsActivity>()

        activity.onSummaryStyleSelected("action_focused")
        activity.onIncludeParticipantsChange(false)
        activity.onIncludeActionItemsChange(false)
        activity.onMaxHistoryChange(30)

        assertEquals("action_focused", MeetingSparkNotesPreferences.getSummaryStyle(context))
        assertFalse(MeetingSparkNotesPreferences.isIncludeParticipants(context))
        assertFalse(MeetingSparkNotesPreferences.isIncludeActionItems(context))
        assertEquals(30, MeetingSparkNotesPreferences.getMaxHistory(context))
        assertEquals(MeetingSparkNotesSettingsState.load(context), activity.state)
    }

    @Test
    fun customPromptOverTheLimitIsIgnored() {
        val activity = createSettingsActivity<MeetingSparkNotesSettingsActivity>()

        activity.onCustomPromptChange("x".repeat(1500))
        activity.onCustomPromptChange("y".repeat(1501))

        assertEquals("x".repeat(1500), activity.state.customPrompt)
        assertEquals("x".repeat(1500), MeetingSparkNotesPreferences.getCustomPrompt(context))
    }

    @Test
    fun clearHistoryShowsToastAndBackFinishes() {
        val activity = createSettingsActivity<MeetingSparkNotesSettingsActivity>()

        activity.onClearHistory()
        assertEquals(context.getString(R.string.compose_history_cleared), ShadowToast.getTextOfLatestToast())

        activity.onBack()
        assertTrue(activity.isFinishing)
    }
}
