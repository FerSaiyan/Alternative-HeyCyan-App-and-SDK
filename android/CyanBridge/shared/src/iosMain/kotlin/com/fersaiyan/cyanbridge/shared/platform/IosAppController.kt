package com.fersaiyan.cyanbridge.shared.platform

import com.fersaiyan.cyanbridge.data.local.iosAppDatabase
import com.fersaiyan.cyanbridge.shared.ai.DEFAULT_RELAY_URL
import com.fersaiyan.cyanbridge.shared.ai.RelayAiModelRegistry
import com.fersaiyan.cyanbridge.shared.ai.RelayChatAiService
import com.fersaiyan.cyanbridge.shared.ai.RelayImageAiService
import com.fersaiyan.cyanbridge.shared.ai.RelayVoiceAiService
import com.fersaiyan.cyanbridge.shared.assistant.AssistantGlassesHooks
import com.fersaiyan.cyanbridge.shared.assistant.AssistantOutput
import com.fersaiyan.cyanbridge.shared.assistant.GlassesAssistant
import com.fersaiyan.cyanbridge.shared.ble.BleConnectionState
import com.fersaiyan.cyanbridge.shared.ble.IosBleManager
import com.fersaiyan.cyanbridge.shared.ble.IosEyevueSession
import com.fersaiyan.cyanbridge.shared.ble.IosTuneBudsSession
import com.fersaiyan.cyanbridge.shared.ble.VendorGlassesMode
import com.fersaiyan.cyanbridge.shared.ble.awaitModeAccepted
import com.fersaiyan.cyanbridge.shared.devices.BleDeviceClassifier
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.GlassesManagerGating
import com.fersaiyan.cyanbridge.shared.devices.ScannedDevice
import com.fersaiyan.cyanbridge.shared.devices.eyevue.EyevueProtocol
import com.fersaiyan.cyanbridge.shared.glasses.AiWakeWordRoute
import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
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
private const val IOS_GLASSES_PREFERENCES = "cyanbridge_ios_glasses"
private const val PREF_AI_WAKE_ROUTE = "ai_wake_word_route"
private const val PREF_THUMBNAIL_QUALITY = "image_thumbnail_quality"
private const val PREF_ASSISTANT_MODE = "assistant_mode"
private const val GLASSES_CHAT_ID = "glasses-assistant"
/** Glasses handled by IosOtherGlasses. */
private val OTHER_GLASSES_CLASSES = setOf(
    DeviceClass.TUNEBUDS,
    DeviceClass.MOYOUNG_W620,
    DeviceClass.META_RAYBAN,
    DeviceClass.MENTRA_LIVE,
    DeviceClass.MEIZU_MYVU,
)
/** Classes the app does not reconnect at launch (audio-only devices; Meizu's relay is RFCOMM-only). */
private val NO_LINK_CLASSES = setOf(DeviceClass.GENERIC_AUDIO, DeviceClass.MEIZU_MYVU)
/** Classes whose dashboard card is useful without a live link (Meta registration, Meizu notice). */
private val CARD_WITHOUT_LINK_CLASSES = setOf(DeviceClass.META_RAYBAN, DeviceClass.MEIZU_MYVU)
// Same option lists as Android's MainActivity for HeyCyan recording limits.
private val MEETING_TIMER_SECONDS = listOf<Long?>(null, 15L * 60L, 60L * 60L, 3L * 60L * 60L)
// Android ImageThumbnailQuality (sdkValue to label).
private val THUMBNAIL_QUALITY_LABELS = mapOf(
    0 to "Instant", 1 to "Quick", 2 to "Smooth", 3 to "Fine", 4 to "Clearer", 5 to "Detailed",
)
internal const val ASSISTANT_TAG = "IosGlassesAssistant"
internal val VIDEO_DURATION_OPTIONS_SECONDS = listOf(15, 30, 60, 180, 540, 720)

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
    private val eyevue = IosEyevueSession(
        bleManager = bleManager,
        onBattery = { battery ->
            scope.launch { updateState { it.copy(batteryPercent = battery.percent, showBattery = true) } }
        },
        onWifiSsid = { ssid ->
            scope.launch { updateState { it.copy(transfer = it.transfer.copy(detail = "Eyevue Wi-Fi: $ssid")) } }
        },
        onPhoto = { image -> answerGlassesPhoto(image) },
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

    /** TuneBuds, MoYoung, Meta, Mentra and Meizu (see IosOtherGlasses). */
    private val otherGlasses: IosOtherGlasses = IosOtherGlasses(
        bleManager = bleManager,
        scope = scope,
        host = object : IosGlassesHost {
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
        },
    )

    private val assistant = GlassesAssistant(
        scope = scope,
        voice = IosAssistantVoice,
        brain = IosAssistantBrain(chatAiService, imageAiService),
        output = object : AssistantOutput {
            override fun status(text: String) = updateState { it.copy(agentLastError = text) }

            override suspend fun saveAnswer(question: String, answer: String, photo: ByteArray?) =
                saveGlassesAnswer(question, answer, photo)
        },
        glassesHooks = { vendor?.let(::QcsdkAssistantHooks) ?: AssistantGlassesHooks.None },
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
    )
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

    private val _deviceBindState = MutableStateFlow<IosDeviceBindUiState?>(null)
    val deviceBindState: StateFlow<IosDeviceBindUiState?> = _deviceBindState.asStateFlow()

    private var scanJob: Job? = null
    private var lastDiscoveredIdentifier: String? = null
    private var selectedDeviceClass: DeviceClass = DeviceClass.UNKNOWN
    private val glassesPreferences = createPlatformPreferences(IOS_GLASSES_PREFERENCES)
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

    fun handle(action: GlassesDashboardAction) {
        if (isEyevueConnected && handleEyevue(action)) return
        if (otherGlasses.handle(action)) return
        if (heyCyan.handle(action)) return
        when (action) {
            GlassesDashboardAction.Scan -> openDeviceBind()
            GlassesDashboardAction.Reconnect -> reconnect()
            GlassesDashboardAction.Disconnect -> if (otherGlasses.usesSdk) {
                otherGlasses.disconnectSdk()
            } else {
                scope.launch { bleManager.disconnect() }
            }
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
            GlassesDashboardAction.TestImageQuestion -> {
                val bridge = vendor
                if (bridge == null && !otherGlasses.canTakeAiPhoto) {
                    otherGlasses.missingSdkMessage()?.let { message -> updateState { it.copy(agentLastError = message) } }
                        ?: heyCyan.reportVendorRequired("Image question")
                } else {
                    assistant.startPhotoQuestion {
                        if (bridge != null) heyCyan.requestAiPhoto(bridge) else otherGlasses.requestAiPhoto()
                    }
                }
            }
            GlassesDashboardAction.TestVoiceQuestion -> assistant.askByVoice()
            is GlassesDashboardAction.SelectAssistantMode -> {
                glassesPreferences.putString(PREF_ASSISTANT_MODE, action.mode.name)
                updateState { it.copy(assistantMode = action.mode) }
            }
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
                        heyCyanServiceUuids = heyCyan.serviceUuids,
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
            DeviceClass.META_RAYBAN -> pairMetaGlasses()
            DeviceClass.GENERIC_AUDIO -> {
                saveProfileAndConnect(device, DeviceClass.GENERIC_AUDIO, connect = false)
            }
            // The MYVU app relay is RFCOMM-only; keep the profile for the assistant over Bluetooth audio.
            DeviceClass.MEIZU_MYVU -> saveProfileAndConnect(device, DeviceClass.MEIZU_MYVU, connect = false)
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

    /** Meta glasses pair through the Meta AI app, not a BLE scan (Android: MetaPairingActivity). */
    fun pairMetaGlasses() {
        pairSdkGlasses(DeviceClass.META_RAYBAN, "Meta glasses")
    }

    /** Mentra Live: the Mentra SDK scans and connects (Android: MentraPairingActivity). */
    fun pairMentraGlasses() {
        pairSdkGlasses(DeviceClass.MENTRA_LIVE, "Mentra Live")
    }

    private fun pairSdkGlasses(deviceClass: DeviceClass, name: String) {
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
            otherGlasses.connectSdk(identifier = "", name = name).onSuccess { identifier ->
                lastDiscoveredIdentifier = identifier
                saveProfile(identifier.ifBlank { deviceClass.name }, name, deviceClass)
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

    private fun updateBind(transform: (IosDeviceBindUiState) -> IosDeviceBindUiState) {
        _deviceBindState.value = _deviceBindState.value?.let(transform)
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

    private fun updateConnectionCapabilities() {
        val showHeyCyan = isBleConnected && selectedDeviceClass == DeviceClass.HEY_CYAN
        // The other glasses follow Android's GlassesManagerGating. Meta (registration) and Meizu
        // (the unsupported notice) show their card without a live link.
        val otherConnected = if (otherGlasses.usesSdk) otherGlasses.isSdkConnected else isBleConnected
        val other = if (selectedDeviceClass in OTHER_GLASSES_CLASSES &&
            (otherConnected || selectedDeviceClass in CARD_WITHOUT_LINK_CLASSES)
        ) {
            GlassesManagerGating.visibleActions(selectedDeviceClass)
        } else {
            emptySet()
        }
        updateState { state ->
            state.copy(
                showHeyCyanControls = showHeyCyan,
                showCaptureSettings = showHeyCyan,
                showAiWakeWordRouting = showHeyCyan,
                showAdvancedControls = showHeyCyan || GlassesManagerGating.Action.ADVANCED_CONTROLS in other,
                showAdvancedLocalAgent = showHeyCyan,
                showAdvancedDeviceInfo = showHeyCyan || GlassesManagerGating.Action.ADVANCED_DEVICE_INFO in other,
                showAdvancedDeviceVolume = showHeyCyan,
                showAdvancedImageQuality = showHeyCyan || GlassesManagerGating.Action.ADVANCED_IMAGE_QUALITY in other,
                showAdvancedDeveloperTools = showHeyCyan,
                showAdvancedOta = showHeyCyan,
                showMetaRaybanControls = GlassesManagerGating.Action.META_RAYBAN_CONTROLS in other,
                showMeizuMyvuControls = GlassesManagerGating.Action.MEIZU_MYVU_CONTROLS in other,
                showTuneBudsControls = GlassesManagerGating.Action.TUNEBUDS_CONTROLS in other,
                showEyevueControls = isEyevueConnected,
                videoRecordingDurationOptionsSeconds = if (isEyevueConnected) {
                    VIDEO_DURATION_OPTIONS_SECONDS
                } else {
                    state.videoRecordingDurationOptionsSeconds
                },
            )
        }
    }
}
