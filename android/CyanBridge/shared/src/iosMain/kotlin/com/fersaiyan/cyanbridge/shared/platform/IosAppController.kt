package com.fersaiyan.cyanbridge.shared.platform

import com.fersaiyan.cyanbridge.bridge.core.GlassesConnectionControl
import com.fersaiyan.cyanbridge.data.local.iosAppDatabase
import com.fersaiyan.cyanbridge.shared.ai.DEFAULT_RELAY_URL
import com.fersaiyan.cyanbridge.shared.ai.RelayAiModelRegistry
import com.fersaiyan.cyanbridge.shared.ai.RelayChatAiService
import com.fersaiyan.cyanbridge.shared.ai.RelayImageAiService
import com.fersaiyan.cyanbridge.shared.ai.RelayVoiceAiService
import com.fersaiyan.cyanbridge.shared.assistant.AssistantOutput
import com.fersaiyan.cyanbridge.shared.assistant.GlassesAssistant
import com.fersaiyan.cyanbridge.shared.ble.BleConnectionState
import com.fersaiyan.cyanbridge.shared.ble.IosBleManager
import com.fersaiyan.cyanbridge.shared.ble.IosTuneBudsSession
import com.fersaiyan.cyanbridge.shared.ble.VendorGlassesMode
import com.fersaiyan.cyanbridge.shared.ble.awaitModeAccepted
import com.fersaiyan.cyanbridge.shared.devices.BleDeviceClassifier
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import com.fersaiyan.cyanbridge.shared.devices.ScannedDevice
import com.fersaiyan.cyanbridge.shared.devices.eyevue.EyevueProtocol
import com.fersaiyan.cyanbridge.shared.glasses.AiWakeWordRoute
import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardController
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.shared.glasses.withIosFamilyGating
import com.fersaiyan.cyanbridge.shared.media.IosMediaTransfer
import com.fersaiyan.cyanbridge.shared.persistence.ChatEntity
import com.fersaiyan.cyanbridge.shared.persistence.ChatMessageEntity
import com.fersaiyan.cyanbridge.shared.persistence.DeviceProfileEntity
import com.fersaiyan.cyanbridge.shared.persistence.ChatRepository
import com.fersaiyan.cyanbridge.shared.persistence.IosChatRepository
import com.fersaiyan.cyanbridge.shared.persistence.IosDeviceProfileRepository
import com.fersaiyan.cyanbridge.shared.persistence.IosMediaRecordRepository
import com.fersaiyan.cyanbridge.shared.persistence.IosMemoryVaultRepository
import com.fersaiyan.cyanbridge.shared.persistence.IosNotesRepository
import com.fersaiyan.cyanbridge.shared.persistence.LegacyImportingChatRepository
import com.fersaiyan.cyanbridge.shared.persistence.RoomChatRepository
import com.fersaiyan.cyanbridge.shared.prefs.AgentPrefs
import com.fersaiyan.cyanbridge.shared.prefs.AssistantPrefs
import com.fersaiyan.cyanbridge.shared.ui.DeviceBindConnection
import com.fersaiyan.cyanbridge.shared.ui.DeviceBindViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBUUID

private const val IOS_BLE_CONNECT_TIMEOUT_MS = 20_000L
private const val GLASSES_CHAT_ID = "glasses-assistant"
/**
 * Classes the app does not reconnect at launch (audio-only devices; Meizu's relay is RFCOMM-only;
 * MemoMind has no iOS link yet).
 */
private val NO_LINK_CLASSES = setOf(DeviceClass.GENERIC_AUDIO, DeviceClass.MEIZU_MYVU, DeviceClass.MEMOMIND)
// Android ImageThumbnailQuality (sdkValue to label).
private val THUMBNAIL_QUALITY_LABELS = mapOf(
    0 to "Instant", 1 to "Quick", 2 to "Smooth", 3 to "Fine", 4 to "Clearer", 5 to "Detailed",
)
internal const val ASSISTANT_TAG = "IosGlassesAssistant"

