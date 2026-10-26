package com.fersaiyan.cyanbridge.plugins.visualdiary

import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.devices.DeviceCapabilityHelper
import com.fersaiyan.cyanbridge.plugins.settings.COMMUNITY_PLUGIN_PREFS
import com.fersaiyan.cyanbridge.plugins.settings.assertServiceIntent
import com.fersaiyan.cyanbridge.plugins.settings.clearPreferenceFiles
import com.fersaiyan.cyanbridge.plugins.settings.createSettingsScreen
import com.fersaiyan.cyanbridge.plugins.settings.nextStartedServiceIntent
import com.fersaiyan.cyanbridge.plugins.settings.nextStoppedServiceIntent
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
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
class VisualDiarySettingsDestinationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.clearPreferenceFiles("visual_diary_prefs", "device_profile", COMMUNITY_PLUGIN_PREFS)
    }

    @Test
    fun firstFrameStateComesFromPreferences() {
        VisualDiaryPreferences.setEnabled(context, true)
        VisualDiaryPreferences.setLastError(context, "Glasses disconnected")
        VisualDiaryPreferences.setCustomPrompt(context, "Describe the room")

        val state = createSettingsScreen<VisualDiarySettingsDestination>(AppRoute.VISUAL_DIARY_SETTINGS).state

        assertEquals(
            VisualDiarySettingsState(
                enabled = true,
                lastError = "Glasses disconnected",
                customPrompt = "Describe the room",
                hasCamera = DeviceCapabilityHelper.hasCamera(context),
                cameraUnavailableReason = DeviceCapabilityHelper.unavailableCameraReason(context),
            ),
            state,
        )
        assertEquals(VisualDiarySettingsState.load(context), state)
    }

    @Test
    fun enableAndDisableGoThroughServiceAndRefreshState() {
        val screen = createSettingsScreen<VisualDiarySettingsDestination>(AppRoute.VISUAL_DIARY_SETTINGS)
        val hasCamera = DeviceCapabilityHelper.hasCamera(context)

        screen.onEnabledChange(true)

        assertEquals(hasCamera, screen.state.enabled)
        assertEquals(hasCamera, VisualDiaryPreferences.isEnabled(context))
        assertEquals(hasCamera, CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.VISUAL_DIARY))

        screen.onEnabledChange(false)

        assertFalse(screen.state.enabled)
        assertFalse(VisualDiaryPreferences.isEnabled(context))
        assertFalse(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.VISUAL_DIARY))
        assertEquals(VisualDiaryService::class.java.name, screen.nextStoppedServiceIntent()?.component?.className)
        assertEquals(
            VisualDiaryService.ACTION_TASKER_DISABLE,
            shadowOf(context as Application).broadcastIntents.last().action,
        )
    }

    @Test
    fun captureNowStartsTheCaptureService() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val screen = createSettingsScreen<VisualDiarySettingsDestination>(AppRoute.VISUAL_DIARY_SETTINGS)

        screen.onCaptureSceneNow()

        assertServiceIntent(
            screen.nextStartedServiceIntent(),
            VisualDiaryService::class.java,
            VisualDiaryService.ACTION_CAPTURE_NOW,
        )
    }

    @Test
    fun customPromptPersistsAndBackFinishes() {
        val screen = createSettingsScreen<VisualDiarySettingsDestination>(AppRoute.VISUAL_DIARY_SETTINGS)

        screen.onCustomPromptChange("Only objects")

        assertEquals("Only objects", screen.state.customPrompt)
        assertEquals("Only objects", VisualDiaryPreferences.getCustomPrompt(context))

        screen.onBack()
        assertTrue(screen.isFinishing)
    }
}
