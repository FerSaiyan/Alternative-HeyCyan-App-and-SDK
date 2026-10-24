package com.fersaiyan.cyanbridge.plugins.handsfreetranslator

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
class HandsFreeTranslatorSettingsActivityTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.clearPreferenceFiles("hands_free_translator_prefs", "hands_free_translator_store", COMMUNITY_PLUGIN_PREFS)
    }

    @Test
    fun firstFrameStateComesFromPreferences() {
        HandsFreeTranslatorPreferences.setEnabled(context, true)
        HandsFreeTranslatorPreferences.setSourceLanguage(context, "de")
        HandsFreeTranslatorPreferences.setTargetLanguage(context, "fr")
        HandsFreeTranslatorPreferences.setAutoDetect(context, false)
        HandsFreeTranslatorPreferences.setSpeakTranslation(context, false)
        HandsFreeTranslatorPreferences.setMaxHistory(context, 400)
        HandsFreeTranslatorPreferences.setCustomPrompt(context, "Keep it formal")

        val expected = HandsFreeTranslatorSettingsState(
            enabled = true,
            sourceLanguage = "de",
            targetLanguage = "fr",
            autoDetect = false,
            speakTranslation = false,
            maxHistory = 400,
            customPrompt = "Keep it formal",
        )
        assertEquals(expected, HandsFreeTranslatorSettingsState.load(context))
        assertEquals(expected, createSettingsActivity<HandsFreeTranslatorSettingsActivity>().state)
    }

    @Test
    fun disablingPersistsBothFlagsAndSendsStopToService() {
        HandsFreeTranslatorPreferences.setEnabled(context, true)
        CommunityPluginPrefs.setNativePluginEnabled(context, NativePluginIds.HANDS_FREE_TRANSLATOR, true)
        val activity = createSettingsActivity<HandsFreeTranslatorSettingsActivity>()

        activity.onEnabledChange(false)

        assertFalse(activity.state.enabled)
        assertFalse(HandsFreeTranslatorPreferences.isEnabled(context))
        assertFalse(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.HANDS_FREE_TRANSLATOR))
        assertServiceIntent(
            activity.nextStartedServiceIntent(),
            HandsFreeTranslatorService::class.java,
            HandsFreeTranslatorService.ACTION_STOP,
        )
        assertNull(activity.nextStartedServiceIntent())
    }

    @Test
    fun enablingPersistsBothFlagsWithoutStartingService() {
        val activity = createSettingsActivity<HandsFreeTranslatorSettingsActivity>()

        activity.onEnabledChange(true)

        assertTrue(HandsFreeTranslatorPreferences.isEnabled(context))
        assertTrue(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.HANDS_FREE_TRANSLATOR))
        assertNull(activity.nextStartedServiceIntent())
    }

    @Test
    fun languageOutputAndHistoryActionsPersist() {
        val activity = createSettingsActivity<HandsFreeTranslatorSettingsActivity>()

        activity.onAutoDetectChange(false)
        activity.onSourceLanguageSelected("it")
        activity.onTargetLanguageSelected("pt")
        activity.onSpeakTranslationChange(false)
        activity.onMaxHistoryChange(60)

        assertFalse(HandsFreeTranslatorPreferences.isAutoDetect(context))
        assertEquals("it", HandsFreeTranslatorPreferences.getSourceLanguage(context))
        assertEquals("pt", HandsFreeTranslatorPreferences.getTargetLanguage(context))
        assertFalse(HandsFreeTranslatorPreferences.isSpeakTranslation(context))
        assertEquals(60, HandsFreeTranslatorPreferences.getMaxHistory(context))
        assertEquals(HandsFreeTranslatorSettingsState.load(context), activity.state)
    }

    @Test
    fun customPromptOverTheLimitIsIgnored() {
        val activity = createSettingsActivity<HandsFreeTranslatorSettingsActivity>()

        activity.onCustomPromptChange("x".repeat(1000))
        activity.onCustomPromptChange("y".repeat(1001))

        assertEquals("x".repeat(1000), activity.state.customPrompt)
        assertEquals("x".repeat(1000), HandsFreeTranslatorPreferences.getCustomPrompt(context))
    }
}
