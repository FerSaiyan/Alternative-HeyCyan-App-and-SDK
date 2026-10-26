package com.fersaiyan.cyanbridge.plugins.autoaudio

import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCapturePrefs
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCaptureService
import com.fersaiyan.cyanbridge.plugins.settings.COMMUNITY_PLUGIN_PREFS
import com.fersaiyan.cyanbridge.plugins.settings.assertServiceIntent
import com.fersaiyan.cyanbridge.plugins.settings.clearPreferenceFiles
import com.fersaiyan.cyanbridge.plugins.settings.createSettingsScreen
import com.fersaiyan.cyanbridge.plugins.settings.nextStartedServiceIntent
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import com.fersaiyan.cyanbridge.shared.plugins.settings.AutoAudioSettingsState
import com.fersaiyan.cyanbridge.ui.CommunityPluginPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AutoAudioSettingsDestinationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.clearPreferenceFiles("auto_audio_capture_prefs", "device_profile", COMMUNITY_PLUGIN_PREFS)
    }

    @Test
    fun firstFrameStateComesFromPreferences() {
        AutoAudioCapturePrefs.setEnabled(context, true)
        AutoAudioCapturePrefs.setVisualNotesEnabled(context, true)
        AutoAudioCapturePrefs.setSpeechExtendEnabled(context, false)
        AutoAudioCapturePrefs.setLoopsPerSync(context, 40)

        val expected = AutoAudioSettingsState(enabled = true, visualNotes = true, speechExtend = false, loopsPerSync = 40)
        assertEquals(expected, createSettingsScreen<AutoAudioSettingsDestination>(AppRoute.AUTO_AUDIO_SETTINGS).viewModel.state)
    }

    @Test
    fun lastPauseReasonIsReadLiveFromPreferences() {
        val screen = createSettingsScreen<AutoAudioSettingsDestination>(AppRoute.AUTO_AUDIO_SETTINGS)
        assertEquals("", screen.viewModel.readLastPauseReason())

        AutoAudioCapturePrefs.setLastPauseReason(context, "meeting_active")

        assertEquals("meeting_active", screen.viewModel.readLastPauseReason())
    }

    @Test
    fun disablingClearsCatalogFlagAndSendsStopToService() {
        AutoAudioCapturePrefs.setEnabled(context, true)
        CommunityPluginPrefs.setNativePluginEnabled(context, NativePluginIds.AUTO_AUDIO, true)
        val screen = createSettingsScreen<AutoAudioSettingsDestination>(AppRoute.AUTO_AUDIO_SETTINGS)

        screen.onEnabledChange(false)

        assertFalse(screen.viewModel.state.enabled)
        assertFalse(AutoAudioCapturePrefs.isEnabled(context))
        assertFalse(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.AUTO_AUDIO))
        assertServiceIntent(
            screen.nextStartedServiceIntent(),
            AutoAudioCaptureService::class.java,
            AutoAudioCaptureService.ACTION_STOP,
        )
    }

    @Test
    fun enablingWithPermissionsMarksCatalogAndStartsService() {
        shadowOf(context as Application).grantPermissions(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        val screen = createSettingsScreen<AutoAudioSettingsDestination>(AppRoute.AUTO_AUDIO_SETTINGS)

        screen.onEnabledChange(true)

        assertTrue(screen.viewModel.state.enabled)
        assertTrue(AutoAudioCapturePrefs.isEnabled(context))
        assertTrue(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.AUTO_AUDIO))
        assertServiceIntent(
            screen.nextStartedServiceIntent(),
            AutoAudioCaptureService::class.java,
            AutoAudioCaptureService.ACTION_START,
        )
    }

    @Test
    fun enablingWithoutPermissionsPersistsSwitchButWaitsForGrant() {
        val screen = createSettingsScreen<AutoAudioSettingsDestination>(AppRoute.AUTO_AUDIO_SETTINGS)

        screen.onEnabledChange(true)

        assertTrue(screen.viewModel.state.enabled)
        assertTrue(AutoAudioCapturePrefs.isEnabled(context))
        assertFalse(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.AUTO_AUDIO))
        assertNull(screen.nextStartedServiceIntent())
    }

    @Test
    fun optionActionsPersist() {
        val screen = createSettingsScreen<AutoAudioSettingsDestination>(AppRoute.AUTO_AUDIO_SETTINGS)

        screen.viewModel.onSpeechExtendChange(false)
        screen.viewModel.onVisualNotesChange(true)
        screen.viewModel.onLoopsPerSyncChange(7)

        assertFalse(AutoAudioCapturePrefs.isSpeechExtendEnabled(context))
        assertTrue(AutoAudioCapturePrefs.isVisualNotesEnabled(context))
        assertEquals(7, AutoAudioCapturePrefs.getLoopsPerSync(context))
        assertEquals(createSettingsScreen<AutoAudioSettingsDestination>(AppRoute.AUTO_AUDIO_SETTINGS).viewModel.state, screen.viewModel.state)
    }
}
