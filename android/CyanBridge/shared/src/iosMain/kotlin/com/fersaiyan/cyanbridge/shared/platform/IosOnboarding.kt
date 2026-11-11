package com.fersaiyan.cyanbridge.shared.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.fersaiyan.cyanbridge.shared.format.formatFixed
import com.fersaiyan.cyanbridge.shared.generated.resources.*
import com.fersaiyan.cyanbridge.shared.icons.AppIcon
import com.fersaiyan.cyanbridge.shared.ui.SystemBackHandler
import com.fersaiyan.cyanbridge.shared.ui.onboarding.FeatureOnboardingScreen
import com.fersaiyan.cyanbridge.shared.ui.onboarding.OnboardingChoice
import com.fersaiyan.cyanbridge.shared.ui.onboarding.OnboardingFeatures
import com.fersaiyan.cyanbridge.shared.ui.onboarding.OnboardingFlowViewModel
import com.fersaiyan.cyanbridge.shared.ui.onboarding.OnboardingPermissionCard
import com.fersaiyan.cyanbridge.shared.ui.onboarding.OnboardingScreen
import com.fersaiyan.cyanbridge.shared.ui.onboarding.OnboardingStep
import com.fersaiyan.cyanbridge.shared.ui.onboarding.VisionAssistanceScreen
import com.fersaiyan.cyanbridge.shared.ui.onboarding.WelcomeScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.compose.resources.stringResource
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionRecordPermissionDenied
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.CoreBluetooth.CBManagerAuthorizationNotDetermined
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.UIKit.UIAccessibilityIsVoiceOverRunning
import platform.UIKit.UIAccessibilityVoiceOverStatusDidChangeNotification
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationOpenSettingsURLString
import kotlin.coroutines.resume

private const val GITHUB_REPOSITORY_URL = "https://github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK"

/** iOS asks for each permission once; after a refusal only the Settings app can change it. */
internal enum class IosPermissionState { NOT_ASKED, ALLOWED, DENIED }

/**
 * iOS setup: no Plugins tab, Tasker or speaker model; microphone and speech recognition are asked
 * in one step. The model step needs the on-device runtimes the Swift host registers.
 */
internal fun iosOnboardingFeatures() = OnboardingFeatures(
    localModels = LocalModelRegistry.bridge != null,
    voicePermissions = true,
    pluginsTab = false,
    voiceEnrollment = false,
)

internal fun iosOnboardingFlow() = OnboardingFlowViewModel(
    features = iosOnboardingFeatures(),
    screenReaderRunning = UIAccessibilityIsVoiceOverRunning(),
    deviceRamGb = IosDeviceCapability.ramGb,
)

/** Permission state read without prompting (prompts happen only from the setup buttons or in use). */
internal object IosOnboardingPermissions {
    fun bluetooth(): IosPermissionState = when (CBManager.authorization) {
        CBManagerAuthorizationAllowedAlways -> IosPermissionState.ALLOWED
        CBManagerAuthorizationNotDetermined -> IosPermissionState.NOT_ASKED
        else -> IosPermissionState.DENIED
    }

    /** Voice questions need both the microphone and speech recognition. */
    fun voice(): IosPermissionState {
        val microphone = AVAudioSession.sharedInstance().recordPermission
        val speech = SFSpeechRecognizer.authorizationStatus()
        return when {
            microphone == AVAudioSessionRecordPermissionGranted &&
                speech == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized ->
                IosPermissionState.ALLOWED
            microphone == AVAudioSessionRecordPermissionDenied ||
                speech == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusDenied ||
                speech == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusRestricted ->
                IosPermissionState.DENIED
            else -> IosPermissionState.NOT_ASKED
        }
    }

    suspend fun requestVoice() {
        if (!IosChatPlatform.requestMicrophonePermission()) return
        suspendCancellableCoroutine<Unit> { continuation ->
            SFSpeechRecognizer.requestAuthorization { _ -> if (continuation.isActive) continuation.resume(Unit) }
        }
    }

    fun openSettings() = openUrl(UIApplicationOpenSettingsURLString)

    fun openUrl(url: String) {
        val target = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(target, options = emptyMap<Any?, Any?>(), completionHandler = null)
    }
}

/**
 * Runs [onChange] when VoiceOver is turned on or off and whenever the app becomes active again
 * (after a permission alert, or back from Settings).
 */
