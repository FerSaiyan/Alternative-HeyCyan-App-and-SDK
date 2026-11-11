package com.fersaiyan.cyanbridge.shared.ui.onboarding

import com.fersaiyan.cyanbridge.shared.platform.createPlatformPreferences
import com.fersaiyan.cyanbridge.shared.prefs.AppPrefs
import com.fersaiyan.cyanbridge.shared.ui.onboarding.OnboardingStep.FINISH
import com.fersaiyan.cyanbridge.shared.ui.onboarding.OnboardingStep.GLASSES_CONNECTION
import com.fersaiyan.cyanbridge.shared.ui.onboarding.OnboardingStep.LOCAL_MODEL
import com.fersaiyan.cyanbridge.shared.ui.onboarding.OnboardingStep.OPTIONAL_FEATURES
import com.fersaiyan.cyanbridge.shared.ui.onboarding.OnboardingStep.VOICE_ENROLLMENT
import com.fersaiyan.cyanbridge.shared.ui.onboarding.OnboardingStep.VOICE_QUESTIONS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnboardingFlowViewModelTest {
    private val ios = OnboardingFeatures(localModels = true, voicePermissions = true, pluginsTab = false, voiceEnrollment = false)

    private class Stores(name: String) {
        val main = createPlatformPreferences("onboarding_test_main_$name").also { it.clear() }
        val iosOnboarding = createPlatformPreferences("onboarding_test_ios_$name").also { it.clear() }
        val vision = createPlatformPreferences("onboarding_test_vision_$name").also { it.clear() }
    }

    private fun flow(stores: Stores, screenReader: Boolean = false, features: OnboardingFeatures = ios) =
        OnboardingFlowViewModel(features, screenReader, 8.0, stores.main, stores.iosOnboarding, stores.vision)

    @Test
    fun stepsPerPlatform() {
        val cases = listOf(
            ios to listOf(GLASSES_CONNECTION, LOCAL_MODEL, VOICE_QUESTIONS, FINISH),
            ios.copy(localModels = false) to listOf(GLASSES_CONNECTION, VOICE_QUESTIONS, FINISH),
            // Android's OnboardingFeatureDestination order.
            OnboardingFeatures(localModels = true, voicePermissions = false, pluginsTab = true, voiceEnrollment = true) to
                listOf(GLASSES_CONNECTION, LOCAL_MODEL, OPTIONAL_FEATURES, VOICE_ENROLLMENT, FINISH),
        )
        cases.forEach { (features, steps) -> assertEquals(steps, onboardingSteps(features), features.toString()) }
    }

    @Test
    fun completionFlags() {
        // (onboarding_completed, legacy iOS welcome_done) -> first screen
        val cases = listOf(
            Triple(false, false, OnboardingScreen.Welcome),
            Triple(true, false, OnboardingScreen.Done),
            Triple(false, true, OnboardingScreen.Done),
        )
        cases.forEachIndexed { index, (completed, welcomeDone, expected) ->
            val stores = Stores("flags$index")
            if (completed) AppPrefs.Main.onboardingCompleted.write(stores.main, true)
            if (welcomeDone) AppPrefs.IosOnboarding.welcomeDone.write(stores.iosOnboarding, true)
            assertEquals(expected, flow(stores).screen, "completed=$completed welcome_done=$welcomeDone")
        }

        val walked = Stores("walk")
        flow(walked).run {
            startSetup()
            next(bluetoothAllowed = false)
            assertTrue(showBluetoothSkipWarning)
            confirmBluetoothSkip()
            repeat(steps.size - 1) { next() }
            assertEquals(OnboardingScreen.Done, screen)
        }
        assertTrue(AppPrefs.Main.onboardingCompleted.read(walked.main))

        val skipped = Stores("skip")
        flow(skipped).run { startSetup(); skipAll() }
        assertTrue(AppPrefs.Main.onboardingCompleted.read(skipped.main))
    }

    @Test
    fun visionOfferBeforeSetupAndOnceWhenTheScreenReaderComesOnLater() {
        val fresh = Stores("vision_fresh")
        flow(fresh, screenReader = true).run {
            assertEquals(OnboardingScreen.VisionOffer(duringSetup = true), screen)
            answerVisionAssistance(enabled = true)
            assertEquals(OnboardingScreen.Welcome, screen)
        }

        val upgraded = Stores("vision_later")
        AppPrefs.IosOnboarding.welcomeDone.write(upgraded.iosOnboarding, true)
        flow(upgraded).run {
            assertEquals(OnboardingScreen.Done, screen)
            onScreenReaderChanged(running = true)
            assertEquals(OnboardingScreen.VisionOffer(duringSetup = false), screen)
            answerVisionAssistance(enabled = false)
            onScreenReaderChanged(running = true)
            assertEquals(OnboardingScreen.Done, screen)
        }
    }
}
