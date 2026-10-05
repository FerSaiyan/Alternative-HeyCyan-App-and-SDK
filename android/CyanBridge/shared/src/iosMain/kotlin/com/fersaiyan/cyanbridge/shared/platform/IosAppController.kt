package com.fersaiyan.cyanbridge.shared.platform

import com.fersaiyan.cyanbridge.shared.assistant.AssistantGlassesHooks
import com.fersaiyan.cyanbridge.shared.assistant.AssistantOutput
import com.fersaiyan.cyanbridge.shared.assistant.GlassesAssistant
import com.fersaiyan.cyanbridge.shared.ai.DEFAULT_RELAY_URL
import com.fersaiyan.cyanbridge.shared.ai.RelayAiModelRegistry
import com.fersaiyan.cyanbridge.shared.ai.RelayChatAiService
import com.fersaiyan.cyanbridge.shared.ai.RelayImageAiService
import com.fersaiyan.cyanbridge.shared.ai.RelayVoiceAiService
import com.fersaiyan.cyanbridge.shared.ble.BleConnectionState
import com.fersaiyan.cyanbridge.shared.ble.BleNotificationListener
import com.fersaiyan.cyanbridge.shared.ble.IosBleManager
import com.fersaiyan.cyanbridge.shared.ble.IosEyevueSession
import com.fersaiyan.cyanbridge.shared.ble.IosTuneBudsSession
import com.fersaiyan.cyanbridge.shared.ble.VendorGlassesEventListener
import com.fersaiyan.cyanbridge.shared.ble.VendorGlassesMode
import com.fersaiyan.cyanbridge.shared.ble.VendorGlassesRegistry
import com.fersaiyan.cyanbridge.shared.ble.awaitAudioSettings
import com.fersaiyan.cyanbridge.shared.ble.awaitBattery
import com.fersaiyan.cyanbridge.shared.ble.awaitDeleteMedia
import com.fersaiyan.cyanbridge.shared.ble.awaitMediaCounts
import com.fersaiyan.cyanbridge.shared.ble.awaitModeAccepted
import com.fersaiyan.cyanbridge.shared.ble.awaitOpenWifi
import com.fersaiyan.cyanbridge.shared.ble.awaitSetAudioSettings
import com.fersaiyan.cyanbridge.shared.ble.awaitSetVideoSettings
import com.fersaiyan.cyanbridge.shared.ble.awaitSetWearingDetection
import com.fersaiyan.cyanbridge.shared.ble.awaitSyncTime
import com.fersaiyan.cyanbridge.shared.ble.awaitVersion
import com.fersaiyan.cyanbridge.shared.ble.awaitVideoSettings
import com.fersaiyan.cyanbridge.shared.ble.awaitVoiceWakeup
import com.fersaiyan.cyanbridge.shared.ble.awaitVolume
import com.fersaiyan.cyanbridge.shared.ble.awaitWearingDetection
import com.fersaiyan.cyanbridge.shared.ble.awaitWifiIp
import com.fersaiyan.cyanbridge.shared.devices.BleDeviceClassifier
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.GlassesManagerGating
import com.fersaiyan.cyanbridge.shared.devices.ScannedDevice
import com.fersaiyan.cyanbridge.shared.devices.eyevue.EyevueProtocol
import com.fersaiyan.cyanbridge.shared.glasses.AiWakeWordRoute
import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.shared.glasses.GlassesTransferUiState
import com.fersaiyan.cyanbridge.shared.media.IosMediaTransfer
import com.fersaiyan.cyanbridge.shared.persistence.ChatEntity
import com.fersaiyan.cyanbridge.shared.persistence.ChatMessageEntity
import com.fersaiyan.cyanbridge.shared.persistence.DeviceProfileEntity
import com.fersaiyan.cyanbridge.shared.persistence.IosChatRepository
import com.fersaiyan.cyanbridge.shared.persistence.IosDeviceProfileRepository
import com.fersaiyan.cyanbridge.shared.persistence.IosMediaRecordRepository
import com.fersaiyan.cyanbridge.shared.persistence.IosMemoryVaultRepository
import com.fersaiyan.cyanbridge.shared.persistence.IosNotesRepository
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData

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
private const val ASSISTANT_TAG = "IosGlassesAssistant"

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
private val VIDEO_DURATION_OPTIONS_SECONDS = listOf(15, 30, 60, 180, 540, 720)
private val AUDIO_DURATION_OPTIONS_SECONDS = listOf(1_800, 3_600, 7_200)
// Android MainActivity meetingTimerOptions: none, 15 min, 1 h, 3 h.
private val MEETING_TIMER_SECONDS = listOf<Long?>(null, 15L * 60L, 60L * 60L, 3L * 60L * 60L)
// Android ImageThumbnailQuality (sdkValue to label).
private val THUMBNAIL_QUALITY_LABELS = mapOf(
    0 to "Instant", 1 to "Quick", 2 to "Smooth", 3 to "Fine", 4 to "Clearer", 5 to "Detailed",
)
private val IOS_TRANSFER_MODE_COMMAND = byteArrayOf(0x02, 0x01, 0x04)

/**
 * Small iOS host controller for the shared dashboard. It owns platform jobs so
 * the composable remains a pure renderer, matching the Android callback shape.
 */
internal class IosAppController {
    val bleManager = IosBleManager()
    val wifiP2pManager = IosWifiP2pManager()
    val chatRepository = IosChatRepository()
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

