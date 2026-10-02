package com.fersaiyan.cyanbridge.shared.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.ui.uikit.ComposeUIViewControllerConfiguration
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import com.fersaiyan.cyanbridge.shared.ui.LocalPlatformBackHandler
import com.fersaiyan.cyanbridge.shared.ui.PlatformBackHandler
import com.fersaiyan.cyanbridge.shared.ui.SystemBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.fersaiyan.cyanbridge.shared.ai.AiModel
import com.fersaiyan.cyanbridge.shared.ai.AiModelRegistry
import com.fersaiyan.cyanbridge.shared.ai.ChatAiService
import com.fersaiyan.cyanbridge.shared.ai.ChatMessage
import com.fersaiyan.cyanbridge.shared.ai.ChatResponse
import com.fersaiyan.cyanbridge.shared.ai.ImageAiService
import com.fersaiyan.cyanbridge.shared.ai.TokenUsage
import com.fersaiyan.cyanbridge.shared.ai.VoiceAiService
import com.fersaiyan.cyanbridge.shared.appearance.APPEARANCE_PREFERENCES_NAME
import com.fersaiyan.cyanbridge.shared.appearance.AppearanceSettingsStore
import com.fersaiyan.cyanbridge.shared.billing.ProSubscriptionAction
import com.fersaiyan.cyanbridge.shared.billing.ProSubscriptionUiState
import com.fersaiyan.cyanbridge.shared.ble.IosBleManager
import com.fersaiyan.cyanbridge.shared.ble.IosEyevueSession
import com.fersaiyan.cyanbridge.shared.devices.eyevue.EyevueProtocol
import com.fersaiyan.cyanbridge.shared.ble.BleConnectionState
import com.fersaiyan.cyanbridge.shared.ble.BleNotificationListener
import com.fersaiyan.cyanbridge.shared.ble.VendorGlassesEventListener
import com.fersaiyan.cyanbridge.shared.ble.VendorGlassesMode
import com.fersaiyan.cyanbridge.shared.ble.VendorGlassesRegistry
import com.fersaiyan.cyanbridge.shared.ble.awaitAiSpeakMode
import com.fersaiyan.cyanbridge.shared.ble.awaitAudioSettings
import com.fersaiyan.cyanbridge.shared.ble.awaitBattery
import com.fersaiyan.cyanbridge.shared.ble.awaitDeleteMedia
import com.fersaiyan.cyanbridge.shared.ble.awaitOpenWifi
import com.fersaiyan.cyanbridge.shared.ble.awaitSetAudioSettings
import com.fersaiyan.cyanbridge.shared.ble.awaitSetVideoSettings
import com.fersaiyan.cyanbridge.shared.ble.awaitSetWearingDetection
import com.fersaiyan.cyanbridge.shared.ble.awaitVideoSettings
import com.fersaiyan.cyanbridge.shared.ble.awaitVolume
import com.fersaiyan.cyanbridge.shared.ble.awaitWearingDetection
import com.fersaiyan.cyanbridge.shared.ble.awaitWifiIp
import com.fersaiyan.cyanbridge.shared.ble.awaitGlassesControl
import com.fersaiyan.cyanbridge.shared.ble.awaitMediaCounts
import com.fersaiyan.cyanbridge.shared.ble.awaitModeAccepted
import com.fersaiyan.cyanbridge.shared.ble.awaitSyncTime
import com.fersaiyan.cyanbridge.shared.ble.awaitVersion
import com.fersaiyan.cyanbridge.shared.glasses.AiWakeWordRoute
import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.shared.glasses.GlassesTransferUiState
import com.fersaiyan.cyanbridge.shared.devices.BleDeviceClassifier
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.ScannedDevice
import com.fersaiyan.cyanbridge.shared.media.IosMediaTransfer
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.network.P2pConnectionState
import com.fersaiyan.cyanbridge.shared.network.P2pPeer
import com.fersaiyan.cyanbridge.shared.network.WifiP2pManager
import com.fersaiyan.cyanbridge.shared.persistence.DeviceProfileEntity
import com.fersaiyan.cyanbridge.shared.persistence.IosChatRepository
import com.fersaiyan.cyanbridge.shared.persistence.IosDeviceProfileRepository
import com.fersaiyan.cyanbridge.shared.persistence.IosMediaRecordRepository
import com.fersaiyan.cyanbridge.shared.persistence.IosMemoryVaultRepository
import com.fersaiyan.cyanbridge.shared.persistence.IosNotesRepository
import com.fersaiyan.cyanbridge.shared.ui.CyanBridgeApp
import com.fersaiyan.cyanbridge.shared.ui.sharedDefaultImageQuestion
import com.fersaiyan.cyanbridge.shared.persistence.ChatEntity
import com.fersaiyan.cyanbridge.shared.persistence.ChatMessageEntity
import com.fersaiyan.cyanbridge.shared.ui.DeviceBindScreen
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelsPlatformFeatures
import com.fersaiyan.cyanbridge.shared.ui.localmodels.LocalModelsConfigureScreen
import com.fersaiyan.cyanbridge.shared.ui.onboarding.WelcomeScreen
import com.fersaiyan.cyanbridge.shared.ui.theme.CyanBridgeMaterialTheme
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import platform.NetworkExtension.NEHotspotConfiguration
import platform.NetworkExtension.NEHotspotConfigurationManager
import platform.NetworkExtension.NEHotspotNetwork
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSString
import platform.Foundation.currentLocale
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding
import platform.Foundation.NSData
import platform.Foundation.base64EncodedStringWithOptions