@Composable
internal fun IosAccessibilityAndResumeEvents(onChange: () -> Unit) {
    val latest by rememberUpdatedState(onChange)
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val observers = listOf(UIAccessibilityVoiceOverStatusDidChangeNotification, UIApplicationDidBecomeActiveNotification)
            .map { name -> center.addObserverForName(name, `object` = null, queue = NSOperationQueue.mainQueue) { _ -> latest() } }
        onDispose { observers.forEach { center.removeObserver(it) } }
    }
}

/** The vision-assistance offer, the welcome card and the setup steps; nothing once setup is done. */
@Composable
internal fun IosOnboardingFlow(flow: OnboardingFlowViewModel, controller: IosAppController) {
    when (val screen = flow.screen) {
        is OnboardingScreen.VisionOffer -> VisionAssistanceScreen(
            screenReaderDetected = true,
            showSetupProgress = screen.duringSetup,
            onConfirm = flow::answerVisionAssistance,
        )
        OnboardingScreen.Welcome -> WelcomeScreen(
            languageOptions = IosAppLanguage.options,
            selectedLanguageId = IosAppLanguage.selectedId,
            languageSelectionComplete = true,
            onLanguageSelected = { option -> IosAppLanguage.select(option.id) },
            onStartSetup = flow::startSetup,
            durationNote = stringResource(Res.string.onboarding_duration_note_short),
        )
        is OnboardingScreen.Step -> IosOnboardingStep(flow, screen, controller)
        OnboardingScreen.Done -> Unit
    }
}

