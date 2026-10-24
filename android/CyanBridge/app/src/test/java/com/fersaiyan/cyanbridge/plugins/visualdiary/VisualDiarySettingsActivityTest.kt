package com.fersaiyan.cyanbridge.plugins.visualdiary

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.devices.DeviceCapabilityHelper
import com.fersaiyan.cyanbridge.plugins.settings.COMMUNITY_PLUGIN_PREFS
import com.fersaiyan.cyanbridge.plugins.settings.assertServiceIntent
import com.fersaiyan.cyanbridge.plugins.settings.clearPreferenceFiles
import com.fersaiyan.cyanbridge.plugins.settings.createSettingsActivity
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VisualDiarySettingsActivityTest {
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

        val state = createSettingsActivity<VisualDiarySettingsActivity>().state

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
        val activity = createSettingsActivity<VisualDiarySettingsActivity>()
        val hasCamera = DeviceCapabilityHelper.hasCamera(context)

        activity.onEnabledChange(true)

        assertEquals(hasCamera, activity.state.enabled)
        assertEquals(hasCamera, VisualDiaryPreferences.isEnabled(context))
        assertEquals(hasCamera, CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.VISUAL_DIARY))

        activity.onEnabledChange(false)

        assertFalse(activity.state.enabled)
        assertFalse(VisualDiaryPreferences.isEnabled(context))
        assertFalse(CommunityPluginPrefs.isNativePluginEnabled(context, NativePluginIds.VISUAL_DIARY))
        assertEquals(VisualDiaryService::class.java.name, activity.nextStoppedServiceIntent()?.component?.className)
        assertEquals(
            VisualDiaryService.ACTION_TASKER_DISABLE,
            shadowOf(context as Application).broadcastIntents.last().action,
        )
    }

    @Test
    fun captureNowStartsTheCaptureService() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val activity = createSettingsActivity<VisualDiarySettingsActivity>()

        activity.onCaptureSceneNow()

        assertServiceIntent(
            activity.nextStartedServiceIntent(),
            VisualDiaryService::class.java,
            VisualDiaryService.ACTION_CAPTURE_NOW,
        )
    }

    @Test
    fun customPromptPersistsAndBackFinishes() {
        val activity = createSettingsActivity<VisualDiarySettingsActivity>()

        activity.onCustomPromptChange("Only objects")

        assertEquals("Only objects", activity.state.customPrompt)
        assertEquals("Only objects", VisualDiaryPreferences.getCustomPrompt(context))

        activity.onBack()
        assertTrue(activity.isFinishing)
    }
}