/**
 * Small iOS host controller for the shared dashboard. It owns platform jobs so
 * the composable remains a pure renderer, matching the Android callback shape.
 */
internal class IosAppController {
    val bleManager = IosBleManager()
    val wifiP2pManager = IosWifiP2pManager()
    // Chats live in the shared app database (the Android tables). Chats an earlier build kept
    // in NSUserDefaults are imported on first use; that copy stays as a backup.
    val chatRepository: ChatRepository = LegacyImportingChatRepository(
        target = RoomChatRepository(iosAppDatabase),
        legacy = IosChatRepository(),
        legacyPrefs = createPlatformPreferences("cyanbridge_chats"),
    )
    val notesRepository = IosNotesRepository()
    val deviceProfileRepository = IosDeviceProfileRepository()
    val memoryVaultRepository = IosMemoryVaultRepository()
    val mediaRecordRepository = IosMediaRecordRepository()
    private val mediaTransfer = IosMediaTransfer(mediaRecordRepository)
    // The "Local" custom provider (Settings ▸ Custom AI provider) overrides the relay when enabled.
    private val chatAiService = IosRoutedChatAiService(RelayChatAiService())
    private val voiceAiService = IosRoutedVoiceAiService(RelayVoiceAiService())
    private val imageAiService = IosRoutedImageAiService(RelayImageAiService())
    private val meetingRecorder = IosMeetingRecorder(voiceAiService, chatAiService, notesRepository)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pluginsRuntime = IosPluginsRuntime(
        chatAiService = chatAiService,
        imageAiService = imageAiService,
        notesRepository = notesRepository,
        relayBaseUrl = DEFAULT_RELAY_URL,
        onShortcutChanged = { shortcut -> updateState { it.copy(nativePluginShortcut = shortcut) } },
        requestGlassesPhoto = { vendor?.awaitModeAccepted(VendorGlassesMode.AI_PHOTO) ?: false },
    )

    /** Scan and reconnect go through the app's pairing flow; [onDisconnect] drops the family's link. */
    private fun glassesConnection(onDisconnect: () -> Unit) = object : GlassesConnectionControl {
        override fun scan() = openDeviceBind()

        override fun reconnect() = this@IosAppController.reconnect()

        override fun disconnect() = onDisconnect()
    }

    private val bleConnection = glassesConnection { scope.launch { bleManager.disconnect() } }

    private val glassesHost = object : IosGlassesHost {
        override val dashboard: GlassesDashboardUiState get() = _dashboardState.value
        override val isBleConnected: Boolean get() = this@IosAppController.isBleConnected

        override fun updateDashboard(transform: (GlassesDashboardUiState) -> GlassesDashboardUiState) =
            updateState(transform)

        override fun onGlassesPhoto(bytes: ByteArray) = answerGlassesPhoto(bytes)

        override fun onGlassesButton() {
            if (assistant.isBusy) return
            // Mentra Live's button is its camera button: ask about what the glasses see.
            if (selectedDeviceClass == DeviceClass.MENTRA_LIVE) {
                handle(GlassesDashboardAction.TestImageQuestion)
            } else {
                scope.launch { onGlassesAiVoice() }
            }
        }

        override fun onSdkConnectionChanged(connected: Boolean) {
            updateConnectionCapabilities()
            if (connected) {
                otherGlasses.sdkBridge?.requestBattery()
                otherGlasses.sdkBridge?.requestMediaCounts()
            }
        }
    }

    /** Eyevue over BLE GATT. */
    private val eyevue = IosEyevueGlasses(bleManager, scope, glassesHost, bleConnection)

    /** TuneBuds, MoYoung, Meta, Mentra and Meizu (see IosOtherGlasses). */
    private val otherGlasses = IosOtherGlasses(bleManager, scope, glassesHost, bleConnection)

