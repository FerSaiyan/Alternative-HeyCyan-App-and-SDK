package com.fersaiyan.cyanbridge.shared.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogRepository
import com.fersaiyan.cyanbridge.shared.accessibility.VisionAssistancePreferences
import com.fersaiyan.cyanbridge.shared.format.formatFixed
import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences
import com.fersaiyan.cyanbridge.shared.prefs.AppPrefs

/** A setup step after the welcome card, in the order shown. */
enum class OnboardingStep {
    GLASSES_CONNECTION,
    LOCAL_MODEL,
    /** Microphone and speech recognition, asked together before the first voice question (iOS). */
    VOICE_QUESTIONS,
    /** AutoDiary, Auto Audio, Local Agent and Pro live in the Plugins tab. */
    OPTIONAL_FEATURES,
    /** Speaker-profile recording; the speaker model ships only in the Android app. */
    VOICE_ENROLLMENT,
    FINISH,
}

/**
 * What a platform's setup can include. Android's OnboardingFeatureDestination shows
 * GLASSES_CONNECTION, LOCAL_MODEL, OPTIONAL_FEATURES, VOICE_ENROLLMENT and FINISH; iOS has no
 * Plugins tab, Tasker or speaker model, and asks for its voice permissions in one step.
 */
data class OnboardingFeatures(
    val localModels: Boolean,
    val voicePermissions: Boolean,
    val pluginsTab: Boolean,
    val voiceEnrollment: Boolean,
)

fun onboardingSteps(features: OnboardingFeatures): List<OnboardingStep> = buildList {
    add(OnboardingStep.GLASSES_CONNECTION)
    if (features.localModels) add(OnboardingStep.LOCAL_MODEL)
    if (features.voicePermissions) add(OnboardingStep.VOICE_QUESTIONS)
    if (features.pluginsTab) add(OnboardingStep.OPTIONAL_FEATURES)
    if (features.voiceEnrollment) add(OnboardingStep.VOICE_ENROLLMENT)
    add(OnboardingStep.FINISH)
}

/**
 * Setup is finished. iOS builds before November 2026 showed only the welcome card and stored
 * `cyanbridge_onboarding/welcome_done`; those users count as finished, read here instead of
 * migrated at startup.
 */
fun isOnboardingCompleted(main: PlatformPreferences, iosOnboarding: PlatformPreferences): Boolean =
    AppPrefs.Main.onboardingCompleted.read(main) || AppPrefs.IosOnboarding.welcomeDone.read(iosOnboarding)

/** What the first-run host shows. */
sealed interface OnboardingScreen {
    /** "Do you need vision assistance?"; [duringSetup] is false when it is offered over the app. */
    data class VisionOffer(val duringSetup: Boolean) : OnboardingScreen
    data object Welcome : OnboardingScreen
    data class Step(val step: OnboardingStep, val index: Int, val count: Int) : OnboardingScreen
    data object Done : OnboardingScreen
}

/**
 * The starter models setup offers (Android's OnboardingFeatureDestination and iOS). The one that
 * fits [ramGb] (LocalModelCatalogRepository.recommendedStarterForRam) is marked.
 */
fun starterModelChoices(ramGb: Double): List<OnboardingChoice> {
    val recommendedId = LocalModelCatalogRepository.recommendedStarterForRam(ramGb)?.id
    return STARTER_MODEL_COPY.mapNotNull { (id, title, description) ->
        val entry = LocalModelCatalogRepository.findById(id) ?: return@mapNotNull null
        OnboardingChoice(
            id = id,
            title = if (id == recommendedId) "$title - Recommended" else title,
            description = "$description\nRequirements: ${formatFixed(entry.minRamGb, 0)} GB RAM • " +
                "${formatFixed(entry.minStorageGb, 1)} GB free • " +
                "${formatFixed(entry.sizeBytes / 1_000_000_000.0, 1)} GB download",
            enabled = ramGb >= entry.minRamGb,
        )
    }
}

private val STARTER_MODEL_COPY = listOf(
    Triple("gemma4-e2b-it-litert", "Gemma 4 E2B - Fast", "Fast local text, image, and audio understanding."),
    Triple("gemma4-e4b-it-litert", "Gemma 4 E4B - Slower but smarter", "Higher-quality local answers for devices with more memory."),
    Triple("qwen3.5-0.8b-q4", "Qwen3.5 0.8B - Super small", "A compact, quick text model for lower-memory devices."),
)

