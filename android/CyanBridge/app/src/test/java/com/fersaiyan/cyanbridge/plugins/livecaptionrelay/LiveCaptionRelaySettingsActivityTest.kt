package com.fersaiyan.cyanbridge.plugins.livecaptionrelay

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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveCaptionRelaySettingsActivityTest {
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
        assertEquals(expected, LiveCaptionRelaySettingsState.load(context))
        assertEquals(expected, createSettingsActivity<LiveCaptionRelaySettingsActivity>().state)
    }

    @Test
    fun disablingPersistsBothFlagsAndSendsStopToService() {
        LiveCaptionRelayPreferences.setEnabled(context, true)
        CommunityPluginPrefs.setNativePluginEnabled(context, NativePluginIds.LIVE_CAPTION_RELAY, true)
        val activity = createSettingsActivity<LiveCaptionRelaySettingsActivity>()

        activity.onEnabledChange(false)

        assertFalse(activity.state.enabled)
        assertFalse(LiveCaptionRelayPreferences.isEnabled(context))
        assertFalse(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.LIVE_CAPTION_RELAY))
        assertServiceIntent(
            activity.nextStartedServiceIntent(),
            LiveCaptionRelayService::class.java,
            LiveCaptionRelayService.ACTION_STOP,
        )
        assertNull(activity.nextStartedServiceIntent())
    }

    @Test
    fun enablingPersistsBothFlagsWithoutStartingService() {
        val activity = createSettingsActivity<LiveCaptionRelaySettingsActivity>()

        activity.onEnabledChange(true)

        assertTrue(activity.state.enabled)
        assertTrue(LiveCaptionRelayPreferences.isEnabled(context))
        assertTrue(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.LIVE_CAPTION_RELAY))
        assertNull(activity.nextStartedServiceIntent())
    }

    @Test
    fun languageTranslationAndHistoryActionsPersist() {
        val activity = createSettingsActivity<LiveCaptionRelaySettingsActivity>()

        activity.onSourceLanguageSelected("it")
        activity.onTranslationEnabledChange(true)
        activity.onTargetLanguageSelected("pt")
        activity.onMaxHistoryChange(320)

        assertEquals("it", LiveCaptionRelayPreferences.getSourceLanguage(context))
        assertTrue(LiveCaptionRelayPreferences.isTranslationEnabled(context))
        assertEquals("pt", LiveCaptionRelayPreferences.getTargetLanguage(context))
        assertEquals(320, LiveCaptionRelayPreferences.getMaxHistory(context))
        assertEquals(LiveCaptionRelaySettingsState.load(context), activity.state)
    }

    @Test
    fun customPromptOverTheLimitIsIgnored() {
        val activity = createSettingsActivity<LiveCaptionRelaySettingsActivity>()

        activity.onCustomPromptChange("x".repeat(1000))
        activity.onCustomPromptChange("y".repeat(1001))

        assertEquals("x".repeat(1000), activity.state.customPrompt)
        assertEquals("x".repeat(1000), LiveCaptionRelayPreferences.getCustomPrompt(context))
    }
}