    private val assistant = GlassesAssistant(
        scope = scope,
        voice = IosAssistantVoice,
        brain = IosAssistantBrain(chatAiService, imageAiService),
        output = object : AssistantOutput {
            override fun status(text: String) = updateState { it.copy(agentLastError = text) }

            override suspend fun saveAnswer(question: String, answer: String, photo: ByteArray?) =
                saveGlassesAnswer(question, answer, photo)
        },
        glassesHooks = { selectedDevice.assistantHooks },
    )

    /** HeyCyan glasses: QCSDK on device builds, raw BLE commands otherwise. */
    private val heyCyan: IosHeyCyanGlasses = IosHeyCyanGlasses(
        bleManager = bleManager,
        wifiP2pManager = wifiP2pManager,
        mediaTransfer = mediaTransfer,
        scope = scope,
        host = object : IosHeyCyanHost {
            override val dashboard: GlassesDashboardUiState get() = _dashboardState.value
            override val isBleConnected: Boolean get() = this@IosAppController.isBleConnected

            override fun updateDashboard(transform: (GlassesDashboardUiState) -> GlassesDashboardUiState) =
                updateState(transform)

            override suspend fun onAiVoice() = onGlassesAiVoice()

            override fun onPhoto(bytes: ByteArray) = answerGlassesPhoto(bytes)
        },
        connection = bleConnection,
    )

    // Like Android, unclassified and audio-only glasses use the HeyCyan path.
    private val genericAudioGlasses = IosProfiledGlasses(GlassesFamilyProfiles.genericAudio, heyCyan)

    /** MemoMind until its iOS link exists: the profile only (voice questions over Bluetooth audio). */
    private val memoMindGlasses = IosProfiledGlasses(GlassesFamilyProfiles.memoMind, heyCyan)
    private val unknownGlasses = IosProfiledGlasses(GlassesFamilyProfiles.unknown, heyCyan)

    /** The selected family's implementation (Android: AndroidGlassesDevices). */
    private val selectedDevice: IosGlassesDevice
        get() = when (selectedDeviceClass) {
            DeviceClass.HEY_CYAN -> heyCyan
            DeviceClass.EYEVUE -> eyevue
            DeviceClass.TUNEBUDS -> otherGlasses.tuneBudsDevice
            DeviceClass.MOYOUNG_W620,
            DeviceClass.META_RAYBAN,
            DeviceClass.MENTRA_LIVE,
            -> otherGlasses.sdkDevice(selectedDeviceClass)
            DeviceClass.MEIZU_MYVU -> otherGlasses.meizuDevice
            DeviceClass.MEMOMIND -> memoMindGlasses
            DeviceClass.GENERIC_AUDIO -> genericAudioGlasses
            DeviceClass.UNKNOWN -> unknownGlasses
        }

    fun debugPhotoCycle() {
        scope.launch {
            delay(5_000L)
            val bridge = vendor ?: return@launch PlatformLogger.w(ASSISTANT_TAG, "Photo cycle: glasses not attached")
            heyCyan.pauseCameraButtonQuestions = true
            PlatformLogger.i(ASSISTANT_TAG, "Photo cycle 1: AI photo turn")
            handle(GlassesDashboardAction.TestImageQuestion)
            delay(4_000L)
            withTimeoutOrNull(120_000L) { while (assistant.isBusy) delay(500L) }
            PlatformLogger.i(ASSISTANT_TAG, "Photo cycle 2: turn finished")
            delay(5_000L)
            PlatformLogger.i(ASSISTANT_TAG, "Photo cycle 3: regular photo accepted=${bridge.awaitModeAccepted(VendorGlassesMode.PHOTO)}")
            delay(8_000L)
            heyCyan.pauseCameraButtonQuestions = false
            PlatformLogger.i(ASSISTANT_TAG, "Photo cycle done")
        }
    }

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

