package com.fersaiyan.cyanbridge.plugins.meetingsparknotes

import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.plugins.settings.COMMUNITY_PLUGIN_PREFS
import com.fersaiyan.cyanbridge.plugins.settings.clearPreferenceFiles
import com.fersaiyan.cyanbridge.plugins.settings.createSettingsScreen
import com.fersaiyan.cyanbridge.plugins.settings.nextStartedServiceIntent
import com.fersaiyan.cyanbridge.plugins.settings.nextStoppedServiceIntent
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.plugins.settings.MeetingSparkNotesSettingsState
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
class MeetingSparkNotesSettingsDestinationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.clearPreferenceFiles("meeting_spark_notes_prefs", "meeting_spark_notes_store", COMMUNITY_PLUGIN_PREFS)
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
        assertEquals(expected, createSettingsScreen<MeetingSparkNotesSettingsDestination>(AppRoute.MEETING_SPARK_NOTES_SETTINGS).viewModel.state)
    }

    @Test
    fun disablingPersistsBothFlagsAndStopsService() {
        MeetingSparkNotesPreferences.setEnabled(context, true)
        CommunityPluginPrefs.setNativePluginEnabled(context, NativePluginIds.MEETING_SPARK_NOTES, true)
        val screen = createSettingsScreen<MeetingSparkNotesSettingsDestination>(AppRoute.MEETING_SPARK_NOTES_SETTINGS)

        screen.viewModel.onEnabledChange(false)

        assertFalse(screen.viewModel.state.enabled)
        assertFalse(MeetingSparkNotesPreferences.isEnabled(context))
        assertFalse(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.MEETING_SPARK_NOTES))
        val stopped = screen.nextStoppedServiceIntent()
        assertEquals(MeetingSparkNotesService::class.java.name, stopped?.component?.className)
        assertNull(stopped?.action)
        assertNull(screen.nextStartedServiceIntent())
    }

    @Test
    fun enablingPersistsBothFlagsWithoutTouchingService() {
        val screen = createSettingsScreen<MeetingSparkNotesSettingsDestination>(AppRoute.MEETING_SPARK_NOTES_SETTINGS)

        screen.viewModel.onEnabledChange(true)

        assertTrue(screen.viewModel.state.enabled)
        assertTrue(MeetingSparkNotesPreferences.isEnabled(context))
        assertTrue(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.MEETING_SPARK_NOTES))
        assertNull(screen.nextStartedServiceIntent())
        assertNull(screen.nextStoppedServiceIntent())
    }

    @Test
    fun styleContentAndHistoryActionsPersist() {
        val screen = createSettingsScreen<MeetingSparkNotesSettingsDestination>(AppRoute.MEETING_SPARK_NOTES_SETTINGS)

        screen.viewModel.onSummaryStyleSelected("action_focused")
        screen.viewModel.onIncludeParticipantsChange(false)
        screen.viewModel.onIncludeActionItemsChange(false)
        screen.viewModel.onMaxHistoryChange(30)

        assertEquals("action_focused", MeetingSparkNotesPreferences.getSummaryStyle(context))
        assertFalse(MeetingSparkNotesPreferences.isIncludeParticipants(context))
        assertFalse(MeetingSparkNotesPreferences.isIncludeActionItems(context))
        assertEquals(30, MeetingSparkNotesPreferences.getMaxHistory(context))
        assertEquals(createSettingsScreen<MeetingSparkNotesSettingsDestination>(AppRoute.MEETING_SPARK_NOTES_SETTINGS).viewModel.state, screen.viewModel.state)
    }

    @Test
    fun customPromptOverTheLimitIsIgnored() {
        val screen = createSettingsScreen<MeetingSparkNotesSettingsDestination>(AppRoute.MEETING_SPARK_NOTES_SETTINGS)

        screen.viewModel.onCustomPromptChange("x".repeat(1500))
        screen.viewModel.onCustomPromptChange("y".repeat(1501))

        assertEquals("x".repeat(1500), screen.viewModel.state.customPrompt)
        assertEquals("x".repeat(1500), MeetingSparkNotesPreferences.getCustomPrompt(context))
    }
}