    private var debugCycleRunning = false

    fun debugPhotoCycle() {
        scope.launch {
            delay(5_000L)
            val bridge = vendor ?: return@launch PlatformLogger.w(ASSISTANT_TAG, "Photo cycle: glasses not attached")
            debugCycleRunning = true
            PlatformLogger.i(ASSISTANT_TAG, "Photo cycle 1: AI photo turn")
            handle(GlassesDashboardAction.TestImageQuestion)
            delay(4_000L)
            withTimeoutOrNull(120_000L) { while (assistant.isBusy) delay(500L) }
            PlatformLogger.i(ASSISTANT_TAG, "Photo cycle 2: turn finished")
            delay(5_000L)
            PlatformLogger.i(ASSISTANT_TAG, "Photo cycle 3: regular photo accepted=${bridge.awaitModeAccepted(VendorGlassesMode.PHOTO)}")
            delay(8_000L)
            debugCycleRunning = false
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
                if (previousPhotos != null && photos > previousPhotos && syncJob?.isActive != true && !debugCycleRunning) {
                    scope.launch { askAboutCameraButtonPhoto() }
                }
            }

            override fun onRawNotify(data: NSData) {
                val bytes = data.toKotlinBytes()
                // Android DeviceNotify: cmd 0x73 (ACTION_DEVICE_DATA_REPORTING), payload[0] = type.
                if (bytes.size < 7 || bytes[0] != 0xBC.toByte() || bytes[1] != 0x73.toByte()) return
                val type = bytes[6].toInt() and 0xFF
                PlatformLogger.i(ASSISTANT_TAG, "Glasses notify 0x${type.toString(16)}: ${bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }}")
                // 0x03 with payload[1] == 1: AI voice ("Hey Cyan" wake word or the AI button).
                if (type == 0x03 && bytes.size > 7 && bytes[7].toInt() == 1) scope.launch { onGlassesAiVoice() }
            }

            override fun onAiImage(data: NSData) {
                // Fired by the glasses' AI photo button and by TestImageQuestion.
                val bytes = data.toKotlinBytes()
                val size = IosMediaPlatform.imageSizeLabel(data)
                PlatformLogger.i(ASSISTANT_TAG, "AI photo received from the glasses: ${bytes.size} bytes, $size")
                answerGlassesPhoto(bytes)
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
            if (identifier != null && selectedDeviceClass !in NO_LINK_CLASSES) {
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
        when (action) {
            GlassesDashboardAction.Scan -> openDeviceBind()
            GlassesDashboardAction.Reconnect -> reconnect()
            GlassesDashboardAction.Disconnect -> if (otherGlasses.usesSdk) {
                otherGlasses.disconnectSdk()
            } else {
                scope.launch { bleManager.disconnect() }
            }
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
            GlassesDashboardAction.TestImageQuestion -> {
                val bridge = vendor
                if (bridge == null && !otherGlasses.canTakeAiPhoto) {
                    otherGlasses.missingSdkMessage()?.let { message -> updateState { it.copy(agentLastError = message) } }
                        ?: reportVendorRequired("Image question")
                } else {
                    assistant.startPhotoQuestion {
                        if (bridge != null) requestAiPhoto(bridge) else otherGlasses.requestAiPhoto()
                    }
                }
            }
            GlassesDashboardAction.TestVoiceQuestion -> assistant.askByVoice()
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
        // Android (configureHeyCyanWakeWordIfNeeded) enables the onboard wake word on every connection.
        PlatformLogger.i(ASSISTANT_TAG, "Hey Cyan wake word enabled=${bridge.awaitVoiceWakeup(true)}")
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

    // ── AI questions from the glasses (GlassesAssistant runs the flows) ──

    private var lastPhotoCount: Int? = null

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

    /**
     * QCSDK's AI-photo mode. Android also sends a quality command ([02 01 06 q q]); on iOS that
     * command (via sendGlassesControl) makes the glasses shoot, but QCSDK never downloads a
     * photo it did not request, so it only added a shutter sound and a 12 s wait. AI photos on
     * iOS therefore stay at the glasses' default size (512x384) for now.
     */
    private suspend fun requestAiPhoto(bridge: com.fersaiyan.cyanbridge.shared.ble.VendorGlassesBridge): Boolean =
        bridge.awaitModeAccepted(VendorGlassesMode.AI_PHOTO).also { accepted ->
            PlatformLogger.i(ASSISTANT_TAG, "AI photo requested via QCSDK mode: accepted=$accepted")
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

    /** A photo from any glasses: the walking aid takes it, otherwise it starts an image question. */
    private fun answerGlassesPhoto(bytes: ByteArray) {
        if (pluginsRuntime.isWalkingAidActive) {
            pluginsRuntime.onGlassesImage(bytes)
        } else {
            // One image question at a time: a new photo replaces the previous turn.
            assistant.askAboutPhoto(bytes)
        }
    }

    private fun reportVendorRequired(label: String) {
        updateState { it.copy(agentLastError = "$label needs HeyCyan glasses connected through the vendor SDK") }
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
        bleManager.preferredWriteCharacteristicUuid = when (deviceClass) {
            DeviceClass.EYEVUE -> EyevueProtocol.COMMAND_WRITE_UUID
            DeviceClass.TUNEBUDS -> IosTuneBudsSession.WRITE_UUID
            else -> null
        }
        otherGlasses.select(deviceClass)
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
