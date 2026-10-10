package com.fersaiyan.cyanbridge.plugins.walkingaid

import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.agent.ProSubscriptionPrefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WalkingAidReadinessCheckerTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        WalkingAidPreferences.setMode(context, WalkingAidMode.WALKING)
        ProSubscriptionPrefs.clearEntitlement(context)
        WalkingAidPreferences.setImageDescriptionSource(context, "local")
        WalkingAidPreferences.setYoloModelType(context, WalkingAidPreferences.MODEL_TYPE_YOLO11)
        WalkingAidPreferences.setDepthEnabled(context, true)
        WalkingAidPreferences.setDepthSource(context, "local")
        WalkingAidPreferences.setStateModelSource(context, "local")
        File(context.filesDir, "yolo11n_float16.tflite").delete()
    }

    @Test
    fun exploreRequiresOnlyDetectorEvenWithSavedCloudSettings() {
        WalkingAidPreferences.setMode(context, WalkingAidMode.EXPLORE)
        WalkingAidPreferences.setImageDescriptionSource(context, "cloud")
        WalkingAidPreferences.setDepthSource(context, "cloud")
        val detector = File(context.filesDir, "yolo11n_float16.tflite")
        detector.writeBytes(ByteArray(1024))
        try {
            val readiness = WalkingAidReadinessChecker.checkReadiness(context)
            assertTrue(readiness.isReady)
            assertFalse(readiness.requiresProForCloud)
        } finally {
            detector.delete()
        }
    }

    @Test
    fun checkReadiness_returnsNotReady_whenLocalAssetsMissing() {
        val readiness = WalkingAidReadinessChecker.checkReadiness(context)
        assertFalse(readiness.isReady)
        assertFalse(readiness.yoloReady)
        assertFalse(readiness.depthReady)
        assertTrue(readiness.llmReady)
        assertTrue(readiness.missingDetails.isNotEmpty())
    }

    @Test
    fun checkReadiness_requiresProForCloud_whenNotProSubscribed() {
        WalkingAidPreferences.setImageDescriptionSource(context, "cloud")
        val readiness = WalkingAidReadinessChecker.checkReadiness(context)
        assertFalse(readiness.isReady)
        assertFalse(readiness.yoloReady)
        assertTrue(readiness.requiresProForCloud)
    }

    @Test
    fun checkReadiness_passesCloud_whenProSubscribed() {
        val detector = File(context.filesDir, "yolo11n_float16.tflite")
        detector.writeBytes(ByteArray(1024))
        ProSubscriptionPrefs.setSubscribed(context, true)
        ProSubscriptionPrefs.setExpiresAt(context, System.currentTimeMillis() + 86400000L)
        WalkingAidPreferences.setImageDescriptionSource(context, "cloud")
        WalkingAidPreferences.setDepthSource(context, "cloud")
        WalkingAidPreferences.setStateModelSource(context, "cloud")

        try {
            val readiness = WalkingAidReadinessChecker.checkReadiness(context)
            assertTrue(readiness.isReady)
            assertTrue(readiness.yoloReady)
            assertTrue(readiness.depthReady)
            assertTrue(readiness.llmReady)
            assertFalse(readiness.requiresProForCloud)
        } finally {
            detector.delete()
        }
    }

    @Test
    fun checkReadiness_cloudEnrichmentStillRequiresLocalSafetyDetector() {
        ProSubscriptionPrefs.setSubscribed(context, true)
        ProSubscriptionPrefs.setExpiresAt(context, System.currentTimeMillis() + 86400000L)
        WalkingAidPreferences.setImageDescriptionSource(context, "cloud")
        WalkingAidPreferences.setDepthEnabled(context, false)

        val readiness = WalkingAidReadinessChecker.checkReadiness(context)

        assertFalse(readiness.isReady)
        assertFalse(readiness.yoloReady)
        assertFalse(readiness.requiresProForCloud)
    }

    @Test
    fun checkReadiness_doesNotRequireSceneLlm() {
        WalkingAidPreferences.setDepthEnabled(context, false)
        val detector = File(context.filesDir, "yolo11n_float16.tflite")
        detector.writeBytes(ByteArray(1024))

        try {
            val readiness = WalkingAidReadinessChecker.checkReadiness(context)

            assertTrue(readiness.isReady)
            assertTrue(readiness.yoloReady)
            assertTrue(readiness.depthReady)
            assertTrue(readiness.llmReady)
            assertTrue(readiness.missingDetails.isEmpty())
        } finally {
            detector.delete()
        }
    }
}
