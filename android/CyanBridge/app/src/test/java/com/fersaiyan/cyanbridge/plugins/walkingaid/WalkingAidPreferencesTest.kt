package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WalkingAidPreferencesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear prefs before each test
        context.getSharedPreferences("walking_aid_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @Test
    fun existingUsersDefaultToWalkingAndInvalidModeFallsBack() {
        assertEquals(WalkingAidMode.WALKING, WalkingAidPreferences.getMode(context))
        context.getSharedPreferences("walking_aid_prefs", Context.MODE_PRIVATE).edit()
            .putString("mode", "unknown_mode").apply()
        assertEquals(WalkingAidMode.WALKING, WalkingAidPreferences.getMode(context))
    }

    @Test
    fun setAndGetCaptureIntervalSeconds() {
        WalkingAidPreferences.setCaptureIntervalSeconds(context, 10)
        assertEquals(10, WalkingAidPreferences.getCaptureIntervalSeconds(context))

        // Clamped to range
        WalkingAidPreferences.setCaptureIntervalSeconds(context, 1)
        assertTrue(WalkingAidPreferences.getCaptureIntervalSeconds(context) >= 2)

        WalkingAidPreferences.setCaptureIntervalSeconds(context, 100)
        assertTrue(WalkingAidPreferences.getCaptureIntervalSeconds(context) <= 60)
    }

    @Test
    fun setAndGetImageHistoryMaxCount() {
        WalkingAidPreferences.setImageHistoryMaxCount(context, 100)
        assertEquals(100, WalkingAidPreferences.getImageHistoryMaxCount(context))

        // Clamped to range
        WalkingAidPreferences.setImageHistoryMaxCount(context, 5)
        assertTrue(WalkingAidPreferences.getImageHistoryMaxCount(context) >= 10)

        WalkingAidPreferences.setImageHistoryMaxCount(context, 500)
        assertTrue(WalkingAidPreferences.getImageHistoryMaxCount(context) <= 200)
    }

    @Test
    fun getImageDescriptionModelOverrideReturnsNullForLocal() {
        WalkingAidPreferences.setImageDescriptionSource(context, "local")
        assertNull(WalkingAidPreferences.getImageDescriptionModelOverride(context))
    }

    @Test
    fun getImageDescriptionModelOverrideReturnsModelIdForCloud() {
        WalkingAidPreferences.setImageDescriptionSource(context, "cloud")
        val modelId = "deepseek/deepseek-v4-flash"
        WalkingAidPreferences.setImageDescriptionCloudModelId(context, modelId)
        assertEquals(modelId, WalkingAidPreferences.getImageDescriptionModelOverride(context))
    }

    @Test
    fun getDepthModelOverrideReturnsNullForLocal() {
        WalkingAidPreferences.setDepthSource(context, "local")
        assertNull(WalkingAidPreferences.getDepthModelOverride(context))
    }

    @Test
    fun getDepthModelOverrideReturnsModelIdForCloud() {
        WalkingAidPreferences.setDepthSource(context, "cloud")
        val modelId = "deepseek/deepseek-v4-flash"
        WalkingAidPreferences.setDepthCloudModelId(context, modelId)
        assertEquals(modelId, WalkingAidPreferences.getDepthModelOverride(context))
    }
}
