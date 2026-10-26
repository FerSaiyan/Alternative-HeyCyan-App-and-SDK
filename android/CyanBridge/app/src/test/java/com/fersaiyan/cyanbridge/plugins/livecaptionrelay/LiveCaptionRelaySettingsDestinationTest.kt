package com.fersaiyan.cyanbridge.plugins.livecaptionrelay

import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.plugins.settings.COMMUNITY_PLUGIN_PREFS
import com.fersaiyan.cyanbridge.plugins.settings.assertServiceIntent
import com.fersaiyan.cyanbridge.plugins.settings.clearPreferenceFiles
import com.fersaiyan.cyanbridge.plugins.settings.createSettingsScreen
import com.fersaiyan.cyanbridge.plugins.settings.nextStartedServiceIntent
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.plugins.settings.LiveCaptionRelaySettingsState
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
class LiveCaptionRelaySettingsDestinationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.clearPreferenceFiles("live_caption_relay_prefs", "live_caption_relay_store", COMMUNITY_PLUGIN_PREFS)
    }

    @Test
    fun firstFrameStateComesFromPreferences() {
        LiveCaptionRelayPreferences.setEnabled(context, true)
        LiveCaptionRelayPreferences.setSourceLanguage(context, "fr")
        LiveCaptionRelayPreferences.setTargetLanguage(context, "de")
        LiveCaptionRelayPreferences.setTranslationEnabled(context, true)
        LiveCaptionRelayPreferences.setMaxHistory(context, 250)
        LiveCaptionRelayPreferences.setCustomPrompt(context, "Be brief")

        val expected = LiveCaptionRelaySettingsState(
            enabled = true,
            sourceLanguage = "fr",
            targetLanguage = "de",
            translationEnabled = true,
            maxHistory = 250,
            customPrompt = "Be brief",
        )
        assertEquals(expected, createSettingsScreen<LiveCaptionRelaySettingsDestination>(AppRoute.LIVE_CAPTION_RELAY_SETTINGS).viewModel.state)
    }

    @Test
    fun disablingPersistsBothFlagsAndSendsStopToService() {
        LiveCaptionRelayPreferences.setEnabled(context, true)
        CommunityPluginPrefs.setNativePluginEnabled(context, NativePluginIds.LIVE_CAPTION_RELAY, true)
        val screen = createSettingsScreen<LiveCaptionRelaySettingsDestination>(AppRoute.LIVE_CAPTION_RELAY_SETTINGS)

        screen.viewModel.onEnabledChange(false)

        assertFalse(screen.viewModel.state.enabled)
        assertFalse(LiveCaptionRelayPreferences.isEnabled(context))
        assertFalse(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.LIVE_CAPTION_RELAY))
        assertServiceIntent(
            screen.nextStartedServiceIntent(),
            LiveCaptionRelayService::class.java,
            LiveCaptionRelayService.ACTION_STOP,
        )
        assertNull(screen.nextStartedServiceIntent())
    }

    @Test
    fun enablingPersistsBothFlagsWithoutStartingService() {
        val screen = createSettingsScreen<LiveCaptionRelaySettingsDestination>(AppRoute.LIVE_CAPTION_RELAY_SETTINGS)

        screen.viewModel.onEnabledChange(true)

        assertTrue(screen.viewModel.state.enabled)
        assertTrue(LiveCaptionRelayPreferences.isEnabled(context))
        assertTrue(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.LIVE_CAPTION_RELAY))
        assertNull(screen.nextStartedServiceIntent())
    }

    @Test
    fun languageTranslationAndHistoryActionsPersist() {
        val screen = createSettingsScreen<LiveCaptionRelaySettingsDestination>(AppRoute.LIVE_CAPTION_RELAY_SETTINGS)

        screen.viewModel.onSourceLanguageSelected("it")
        screen.viewModel.onTranslationEnabledChange(true)
        screen.viewModel.onTargetLanguageSelected("pt")
        screen.viewModel.onMaxHistoryChange(320)

        assertEquals("it", LiveCaptionRelayPreferences.getSourceLanguage(context))
        assertTrue(LiveCaptionRelayPreferences.isTranslationEnabled(context))
        assertEquals("pt", LiveCaptionRelayPreferences.getTargetLanguage(context))
        assertEquals(320, LiveCaptionRelayPreferences.getMaxHistory(context))
        assertEquals(createSettingsScreen<LiveCaptionRelaySettingsDestination>(AppRoute.LIVE_CAPTION_RELAY_SETTINGS).viewModel.state, screen.viewModel.state)
    }

    @Test
    fun customPromptOverTheLimitIsIgnored() {
        val screen = createSettingsScreen<LiveCaptionRelaySettingsDestination>(AppRoute.LIVE_CAPTION_RELAY_SETTINGS)

        screen.viewModel.onCustomPromptChange("x".repeat(1000))
        screen.viewModel.onCustomPromptChange("y".repeat(1001))

        assertEquals("x".repeat(1000), screen.viewModel.state.customPrompt)
        assertEquals("x".repeat(1000), LiveCaptionRelayPreferences.getCustomPrompt(context))
    }
}
