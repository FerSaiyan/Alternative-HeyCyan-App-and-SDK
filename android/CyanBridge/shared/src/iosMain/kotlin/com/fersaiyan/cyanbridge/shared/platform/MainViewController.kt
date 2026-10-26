package com.fersaiyan.cyanbridge.shared.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.uikit.ComposeUIViewControllerConfiguration
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import com.fersaiyan.cyanbridge.shared.appearance.AppearanceSettingsStore
import com.fersaiyan.cyanbridge.shared.billing.ProSubscriptionAction
import com.fersaiyan.cyanbridge.shared.billing.ProSubscriptionUiState
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelsPlatformFeatures
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.prefs.AppPrefs
import com.fersaiyan.cyanbridge.shared.ui.CyanBridgeApp
import com.fersaiyan.cyanbridge.shared.ui.DeviceBindScreen
import com.fersaiyan.cyanbridge.shared.ui.LocalPlatformBackHandler
import com.fersaiyan.cyanbridge.shared.ui.PlatformBackHandler
import com.fersaiyan.cyanbridge.shared.ui.SystemBackHandler
import com.fersaiyan.cyanbridge.shared.ui.localmodels.LocalModelsConfigureScreen
import com.fersaiyan.cyanbridge.shared.ui.onboarding.WelcomeScreen
import com.fersaiyan.cyanbridge.shared.ui.theme.CyanBridgeMaterialTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val IOS_PRO_SUBSCRIPTION_STATE = ProSubscriptionUiState(
    status = "iOS checkout is unavailable until account sign-in and verified billing are implemented. Pro is not active.",
    selectedPlan = "free_trial",
    webCheckoutAvailable = false,
    isSubscribed = false,
)


/**
 * Initialize CyanBridgeServices with iOS implementations and return the ComposeUIViewController.
 */
/**
 * Keyboard: shared screens pad themselves with imePadding, so UIKit must not also pan the whole
 * view up (that pushed the chat composer to the top and hid the history and back button).
 * Back: UIKit's left-edge swipe drives SystemBackHandler in shared sub-screens.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun ComposeUIViewControllerConfiguration.cyanBridgeDefaults() {
    onFocusBehavior = OnFocusBehavior.DoNothing
    enableBackGesture = true
}

fun MainViewController() = ComposeUIViewController(configure = { cyanBridgeDefaults() }) {
    val controller = remember { IosAppController() }
    if (!CyanBridgeServices.isInitialized()) {
        controller.initializeServices()
    }
    val dashboardState by controller.dashboardState.collectAsState()
    IosCyanBridgeApp(
        controller = controller,
        dashboardState = dashboardState,
    )
}

/** Used only by the simulator screenshot harness to exercise each root route. */
fun MainViewControllerForDestination(destination: String) = ComposeUIViewController(configure = { cyanBridgeDefaults() }) {
    val controller = remember { IosAppController() }
    if (!CyanBridgeServices.isInitialized()) {
        controller.initializeServices()
    }
    val dashboardState by controller.dashboardState.collectAsState()
    // "local-models" or "local-models:<search>" opens Settings ▸ Local for screenshots.
    remember(destination) {
        // "preview:<file in Documents>" opens the QuickLook preview used by chat photos.
        if (destination.startsWith("preview:")) {
            controller.launchAfterStart { IosMediaPlatform.openMedia(PlatformFilePaths.dataDirectory() + "/" + destination.substringAfter(':')) }
        }
        // "photo-cycle": regular photo, AI photo turn, regular photo — checks the glasses still
        // report the camera after an AI turn.
        if (destination == "photo-cycle") controller.launchAfterStart { controller.debugPhotoCycle() }
        // "ai-photo": same as tapping Test description (checks the AI photo resolution).
        if (destination == "ai-photo") {
            controller.launchAfterStart { scopeDelayedTestImageQuestion(controller) }
        }
        // "chat:<thread id>" opens that conversation.
        if (destination.startsWith("chat:")) SharedChatHooks.initialThreadId = destination.substringAfter(':')
        if (destination.startsWith("local-models")) {
            IosLocalModels.open()
            destination.substringAfter(':', "").takeIf { it.isNotBlank() }?.let(IosLocalModels::onCatalogSearchQueryChange)
        }
    }
    IosCyanBridgeApp(
        controller = controller,
        initialDestination = when (destination.substringBefore(':')) {
            "local-models" -> AppDestination.SETTINGS
            "chat" -> AppDestination.CHATS
            "chats" -> AppDestination.CHATS
            "media" -> AppDestination.MEDIA
            "plugins" -> AppDestination.PLUGINS
            "settings" -> AppDestination.SETTINGS
            else -> AppDestination.GLASSES
        },
        dashboardState = dashboardState,
    )
}