    /** Session policy, family gating and device dispatch are shared; [IosDashboardHost] runs the effects. */
    private val dashboardController = GlassesDashboardController(
        IosDashboardHost(
            state = _dashboardState,
            scope = scope,
            selectedGlasses = { selectedDevice },
            assistant = assistant,
            meetingRecorder = meetingRecorder,
            pluginsRuntime = pluginsRuntime,
        ),
    )

    /** The shared DeviceBindScreen's state while it is shown over the dashboard. */
    private val _deviceBindState = MutableStateFlow<DeviceBindViewModel?>(null)
    val deviceBindState: StateFlow<DeviceBindViewModel?> = _deviceBindState.asStateFlow()

    private var scanJob: Job? = null
    private var lastDiscoveredIdentifier: String? = null
    private var selectedDeviceClass: DeviceClass = DeviceClass.UNKNOWN
    private val vendor get() = heyCyan.vendor

    private var isBleConnected = false

    init {
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
        val thumbnailQuality = AssistantPrefs.ImageQuestions.thumbnailQuality.get()
        updateState {
            it.copy(
                aiWakeWordRoute = AssistantPrefs.WakeWord.route.get(),
                assistantMode = GlassesAssistantMode.entries.firstOrNull {
                    it.name == AgentPrefs.LocalAgent.glassesAssistantMode.get()
                } ?: GlassesAssistantMode.CUSTOM_AI_PROVIDER,
                imageThumbnailQualitySdkValue = thumbnailQuality,
                imageThumbnailQualityLabel = THUMBNAIL_QUALITY_LABELS[thumbnailQuality] ?: "Clearer",
            )
        }
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
            if (identifier != null && selectedDeviceClass !in NO_LINK_CLASSES) {
                val poweredOn = withTimeoutOrNull(10_000L) { bleManager.isBluetoothEnabled.first { it } } ?: false
                if (poweredOn && !isBleConnected) {
                    lastDiscoveredIdentifier = identifier
                    connectTo(identifier)
                }
            }
        }
        scope.launch {
            bleManager.connectionState.collect { connectionState ->
                val wasBleConnected = isBleConnected
                isBleConnected = connectionState == BleConnectionState.CONNECTED
                if (!wasBleConnected && isBleConnected && selectedDeviceClass == DeviceClass.EYEVUE) {
                    scope.launch { eyevue.onConnected() }
                }
                if (!wasBleConnected && isBleConnected && selectedDeviceClass == DeviceClass.TUNEBUDS) {
                    scope.launch { otherGlasses.onBleConnected() }
                }
                if (wasBleConnected && connectionState == BleConnectionState.DISCONNECTED) {
                    IosTransferModeConfiguration.clearHotspot()
                }
                // SDK-driven glasses report their own connection (IosOtherGlasses).
                if (!otherGlasses.usesSdk) {
                    updateState { state ->
                        state.copy(
                            connectionLabel = when (connectionState) {
                                BleConnectionState.DISCONNECTED -> "Disconnected"
                                BleConnectionState.CONNECTING -> "Connecting"
                                BleConnectionState.CONNECTED -> "Connected"
                                BleConnectionState.DISCONNECTING -> "Disconnecting"
                            },
                        )
                    }
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
            aiModelRegistry = RelayAiModelRegistry(),
        )
    }

    fun handle(action: GlassesDashboardAction) = dashboardController.handle(action)

    private fun openDeviceBind() {
        _deviceBindState.value = DeviceBindViewModel()
        updateState { it.copy(agentLastError = "") }
        startDeviceScan()
    }

    fun startDeviceScan() {
        scanJob?.cancel()
        _deviceBindState.value?.let { bind ->
            bind.onScanStarted()
            bind.isScanning = true
        }
        scanJob = scope.launch {
            try {
                bleManager.startScan(timeoutMs = 15_000L).collect { found ->
                    val detected = BleDeviceClassifier.guessDeviceClass(
                        advertisedName = found.name,
                        serviceUuids = advertisedServiceUuids(found.advertisementData),
                        heyCyanServiceUuids = heyCyan.serviceUuids,
                    )
                    _deviceBindState.value?.let { bind ->
                        bind.onDevicesChanged(bind.devices.upsert(found.identifier, found.name, found.rssi, detected))
                    }
                }
            } finally {
                _deviceBindState.value?.isScanning = false
            }
        }
    }

    fun selectDevice(device: ScannedDevice) {
        _deviceBindState.value?.selectDevice(device)
    }

    private fun advertisedServiceUuids(advertisementData: Map<String, Any>): List<String> =
        (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? List<*>)
            .orEmpty()
            .mapNotNull { (it as? CBUUID)?.UUIDString }

    fun selectDeviceClass(deviceClass: DeviceClass) {
        _deviceBindState.value?.selectDeviceClass(deviceClass)
    }

    fun dismissConnection() {
        _deviceBindState.value?.dismissConnection()
    }

    fun closeDeviceBind(message: String = "") {
        scanJob?.cancel()
        bleManager.stopScan()
        _deviceBindState.value = null
        if (message.isNotEmpty()) updateState { it.copy(agentLastError = message) }
    }

    fun confirmConnection() {
        _deviceBindState.value?.confirmConnection(canConnect = { true })?.let(::connect)
    }

    fun confirmManualProtocol(deviceClass: DeviceClass) {
        _deviceBindState.value?.confirmManualProtocol(deviceClass, canConnect = { true })?.let(::connect)
    }

    /** Runs a pairing chosen in [DeviceBindViewModel] for the classes the iOS host can drive. */
    private fun connect(connection: DeviceBindConnection) {
        val device = connection.device
        when (connection.deviceClass) {
            DeviceClass.META_RAYBAN -> pairMetaGlasses()
            // The Mentra SDK connects to the chosen glasses (by identifier, then name).
            DeviceClass.MENTRA_LIVE -> pairSdkGlasses(
                DeviceClass.MENTRA_LIVE,
                device.advertisedName ?: DeviceClass.MENTRA_LIVE.displayName(),
                identifier = device.macAddress,
            )
            DeviceClass.GENERIC_AUDIO -> saveProfileAndConnect(device, DeviceClass.GENERIC_AUDIO, connect = false)
            // The MYVU app relay is RFCOMM-only; keep the profile for the assistant over Bluetooth audio.
            DeviceClass.MEIZU_MYVU -> saveProfileAndConnect(device, DeviceClass.MEIZU_MYVU, connect = false)
            DeviceClass.MEMOMIND -> saveProfileAndConnect(device, DeviceClass.MEMOMIND, connect = false)
            DeviceClass.UNKNOWN -> closeDeviceBind("Unknown device type.")
            else -> saveProfileAndConnect(device, connection.deviceClass)
        }
    }

    /** Meta glasses pair through the Meta AI app, not a BLE scan (Android: MetaPairingActivity). */
    fun pairMetaGlasses() {
        pairSdkGlasses(DeviceClass.META_RAYBAN, "Meta glasses")
    }

    /** [identifier] empty: the SDK connects to the first glasses it finds (Meta pairs in the Meta AI app). */
    private fun pairSdkGlasses(deviceClass: DeviceClass, name: String, identifier: String = "") {
        closeDeviceBind()
        scope.launch { if (bleManager.isConnected()) bleManager.disconnect() }
        applySelectedClass(deviceClass)
        otherGlasses.missingSdkMessage()?.let { message ->
            updateState { it.copy(agentLastError = message) }
            return
        }
        updateState { it.copy(agentLastError = "Connecting to $name…") }
        scope.launch {
            // Meta finishes pairing in the Meta AI app, so keep the choice even if that is still pending.
            if (deviceClass == DeviceClass.META_RAYBAN) saveProfile(deviceClass.name, name, deviceClass)
            otherGlasses.connectSdk(identifier = identifier, name = name).onSuccess { connected ->
                lastDiscoveredIdentifier = connected
                saveProfile(connected.ifBlank { deviceClass.name }, name, deviceClass)
                updateState { it.copy(agentLastError = "") }
            }
        }
    }

    private suspend fun saveProfile(identifier: String, name: String?, deviceClass: DeviceClass) {
        deviceProfileRepository.upsert(
            DeviceProfileEntity(
                macAddress = identifier,
                advertisedName = name,
                detectedClass = deviceClass.name,
                selectedClass = deviceClass.name,
                userOverridden = true,
                lastConnectedAt = platformCurrentTimeMillis(),
            ),
        )
    }

    private fun saveProfileAndConnect(device: ScannedDevice, deviceClass: DeviceClass, connect: Boolean = true) {
        closeDeviceBind()
        applySelectedClass(deviceClass)
        lastDiscoveredIdentifier = device.macAddress
        if (deviceClass == DeviceClass.MEIZU_MYVU) {
            updateState { it.copy(agentLastError = IosOtherGlasses.MEIZU_UNSUPPORTED) }
        }
        scope.launch {
            saveProfile(device.macAddress, device.advertisedName, deviceClass)
            if (connect) connectTo(device.macAddress, device.advertisedName)
        }
    }

    private suspend fun connectTo(identifier: String, name: String? = null) {
        if (otherGlasses.usesSdk) {
            otherGlasses.connectSdk(identifier.takeIf { it != selectedDeviceClass.name }.orEmpty(), name.orEmpty())
            return
        }
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

    /** Wake word / AI button: run the action chosen in "Wake word action" (Android: AiWakeWordRoute). */
    private suspend fun onGlassesAiVoice() {
        if (assistant.isBusy) return
        // Android stopGlassesAiAudio: end the glasses' own AI voice mode first, otherwise they
        // stay busy and reject the AI photo.
        vendor?.let { QcsdkAssistantHooks(it).releaseAiMode() }
        delay(300L)
        when (_dashboardState.value.aiWakeWordRoute) {
            AiWakeWordRoute.IMAGE_QUESTION -> {
                PlatformLogger.i(ASSISTANT_TAG, "Wake word: image question")
                handle(GlassesDashboardAction.TestImageQuestion)
            }
            AiWakeWordRoute.VOICE_QUESTION -> {
                PlatformLogger.i(ASSISTANT_TAG, "Wake word: voice question")
                assistant.askByVoice()
            }
        }
    }

    /** A photo from any glasses: the walking aid takes it, otherwise it starts an image question. */
    private fun answerGlassesPhoto(bytes: ByteArray) {
        if (pluginsRuntime.isWalkingAidActive) {
            pluginsRuntime.onGlassesImage(bytes)
        } else {
            // One image question at a time: a new photo replaces the previous turn.
            assistant.askAboutPhoto(bytes)
        }
    }

    /** Keeps a spoken answer in the "Glasses" chat, with the photo attached to the first one. */
    private suspend fun saveGlassesAnswer(question: String, answer: String, photo: ByteArray?) {
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

    private fun applySelectedClass(deviceClass: DeviceClass) {
        selectedDeviceClass = deviceClass
        bleManager.attachVendorOnConnect = deviceClass == DeviceClass.HEY_CYAN
        bleManager.preferredWriteCharacteristicUuid = when (deviceClass) {
            DeviceClass.EYEVUE -> EyevueProtocol.COMMAND_WRITE_UUID
            DeviceClass.TUNEBUDS -> IosTuneBudsSession.WRITE_UUID
            else -> null
        }
        otherGlasses.select(deviceClass)
        updateState { it.copy(deviceClassLabel = deviceClass.displayName()) }
        updateConnectionCapabilities()
    }

    private fun updateState(transform: (GlassesDashboardUiState) -> GlassesDashboardUiState) {
        _dashboardState.value = transform(_dashboardState.value)
    }

    /** The shared family gating of the selected glasses, with the iOS differences. */
    private fun updateConnectionCapabilities() {
        val device = selectedDevice
        updateState { it.withIosFamilyGating(device.profile, linked = device.isLinked) }
    }
}