/**
 * First-run flow shared by the hosts: the vision-assistance offer for screen-reader users, the
 * welcome card, then [steps]. The answer to the offer and the finished flag are stored at once;
 * the step position is not, so an interrupted setup starts again at the welcome card.
 */
class OnboardingFlowViewModel(
    features: OnboardingFeatures,
    screenReaderRunning: Boolean,
    private val deviceRamGb: Double = 0.0,
    private val main: PlatformPreferences = AppPrefs.Main.store(),
    iosOnboarding: PlatformPreferences = AppPrefs.IosOnboarding.store(),
    private val vision: PlatformPreferences = VisionAssistancePreferences.store(),
) : ViewModel() {
    val steps: List<OnboardingStep> = onboardingSteps(features)

    private var completed by mutableStateOf(isOnboardingCompleted(main, iosOnboarding))
    private var welcomeDone by mutableStateOf(false)
    private var stepIndex by mutableStateOf(0)
    private var visionOffer by mutableStateOf(VisionAssistancePreferences.shouldOffer(vision, screenReaderRunning))

    var showBluetoothSkipWarning by mutableStateOf(false)
        private set

    val modelChoices: List<OnboardingChoice> = starterModelChoices(deviceRamGb)

    /** A stored choice that still fits, else the recommended starter, else no model. */
    var selectedModelId by mutableStateOf(
        AppPrefs.Main.onboardingModelId.read(main)
            ?.takeIf { id -> id == NO_MODEL_ID || modelChoices.any { it.id == id && it.enabled } }
            ?: LocalModelCatalogRepository.recommendedStarterForRam(deviceRamGb)?.id
            ?: NO_MODEL_ID,
    )
        private set

    val screen: OnboardingScreen
        get() = when {
            visionOffer -> OnboardingScreen.VisionOffer(duringSetup = !completed)
            completed -> OnboardingScreen.Done
            !welcomeDone -> OnboardingScreen.Welcome
            else -> OnboardingScreen.Step(steps[stepIndex], stepIndex, steps.size)
        }

    /** Setup was finished or skipped (the vision offer may still show over the app). */
    val isCompleted: Boolean get() = completed

    val currentStep: OnboardingStep? get() = (screen as? OnboardingScreen.Step)?.step

    /** A screen reader came on (or was found at launch): offer vision assistance once, as on Android. */
    fun onScreenReaderChanged(running: Boolean) {
        if (VisionAssistancePreferences.shouldOffer(vision, running)) visionOffer = true
    }

    fun answerVisionAssistance(enabled: Boolean) {
        VisionAssistancePreferences.setEnabled(vision, enabled)
        visionOffer = false
    }

    fun startSetup() {
        welcomeDone = true
        stepIndex = 0
    }

    /** Next; on the glasses step without Bluetooth access it first asks to continue anyway. */
    fun next(bluetoothAllowed: Boolean = true) {
        if (currentStep == OnboardingStep.GLASSES_CONNECTION && !bluetoothAllowed) {
            showBluetoothSkipWarning = true
            return
        }
        advance()
    }

    fun confirmBluetoothSkip() {
        showBluetoothSkipWarning = false
        advance()
    }

    fun dismissBluetoothSkip() {
        showBluetoothSkipWarning = false
    }

    /** Back from the first step returns to the welcome card. */
    fun back() {
        if (stepIndex == 0) welcomeDone = false else stepIndex--
    }

    fun skipAll() = finish()

    fun selectModel(id: String) {
        if (id != NO_MODEL_ID && modelChoices.none { it.id == id && it.enabled }) return
        selectedModelId = id
        AppPrefs.Main.onboardingModelId.write(main, id)
    }

    /** The catalog id to download when the model step is confirmed, or null for no model. */
    val modelToDownload: String?
        get() = selectedModelId.takeIf { id -> id != NO_MODEL_ID && modelChoices.any { it.id == id && it.enabled } }

    private fun advance() {
        if (stepIndex >= steps.lastIndex) finish() else stepIndex++
    }

    private fun finish() {
        AppPrefs.Main.onboardingCompleted.write(main, true)
        completed = true
    }

    companion object {
        /** The "no on-device model for now" choice of the model step. */
        const val NO_MODEL_ID = "none"
    }
}