@Composable
private fun IosCyanBridgeApp(
    controller: IosAppController,
    dashboardState: GlassesDashboardUiState,
    initialDestination: AppDestination = AppDestination.GLASSES,
) {
    val appearanceStore = remember {
        AppearanceSettingsStore(
            preferences = AppPrefs.Appearance.store(),
            dynamicColorAvailable = false,
        )
    }
    var appearanceSettings by remember { mutableStateOf(appearanceStore.load()) }

    val deviceBindState by controller.deviceBindState.collectAsState()
    var welcomeDone by remember { mutableStateOf(AppPrefs.IosOnboarding.welcomeDone.get()) }

    // Re-keying on the language rebuilds the tree so Compose resources pick up the new locale.
    key(IosAppLanguage.selectedId) {
    CompositionLocalProvider(
        LocalPlatformBackHandler provides IosBackHandler,
        com.fersaiyan.cyanbridge.shared.ui.chat.LocalChatMathRenderer provides IosKatexRenderer,
    ) {
    CyanBridgeMaterialTheme(settings = appearanceSettings) {
        if (!welcomeDone) {
            WelcomeScreen(
                languageOptions = IosAppLanguage.options,
                selectedLanguageId = IosAppLanguage.selectedId,
                languageSelectionComplete = true,
                onLanguageSelected = { option -> IosAppLanguage.select(option.id) },
                onStartSetup = {
                    AppPrefs.IosOnboarding.welcomeDone.set(true)
                    welcomeDone = true
                },
            )
            return@CyanBridgeMaterialTheme
        }
        Box(modifier = Modifier.fillMaxSize()) {
            CyanBridgeApp(
                initialDestination = initialDestination,
                dashboardState = dashboardState,
                onDashboardAction = controller::handle,
                appearanceSettings = appearanceSettings,
                onAppearanceSettingsChange = { nextSettings ->
                    appearanceStore.save(nextSettings)
                    appearanceSettings = appearanceStore.load()
                },
                onAppearanceReset = {
                    appearanceStore.reset()
                    appearanceSettings = appearanceStore.load()
                },
                useSharedDestinations = true,
                proSubscriptionState = IOS_PRO_SUBSCRIPTION_STATE,
                onProSubscriptionAction = ::iosProSubscriptionActionStatus,
                hiddenDestinations = setOf(AppDestination.PLUGINS),
            )
            // Android opens its device bind screen for Scan; iOS shows the same shared screen on top.
            deviceBindState?.let { bind ->
                SystemBackHandler { controller.closeDeviceBind() }
                DeviceBindScreen(
                    devices = bind.devices,
                    isScanning = bind.isScanning,
                    connectingDevice = bind.connectingDevice,
                    selectedClass = bind.selectedClass,
                    onScan = controller::startDeviceScan,
                    onPairMetaGlasses = controller::pairMetaGlasses,
                    onSelectDevice = controller::selectDevice,
                    onSelectedClassChange = controller::selectDeviceClass,
                    onConfirmConnection = controller::confirmConnection,
                    onConfirmManualProtocol = controller::confirmManualProtocol,
                    onDismissConnection = controller::dismissConnection,
                    onBack = { controller.closeDeviceBind() },
                )
            }
            if (IosLocalModels.isOpen) {
                SystemBackHandler { IosLocalModels.handle(com.fersaiyan.cyanbridge.shared.localmodels.LocalModelsAction.Back) }
                LocalModelsConfigureScreen(
                    state = IosLocalModels.uiState,
                    onAction = IosLocalModels::handle,
                    features = LocalModelsPlatformFeatures(studioBridge = false, mtp = false, runtimeTuning = false),
                    onCatalogSearchQueryChange = IosLocalModels::onCatalogSearchQueryChange,
                )
            }
        }
    }
    }
    }
}

/** UIKit's back swipe (Compose Multiplatform BackHandler) for shared sub-screens. */
private object IosBackHandler : PlatformBackHandler {
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    override fun Handle(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)
}

private fun iosProSubscriptionActionStatus(action: ProSubscriptionAction): String = when (action) {
    ProSubscriptionAction.SUBSCRIBE ->
        "iOS billing is not available yet. No payment was started and no Pro entitlement was granted."
    ProSubscriptionAction.DONATE ->
        "iOS donations are not available yet. No payment was started."
}

/** Screenshot harness: waits for QCSDK to attach, then runs Test description. */
private fun scopeDelayedTestImageQuestion(controller: IosAppController) {
    kotlinx.coroutines.MainScope().launch {
        delay(5_000L)
        controller.handle(GlassesDashboardAction.TestImageQuestion)
    }
}
