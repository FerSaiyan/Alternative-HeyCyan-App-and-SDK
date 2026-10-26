package com.fersaiyan.cyanbridge.plugins.handsfreetranslator

import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.plugins.settings.COMMUNITY_PLUGIN_PREFS
import com.fersaiyan.cyanbridge.plugins.settings.assertServiceIntent
import com.fersaiyan.cyanbridge.plugins.settings.clearPreferenceFiles
import com.fersaiyan.cyanbridge.plugins.settings.createSettingsScreen
import com.fersaiyan.cyanbridge.plugins.settings.nextStartedServiceIntent
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.plugins.settings.HandsFreeTranslatorSettingsState
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
class HandsFreeTranslatorSettingsDestinationTest {
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
        assertEquals(expected, createSettingsScreen<HandsFreeTranslatorSettingsDestination>(AppRoute.HANDS_FREE_TRANSLATOR_SETTINGS).viewModel.state)
    }

    @Test
    fun disablingPersistsBothFlagsAndSendsStopToService() {
        HandsFreeTranslatorPreferences.setEnabled(context, true)
        CommunityPluginPrefs.setNativePluginEnabled(context, NativePluginIds.HANDS_FREE_TRANSLATOR, true)
        val screen = createSettingsScreen<HandsFreeTranslatorSettingsDestination>(AppRoute.HANDS_FREE_TRANSLATOR_SETTINGS)

        screen.viewModel.onEnabledChange(false)

        assertFalse(screen.viewModel.state.enabled)
        assertFalse(HandsFreeTranslatorPreferences.isEnabled(context))
        assertFalse(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.HANDS_FREE_TRANSLATOR))
        assertServiceIntent(
            screen.nextStartedServiceIntent(),
            HandsFreeTranslatorService::class.java,
            HandsFreeTranslatorService.ACTION_STOP,
        )
        assertNull(screen.nextStartedServiceIntent())
    }

    @Test
    fun enablingPersistsBothFlagsWithoutStartingService() {
        val screen = createSettingsScreen<HandsFreeTranslatorSettingsDestination>(AppRoute.HANDS_FREE_TRANSLATOR_SETTINGS)

        screen.viewModel.onEnabledChange(true)

        assertTrue(HandsFreeTranslatorPreferences.isEnabled(context))
        assertTrue(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.HANDS_FREE_TRANSLATOR))
        assertNull(screen.nextStartedServiceIntent())
    }

    @Test
    fun languageOutputAndHistoryActionsPersist() {
        val screen = createSettingsScreen<HandsFreeTranslatorSettingsDestination>(AppRoute.HANDS_FREE_TRANSLATOR_SETTINGS)

        screen.viewModel.onAutoDetectChange(false)
        screen.viewModel.onSourceLanguageSelected("it")
        screen.viewModel.onTargetLanguageSelected("pt")
        screen.viewModel.onSpeakTranslationChange(false)
        screen.viewModel.onMaxHistoryChange(60)

        assertFalse(HandsFreeTranslatorPreferences.isAutoDetect(context))
        assertEquals("it", HandsFreeTranslatorPreferences.getSourceLanguage(context))
        assertEquals("pt", HandsFreeTranslatorPreferences.getTargetLanguage(context))
        assertFalse(HandsFreeTranslatorPreferences.isSpeakTranslation(context))
        assertEquals(60, HandsFreeTranslatorPreferences.getMaxHistory(context))
        assertEquals(createSettingsScreen<HandsFreeTranslatorSettingsDestination>(AppRoute.HANDS_FREE_TRANSLATOR_SETTINGS).viewModel.state, screen.viewModel.state)
    }

    @Test
    fun customPromptOverTheLimitIsIgnored() {
        val screen = createSettingsScreen<HandsFreeTranslatorSettingsDestination>(AppRoute.HANDS_FREE_TRANSLATOR_SETTINGS)

        screen.viewModel.onCustomPromptChange("x".repeat(1000))
        screen.viewModel.onCustomPromptChange("y".repeat(1001))

        assertEquals("x".repeat(1000), screen.viewModel.state.customPrompt)
        assertEquals("x".repeat(1000), HandsFreeTranslatorPreferences.getCustomPrompt(context))
    }
}