private const val DEFAULT_RELAY_URL = "https://cyanbridge.vercel.app"
private const val IOS_TRANSFER_IP_TIMEOUT_MS = 15_000L
private const val IOS_HOST_CREDENTIAL_TIMEOUT_MS = 10_000L
private const val IOS_BLE_CONNECT_TIMEOUT_MS = 20_000L
private const val IOS_MANUAL_JOIN_TIMEOUT_MS = 180_000L
private const val HEYCYAN_DEFAULT_HOTSPOT_PASSWORD = "123456789"
private const val IOS_GLASSES_PREFERENCES = "cyanbridge_ios_glasses"
private const val PREF_AI_WAKE_ROUTE = "ai_wake_word_route"
private const val PREF_THUMBNAIL_QUALITY = "image_thumbnail_quality"
private const val PREF_ASSISTANT_MODE = "assistant_mode"
private const val GLASSES_CHAT_ID = "glasses-assistant"
private const val VOICE_QUESTION_INITIAL_LISTENING_MS = 6_000L
/** Android: IMAGE_QUESTION_INITIAL_LISTENING_TIMEOUT_MS and the 500 ms shutter settle delay. */
private const val IMAGE_QUESTION_INITIAL_LISTENING_MS = 3_300L
private const val IMAGE_QUESTION_SHUTTER_SETTLE_MS = 500L
private const val MAX_IMAGE_FOLLOW_UPS = 3
/** Android ImageThumbnailQuality "Detailed": the largest AI photo the glasses send. */
private const val MANUAL_PHOTO_QUALITY = 5
private const val RAW_PHOTO_WAIT_MS = 12_000L
private const val ASSISTANT_TAG = "IosGlassesAssistant"
// Same option lists as Android's MainActivity for HeyCyan recording limits.
private val VIDEO_DURATION_OPTIONS_SECONDS = listOf(15, 30, 60, 180, 540, 720)
private val AUDIO_DURATION_OPTIONS_SECONDS = listOf(1_800, 3_600, 7_200)
// Android MainActivity meetingTimerOptions: none, 15 min, 1 h, 3 h.
private val MEETING_TIMER_SECONDS = listOf<Long?>(null, 15L * 60L, 60L * 60L, 3L * 60L * 60L)
// Android ImageThumbnailQuality (sdkValue to label).
private val THUMBNAIL_QUALITY_LABELS = mapOf(
    0 to "Instant", 1 to "Quick", 2 to "Smooth", 3 to "Fine", 4 to "Clearer", 5 to "Detailed",
)
private val IOS_TRANSFER_MODE_COMMAND = byteArrayOf(0x02, 0x01, 0x04)
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
            preferences = createPlatformPreferences(APPEARANCE_PREFERENCES_NAME),
            dynamicColorAvailable = false,
        )
    }
    var appearanceSettings by remember { mutableStateOf(appearanceStore.load()) }

    val deviceBindState by controller.deviceBindState.collectAsState()
    val onboardingPreferences = remember { createPlatformPreferences("cyanbridge_onboarding") }
    var welcomeDone by remember { mutableStateOf(onboardingPreferences.getBoolean("welcome_done", false)) }

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
                    onboardingPreferences.putBoolean("welcome_done", true)
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
            // Android opens DeviceBindActivity for Scan; iOS shows the same shared screen on top.
            deviceBindState?.let { bind ->
                SystemBackHandler { controller.closeDeviceBind() }
                DeviceBindScreen(
                    devices = bind.devices,
                    isScanning = bind.isScanning,
                    connectingDevice = bind.connectingDevice,
                    selectedClass = bind.selectedClass,
                    onScan = controller::startDeviceScan,
                    onPairMetaGlasses = { controller.closeDeviceBind("Meta glasses pairing is not available on iOS yet") },
                    onPairMentraGlasses = { controller.closeDeviceBind("Mentra Live pairing is not available on iOS yet") },
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

// ── iOS local Wi-Fi manager using NEHotspotConfiguration ──

/**
 * iOS local Wi-Fi manager using NEHotspotConfiguration.
 * iOS does not support Android-style Wi-Fi Direct peer discovery. This adapter
 * joins a glasses-owned hotspot and relies on the BLE-reported device IP.
 */
private class IosWifiP2pManager : WifiP2pManager {
    private val _isAvailable = MutableStateFlow(true)
    override val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()
    override val supportsTrueWifiDirect: Boolean = false

    private val _connectionState = MutableStateFlow(P2pConnectionState.IDLE)
    override val connectionState: Flow<P2pConnectionState> = _connectionState.asStateFlow()

    private val _glassesIpAddress = MutableStateFlow<String?>(null)
    override val glassesIpAddress: StateFlow<String?> = _glassesIpAddress.asStateFlow()
    private var connectedSsid: String? = null

    override fun discoverPeers(): Flow<P2pPeer> = flow {
        PlatformLogger.i(TAG, "Wi-Fi discovery on iOS uses NEHotspotConfiguration")
        // iOS doesn't support Wi-Fi Direct peer discovery like Android.
        // The glasses expose a Wi-Fi hotspot that the phone joins via NEHotspotConfiguration.
        // Discovery is handled by BLE scanning instead.
    }

    override fun stopDiscovery() {
        PlatformLogger.i(TAG, "Stopping Wi-Fi discovery")
    }

    override suspend fun connect(peerAddress: String) {
        val separator = peerAddress.indexOf('|')
        val ssid = if (separator >= 0) peerAddress.substring(0, separator) else peerAddress
        val passphrase = if (separator >= 0) peerAddress.substring(separator + 1) else ""
        connectToHotspot(ssid, passphrase)
    }

    suspend fun connectToHotspot(ssidValue: String, passphrase: String) {
        val ssid = ssidValue.trim()
        require(ssid.isNotEmpty()) { "An iOS hotspot SSID is required" }
        PlatformLogger.i(TAG, "Preparing to join Wi-Fi hotspot: $ssid")
        _connectionState.value = P2pConnectionState.CONNECTING
        try {
            if (currentNetworkSsid() == ssid) {
                connectedSsid = ssid
                _connectionState.value = P2pConnectionState.CONNECTED
                PlatformLogger.i(TAG, "Already connected to Wi-Fi hotspot: $ssid")
                return
            }

            val configuration = if (passphrase.isBlank()) {
                NEHotspotConfiguration(sSID = ssid)
            } else {
                NEHotspotConfiguration(sSID = ssid, passphrase = passphrase, isWEP = false)
            }
            configuration.joinOnce = true
            val applyError = applyConfiguration(configuration)
            if (applyError != null) {
                PlatformLogger.w(TAG, "iOS hotspot configuration was not accepted: $applyError")
            }

            check(waitForCurrentNetwork(ssid)) {
                if (applyError == null) {
                    "iOS accepted the hotspot request, but is not connected to $ssid. " +
                        "Open Settings > Wi-Fi and join the glasses hotspot, then retry."
                } else {
                    "iOS could not join $ssid ($applyError). " +
                        "Open Settings > Wi-Fi and join the glasses hotspot, then retry."
                }
            }
            connectedSsid = ssid
            _connectionState.value = P2pConnectionState.CONNECTED
            PlatformLogger.i(TAG, "Wi-Fi hotspot connected: $ssid")
        } catch (error: Exception) {
            _connectionState.value = P2pConnectionState.ERROR
            PlatformLogger.e(TAG, "Wi-Fi hotspot connection failed", error)
            throw error
        }
    }

    override suspend fun disconnect() {
        PlatformLogger.i(TAG, "Disconnecting from Wi-Fi hotspot")
        _connectionState.value = P2pConnectionState.DISCONNECTING
        connectedSsid?.let { ssid ->
            NEHotspotConfigurationManager.sharedManager.removeConfigurationForSSID(ssid)
        }
        connectedSsid = null
        _connectionState.value = P2pConnectionState.IDLE
    }

    override fun isConnected(): Boolean = _connectionState.value == P2pConnectionState.CONNECTED

    override fun setGlassesIpAddress(ip: String) {
        PlatformLogger.i(TAG, "Glasses IP address set: $ip")
        _glassesIpAddress.value = ip
    }

    override suspend fun bindToP2pNetwork(): Boolean {
        // iOS has no process-level equivalent of Android's bindProcessToNetwork().
        // If the SSID is known, verify it; otherwise the media.config request is
        // the end-to-end readiness probe for an already-connected network.
        val expectedSsid = connectedSsid ?: return true
        if (currentNetworkSsid() != expectedSsid) {
            _connectionState.value = P2pConnectionState.IDLE
            return false
        }
        return true
    }

    override fun cancelConnection() {
        _connectionState.value = P2pConnectionState.IDLE
    }

    suspend fun hasCurrentWifiConnection(): Boolean = currentNetworkSsid() != null

    /**
     * Asks iOS to join the glasses hotspot. Returns null when iOS accepted the
     * request, or the error text when it did not (for example, builds signed
     * without the Hotspot entitlement). Callers verify the link over HTTP, which
     * works without the Wi-Fi information entitlement.
     */
    suspend fun requestHotspotJoin(ssidValue: String, passphrase: String): String? {
        val ssid = ssidValue.trim()
        if (ssid.isEmpty()) return "Missing hotspot SSID"
        _connectionState.value = P2pConnectionState.CONNECTING
        val configuration = if (passphrase.isBlank()) {
            NEHotspotConfiguration(sSID = ssid)
        } else {
            NEHotspotConfiguration(sSID = ssid, passphrase = passphrase, isWEP = false)
        }
        configuration.joinOnce = true
        return applyConfiguration(configuration).also { error ->
            connectedSsid = if (error == null) ssid else null
        }
    }

    fun markConnected() {
        _connectionState.value = P2pConnectionState.CONNECTED
    }

    private suspend fun applyConfiguration(configuration: NEHotspotConfiguration): String? =
        suspendCancellableCoroutine { continuation ->
            NEHotspotConfigurationManager.sharedManager.applyConfiguration(configuration) { error ->
                if (continuation.isActive) {
                    continuation.resume(error?.localizedDescription)
                }
            }
        }

    private suspend fun waitForCurrentNetwork(expectedSsid: String): Boolean {
        repeat(20) { attempt ->
            if (currentNetworkSsid() == expectedSsid) return true
            if (attempt < 19) delay(1_000L)
        }
        return false
    }

    private suspend fun currentNetworkSsid(): String? =
        suspendCancellableCoroutine { continuation ->
            NEHotspotNetwork.fetchCurrentWithCompletionHandler { network ->
                if (continuation.isActive) {
                    continuation.resume(network?.SSID)
                }
            }
        }

    companion object {
        private const val TAG = "IosWifiP2p"
    }
}

/** State for the shared DeviceBindScreen, shown over the dashboard while pairing. */
private data class IosDeviceBindUiState(
    val devices: List<ScannedDevice> = emptyList(),
    val isScanning: Boolean = false,
    val connectingDevice: ScannedDevice? = null,
    val selectedClass: DeviceClass = DeviceClass.HEY_CYAN,
)

/** Named devices first, strongest signal first, like the Android bind list. */
private fun List<ScannedDevice>.upsert(
    identifier: String,
    name: String?,
    rssi: Int,
    detectedClass: DeviceClass,
): List<ScannedDevice> {
    val sanitizedName = name?.trim()?.takeIf { it.isNotEmpty() }
    val existing = firstOrNull { it.macAddress == identifier }
    val updated = existing?.copy(
        advertisedName = existing.advertisedName ?: sanitizedName,
        rssi = rssi,
        detectedClass = if (detectedClass != DeviceClass.UNKNOWN) detectedClass else existing.detectedClass,
    ) ?: ScannedDevice(
        macAddress = identifier,
        advertisedName = sanitizedName,
        rssi = rssi,
        detectedClass = detectedClass,
        selectedClass = null,
        userOverridden = false,
    )
    return (filterNot { it.macAddress == identifier } + updated)
        .sortedWith(compareBy<ScannedDevice> { it.advertisedName == null }.thenByDescending { it.rssi })
}

/**
 * Small iOS host controller for the shared dashboard. It owns platform jobs so
 * the composable remains a pure renderer, matching the Android callback shape.
 */
private class IosAppController {
    val bleManager = IosBleManager()
    val wifiP2pManager = IosWifiP2pManager()
    val chatRepository = IosChatRepository()
    val notesRepository = IosNotesRepository()
    val deviceProfileRepository = IosDeviceProfileRepository()
    val memoryVaultRepository = IosMemoryVaultRepository()
    val mediaRecordRepository = IosMediaRecordRepository()
    private val mediaTransfer = IosMediaTransfer(mediaRecordRepository)
    // The "Local" custom provider (Settings ▸ Custom AI provider) overrides the relay when enabled.
    private val chatAiService = IosRoutedChatAiService(IosRelayChatAiService())
    private val voiceAiService = IosRoutedVoiceAiService(IosRelayVoiceAiService())
    private val imageAiService = IosRoutedImageAiService(IosRelayImageAiService())
    private val meetingRecorder = IosMeetingRecorder(voiceAiService, chatAiService, notesRepository)
    private val eyevue = IosEyevueSession(
        bleManager = bleManager,
        onBattery = { battery ->
            scope.launch { updateState { it.copy(batteryPercent = battery.percent, showBattery = true) } }
        },
        onWifiSsid = { ssid ->
            scope.launch { updateState { it.copy(transfer = it.transfer.copy(detail = "Eyevue Wi-Fi: $ssid")) } }
        },
        onPhoto = { image -> scope.launch { answerImageQuestion(image) } },
    )
    private val isEyevueConnected get() = isBleConnected && selectedDeviceClass == DeviceClass.EYEVUE
    private val pluginsRuntime = IosPluginsRuntime(
        chatAiService = chatAiService,
        imageAiService = imageAiService,
        notesRepository = notesRepository,
        relayBaseUrl = DEFAULT_RELAY_URL,
        onShortcutChanged = { shortcut -> updateState { it.copy(nativePluginShortcut = shortcut) } },
        requestGlassesPhoto = { vendor?.awaitModeAccepted(VendorGlassesMode.AI_PHOTO) ?: false },
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Screenshot harness: runs [block] once the UI had time to appear. */
    fun launchAfterStart(block: () -> Unit) {
        scope.launch {
            delay(1_500L)
            block()
        }
    }
    private val _dashboardState = MutableStateFlow(
        GlassesDashboardUiState(
            connectionLabel = "Bluetooth unavailable",
            agentStatus = "iOS shared host",
            showExternalAutomationSetup = false,
        ),
    )
    val dashboardState: StateFlow<GlassesDashboardUiState> = _dashboardState.asStateFlow()

    private val _deviceBindState = MutableStateFlow<IosDeviceBindUiState?>(null)
    val deviceBindState: StateFlow<IosDeviceBindUiState?> = _deviceBindState.asStateFlow()

    private var scanJob: Job? = null
    private var syncJob: Job? = null
    private var lastDiscoveredIdentifier: String? = null
    private var selectedDeviceClass: DeviceClass = DeviceClass.UNKNOWN
    private val vendorBridge = VendorGlassesRegistry.bridge
    private val glassesPreferences = createPlatformPreferences(IOS_GLASSES_PREFERENCES)
    private var videoAngle = 0
    private var audioAngle = 0
    private val vendor get() = vendorBridge?.takeIf { bleManager.isVendorAttached.value }
    private var isBleConnected = false

    init {
        bleManager.addNotificationListener(object : BleNotificationListener {
            override fun onNotification(characteristicId: String, data: ByteArray) {
                // With QCSDK attached the sync flow resolves the IP itself; raw vendor
                // frames are offset differently on iOS and decode to bogus addresses.
                if (vendor != null) return
                extractGlassesIp(data)?.let(wifiP2pManager::setGlassesIpAddress)
            }
        })
        IosMediaPlatform.installSharedMediaHooks()
        IosChatPlatform.installSharedChatHooks()
        SharedRecordingsHooks.provider = meetingRecorder
        SharedPluginsHooks.platform = pluginsRuntime
        // Firmware flashing needs account auth and hardware validation on iOS (plan tasks 5.7-5.9).
        updateState {
            it.copy(
                ota = com.fersaiyan.cyanbridge.shared.glasses.OtaSectionUiState(
                    stateLabel = "Not available on iOS yet",
                    detail = "Firmware updates still require the Android app.",
                    canStart = false,
                ),
            )
        }
        SharedSettingsHooks.platform = IosSettingsPlatform(
            chatRepository = chatRepository,
            notesRepository = notesRepository,
            mediaRecordRepository = mediaRecordRepository,
            meetingRecorder = meetingRecorder,
            relayBaseUrl = DEFAULT_RELAY_URL,
        )
        scope.launch {
            meetingRecorder.meetingState.collect { meeting ->
                updateState {
                    it.copy(
                        meeting = it.meeting.copy(
                            isRecording = meeting.isRecording,
                            sourceLabel = meeting.sourceLabel ?: "(not recording)",
                            bannerLabel = if (meeting.isRecording) "Recording meeting · ${meeting.sourceLabel}" else "",
                        ),
                    )
                }
            }
        }
        bleManager.vendorBridge = vendorBridge
        val thumbnailQuality = glassesPreferences.getInt(PREF_THUMBNAIL_QUALITY, 4)
        updateState {
            it.copy(
                aiWakeWordRoute = AiWakeWordRoute.fromRaw(glassesPreferences.getString(PREF_AI_WAKE_ROUTE, "")),
                assistantMode = GlassesAssistantMode.entries.firstOrNull {
                    it.name == glassesPreferences.getString(PREF_ASSISTANT_MODE, "")
                } ?: GlassesAssistantMode.PHONE_ASSISTANT,
                imageThumbnailQualitySdkValue = thumbnailQuality,
                imageThumbnailQualityLabel = THUMBNAIL_QUALITY_LABELS[thumbnailQuality] ?: "Clearer",
            )
        }
        vendorBridge?.setEventListener(object : VendorGlassesEventListener {
            override fun onBatteryChanged(level: Int, charging: Boolean) {
                scope.launch { updateState { it.copy(batteryPercent = level, showBattery = true) } }
            }

            override fun onMediaCountsChanged(photos: Int, videos: Int, audio: Int) {
                PlatformLogger.i(ASSISTANT_TAG, "Glasses media counts: $photos photos, $videos videos, $audio audio")
                val previousPhotos = lastPhotoCount
                lastPhotoCount = photos
                scope.launch { showMediaCounts(photos, videos, audio) }
                // iOS QCSDK only reports the camera button as a new photo in the count (Android
                // gets a photo-ready notify); answer it like the AI-photo button.
                if (previousPhotos != null && photos > previousPhotos && syncJob?.isActive != true) {
                    scope.launch { askAboutCameraButtonPhoto() }
                }
            }

            override fun onAiImage(data: NSData) {
                // Fired by the glasses' AI photo button and by TestImageQuestion.
                val bytes = data.toKotlinBytes()
                aiPhotosReceived++
                val size = IosMediaPlatform.imageSizeLabel(data)
                PlatformLogger.i(ASSISTANT_TAG, "AI photo received from the glasses: ${bytes.size} bytes, $size")
                if (pluginsRuntime.isWalkingAidActive) {
                    pluginsRuntime.onGlassesImage(bytes)
                } else {
                    // One image question at a time: a new photo replaces the previous turn
                    // (its speech, listening and generation) instead of piling up.
                    imageQuestionJob?.cancel()
                    IosChatPlatform.stopSpeaking()
                    imageQuestionJob = scope.launch { answerImageQuestion(bytes) }
                }
            }
        })
        scope.launch {
            val selectedProfile = deviceProfileRepository.getAll()
                .maxByOrNull { it.lastConnectedAt }
            applySelectedClass(
                selectedProfile?.selectedClass
                    ?.let { value -> DeviceClass.entries.firstOrNull { it.name == value } }
                    ?: DeviceClass.UNKNOWN,
            )
            // Android's AutoPair reconnects the last glasses on launch; do the same here.
            val identifier = selectedProfile?.macAddress
            if (identifier != null && selectedDeviceClass != DeviceClass.GENERIC_AUDIO) {
                val poweredOn = withTimeoutOrNull(10_000L) { bleManager.isBluetoothEnabled.first { it } } ?: false
                if (poweredOn && !isBleConnected) {
                    lastDiscoveredIdentifier = identifier
                    connectTo(identifier)
                }
            }
        }
        scope.launch {
            bleManager.isVendorAttached.collect { attached ->
                if (attached) onVendorConnected() else resetVendorState()
            }
        }
        scope.launch {
            bleManager.connectionState.collect { connectionState ->
                val wasBleConnected = isBleConnected
                isBleConnected = connectionState == BleConnectionState.CONNECTED
                if (!wasBleConnected && isBleConnected && selectedDeviceClass == DeviceClass.EYEVUE) {
                    scope.launch { eyevue.onConnected() }
                }
                if (wasBleConnected && connectionState == BleConnectionState.DISCONNECTED) {
                    IosTransferModeConfiguration.clearHotspot()
                }
                updateState { state ->
                    state.copy(
                        connectionLabel = when (connectionState) {
                            BleConnectionState.DISCONNECTED -> "Disconnected"
                            BleConnectionState.CONNECTING -> "Connecting"
                            BleConnectionState.CONNECTED -> "Connected"
                            BleConnectionState.DISCONNECTING -> "Disconnecting"
                        },
                        showHeyCyanControls = connectionState == BleConnectionState.CONNECTED &&
                            selectedDeviceClass == DeviceClass.HEY_CYAN,
                        showCaptureSettings = connectionState == BleConnectionState.CONNECTED &&
                            selectedDeviceClass == DeviceClass.HEY_CYAN,
                        showAiWakeWordRouting = connectionState == BleConnectionState.CONNECTED &&
                            selectedDeviceClass == DeviceClass.HEY_CYAN,
                        showAdvancedControls = connectionState == BleConnectionState.CONNECTED &&
                            selectedDeviceClass == DeviceClass.HEY_CYAN,
                        showAdvancedLocalAgent = connectionState == BleConnectionState.CONNECTED &&
                            selectedDeviceClass == DeviceClass.HEY_CYAN,
                        showAdvancedDeviceInfo = connectionState == BleConnectionState.CONNECTED &&
                            selectedDeviceClass == DeviceClass.HEY_CYAN,
                        showAdvancedDeviceVolume = connectionState == BleConnectionState.CONNECTED &&
                            selectedDeviceClass == DeviceClass.HEY_CYAN,
                        showAdvancedImageQuality = connectionState == BleConnectionState.CONNECTED &&
                            selectedDeviceClass == DeviceClass.HEY_CYAN,
                        showAdvancedDeveloperTools = connectionState == BleConnectionState.CONNECTED &&
                            selectedDeviceClass == DeviceClass.HEY_CYAN,
                        showAdvancedOta = connectionState == BleConnectionState.CONNECTED &&
                            selectedDeviceClass == DeviceClass.HEY_CYAN,
                        showMetaRaybanControls = connectionState == BleConnectionState.CONNECTED &&
                            selectedDeviceClass == DeviceClass.META_RAYBAN,
                    )
                }
                updateConnectionCapabilities()
            }
        }
    }

    fun initializeServices() {
        if (CyanBridgeServices.isInitialized()) return
        CyanBridgeServices.initialize(
            bleManager = bleManager,
            wifiP2pManager = wifiP2pManager,
            chatRepository = chatRepository,
            notesRepository = notesRepository,
            deviceProfileRepository = deviceProfileRepository,
            memoryVaultRepository = memoryVaultRepository,
            mediaRecordRepository = mediaRecordRepository,
            chatAiService = chatAiService,
            voiceAiService = voiceAiService,
            imageAiService = imageAiService,
            aiModelRegistry = IosRelayAiModelRegistry(),
        )
    }

    fun handle(action: GlassesDashboardAction) {
        if (isEyevueConnected && handleEyevue(action)) return
        when (action) {
            GlassesDashboardAction.Scan -> openDeviceBind()
            GlassesDashboardAction.Reconnect -> reconnect()
            GlassesDashboardAction.Disconnect -> scope.launch { bleManager.disconnect() }
            GlassesDashboardAction.RequestBattery -> requestBattery()
            GlassesDashboardAction.RequestVersion -> requestVersion()
            GlassesDashboardAction.StartSync -> if (vendor != null) startVendorSync() else startSync()
            GlassesDashboardAction.StopSync -> stopSync()
            GlassesDashboardAction.CapturePhoto -> if (vendor != null) {
                vendorMode(VendorGlassesMode.PHOTO, "Photo")
            } else {
                sendGlassesCommand("camera", byteArrayOf(0x02, 0x01, 0x01))
            }
            GlassesDashboardAction.ToggleVideo -> toggleVideo()
            GlassesDashboardAction.StartAudioRecording -> if (vendor != null) {
                toggleAudio()
            } else {
                sendGlassesCommand("audio recording", byteArrayOf(0x02, 0x01, 0x08))
            }
            GlassesDashboardAction.RequestMediaCount -> if (vendor != null) {
                requestMediaCounts()
            } else {
                sendGlassesCommand("media count", byteArrayOf(0x02, 0x04))
            }
            GlassesDashboardAction.SyncTime -> syncTime()
            GlassesDashboardAction.RequestVolume -> requestVolume()
            is GlassesDashboardAction.RunNativePluginShortcut -> pluginsRuntime.runShortcut(action.action)
            is GlassesDashboardAction.RequestOtaFirmware,
            GlassesDashboardAction.CancelOta,
            GlassesDashboardAction.DumpOtaInfo,
            GlassesDashboardAction.TestPullOta,
            -> updateState { it.copy(agentLastError = "Firmware updates still require the Android app") }
            is GlassesDashboardAction.SelectMeetingTimer -> updateState {
                it.copy(meeting = it.meeting.copy(timerIndex = action.index.coerceIn(0, MEETING_TIMER_SECONDS.lastIndex)))
            }
            GlassesDashboardAction.StartMeetingCapture -> scope.launch {
                val timer = MEETING_TIMER_SECONDS[_dashboardState.value.meeting.timerIndex]
                meetingRecorder.start(timer)?.let { error -> updateState { it.copy(agentLastError = error) } }
            }
            GlassesDashboardAction.StopMeetingCapture -> meetingRecorder.stopMeetingCapture()
            GlassesDashboardAction.TestImageQuestion -> scope.launch {
                val bridge = vendor ?: return@launch reportVendorRequired("Image question")
                imageQuestionJob?.cancel()
                pendingImageQuestion?.cancel()
                updateState { it.copy(agentLastError = "Waiting for the glasses photo… ask about it now") }
                // Android: startParallelAudioQuestionIfEligible — listen while the photo transfers.
                val question = scope.async {
                    delay(IMAGE_QUESTION_SHUTTER_SETTLE_MS)
                    IosVoiceQuestion.ask(IMAGE_QUESTION_INITIAL_LISTENING_MS)
                }
                pendingImageQuestion = question
                if (!requestAiPhoto(bridge)) {
                    question.cancel()
                    if (pendingImageQuestion === question) pendingImageQuestion = null
                    updateState { it.copy(agentLastError = "Image question was rejected by the glasses (they may be busy)") }
                }
            }
            GlassesDashboardAction.TestVoiceQuestion -> scope.launch { answerVoiceQuestion() }
            is GlassesDashboardAction.SelectAssistantMode -> {
                glassesPreferences.putString(PREF_ASSISTANT_MODE, action.mode.name)
                updateState { it.copy(assistantMode = action.mode) }
            }
            is GlassesDashboardAction.SetWearingDetection -> setWearingDetection(action.enabled)
            GlassesDashboardAction.RefreshRecordingSettings -> refreshRecordingSettings(showErrors = true)
            is GlassesDashboardAction.SetVideoRecordingDuration -> setRecordingDuration(isAudio = false, seconds = action.seconds)
            is GlassesDashboardAction.SetAudioRecordingDuration -> setRecordingDuration(isAudio = true, seconds = action.seconds)
            is GlassesDashboardAction.SetAiWakeWordRoute -> {
                glassesPreferences.putString(PREF_AI_WAKE_ROUTE, action.route.name)
                updateState { it.copy(aiWakeWordRoute = action.route) }
            }
            is GlassesDashboardAction.SelectImageThumbnailQuality -> {
                val value = action.sdkValue.takeIf { it in THUMBNAIL_QUALITY_LABELS } ?: 4
                glassesPreferences.putInt(PREF_THUMBNAIL_QUALITY, value)
                updateState {
                    it.copy(
                        imageThumbnailQualitySdkValue = value,
                        imageThumbnailQualityLabel = THUMBNAIL_QUALITY_LABELS.getValue(value),
                    )
                }
            }
            GlassesDashboardAction.ToggleAdvanced -> updateState { it.copy(advancedExpanded = !it.advancedExpanded) }
            is GlassesDashboardAction.Navigate -> Unit
            else -> updateState { it.copy(agentLastError = "This control is not implemented in the iOS host yet") }
        }
    }

    /** Eyevue actions over BLE GATT (Android: EyevueManager); false when not an Eyevue action. */
    private fun handleEyevue(action: GlassesDashboardAction): Boolean {
        val job: (suspend () -> Unit)? = when (action) {
            GlassesDashboardAction.CapturePhoto -> ({ eyevue.takePhoto(highQuality = true) })
            GlassesDashboardAction.TestImageQuestion -> ({
                updateState { it.copy(agentLastError = "Waiting for the glasses photo…") }
                eyevue.takePhoto(highQuality = _dashboardState.value.imageThumbnailQualitySdkValue >= 5)
            })
            GlassesDashboardAction.ToggleVideo -> ({
                val start = !_dashboardState.value.isVideoRecording
                if (eyevue.setVideoRecording(start)) updateState { it.copy(isVideoRecording = start) }
            })
            GlassesDashboardAction.StartAudioRecording -> ({
                val start = !_dashboardState.value.isAudioRecording
                if (eyevue.setAudioRecording(start)) updateState { it.copy(isAudioRecording = start) }
            })
            GlassesDashboardAction.RequestBattery -> ({ eyevue.requestBattery() })
            GlassesDashboardAction.SyncTime -> ({
                val synced = eyevue.syncTime()
                updateState { it.copy(agentLastError = if (synced) "Glasses clock synced" else "Clock sync failed") }
            })
            is GlassesDashboardAction.SetWearingDetection -> ({
                if (eyevue.setWearingDetection(action.enabled)) updateState { it.copy(wearingDetectionEnabled = action.enabled) }
            })
            is GlassesDashboardAction.SetVideoRecordingDuration -> ({
                if (eyevue.setRecordingDuration(action.seconds)) {
                    updateState { it.copy(videoRecordingDurationSeconds = action.seconds) }
                }
            })
            is GlassesDashboardAction.SetAudioRecordingDuration -> ({
                if (eyevue.setRecordingDuration(action.seconds)) {
                    updateState { it.copy(audioRecordingDurationSeconds = action.seconds) }
                }
            })
            else -> null
        }
        job ?: return false
        scope.launch { job() }
        return true
    }

    private fun openDeviceBind() {
        _deviceBindState.value = IosDeviceBindUiState()
        updateState { it.copy(agentLastError = "") }
        startDeviceScan()
    }

    fun startDeviceScan() {
        scanJob?.cancel()
        updateBind { it.copy(devices = emptyList(), isScanning = true) }
        scanJob = scope.launch {
            try {
                bleManager.startScan(timeoutMs = 15_000L).collect { found ->
                    val detected = BleDeviceClassifier.guessDeviceClass(
                        advertisedName = found.name,
                        serviceUuids = advertisedServiceUuids(found.advertisementData),
                        heyCyanServiceUuids = vendorBridge?.serviceUuids.orEmpty(),
                    )
                    updateBind { bind ->
                        bind.copy(devices = bind.devices.upsert(found.identifier, found.name, found.rssi, detected))
                    }
                }
            } finally {
                updateBind { it.copy(isScanning = false) }
            }
        }
    }

    fun selectDevice(device: ScannedDevice) {
        val pairingChoice = when (device.effectiveSelectedClass()) {
            DeviceClass.META_RAYBAN -> DeviceClass.META_RAYBAN
            DeviceClass.MEIZU_MYVU -> DeviceClass.MEIZU_MYVU
            DeviceClass.GENERIC_AUDIO -> DeviceClass.GENERIC_AUDIO
            else -> DeviceClass.HEY_CYAN
        }
        updateBind { it.copy(connectingDevice = device, selectedClass = pairingChoice) }
    }

    private fun advertisedServiceUuids(advertisementData: Map<String, Any>): List<String> =
        (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? List<*>)
            .orEmpty()
            .mapNotNull { (it as? CBUUID)?.UUIDString }

    fun selectDeviceClass(deviceClass: DeviceClass) {
        updateBind { it.copy(selectedClass = deviceClass) }
    }

    fun dismissConnection() {
        updateBind { it.copy(connectingDevice = null) }
    }

    fun closeDeviceBind(message: String = "") {
        scanJob?.cancel()
        bleManager.stopScan()
        _deviceBindState.value = null
        if (message.isNotEmpty()) updateState { it.copy(agentLastError = message) }
    }

    /** Mirrors DeviceBindActivity.confirmConnection for the classes the iOS host can drive. */
    fun confirmConnection() {
        val bind = _deviceBindState.value ?: return
        val device = bind.connectingDevice ?: return
        when (bind.selectedClass) {
            DeviceClass.META_RAYBAN -> closeDeviceBind("Meta glasses require the native MWDAT adapter on iOS")
            DeviceClass.GENERIC_AUDIO -> {
                saveProfileAndConnect(device, DeviceClass.GENERIC_AUDIO, connect = false)
            }
            DeviceClass.MEIZU_MYVU -> saveProfileAndConnect(device, DeviceClass.MEIZU_MYVU)
            // HEY_CYAN is the sentinel for the manual consumer picker handled by the screen.
            else -> confirmManualProtocol(DeviceClass.HEY_CYAN)
        }
    }

    fun confirmManualProtocol(deviceClass: DeviceClass) {
        val device = _deviceBindState.value?.connectingDevice ?: return
        val normalized = when (deviceClass) {
            DeviceClass.HEY_CYAN,
            DeviceClass.EYEVUE,
            DeviceClass.TUNEBUDS,
            DeviceClass.MOYOUNG_W620,
            -> deviceClass
            else -> DeviceClass.HEY_CYAN
        }
        saveProfileAndConnect(device, normalized)
    }

    private fun saveProfileAndConnect(device: ScannedDevice, deviceClass: DeviceClass, connect: Boolean = true) {
        closeDeviceBind()
        applySelectedClass(deviceClass)
        lastDiscoveredIdentifier = device.macAddress
        scope.launch {
            deviceProfileRepository.upsert(
                DeviceProfileEntity(
                    macAddress = device.macAddress,
                    advertisedName = device.advertisedName,
                    detectedClass = deviceClass.name,
                    selectedClass = deviceClass.name,
                    userOverridden = true,
                    lastConnectedAt = platformCurrentTimeMillis(),
                ),
            )
            if (connect) connectTo(device.macAddress)
        }
    }

    private suspend fun connectTo(identifier: String) {
        runCatching { withTimeout(IOS_BLE_CONNECT_TIMEOUT_MS) { bleManager.connect(identifier) } }
            .onFailure { error ->
                updateState { it.copy(agentLastError = error.message ?: "Connection failed") }
            }
    }

    private fun reconnect() {
        scope.launch {
            val identifier = lastDiscoveredIdentifier
                ?: deviceProfileRepository.getAll().maxByOrNull { it.lastConnectedAt }?.macAddress
            if (identifier == null) {
                openDeviceBind()
            } else {
                connectTo(identifier)
            }
        }
    }

    private fun updateBind(transform: (IosDeviceBindUiState) -> IosDeviceBindUiState) {
        _deviceBindState.value = _deviceBindState.value?.let(transform)
    }

    private fun requestBattery() {
        scope.launch {
            val battery = vendor?.let { bridge -> bridge.awaitBattery()?.first }
                ?: runCatching { bleManager.requestBatteryLevel() }.getOrNull()
            updateState { it.copy(batteryPercent = battery, showBattery = battery != null) }
        }
    }

    private fun requestVersion() {
        scope.launch {
            val bridge = vendor
            val label = if (bridge != null) {
                bridge.awaitVersion()?.let(::formatVendorVersion)
            } else {
                runCatching { bleManager.requestFirmwareVersion() }.getOrNull()?.let { "Firmware: $it" }
            }
            updateState {
                it.copy(
                    deviceInfoLabel = label ?: it.deviceInfoLabel,
                    agentLastError = label ?: "Firmware version unavailable",
                )
            }
        }
    }

    // ── HeyCyan vendor SDK (QCSDK) session ──

    private suspend fun onVendorConnected() {
        updateState { it.copy(agentLastError = "") }
        vendor?.awaitSyncTime()
        val bridge = vendor ?: return
        bridge.awaitBattery()?.let { (level, _) -> updateState { it.copy(batteryPercent = level, showBattery = true) } }
        bridge.awaitVersion()?.let { info -> updateState { it.copy(deviceInfoLabel = formatVendorVersion(info)) } }
        bridge.awaitMediaCounts()?.let { counts -> showMediaCounts(counts.photos, counts.videos, counts.audio) }
        bridge.awaitWearingDetection()?.let { enabled -> updateState { it.copy(wearingDetectionEnabled = enabled) } }
        refreshRecordingSettings(showErrors = false)
    }

    private fun requestVolume() {
        val bridge = vendor ?: return reportVendorRequired("Volume")
        scope.launch {
            val volume = bridge.awaitVolume()
            updateState {
                it.copy(
                    agentLastError = volume?.let { v ->
                        "Volume: music ${v.musicCurrent}/${v.musicMax} · call ${v.callCurrent}/${v.callMax} · " +
                            "system ${v.systemCurrent}/${v.systemMax}"
                    } ?: "Volume unavailable",
                )
            }
        }
    }

    private fun setWearingDetection(enabled: Boolean) {
        val bridge = vendor ?: return reportVendorRequired("Wearing detection")
        scope.launch {
            if (bridge.awaitSetWearingDetection(enabled)) {
                updateState { it.copy(wearingDetectionEnabled = enabled) }
            } else {
                updateState { it.copy(agentLastError = "Wearing detection change failed") }
            }
        }
    }

    private suspend fun loadRecordingSettings(showErrors: Boolean) {
        val bridge = vendor ?: return
        val video = bridge.awaitVideoSettings()
        val audio = bridge.awaitAudioSettings()
        video?.let { videoAngle = it.angle }
        audio?.let { audioAngle = it.angle }
        updateState {
            it.copy(
                videoRecordingDurationSeconds = video?.durationSeconds ?: it.videoRecordingDurationSeconds,
                videoRecordingDurationOptionsSeconds = VIDEO_DURATION_OPTIONS_SECONDS,
                audioRecordingDurationSeconds = audio?.durationSeconds ?: it.audioRecordingDurationSeconds,
                audioRecordingDurationOptionsSeconds = AUDIO_DURATION_OPTIONS_SECONDS,
                agentLastError = if (showErrors && (video == null || audio == null)) {
                    "Recording limits unavailable"
                } else {
                    it.agentLastError
                },
            )
        }
    }

    private fun refreshRecordingSettings(showErrors: Boolean) {
        if (vendor == null) {
            if (showErrors) reportVendorRequired("Recording limits")
            return
        }
        scope.launch { loadRecordingSettings(showErrors) }
    }

    private fun setRecordingDuration(isAudio: Boolean, seconds: Int) {
        val allowed = if (isAudio) AUDIO_DURATION_OPTIONS_SECONDS else VIDEO_DURATION_OPTIONS_SECONDS
        if (seconds !in allowed) return
        val bridge = vendor ?: return reportVendorRequired("Recording limits")
        scope.launch {
            val saved = if (isAudio) {
                bridge.awaitSetAudioSettings(audioAngle, seconds)
            } else {
                bridge.awaitSetVideoSettings(videoAngle, seconds)
            }
            if (saved) {
                updateState {
                    if (isAudio) it.copy(audioRecordingDurationSeconds = seconds) else it.copy(videoRecordingDurationSeconds = seconds)
                }
            } else {
                updateState { it.copy(agentLastError = "Recording limit change failed") }
            }
        }
    }

    // ── AI questions from the glasses (Android: ImageQuestion / VoiceQuestion flows) ──

    /** Question captured while the photo is still being taken (TestImageQuestion). */
    private var pendingImageQuestion: Deferred<String?>? = null
    private var imageQuestionJob: Job? = null
    private var lastPhotoCount: Int? = null
    private var aiPhotosReceived = 0

    /**
     * Manual photos (Test description, camera button) use the highest AI-photo quality, as
     * Android does: configure the AI vision mode with the quality ([02 01 06 q q], sent raw
     * because QCSDK for iOS has no quality parameter). If no photo arrives, fall back to
     * QCSDK's plain AI-photo mode.
     */
    private suspend fun requestAiPhoto(bridge: com.fersaiyan.cyanbridge.shared.ble.VendorGlassesBridge): Boolean {
        val before = aiPhotosReceived
        val quality = MANUAL_PHOTO_QUALITY.toByte()
        if (bridge.awaitGlassesControl(byteArrayOf(0x02, 0x01, 0x06, quality, quality))) {
            PlatformLogger.i(ASSISTANT_TAG, "AI photo requested at quality $MANUAL_PHOTO_QUALITY (raw command)")
            val arrived = withTimeoutOrNull(RAW_PHOTO_WAIT_MS) {
                while (aiPhotosReceived == before) delay(200L)
                true
            } ?: false
            if (arrived) return true
            PlatformLogger.w(ASSISTANT_TAG, "No photo after the raw quality command; using QCSDK AI-photo mode")
        }
        return bridge.awaitModeAccepted(VendorGlassesMode.AI_PHOTO).also { accepted ->
            PlatformLogger.i(ASSISTANT_TAG, "AI photo requested via QCSDK mode: accepted=$accepted")
        }
    }

    /** The camera button stores a regular photo; take an AI photo of the same view to ask about it. */
    private suspend fun askAboutCameraButtonPhoto() {
        val bridge = vendor ?: return
        PlatformLogger.i(ASSISTANT_TAG, "Camera button photo detected; requesting an AI photo for a question")
        updateState { it.copy(agentLastError = "Camera button: getting the photo for your question…") }
        // The glasses may still be saving the photo; retry while they report busy.
        repeat(3) { attempt ->
            if (requestAiPhoto(bridge)) return
            PlatformLogger.w(ASSISTANT_TAG, "AI photo after camera button rejected (attempt ${attempt + 1})")
            delay(1_500L)
        }
        updateState { it.copy(agentLastError = "The glasses were busy; press Test description to ask about the view") }
    }

    /**
     * Android's image-question flow: ask "Pergunte." while the photo arrives (the model loads in
     * parallel), answer the spoken question or the default description, then offer follow-ups
     * about the same photo until the user stays silent.
     */
    private suspend fun answerImageQuestion(image: ByteArray) {
        scope.launch { IosLocalModels.preload() }
        updateState { it.copy(agentLastError = "Ask about the photo now, or wait for the description…") }
        val spoken = pendingImageQuestion?.also { pendingImageQuestion = null }?.await()
            ?: IosVoiceQuestion.ask(IMAGE_QUESTION_INITIAL_LISTENING_MS)
        var question = spoken ?: sharedDefaultImageQuestion()
        repeat(MAX_IMAGE_FOLLOW_UPS + 1) { round ->
            updateState { it.copy(agentLastError = "Analyzing the photo: $question") }
            // Photos take several seconds on the iPhone; say so instead of going silent.
            IosChatPlatform.enqueueSpeech(IosChatPlatform.waitCue())
            val reply = speakAnswer { onToken ->
                if (IosLocalModels.isActive && !IosRemoteModelSettings.isActive) {
                    IosLocalModels.describeImage(image, question, onToken)
                } else {
                    imageAiService.analyzeImage(image, question)
                }
            }
            deliverAnswer("📷 $question", reply, photo = image.takeIf { round == 0 })
            if (reply.isFailure) return
            question = IosVoiceQuestion.ask(IMAGE_QUESTION_INITIAL_LISTENING_MS) ?: return
        }
    }

    private suspend fun answerVoiceQuestion() {
        scope.launch { IosLocalModels.preload() }
        updateState { it.copy(agentLastError = "Listening… ask your question") }
        val question = IosVoiceQuestion.ask(VOICE_QUESTION_INITIAL_LISTENING_MS)
        if (question.isNullOrBlank()) {
            updateState { it.copy(agentLastError = "No question heard. Tap Test voice and speak after \"${IosChatPlatform.questionCue()}\"") }
            return
        }
        updateState { it.copy(agentLastError = "You asked: $question") }
        IosChatPlatform.enqueueSpeech(IosChatPlatform.waitCue())
        val reply = speakAnswer { onToken ->
            if (IosLocalModels.isActive && !IosRemoteModelSettings.isActive) {
                IosLocalModels.chat(listOf(com.fersaiyan.cyanbridge.shared.ai.ChatMessage("user", question)), onToken = onToken)
            } else {
                chatAiService.chat(listOf(com.fersaiyan.cyanbridge.shared.ai.ChatMessage("user", question))).message.content
            }
        }
        deliverAnswer(question, reply)
    }

    /**
     * Runs [generate] and speaks the reply through the glasses while it streams (on-device
     * models stream tokens; remote/relay replies are spoken chunk by chunk when they arrive).
     */
    private suspend fun speakAnswer(generate: suspend (onToken: (String) -> Unit) -> String): Result<String> {
        // No QCSDK AI-speak mode changes here: Android never sends them, and switching the glasses'
        // AI state mid-answer played a sound over the speech and cut words.
        val speech = IosStreamingSpeech()
        var streamed = false
        val reply = runCatching {
            generate { token ->
                streamed = true
                speech.append(token)
            }
        }.mapCatching { text ->
            if (text.startsWith("Error:")) error(text.removePrefix("Error:").trim())
            text
        }
        reply.onSuccess { text -> if (!streamed) speech.append(text) }.onFailure { speech.cancel() }
        if (reply.isSuccess) speech.finish()
        PlatformLogger.i(ASSISTANT_TAG, reply.fold({ "Answer: $it" }, { "Answer failed: ${it.message}" }))
        return reply
    }

    /** Shows the reply and keeps it in the "Glasses" chat (it was already spoken by speakAnswer). */
    private suspend fun deliverAnswer(question: String, reply: Result<String>, photo: ByteArray? = null) {
        val answer = reply.getOrElse { error ->
            updateState { it.copy(agentLastError = "AI request failed: ${error.message ?: "unknown error"}") }
            return
        }
        updateState { it.copy(agentLastError = answer) }
        val now = platformCurrentTimeMillis()
        val photoFile = photo?.let { bytes ->
            "chat-image-$now.jpg".takeIf { IosMediaPlatform.writeFile(PlatformFilePaths.dataDirectory() + "/" + it, bytes) }
        }
        val userContent = photoFile?.let { com.fersaiyan.cyanbridge.shared.chat.ChatImageMarkup.attach(it, question) } ?: question
        if (chatRepository.getChat(GLASSES_CHAT_ID) == null) {
            chatRepository.insertChat(ChatEntity(id = GLASSES_CHAT_ID, title = "Glasses", createdAt = now, updatedAt = now))
        }
        chatRepository.insertMessage(ChatMessageEntity("user-$now", GLASSES_CHAT_ID, "user", userContent, now))
        chatRepository.insertMessage(ChatMessageEntity("assistant-$now", GLASSES_CHAT_ID, "assistant", answer, now + 1))
    }

    private fun reportVendorRequired(label: String) {
        updateState { it.copy(agentLastError = "$label needs HeyCyan glasses connected through the vendor SDK") }
    }

    // ── Media sync through QCSDK (Android: startDataDownload) ──

    private fun startVendorSync() {
        val bridge = vendor ?: return
        syncJob?.cancel()
        syncJob = scope.launch {
            fun detail(text: String, progress: Float? = null) = updateState {
                it.copy(transfer = it.transfer.copy(isVisible = true, detail = text, progress = progress))
            }
            updateState { it.copy(agentLastError = "", transfer = GlassesTransferUiState(isVisible = true)) }

            detail("Enabling glasses transfer mode")
            val credentials = bridge.awaitOpenWifi(VendorGlassesMode.TRANSFER)
            if (credentials == null) {
                detail("The glasses did not enter transfer mode. Make sure they are not recording, then retry.")
                return@launch
            }

            detail("Waiting for the glasses hotspot ${credentials.ssid}")
            var reportedIp: String? = null
            for (attempt in 1..10) {
                reportedIp = bridge.awaitWifiIp()
                if (reportedIp != null) break
                delay(2_000L)
            }
            if (reportedIp == null) {
                detail("The glasses hotspot did not report an IP address. Retry sync.")
                return@launch
            }
            PlatformLogger.i(
                "IosGlassesSync",
                "Transfer hotspot ${credentials.ssid} (password ${credentials.passphrase.length} chars), QCSDK IP $reportedIp",
            )
            val candidates = glassesIpCandidates(reportedIp)
            IosTransferModeConfiguration.configurePreparedHotspot(credentials.ssid, credentials.passphrase, candidates.first())

            var ip = firstReachableIp(candidates)
            if (ip == null) {
                val joinError = wifiP2pManager.requestHotspotJoin(credentials.ssid, credentials.passphrase)
                if (joinError == null) {
                    detail("Joining ${credentials.ssid}")
                } else {
                    // Free-account builds cannot join automatically; the user joins in Settings.
                    val password = credentials.passphrase.ifBlank { HEYCYAN_DEFAULT_HOTSPOT_PASSWORD }
                    val instructions = "Open Settings ▸ Wi-Fi and join:\n${credentials.ssid}\n" +
                        "Password: $password\n(if rejected, try $HEYCYAN_DEFAULT_HOTSPOT_PASSWORD)\n\nThen return to CyanBridge."
                    detail(instructions)
                    IosMediaPlatform.showCopyAlert(
                        title = "Join the glasses Wi-Fi",
                        message = instructions,
                        copyTitle = "Copy password",
                        copyValue = password,
                    )
                }
                ip = withTimeoutOrNull(IOS_MANUAL_JOIN_TIMEOUT_MS) {
                    var found: String? = null
                    while (found == null) {
                        found = firstReachableIp(candidates)
                        if (found == null) delay(3_000L)
                    }
                    found
                }
                if (ip == null) {
                    detail("Could not reach the glasses (tried ${candidates.joinToString()}). Join ${credentials.ssid} and retry sync.")
                    return@launch
                }
            }
            wifiP2pManager.setGlassesIpAddress(ip)
            wifiP2pManager.markConnected()

            detail("Downloading media.config")
            val result = runCatching {
                mediaTransfer.sync(ip) { completed, total ->
                    detail("Downloaded $completed of $total files", if (total == 0) 1f else completed.toFloat() / total)
                }
            }.getOrElse { error ->
                detail(error.message ?: "Sync failed")
                exitTransferMode(bridge)
                return@launch
            }

            val newRecords = result.newRecords
            updateState {
                it.copy(
                    transfer = it.transfer.copy(
                        countsLabel = "Photos: ${newRecords.count { r -> IosMediaPlatform.isImage(r.filePath) }}  " +
                            "Videos: ${newRecords.count { r -> IosMediaPlatform.isVideo(r.filePath) }}  " +
                            "Audio: ${newRecords.count { r -> r.filename.endsWith(".opus", ignoreCase = true) }}",
                    ),
                )
            }

            var savedToPhotos = 0
            newRecords.filter { IosMediaPlatform.isImage(it.filePath) || IosMediaPlatform.isVideo(it.filePath) }
                .forEach { record ->
                    if (IosMediaPlatform.saveToPhotoLibrary(record.filePath, IosMediaPlatform.isVideo(record.filePath))) {
                        savedToPhotos++
                    }
                }
            exitTransferMode(bridge)
            detail("Sync complete: ${newRecords.size} new files, $savedToPhotos saved to Photos", 1f)

            if (newRecords.isNotEmpty() && IosMediaPlatform.confirm(
                    title = "Delete from glasses?",
                    message = "${newRecords.size} files are now on this iPhone. Delete them from the glasses to free space?",
                    confirmTitle = "Delete",
                    cancelTitle = "Keep",
                )
            ) {
                val deleted = newRecords.count { bridge.awaitDeleteMedia(it.filename) }
                detail("Sync complete. Deleted $deleted of ${newRecords.size} files from the glasses.", 1f)
                bridge.awaitMediaCounts()?.let { counts -> showMediaCounts(counts.photos, counts.videos, counts.audio) }
            }
        }
    }

    private suspend fun exitTransferMode(bridge: com.fersaiyan.cyanbridge.shared.ble.VendorGlassesBridge) {
        // The official app leaves transfer mode after downloads so the glasses can capture again.
        bridge.awaitModeAccepted(VendorGlassesMode.TRANSFER_STOP)
        wifiP2pManager.disconnect()
    }

    private fun resetVendorState() {
        updateState {
            it.copy(
                isVideoRecording = false,
                isAudioRecording = false,
                showStorage = false,
                storageLabel = "--",
                mediaCounts = null,
            )
        }
    }

    private fun vendorMode(mode: Int, label: String, onAccepted: () -> Unit = {}) {
        val bridge = vendor ?: run {
            updateState { it.copy(agentLastError = "$label needs HeyCyan glasses connected through the vendor SDK") }
            return
        }
        scope.launch {
            if (bridge.awaitModeAccepted(mode)) {
                onAccepted()
                updateState { it.copy(agentLastError = "") }
            } else {
                PlatformLogger.w(ASSISTANT_TAG, "$label: glasses rejected or did not answer mode $mode")
                updateState { it.copy(agentLastError = "$label was rejected by the glasses (they may be busy)") }
            }
        }
    }

    private fun toggleVideo() {
        val recording = _dashboardState.value.isVideoRecording
        vendorMode(if (recording) VendorGlassesMode.VIDEO_STOP else VendorGlassesMode.VIDEO, "Video recording") {
            updateState { it.copy(isVideoRecording = !recording) }
        }
    }

    private fun toggleAudio() {
        val recording = _dashboardState.value.isAudioRecording
        vendorMode(if (recording) VendorGlassesMode.AUDIO_STOP else VendorGlassesMode.AUDIO, "Audio recording") {
            updateState { it.copy(isAudioRecording = !recording) }
        }
    }

    private fun requestMediaCounts() {
        val bridge = vendor ?: return
        scope.launch {
            val counts = bridge.awaitMediaCounts()
            if (counts != null) {
                showMediaCounts(counts.photos, counts.videos, counts.audio)
            } else {
                updateState { it.copy(agentLastError = "Media count unavailable") }
            }
        }
    }

    private fun syncTime() {
        val bridge = vendor ?: run {
            updateState { it.copy(agentLastError = "Clock sync needs HeyCyan glasses connected through the vendor SDK") }
            return
        }
        scope.launch {
            val synced = bridge.awaitSyncTime()
            updateState { it.copy(agentLastError = if (synced) "Glasses clock synced" else "Clock sync failed") }
        }
    }

    private fun showMediaCounts(photos: Int, videos: Int, audio: Int) {
        lastPhotoCount = photos
        updateState {
            it.copy(
                storageLabel = "$photos photos / $videos videos / $audio audio",
                mediaCounts = com.fersaiyan.cyanbridge.shared.glasses.GlassesMediaCounts(photos, videos, audio),
                showStorage = true,
                transfer = it.transfer.copy(countsLabel = "Photos: $photos  Videos: $videos  Audio: $audio"),
            )
        }
    }

    private fun formatVendorVersion(info: com.fersaiyan.cyanbridge.shared.ble.VendorVersionInfo): String =
        listOf(
            "BT FW: ${info.firmware}",
            "BT HW: ${info.hardware}",
            "Wi-Fi FW: ${info.wifiFirmware}",
            "Wi-Fi HW: ${info.wifiHardware}",
        ).joinToString("\n")

    private fun applySelectedClass(deviceClass: DeviceClass) {
        selectedDeviceClass = deviceClass
        bleManager.attachVendorOnConnect = deviceClass == DeviceClass.HEY_CYAN
        bleManager.preferredWriteCharacteristicUuid =
            EyevueProtocol.COMMAND_WRITE_UUID.takeIf { deviceClass == DeviceClass.EYEVUE }
        updateState { it.copy(deviceClassLabel = deviceClass.displayName()) }
        updateConnectionCapabilities()
    }

    private fun startSync() {
        if (selectedDeviceClass == DeviceClass.META_RAYBAN) {
            updateState { it.copy(agentLastError = "Meta media sync requires the native MWDAT adapter") }
            return
        }
        syncJob?.cancel()
        updateState {
            it.copy(
                transfer = GlassesTransferUiState(
                    isVisible = true,
                    detail = "Preparing glasses for Wi-Fi transfer",
                ),
                agentLastError = "",
            )
        }
        syncJob = scope.launch {
            if (!isBleConnected) {
                updateState {
                    it.copy(transfer = it.transfer.copy(detail = "Connect to the glasses over Bluetooth first"))
                }
                return@launch
            }

            if (!bleManager.awaitCommandReady()) {
                updateState {
                    it.copy(
                        transfer = it.transfer.copy(
                            detail = "Bluetooth is connected, but the glasses command channel is not ready",
                        ),
                    )
                }
                return@launch
            }

            var hotspotCredentials = IosTransferModeConfiguration.current()
            if (hotspotCredentials?.transferModePrepared == true) {
                updateState {
                    it.copy(transfer = it.transfer.copy(detail = "Using host-prepared glasses transfer mode"))
                }
            } else {
                updateState {
                    it.copy(transfer = it.transfer.copy(detail = "Requesting glasses transfer mode over Bluetooth"))
                }
                runCatching { bleManager.sendCommand(IOS_TRANSFER_MODE_COMMAND) }
                .onFailure { error ->
                    updateState { it.copy(transfer = it.transfer.copy(detail = error.message ?: "Unable to enter transfer mode")) }
                    return@launch
                }
            }

            var ip = wifiP2pManager.glassesIpAddress.value
            val hasCurrentWifi = hotspotCredentials == null && wifiP2pManager.hasCurrentWifiConnection()
            if (hotspotCredentials == null &&
                !wifiP2pManager.isConnected() &&
                ip == null &&
                !hasCurrentWifi
            ) {
                updateState {
                    it.copy(
                        transfer = it.transfer.copy(
                            detail = "Waiting for iOS hotspot credentials from the host",
                        ),
                    )
                }
                hotspotCredentials = IosTransferModeConfiguration.awaitCredentials(IOS_HOST_CREDENTIAL_TIMEOUT_MS)
            }

            hotspotCredentials?.deviceIp?.let(wifiP2pManager::setGlassesIpAddress)
            ip = wifiP2pManager.glassesIpAddress.value ?: ip
            val credentials = hotspotCredentials
            if (credentials != null) {
                updateState {
                    it.copy(transfer = it.transfer.copy(detail = "Waiting for the glasses Wi-Fi readiness signal"))
                }
                ip = awaitGlassesIp(ip)
                if (ip == null) {
                    updateState {
                        it.copy(
                            transfer = it.transfer.copy(
                                detail = "The glasses did not report a Wi-Fi IP. Retry transfer mode or wire the host QCSDK readiness callback.",
                            ),
                        )
                    }
                    return@launch
                }

                updateState {
                    it.copy(transfer = it.transfer.copy(detail = "Joining glasses hotspot ${credentials.ssid}"))
                }
                runCatching {
                    wifiP2pManager.connectToHotspot(credentials.ssid, credentials.passphrase)
                }.onFailure { error ->
                    updateState {
                        it.copy(
                            transfer = it.transfer.copy(
                                detail = error.message ?: "Unable to join the glasses hotspot",
                            ),
                        )
                    }
                    return@launch
                }
            } else if (!wifiP2pManager.isConnected() && ip == null && !hasCurrentWifi) {
                updateState {
                    it.copy(
                        transfer = it.transfer.copy(
                            detail = "iOS hotspot credentials are unavailable. The host must call IosTransferModeConfiguration.configurePreparedHotspot after QCSDK openWifiWithMode, or join the hotspot first.",
                        ),
                    )
                }
                return@launch
            } else {
                updateState {
                    it.copy(transfer = it.transfer.copy(detail = "Using the current iOS Wi-Fi connection; verifying media.config"))
                }
            }

            if (!wifiP2pManager.bindToP2pNetwork()) {
                updateState {
                    it.copy(transfer = it.transfer.copy(detail = "The iOS Wi-Fi connection changed before transfer started"))
                }
                return@launch
            }

            ip = awaitGlassesIp(ip)
            if (ip == null) {
                updateState {
                    it.copy(transfer = it.transfer.copy(detail = "Waiting for the glasses BLE IP notification"))
                }
                return@launch
            }

            updateState { it.copy(transfer = it.transfer.copy(detail = "Downloading media.config")) }
            runCatching {
                mediaTransfer.sync(ip) { completed, total ->
                    updateState {
                        it.copy(
                            transfer = it.transfer.copy(
                                isVisible = true,
                                detail = "Downloaded $completed of $total files",
                                progress = if (total == 0) 1f else completed.toFloat() / total,
                            ),
                        )
                    }
                }
            }.onSuccess {
                updateState { it.copy(transfer = it.transfer.copy(detail = "Sync complete", progress = 1f)) }
            }.onFailure { error ->
                updateState { it.copy(transfer = it.transfer.copy(detail = error.message ?: "Sync failed")) }
            }
        }
    }

    private suspend fun awaitGlassesIp(existingIp: String?): String? {
        existingIp?.takeIf { it.isNotBlank() }?.let { return it }
        return withTimeoutOrNull(IOS_TRANSFER_IP_TIMEOUT_MS) {
            wifiP2pManager.glassesIpAddress.filterNotNull().first()
        }
    }

    private fun stopSync() {
        syncJob?.cancel()
        syncJob = null
        vendor?.let { bridge -> scope.launch { exitTransferMode(bridge) } }
        updateState { it.copy(transfer = GlassesTransferUiState(detail = "Sync stopped")) }
    }

    private fun sendGlassesCommand(label: String, command: ByteArray) {
        if (selectedDeviceClass == DeviceClass.META_RAYBAN) {
            updateState { it.copy(agentLastError = "Meta devices cannot receive HeyCyan BLE command bytes") }
            return
        }
        scope.launch {
            runCatching { bleManager.sendCommand(command) }
                .onFailure { error ->
                    updateState { it.copy(agentLastError = "$label failed: ${error.message ?: "BLE command error"}") }
                }
        }
    }

    private fun updateState(transform: (GlassesDashboardUiState) -> GlassesDashboardUiState) {
        _dashboardState.value = transform(_dashboardState.value)
    }

    private fun updateConnectionCapabilities() {
        val showHeyCyan = isBleConnected && selectedDeviceClass == DeviceClass.HEY_CYAN
        updateState { state ->
            state.copy(
                showHeyCyanControls = showHeyCyan,
                showCaptureSettings = showHeyCyan,
                showAiWakeWordRouting = showHeyCyan,
                showAdvancedControls = showHeyCyan,
                showAdvancedLocalAgent = showHeyCyan,
                showAdvancedDeviceInfo = showHeyCyan,
                showAdvancedDeviceVolume = showHeyCyan,
                showAdvancedImageQuality = showHeyCyan,
                showAdvancedDeveloperTools = showHeyCyan,
                showAdvancedOta = showHeyCyan,
                showMetaRaybanControls = isBleConnected && selectedDeviceClass == DeviceClass.META_RAYBAN,
                showEyevueControls = isEyevueConnected,
                videoRecordingDurationOptionsSeconds = if (isEyevueConnected) {
                    VIDEO_DURATION_OPTIONS_SECONDS
                } else {
                    state.videoRecordingDurationOptionsSeconds
                },
            )
        }
    }

    /**
     * QCSDK reports the hotspot IP rotated by one octet (for example 3.192.168.31);
     * the vendor demo probes common hotspot addresses for the same reason.
     */
    private fun glassesIpCandidates(reportedIp: String): List<String> {
        val octets = reportedIp.split('.').mapNotNull { it.toIntOrNull() }
        val candidates = mutableListOf<String>()
        if (octets.size == 4) {
            if (octets[0] in setOf(10, 172, 192)) candidates += reportedIp
            if (octets[1] in setOf(10, 172, 192)) candidates += (octets.drop(1) + octets[0]).joinToString(".")
        }
        candidates += listOf("192.168.31.1", reportedIp, "192.168.43.1", "192.168.4.1")
        return candidates.distinct()
    }

    private suspend fun firstReachableIp(candidates: List<String>): String? =
        candidates.firstOrNull { mediaTransfer.isReachable(it) }

    private fun extractGlassesIp(data: ByteArray): String? {
        if (data.size >= 11 && data[6].toInt() and 0xFF == 0x08) {
            return data.slice(7..10).joinToString(".") { (it.toInt() and 0xFF).toString() }
        }
        return Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
            .find(data.decodeToString())
            ?.value
    }
}

// ── iOS AI services via CyanBridge relay server ──

/**
 * iOS chat AI service that calls the CyanBridge relay server.
 * Endpoint: POST /chat
 */
private class IosRelayChatAiService(
    private val baseUrl: String = DEFAULT_RELAY_URL,
) : ChatAiService {
    private val httpClient = PlatformHttpClient()

    override suspend fun chat(messages: List<ChatMessage>, model: String?): ChatResponse {
        return try {
            val messagesJson = messages.joinToString(",") { msg ->
                """{"role":"${msg.role}","content":"${msg.content.escapeJson()}"}"""
            }
            val prompt = messages.lastOrNull { it.role.equals("user", ignoreCase = true) }?.content.orEmpty()
            val modelField = model?.trim().orEmpty()
            val body = buildString {
                append("{\"messages\":[")
                append(messagesJson)
                append("],\"prompt\":\"")
                append(prompt.escapeJson())
                append('"')
                if (modelField.isNotBlank()) {
                    append(",\"model\":\"")
                    append(modelField.escapeJson())
                    append('"')
                }
                append('}')
            }
            val headers = mapOf("Content-Type" to "application/json; charset=UTF-8")

            val response = httpClient.post("$baseUrl/chat", body, headers)

            if (response.isSuccessful) {
                parseChatResponse(response.body)
            } else {
                PlatformLogger.e(TAG, "Chat request failed: ${response.statusCode}")
                ChatResponse(
                    message = ChatMessage("assistant", "Error: Server returned ${response.statusCode}"),
                )
            }
        } catch (e: Exception) {
            PlatformLogger.e(TAG, "Chat request error", e)
            ChatResponse(
                message = ChatMessage("assistant", "Error: ${e.message ?: "Unknown error"}"),
            )
        }
    }

    private fun parseChatResponse(body: String): ChatResponse {
        val responseText = body.extractJsonText("reply", "response", "message")

        return ChatResponse(
            message = ChatMessage("assistant", responseText),
        )
    }

    companion object {
        private const val TAG = "IosRelayChatAi"
    }
}

/**
 * iOS voice AI service that calls the CyanBridge relay server.
 * Endpoint: POST /transcribe
 */
private class IosRelayVoiceAiService(
    private val baseUrl: String = DEFAULT_RELAY_URL,
) : VoiceAiService {
    private val httpClient = PlatformHttpClient()

    override suspend fun transcribe(audioData: ByteArray, mimeType: String): String {
        return try {
            // Send audio as base64 in JSON body
            val base64Audio = audioData.encodeBase64()
            val body = """{"audio":"$base64Audio","mime_type":"$mimeType"}"""
            val headers = mapOf("Content-Type" to "application/json; charset=UTF-8")

            val response = httpClient.post("$baseUrl/transcribe", body, headers)

            if (response.isSuccessful) {
                response.body.extractJsonText("text", "transcript", "reply")
            } else {
                PlatformLogger.e(TAG, "Voice transcription failed: ${response.statusCode}")
                ""
            }
        } catch (e: Exception) {
            PlatformLogger.e(TAG, "Voice transcription error", e)
            ""
        }
    }

    companion object {
        private const val TAG = "IosRelayVoiceAi"
    }
}

/**
 * iOS image AI service that calls the CyanBridge relay server.
 * Endpoint: POST /image-query
 */
private class IosRelayImageAiService(
    private val baseUrl: String = DEFAULT_RELAY_URL,
) : ImageAiService {
    private val httpClient = PlatformHttpClient()

    override suspend fun analyzeImage(imageData: ByteArray, prompt: String, mimeType: String): String {
        return try {
            val base64Image = imageData.encodeBase64()
            val filename = if (mimeType.equals("image/png", ignoreCase = true)) "image.png" else "image.jpg"
            val body = """{"imageBase64":"$base64Image","filename":"$filename","prompt":"${prompt.escapeJson()}"}"""
            val headers = mapOf("Content-Type" to "application/json; charset=UTF-8")

            val response = httpClient.post("$baseUrl/image-query", body, headers)

            if (response.isSuccessful) {
                response.body.extractJsonText("reply", "response", "text")
            } else {
                PlatformLogger.e(TAG, "Image analysis failed: ${response.statusCode}")
                "Error: Server returned ${response.statusCode}"
            }
        } catch (e: Exception) {
            PlatformLogger.e(TAG, "Image analysis error", e)
            "Error: ${e.message ?: "Unknown error"}"
        }
    }

    companion object {
        private const val TAG = "IosRelayImageAi"
    }
}

/**
 * iOS AI model registry that fetches models from the relay server.
 * Endpoint: GET /models
 */
private class IosRelayAiModelRegistry(
    private val baseUrl: String = DEFAULT_RELAY_URL,
) : AiModelRegistry {
    private val httpClient = PlatformHttpClient()
    private var cachedModels: List<AiModel>? = null

    override suspend fun listModels(): List<AiModel> {
        cachedModels?.let { return it }

        return try {
            val response = httpClient.get("$baseUrl/models")
            if (response.isSuccessful) {
                val models = parseModels(response.body)
                cachedModels = models
                models
            } else {
                PlatformLogger.e(TAG, "Failed to fetch models: ${response.statusCode}")
                defaultModels()
            }
        } catch (e: Exception) {
            PlatformLogger.e(TAG, "Failed to fetch models", e)
            defaultModels()
        }
    }

    override fun getDefaultModelId(): String = "relay-chat"

    private fun parseModels(body: String): List<AiModel> {
        // Simple JSON array parsing
        val modelPattern = Regex("""\{[^}]*"id"\s*:\s*"([^"]*)"[^}]*"name"\s*:\s*"([^"]*)"[^}]*\}""")
        return modelPattern.findAll(body).map { match ->
            AiModel(
                id = match.groupValues[1],
                name = match.groupValues[2],
                provider = "cyanbridge",
            )
        }.toList().ifEmpty { defaultModels() }
    }

    private fun defaultModels() = listOf(
        AiModel("relay-chat", "Relay Chat", "cyanbridge"),
        AiModel("relay-vision", "Relay Vision", "cyanbridge"),
    )

    companion object {
        private const val TAG = "IosRelayModelRegistry"
    }
}

// ── JSON/String helpers ──

private fun String.escapeJson(): String =
    replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

private fun String.unescapeJson(): String =
    replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

private fun String.extractJsonText(vararg keys: String): String {
    for (key in keys) {
        val match = Regex(""""$key"\s*:\s*"([^"]*?)"""").find(this)
        if (match != null) return match.groupValues[1].unescapeJson()
    }
    return this
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.encodeBase64(): String {
    if (isEmpty()) return ""
    // Use a simple base64 encoding for iOS
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val sb = StringBuilder()
    var i = 0
    while (i < size) {
        val b0 = this[i].toInt() and 0xFF
        val b1 = if (i + 1 < size) this[i + 1].toInt() and 0xFF else 0
        val b2 = if (i + 2 < size) this[i + 2].toInt() and 0xFF else 0
        sb.append(chars[(b0 shr 2) and 0x3F])
        sb.append(chars[((b0 shl 4) or (b1 shr 4)) and 0x3F])
        if (i + 1 < size) sb.append(chars[((b1 shl 2) or (b2 shr 6)) and 0x3F]) else sb.append('=')
        if (i + 2 < size) sb.append(chars[b2 and 0x3F]) else sb.append('=')
        i += 3
    }
    return sb.toString()
}

/** Screenshot harness: waits for QCSDK to attach, then runs Test description. */
private fun scopeDelayedTestImageQuestion(controller: IosAppController) {
    kotlinx.coroutines.MainScope().launch {
        delay(5_000L)
        controller.handle(GlassesDashboardAction.TestImageQuestion)
    }
}