@Composable
private fun IosOnboardingStep(flow: OnboardingFlowViewModel, screen: OnboardingScreen.Step, controller: IosAppController) {
    val scope = rememberCoroutineScope()
    var bluetooth by remember { mutableStateOf(IosOnboardingPermissions.bluetooth()) }
    var voice by remember { mutableStateOf(IosOnboardingPermissions.voice()) }
    val bluetoothOn by controller.bleManager.isBluetoothEnabled.collectAsState()
    LaunchedEffect(bluetoothOn) { bluetooth = IosOnboardingPermissions.bluetooth() }
    IosAccessibilityAndResumeEvents {
        bluetooth = IosOnboardingPermissions.bluetooth()
        voice = IosOnboardingPermissions.voice()
    }
    SystemBackHandler { flow.back() }

    val step = screen.step
    val isModelStep = step == OnboardingStep.LOCAL_MODEL
    val ramGb = IosDeviceCapability.ramGb
    val ramLabel = formatFixed(ramGb, 1)
    val settingsLabel = stringResource(Res.string.onboarding_open_ios_settings)
    val permissionCards = when (step) {
        OnboardingStep.GLASSES_CONNECTION -> listOf(
            OnboardingPermissionCard(
                granted = bluetooth == IosPermissionState.ALLOWED,
                grantedText = stringResource(Res.string.onboarding_bluetooth_allowed),
                pendingText = stringResource(Res.string.onboarding_bluetooth_denied),
                buttonText = if (bluetooth == IosPermissionState.DENIED) settingsLabel
                else stringResource(Res.string.onboarding_allow_glasses_connection),
                onRequest = {
                    // Creating the Bluetooth manager is what makes iOS ask.
                    if (bluetooth == IosPermissionState.DENIED) IosOnboardingPermissions.openSettings()
                    else controller.bleManager.activate()
                },
            ),
        )
        OnboardingStep.VOICE_QUESTIONS -> listOf(
            OnboardingPermissionCard(
                granted = voice == IosPermissionState.ALLOWED,
                grantedText = stringResource(Res.string.onboarding_voice_allowed),
                pendingText = stringResource(Res.string.onboarding_voice_denied),
                buttonText = if (voice == IosPermissionState.DENIED) settingsLabel
                else stringResource(Res.string.onboarding_allow_voice),
                onRequest = {
                    if (voice == IosPermissionState.DENIED) {
                        IosOnboardingPermissions.openSettings()
                    } else {
                        scope.launch {
                            IosOnboardingPermissions.requestVoice()
                            voice = IosOnboardingPermissions.voice()
                        }
                    }
                },
            ),
        )
        else -> emptyList()
    }
    val choices = if (isModelStep) {
        flow.modelChoices + OnboardingChoice(
            id = OnboardingFlowViewModel.NO_MODEL_ID,
            title = stringResource(Res.string.onboarding_model_none_title),
            description = stringResource(Res.string.onboarding_model_none_desc),
        )
    } else {
        emptyList()
    }
    val lowMemory = isModelStep && ramGb < 6.0
    val isLast = screen.index == screen.count - 1

    FeatureOnboardingScreen(
        title = stringResource(
            when (step) {
                OnboardingStep.GLASSES_CONNECTION -> Res.string.onboarding_glasses_connection_title
                OnboardingStep.LOCAL_MODEL -> Res.string.onboarding_local_model_title
                OnboardingStep.VOICE_QUESTIONS -> Res.string.onboarding_voice_title
                else -> Res.string.onboarding_open_source_title
            },
        ),
        description = stringResource(
            when (step) {
                OnboardingStep.GLASSES_CONNECTION -> Res.string.onboarding_glasses_connection_desc
                OnboardingStep.LOCAL_MODEL -> Res.string.onboarding_local_model_desc
                OnboardingStep.VOICE_QUESTIONS -> Res.string.onboarding_voice_desc
                else -> Res.string.onboarding_open_source_desc
            },
        ),
        details = stringResource(
            when (step) {
                OnboardingStep.GLASSES_CONNECTION -> Res.string.onboarding_glasses_connection_details
                OnboardingStep.LOCAL_MODEL -> Res.string.onboarding_local_model_details
                OnboardingStep.VOICE_QUESTIONS -> Res.string.onboarding_voice_details
                else -> Res.string.onboarding_open_source_details
            },
        ),
        showGlassesConnectionPermission = false,
        glassesConnectionPermissionGranted = bluetooth == IosPermissionState.ALLOWED,
        showStoragePermission = false,
        storagePermissionGranted = true,
        showOpenSourceContribution = step == OnboardingStep.FINISH,
        choices = choices,
        selectedChoiceId = flow.selectedModelId,
        backLabel = stringResource(if (screen.index == 0) Res.string.onboarding_skip_all else Res.string.onboarding_back),
        nextLabel = stringResource(if (isLast) Res.string.onboarding_get_started else Res.string.onboarding_next),
        stepIndex = screen.index,
        stepCount = screen.count,
        deviceCapabilitySummary = if (isModelStep) stringResource(Res.string.onboarding_device_ram, ramLabel) else null,
        lowMemoryWarningTitle = if (lowMemory) stringResource(Res.string.onboarding_ram_warning_title) else null,
        lowMemoryWarning = if (lowMemory) {
            stringResource(if (ramGb < 4.0) Res.string.onboarding_ram_too_low else Res.string.onboarding_ram_limited, ramLabel)
        } else {
            null
        },
        showBluetoothSkipWarning = flow.showBluetoothSkipWarning,
        bluetoothSkipWarningTitle = stringResource(Res.string.onboarding_bluetooth_skip_title),
        bluetoothSkipWarningBody = stringResource(Res.string.onboarding_bluetooth_skip_body),
        bluetoothSkipConfirmLabel = stringResource(Res.string.onboarding_bluetooth_skip_confirm),
        bluetoothSkipCancelLabel = stringResource(Res.string.onboarding_bluetooth_skip_cancel),
        findGlassesLabel = stringResource(Res.string.onboarding_find_glasses),
        permissionCards = permissionCards,
        showFindGlassesButton = step == OnboardingStep.GLASSES_CONNECTION && bluetooth == IosPermissionState.ALLOWED,
        icon = if (step == OnboardingStep.VOICE_QUESTIONS) AppIcon.Microphone else null,
        onRequestGlassesConnectionPermission = {},
        onRequestStoragePermission = {},
        onFindGlasses = controller::openDeviceBind,
        onOpenSourceRepository = { IosOnboardingPermissions.openUrl(GITHUB_REPOSITORY_URL) },
        onChoiceSelected = flow::selectModel,
        onConfirmBluetoothSkip = flow::confirmBluetoothSkip,
        onDismissBluetoothSkip = flow::dismissBluetoothSkip,
        onBack = { if (screen.index == 0) flow.skipAll() else flow.back() },
        onNext = {
            // Same as Android: the chosen starter downloads while setup continues. The assistant
            // mode is left unset, which is the custom AI provider (local model or Pro relay).
            if (isModelStep) flow.modelToDownload?.let(IosLocalModels::downloadStarter)
            flow.next(bluetoothAllowed = bluetooth == IosPermissionState.ALLOWED)
        },
    )
}
