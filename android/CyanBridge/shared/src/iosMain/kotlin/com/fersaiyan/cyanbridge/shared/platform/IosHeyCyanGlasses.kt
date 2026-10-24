package com.fersaiyan.cyanbridge.shared.platform

import com.fersaiyan.cyanbridge.bridge.core.AiPhotoCapture
import com.fersaiyan.cyanbridge.bridge.core.BatteryReporting
import com.fersaiyan.cyanbridge.bridge.core.CameraCapture
import com.fersaiyan.cyanbridge.bridge.core.DeviceInfoReporting
import com.fersaiyan.cyanbridge.bridge.core.GlassesConnectionControl
import com.fersaiyan.cyanbridge.bridge.core.MediaSyncTransport
import com.fersaiyan.cyanbridge.bridge.core.OnboardRecording
import com.fersaiyan.cyanbridge.bridge.core.RecordingSettingsControl
import com.fersaiyan.cyanbridge.bridge.core.VolumeReporting
import com.fersaiyan.cyanbridge.bridge.core.WearingDetectionControl
import com.fersaiyan.cyanbridge.shared.assistant.AssistantGlassesHooks
import com.fersaiyan.cyanbridge.shared.ble.BleNotificationListener
import com.fersaiyan.cyanbridge.shared.ble.IosBleManager
import com.fersaiyan.cyanbridge.shared.ble.VendorGlassesBridge
import com.fersaiyan.cyanbridge.shared.ble.VendorVersionInfo
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
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfile
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.shared.glasses.GlassesTransferUiState
import com.fersaiyan.cyanbridge.shared.media.IosMediaTransfer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSData

private const val IOS_TRANSFER_IP_TIMEOUT_MS = 15_000L
private const val IOS_HOST_CREDENTIAL_TIMEOUT_MS = 10_000L
private const val IOS_MANUAL_JOIN_TIMEOUT_MS = 180_000L
private const val HEYCYAN_DEFAULT_HOTSPOT_PASSWORD = "123456789"
private val AUDIO_DURATION_OPTIONS_SECONDS = listOf(1_800, 3_600, 7_200)
// Android MainActivity meetingTimerOptions: none, 15 min, 1 h, 3 h.
private val IOS_TRANSFER_MODE_COMMAND = byteArrayOf(0x02, 0x01, 0x04)
private const val TAG = "IosHeyCyanGlasses"

/** What the HeyCyan session needs from the app controller. */
internal interface IosHeyCyanHost {
    val dashboard: GlassesDashboardUiState
    val isBleConnected: Boolean

    fun updateDashboard(transform: (GlassesDashboardUiState) -> GlassesDashboardUiState)

    /** "Hey Cyan" wake word or the AI button. */
    suspend fun onAiVoice()

    /** An AI photo from the glasses. */
    fun onPhoto(bytes: ByteArray)
}

/**
 * HeyCyan glasses on iOS (Android: HeyCyanGlasses over the glasses_sdk controls). Device builds
 * drive them through QCSDK once it attached to the peripheral; otherwise the documented raw BLE
 * commands and the hotspot flow of IosTransferModeConfiguration are used.
 */
