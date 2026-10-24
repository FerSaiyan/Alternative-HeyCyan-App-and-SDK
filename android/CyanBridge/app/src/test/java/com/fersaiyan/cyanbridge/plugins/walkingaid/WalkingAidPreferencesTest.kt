package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun defaultEnabledIsFalse() {
        assertFalse(WalkingAidPreferences.isEnabled(context))
    }

    @Test
    fun defaultCaptureIntervalSecondsIs5() {
        assertEquals(5, WalkingAidPreferences.getCaptureIntervalSeconds(context))
    }

    @Test
    fun defaultImageDescriptionSourceIsLocal() {
        assertEquals("local", WalkingAidPreferences.getImageDescriptionSource(context))
    }

    @Test
    fun defaultImageDescriptionCloudModelIdUsesWalkingAidRelayAlias() {
        assertEquals(
            "cyanbridge/walking-aid-vision",
            WalkingAidPreferences.getImageDescriptionCloudModelId(context),
        )
    }

    @Test
    fun defaultDepthEnabledIsTrue() {
        assertTrue(WalkingAidPreferences.isDepthEnabled(context))
    }

    @Test
    fun defaultDepthSourceIsCloud() {
        assertEquals("cloud", WalkingAidPreferences.getDepthSource(context))
    }

    @Test
    fun defaultTtsEnabledIsTrue() {
        assertTrue(WalkingAidPreferences.isTtsEnabled(context))
    }

    @Test
    fun defaultSafetyDisclaimerEnabledIsTrue() {
        assertTrue(WalkingAidPreferences.isSafetyDisclaimerEnabled(context))
    }

    @Test
    fun defaultImageHistoryMaxCountIs50() {
        assertEquals(50, WalkingAidPreferences.getImageHistoryMaxCount(context))
    }

    @Test
    fun setAndGetEnabled() {
        WalkingAidPreferences.setEnabled(context, true)
        assertTrue(WalkingAidPreferences.isEnabled(context))

        WalkingAidPreferences.setEnabled(context, false)
        assertFalse(WalkingAidPreferences.isEnabled(context))
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
    fun setAndGetImageDescriptionSource() {
        WalkingAidPreferences.setImageDescriptionSource(context, "cloud")
        assertEquals("cloud", WalkingAidPreferences.getImageDescriptionSource(context))

        WalkingAidPreferences.setImageDescriptionSource(context, "local")
        assertEquals("local", WalkingAidPreferences.getImageDescriptionSource(context))
    }

    @Test
    fun setAndGetImageDescriptionCloudModelId() {
        val modelId = "openai/gpt-5.4"
        WalkingAidPreferences.setImageDescriptionCloudModelId(context, modelId)
        assertEquals(modelId, WalkingAidPreferences.getImageDescriptionCloudModelId(context))
    }

    @Test
    fun setAndGetDepthEnabled() {
        WalkingAidPreferences.setDepthEnabled(context, false)
        assertFalse(WalkingAidPreferences.isDepthEnabled(context))
    }

    @Test
    fun setAndGetDepthSource() {
        WalkingAidPreferences.setDepthSource(context, "local")
        assertEquals("local", WalkingAidPreferences.getDepthSource(context))
    }

    @Test
    fun setAndGetDepthCloudModelId() {
        val modelId = "google/gemini-3-flash-preview"
        WalkingAidPreferences.setDepthCloudModelId(context, modelId)
        assertEquals(modelId, WalkingAidPreferences.getDepthCloudModelId(context))
    }

    @Test
    fun setAndGetStateModelSource() {
        WalkingAidPreferences.setStateModelSource(context, "cloud")
        assertEquals("cloud", WalkingAidPreferences.getStateModelSource(context))
    }

    @Test
    fun setAndGetTtsEnabled() {
        WalkingAidPreferences.setTtsEnabled(context, false)
        assertFalse(WalkingAidPreferences.isTtsEnabled(context))
    }

    @Test
    fun setAndGetSafetyDisclaimerEnabled() {
        WalkingAidPreferences.setSafetyDisclaimerEnabled(context, false)
        assertFalse(WalkingAidPreferences.isSafetyDisclaimerEnabled(context))
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

    @Test
    fun shouldUseCloudReturnsFalseWhenBothLocal() {
        WalkingAidPreferences.setImageDescriptionSource(context, "local")
        WalkingAidPreferences.setDepthSource(context, "local")
        assertFalse(WalkingAidPreferences.shouldUseCloud(context))
    }

    @Test
    fun shouldUseCloudReturnsTrueWhenImageDescriptionCloud() {
        WalkingAidPreferences.setImageDescriptionSource(context, "cloud")
        WalkingAidPreferences.setDepthSource(context, "local")
        assertTrue(WalkingAidPreferences.shouldUseCloud(context))
    }

    @Test
    fun shouldUseCloudReturnsTrueWhenDepthCloud() {
        WalkingAidPreferences.setImageDescriptionSource(context, "local")
        WalkingAidPreferences.setDepthSource(context, "cloud")
        assertTrue(WalkingAidPreferences.shouldUseCloud(context))
    }

    @Test
    fun naturalFocusDescriptionIsStoredAndMapped() {
        val description = "Please pay attention to pets and traffic"

        WalkingAidPreferences.setFocusDescription(context, description)

        assertEquals(description, WalkingAidPreferences.getFocusDescription(context))
        assertTrue(WalkingAidPreferences.getWatchlistTerms(context).containsAll(listOf("cat", "dog", "car")))
    }
}
