package com.fersaiyan.cyanbridge.shared.platform

import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.fersaiyan.cyanbridge.shared.ble.BleConnectionState
import com.fersaiyan.cyanbridge.shared.ble.BleNotificationListener
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.shared.glasses.GlassesTransferUiState
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.media.IosMediaTransfer
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.network.P2pConnectionState
import com.fersaiyan.cyanbridge.shared.network.P2pPeer
import com.fersaiyan.cyanbridge.shared.network.WifiP2pManager
import com.fersaiyan.cyanbridge.shared.persistence.IosChatRepository
import com.fersaiyan.cyanbridge.shared.persistence.IosDeviceProfileRepository
import com.fersaiyan.cyanbridge.shared.persistence.IosMediaRecordRepository
import com.fersaiyan.cyanbridge.shared.persistence.IosMemoryVaultRepository
import com.fersaiyan.cyanbridge.shared.persistence.IosNotesRepository
import com.fersaiyan.cyanbridge.shared.ui.CyanBridgeApp
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import platform.NetworkExtension.NEHotspotConfiguration
import platform.NetworkExtension.NEHotspotConfigurationManager
import platform.NetworkExtension.NEHotspotNetwork
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding
import platform.Foundation.NSData
import platform.Foundation.base64EncodedStringWithOptions

private const val DEFAULT_RELAY_URL = "https://cyanbridge.vercel.app"
private const val IOS_TRANSFER_IP_TIMEOUT_MS = 15_000L
private const val IOS_HOST_CREDENTIAL_TIMEOUT_MS = 10_000L
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
fun MainViewController() = ComposeUIViewController {
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
fun MainViewControllerForDestination(destination: String) = ComposeUIViewController {
    val controller = remember { IosAppController() }
    if (!CyanBridgeServices.isInitialized()) {
        controller.initializeServices()
    }
    val dashboardState by controller.dashboardState.collectAsState()
    IosCyanBridgeApp(
        controller = controller,
        initialDestination = when (destination) {
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

    CyanBridgeMaterialTheme(settings = appearanceSettings) {
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
        )
    }
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _dashboardState = MutableStateFlow(
        GlassesDashboardUiState(
            connectionLabel = "Bluetooth unavailable",
            agentStatus = "iOS shared host",
        ),
    )
    val dashboardState: StateFlow<GlassesDashboardUiState> = _dashboardState.asStateFlow()

    private var scanJob: Job? = null
    private var syncJob: Job? = null
    private var lastDiscoveredIdentifier: String? = null
    private var selectedDeviceClass: DeviceClass = DeviceClass.UNKNOWN
    private var isBleConnected = false

    init {
        bleManager.addNotificationListener(object : BleNotificationListener {
            override fun onNotification(characteristicId: String, data: ByteArray) {
                extractGlassesIp(data)?.let { ip ->
                    wifiP2pManager.setGlassesIpAddress(ip)
                    updateState { state ->
                        state.copy(
                            transfer = state.transfer.copy(detail = "Glasses IP: $ip"),
                        )
                    }
                }
            }
        })
        scope.launch {
            val selectedProfile = deviceProfileRepository.getAll()
                .maxByOrNull { it.lastConnectedAt }
            selectedDeviceClass = selectedProfile?.selectedClass
                ?.let { value -> DeviceClass.entries.firstOrNull { it.name == value } }
                ?: DeviceClass.UNKNOWN
            updateConnectionCapabilities()
        }
        scope.launch {
            bleManager.connectionState.collect { connectionState ->
                val wasBleConnected = isBleConnected
                isBleConnected = connectionState == BleConnectionState.CONNECTED
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
            chatAiService = IosRelayChatAiService(),
            voiceAiService = IosRelayVoiceAiService(),
            imageAiService = IosRelayImageAiService(),
            aiModelRegistry = IosRelayAiModelRegistry(),
        )
    }

    fun handle(action: GlassesDashboardAction) {
        when (action) {
            GlassesDashboardAction.Scan -> startScan()
            GlassesDashboardAction.Reconnect -> reconnect()
            GlassesDashboardAction.Disconnect -> scope.launch { bleManager.disconnect() }
            GlassesDashboardAction.RequestBattery -> requestBattery()
            GlassesDashboardAction.RequestVersion -> requestVersion()
            GlassesDashboardAction.StartSync -> startSync()
            GlassesDashboardAction.StopSync -> stopSync()
            GlassesDashboardAction.CapturePhoto -> sendGlassesCommand("camera", byteArrayOf(0x02, 0x01, 0x01))
            GlassesDashboardAction.StartAudioRecording -> sendGlassesCommand("audio recording", byteArrayOf(0x02, 0x01, 0x08))
            GlassesDashboardAction.RequestMediaCount -> sendGlassesCommand("media count", byteArrayOf(0x02, 0x04))
            GlassesDashboardAction.ToggleAdvanced -> updateState { it.copy(advancedExpanded = !it.advancedExpanded) }
            is GlassesDashboardAction.Navigate -> Unit
            else -> updateState { it.copy(agentLastError = "This control is not implemented in the iOS host yet") }
        }
    }

    private fun startScan() {
        scanJob?.cancel()
        updateState { it.copy(connectionLabel = "Scanning for glasses", agentLastError = "") }
        scanJob = scope.launch {
            var found = false
            bleManager.startScan(timeoutMs = 15_000L).collect { device ->
                found = true
                lastDiscoveredIdentifier = device.identifier
                updateState {
                    it.copy(connectionLabel = "Found ${device.name ?: device.identifier}")
                }
            }
            if (!found) {
                updateState { it.copy(connectionLabel = "No glasses found") }
            }
        }
    }

    private fun reconnect() {
        val identifier = lastDiscoveredIdentifier
        if (identifier == null) {
            updateState { it.copy(agentLastError = "Scan first so iOS can identify the glasses") }
            return
        }
        scope.launch {
            runCatching { bleManager.connect(identifier) }
                .onFailure { error -> updateState { it.copy(agentLastError = error.message ?: "Connection failed") } }
        }
    }

    private fun requestBattery() {
        scope.launch {
            val battery = runCatching { bleManager.requestBatteryLevel() }.getOrNull()
            updateState { it.copy(batteryPercent = battery, showBattery = battery != null) }
        }
    }

    private fun requestVersion() {
        scope.launch {
            val version = runCatching { bleManager.requestFirmwareVersion() }.getOrNull()
            updateState { it.copy(agentLastError = version?.let { value -> "Firmware: $value" } ?: "Firmware version unavailable") }
        }
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
            )
        }
    }

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