internal class IosHeyCyanGlasses(
    private val bleManager: IosBleManager,
    private val wifiP2pManager: IosWifiP2pManager,
    private val mediaTransfer: IosMediaTransfer,
    private val scope: CoroutineScope,
    private val host: IosHeyCyanHost,
    override val connection: GlassesConnectionControl,
) : IosGlassesDevice {
    override val profile: GlassesFamilyProfile = GlassesFamilyProfiles.heyCyan

    override val isLinked: Boolean get() = host.isBleConnected

    private val vendorBridge = VendorGlassesRegistry.bridge

    /** QCSDK's advertised services, so the pairing scan recognizes HeyCyan glasses. */
    val serviceUuids: List<String> get() = vendorBridge?.serviceUuids.orEmpty()

    /** The QCSDK bridge while it drives the connected glasses. */
    val vendor: VendorGlassesBridge? get() = vendorBridge?.takeIf { bleManager.isVendorAttached.value }
    private var syncJob: Job? = null
    private var videoAngle = 0
    private var audioAngle = 0
    private var lastPhotoCount: Int? = null


    /** Pauses the camera-button questions (debug photo cycle). */
    var pauseCameraButtonQuestions = false

    init {
        bleManager.addNotificationListener(object : BleNotificationListener {
            override fun onNotification(characteristicId: String, data: ByteArray) {
                // With QCSDK attached the sync flow resolves the IP itself; raw vendor
                // frames are offset differently on iOS and decode to bogus addresses.
                if (vendor != null) return
                extractGlassesIp(data)?.let(wifiP2pManager::setGlassesIpAddress)
            }
        })
        bleManager.vendorBridge = vendorBridge
        vendorBridge?.setEventListener(object : VendorGlassesEventListener {
            override fun onBatteryChanged(level: Int, charging: Boolean) {
                scope.launch { host.updateDashboard { it.copy(batteryPercent = level) } }
            }

            override fun onMediaCountsChanged(photos: Int, videos: Int, audio: Int) {
                PlatformLogger.i(TAG, "Glasses media counts: $photos photos, $videos videos, $audio audio")
                val previousPhotos = lastPhotoCount
                lastPhotoCount = photos
                scope.launch { showMediaCounts(photos, videos, audio) }
                // iOS QCSDK only reports the camera button as a new photo in the count (Android
                // gets a photo-ready notify); answer it like the AI-photo button.
                if (previousPhotos != null && photos > previousPhotos && syncJob?.isActive != true && !pauseCameraButtonQuestions) {
                    scope.launch { askAboutCameraButtonPhoto() }
                }
            }

            override fun onRawNotify(data: NSData) {
                val bytes = data.toKotlinBytes()
                // Android DeviceNotify: cmd 0x73 (ACTION_DEVICE_DATA_REPORTING), payload[0] = type.
                if (bytes.size < 7 || bytes[0] != 0xBC.toByte() || bytes[1] != 0x73.toByte()) return
                val type = bytes[6].toInt() and 0xFF
                PlatformLogger.i(TAG, "Glasses notify 0x${type.toString(16)}: ${bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }}")
                // 0x03 with payload[1] == 1: AI voice ("Hey Cyan" wake word or the AI button).
                if (type == 0x03 && bytes.size > 7 && bytes[7].toInt() == 1) scope.launch { host.onAiVoice() }
            }

            override fun onAiImage(data: NSData) {
                // Fired by the glasses' AI photo button and by TestImageQuestion.
                val bytes = data.toKotlinBytes()
                val size = IosMediaPlatform.imageSizeLabel(data)
                PlatformLogger.i(TAG, "AI photo received from the glasses: ${bytes.size} bytes, $size")
                host.onPhoto(bytes)
            }
        })
        scope.launch {
            bleManager.isVendorAttached.collect { attached ->
                if (attached) onVendorConnected() else resetVendorState()
            }
        }
    }

    override val camera = CameraCapture {
        if (vendor != null) {
            vendorMode(VendorGlassesMode.PHOTO, "Photo")
        } else {
            sendGlassesCommand("camera", byteArrayOf(0x02, 0x01, 0x01))
        }
    }

    override val recording = object : OnboardRecording {
        override fun toggleVideoRecording() = toggleVideo()

        override fun toggleAudioRecording() {
            if (vendor != null) {
                toggleAudio()
            } else {
                sendGlassesCommand("audio recording", byteArrayOf(0x02, 0x01, 0x08))
            }
        }
    }

    override val mediaSync = object : MediaSyncTransport {
        override fun requestMediaCount() {
            if (vendor != null) {
                requestMediaCounts()
            } else {
                sendGlassesCommand("media count", byteArrayOf(0x02, 0x04))
            }
        }

        override fun startSync() {
            if (vendor != null) startVendorSync() else startHotspotSync()
        }

        override fun stopSync() = stopMediaSync()
    }

    override val battery = object : BatteryReporting {
        override val pollsPeriodically: Boolean = false

        override fun requestBattery() = requestBatteryLevel()

        override fun refreshAfterSelection() = Unit
    }

    override val deviceInfo = object : DeviceInfoReporting {
        override fun requestVersion() = requestFirmwareVersion()

        override fun syncTime() = syncGlassesTime()
    }

    override val volume = VolumeReporting { requestVolume() }

    override val recordingSettings = object : RecordingSettingsControl {
        override fun refreshRecordingSettings() = this@IosHeyCyanGlasses.refreshRecordingSettings(showErrors = true)

        override fun setRecordingDuration(isAudio: Boolean, seconds: Int) = setRecordingLimit(isAudio, seconds)
    }

    override val wearingDetection = WearingDetectionControl { enabled -> setWearingDetection(enabled) }

    /** QCSDK's AI-photo mode; the photo arrives through [IosHeyCyanHost.onPhoto]. */
    override val aiPhoto = object : AiPhotoCapture {
        override suspend fun requestAiPhoto(): Boolean = vendor?.let { requestVendorAiPhoto(it) } ?: false
    }

    override val canTakeAiPhoto: Boolean get() = vendor != null

    /** The AI-speak modes and the release of the glasses' AI mode around an answer. */
    override val assistantHooks: AssistantGlassesHooks
        get() = vendor?.let(::QcsdkAssistantHooks) ?: AssistantGlassesHooks.None

    private fun requestBatteryLevel() {
        scope.launch {
            val battery = vendor?.let { bridge -> bridge.awaitBattery()?.first }
                ?: runCatching { bleManager.requestBatteryLevel() }.getOrNull()
            host.updateDashboard { it.copy(batteryPercent = battery) }
        }
    }

    private fun requestFirmwareVersion() {
        scope.launch {
            val bridge = vendor
            val label = if (bridge != null) {
                bridge.awaitVersion()?.let(::formatVendorVersion)
            } else {
                runCatching { bleManager.requestFirmwareVersion() }.getOrNull()?.let { "Firmware: $it" }
            }
            host.updateDashboard {
                it.copy(
                    deviceInfoLabel = label ?: it.deviceInfoLabel,
                    agentLastError = label ?: "Firmware version unavailable",
                )
            }
        }
    }

    // ── HeyCyan vendor SDK (QCSDK) session ──

    private suspend fun onVendorConnected() {
        host.updateDashboard { it.copy(agentLastError = "") }
        vendor?.awaitSyncTime()
        val bridge = vendor ?: return
        bridge.awaitBattery()?.let { (level, _) -> host.updateDashboard { it.copy(batteryPercent = level) } }
        bridge.awaitVersion()?.let { info -> host.updateDashboard { it.copy(deviceInfoLabel = formatVendorVersion(info)) } }
        bridge.awaitMediaCounts()?.let { counts -> showMediaCounts(counts.photos, counts.videos, counts.audio) }
        bridge.awaitWearingDetection()?.let { enabled -> host.updateDashboard { it.copy(wearingDetectionEnabled = enabled) } }
        refreshRecordingSettings(showErrors = false)
        // Android (configureHeyCyanWakeWordIfNeeded) enables the onboard wake word on every connection.
        PlatformLogger.i(TAG, "Hey Cyan wake word enabled=${bridge.awaitVoiceWakeup(true)}")
    }

    private fun requestVolume() {
        val bridge = vendor ?: return reportVendorRequired("Volume")
        scope.launch {
            val volume = bridge.awaitVolume()
            host.updateDashboard {
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
                host.updateDashboard { it.copy(wearingDetectionEnabled = enabled) }
            } else {
                host.updateDashboard { it.copy(agentLastError = "Wearing detection change failed") }
            }
        }
    }

    private suspend fun loadRecordingSettings(showErrors: Boolean) {
        val bridge = vendor ?: return
        val video = bridge.awaitVideoSettings()
        val audio = bridge.awaitAudioSettings()
        video?.let { videoAngle = it.angle }
        audio?.let { audioAngle = it.angle }
        host.updateDashboard {
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

    private fun setRecordingLimit(isAudio: Boolean, seconds: Int) {
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
                host.updateDashboard {
                    if (isAudio) it.copy(audioRecordingDurationSeconds = seconds) else it.copy(videoRecordingDurationSeconds = seconds)
                }
            } else {
                host.updateDashboard { it.copy(agentLastError = "Recording limit change failed") }
            }
        }
    }

    /**
     * QCSDK's AI-photo mode. Android also sends a quality command ([02 01 06 q q]); on iOS that
     * command (via sendGlassesControl) makes the glasses shoot, but QCSDK never downloads a
     * photo it did not request, so it only added a shutter sound and a 12 s wait. AI photos on
     * iOS therefore stay at the glasses' default size (512x384) for now.
     */
    private suspend fun requestVendorAiPhoto(bridge: VendorGlassesBridge): Boolean =
        bridge.awaitModeAccepted(VendorGlassesMode.AI_PHOTO).also { accepted ->
            PlatformLogger.i(TAG, "AI photo requested via QCSDK mode: accepted=$accepted")
        }

    /** The camera button stores a regular photo; take an AI photo of the same view to ask about it. */
    private suspend fun askAboutCameraButtonPhoto() {
        val bridge = vendor ?: return
        PlatformLogger.i(TAG, "Camera button photo detected; requesting an AI photo for a question")
        host.updateDashboard { it.copy(agentLastError = "Camera button: getting the photo for your question…") }
        // The glasses may still be saving the photo; retry while they report busy.
        repeat(3) { attempt ->
            if (requestVendorAiPhoto(bridge)) return
            PlatformLogger.w(TAG, "AI photo after camera button rejected (attempt ${attempt + 1})")
            delay(1_500L)
        }
        host.updateDashboard { it.copy(agentLastError = "The glasses were busy; press Test description to ask about the view") }
    }

    private fun reportVendorRequired(label: String) {
        host.updateDashboard { it.copy(agentLastError = "$label needs HeyCyan glasses connected through the vendor SDK") }
    }

    // ── Media sync through QCSDK (Android: startDataDownload) ──

    private fun startVendorSync() {
        val bridge = vendor ?: return
        syncJob?.cancel()
        syncJob = scope.launch {
            fun detail(text: String, progress: Float? = null) = host.updateDashboard {
                it.copy(transfer = it.transfer.copy(isVisible = true, detail = text, progress = progress))
            }
            host.updateDashboard { it.copy(agentLastError = "", transfer = GlassesTransferUiState(isVisible = true)) }

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
            host.updateDashboard {
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

    private suspend fun exitTransferMode(bridge: VendorGlassesBridge) {
        // The official app leaves transfer mode after downloads so the glasses can capture again.
        bridge.awaitModeAccepted(VendorGlassesMode.TRANSFER_STOP)
        wifiP2pManager.disconnect()
    }

    private fun resetVendorState() {
        host.updateDashboard {
            it.copy(
                isVideoRecording = false,
                isAudioRecording = false,
                storageLabel = "--",
                mediaCounts = null,
            )
        }
    }

    private fun vendorMode(mode: Int, label: String, onAccepted: () -> Unit = {}) {
        val bridge = vendor ?: run {
            host.updateDashboard { it.copy(agentLastError = "$label needs HeyCyan glasses connected through the vendor SDK") }
            return
        }
        scope.launch {
            if (bridge.awaitModeAccepted(mode)) {
                onAccepted()
                host.updateDashboard { it.copy(agentLastError = "") }
            } else {
                PlatformLogger.w(TAG, "$label: glasses rejected or did not answer mode $mode")
                host.updateDashboard { it.copy(agentLastError = "$label was rejected by the glasses (they may be busy)") }
            }
        }
    }

    private fun toggleVideo() {
        val recording = host.dashboard.isVideoRecording
        vendorMode(if (recording) VendorGlassesMode.VIDEO_STOP else VendorGlassesMode.VIDEO, "Video recording") {
            host.updateDashboard { it.copy(isVideoRecording = !recording) }
        }
    }

    private fun toggleAudio() {
        val recording = host.dashboard.isAudioRecording
        vendorMode(if (recording) VendorGlassesMode.AUDIO_STOP else VendorGlassesMode.AUDIO, "Audio recording") {
            host.updateDashboard { it.copy(isAudioRecording = !recording) }
        }
    }

    private fun requestMediaCounts() {
        val bridge = vendor ?: return
        scope.launch {
            val counts = bridge.awaitMediaCounts()
            if (counts != null) {
                showMediaCounts(counts.photos, counts.videos, counts.audio)
            } else {
                host.updateDashboard { it.copy(agentLastError = "Media count unavailable") }
            }
        }
    }

    private fun syncGlassesTime() {
        val bridge = vendor ?: run {
            host.updateDashboard { it.copy(agentLastError = "Clock sync needs HeyCyan glasses connected through the vendor SDK") }
            return
        }
        scope.launch {
            val synced = bridge.awaitSyncTime()
            host.updateDashboard { it.copy(agentLastError = if (synced) "Glasses clock synced" else "Clock sync failed") }
        }
    }

    private fun showMediaCounts(photos: Int, videos: Int, audio: Int) {
        lastPhotoCount = photos
        host.updateDashboard {
            it.copy(
                storageLabel = "$photos photos / $videos videos / $audio audio",
                mediaCounts = com.fersaiyan.cyanbridge.shared.glasses.GlassesMediaCounts(photos, videos, audio),
                transfer = it.transfer.copy(countsLabel = "Photos: $photos  Videos: $videos  Audio: $audio"),
            )
        }
    }

    private fun formatVendorVersion(info: VendorVersionInfo): String =
        listOf(
            "BT FW: ${info.firmware}",
            "BT HW: ${info.hardware}",
            "Wi-Fi FW: ${info.wifiFirmware}",
            "Wi-Fi HW: ${info.wifiHardware}",
        ).joinToString("\n")

    private fun startHotspotSync() {
        syncJob?.cancel()
        host.updateDashboard {
            it.copy(
                transfer = GlassesTransferUiState(
                    isVisible = true,
                    detail = "Preparing glasses for Wi-Fi transfer",
                ),
                agentLastError = "",
            )
        }
        syncJob = scope.launch {
            if (!host.isBleConnected) {
                host.updateDashboard {
                    it.copy(transfer = it.transfer.copy(detail = "Connect to the glasses over Bluetooth first"))
                }
                return@launch
            }

            if (!bleManager.awaitCommandReady()) {
                host.updateDashboard {
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
                host.updateDashboard {
                    it.copy(transfer = it.transfer.copy(detail = "Using host-prepared glasses transfer mode"))
                }
            } else {
                host.updateDashboard {
                    it.copy(transfer = it.transfer.copy(detail = "Requesting glasses transfer mode over Bluetooth"))
                }
                runCatching { bleManager.sendCommand(IOS_TRANSFER_MODE_COMMAND) }
                .onFailure { error ->
                    host.updateDashboard { it.copy(transfer = it.transfer.copy(detail = error.message ?: "Unable to enter transfer mode")) }
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
                host.updateDashboard {
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
                host.updateDashboard {
                    it.copy(transfer = it.transfer.copy(detail = "Waiting for the glasses Wi-Fi readiness signal"))
                }
                ip = awaitGlassesIp(ip)
                if (ip == null) {
                    host.updateDashboard {
                        it.copy(
                            transfer = it.transfer.copy(
                                detail = "The glasses did not report a Wi-Fi IP. Retry transfer mode or wire the host QCSDK readiness callback.",
                            ),
                        )
                    }
                    return@launch
                }

                host.updateDashboard {
                    it.copy(transfer = it.transfer.copy(detail = "Joining glasses hotspot ${credentials.ssid}"))
                }
                runCatching {
                    wifiP2pManager.connectToHotspot(credentials.ssid, credentials.passphrase)
                }.onFailure { error ->
                    host.updateDashboard {
                        it.copy(
                            transfer = it.transfer.copy(
                                detail = error.message ?: "Unable to join the glasses hotspot",
                            ),
                        )
                    }
                    return@launch
                }
            } else if (!wifiP2pManager.isConnected() && ip == null && !hasCurrentWifi) {
                host.updateDashboard {
                    it.copy(
                        transfer = it.transfer.copy(
                            detail = "iOS hotspot credentials are unavailable. The host must call IosTransferModeConfiguration.configurePreparedHotspot after QCSDK openWifiWithMode, or join the hotspot first.",
                        ),
                    )
                }
                return@launch
            } else {
                host.updateDashboard {
                    it.copy(transfer = it.transfer.copy(detail = "Using the current iOS Wi-Fi connection; verifying media.config"))
                }
            }

            if (!wifiP2pManager.bindToP2pNetwork()) {
                host.updateDashboard {
                    it.copy(transfer = it.transfer.copy(detail = "The iOS Wi-Fi connection changed before transfer started"))
                }
                return@launch
            }

            ip = awaitGlassesIp(ip)
            if (ip == null) {
                host.updateDashboard {
                    it.copy(transfer = it.transfer.copy(detail = "Waiting for the glasses BLE IP notification"))
                }
                return@launch
            }

            host.updateDashboard { it.copy(transfer = it.transfer.copy(detail = "Downloading media.config")) }
            runCatching {
                mediaTransfer.sync(ip) { completed, total ->
                    host.updateDashboard {
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
                host.updateDashboard { it.copy(transfer = it.transfer.copy(detail = "Sync complete", progress = 1f)) }
            }.onFailure { error ->
                host.updateDashboard { it.copy(transfer = it.transfer.copy(detail = error.message ?: "Sync failed")) }
            }
        }
    }

    private suspend fun awaitGlassesIp(existingIp: String?): String? {
        existingIp?.takeIf { it.isNotBlank() }?.let { return it }
        return withTimeoutOrNull(IOS_TRANSFER_IP_TIMEOUT_MS) {
            wifiP2pManager.glassesIpAddress.filterNotNull().first()
        }
    }

    private fun stopMediaSync() {
        syncJob?.cancel()
        syncJob = null
        vendor?.let { bridge -> scope.launch { exitTransferMode(bridge) } }
        host.updateDashboard { it.copy(transfer = GlassesTransferUiState(detail = "Sync stopped")) }
    }

    private fun sendGlassesCommand(label: String, command: ByteArray) {
        scope.launch {
            runCatching { bleManager.sendCommand(command) }
                .onFailure { error ->
                    host.updateDashboard { it.copy(agentLastError = "$label failed: ${error.message ?: "BLE command error"}") }
                }
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
