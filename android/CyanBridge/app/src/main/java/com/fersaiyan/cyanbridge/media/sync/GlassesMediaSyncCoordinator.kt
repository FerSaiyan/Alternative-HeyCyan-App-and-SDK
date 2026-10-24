package com.fersaiyan.cyanbridge.media.sync

import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.ai.image.HighQualityFailureChoice
import com.fersaiyan.cyanbridge.ai.image.ImageQuestionSource
import com.fersaiyan.cyanbridge.ai.image.ImageQuestionSourcePolicy
import com.fersaiyan.cyanbridge.glasses.GlassesSession
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.fersaiyan.cyanbridge.media.AdaptiveHttpRoute
import com.fersaiyan.cyanbridge.media.AdaptiveP2pProfileStore
import com.fersaiyan.cyanbridge.media.AdaptiveP2pSyncSession
import com.fersaiyan.cyanbridge.media.AdaptiveSyncCheckpoint
import com.fersaiyan.cyanbridge.media.HeyCyanP2pPolicy
import com.fersaiyan.cyanbridge.media.OfficialHeyCyanApp
import com.fersaiyan.cyanbridge.shared.media.OggOpusWrapper
import com.fersaiyan.cyanbridge.media.VendorAlbumDownloader
import com.fersaiyan.cyanbridge.media.autocapture.GlassesSyncedAudioIngestor
import com.fersaiyan.cyanbridge.network.GlassesNetworkSessionCoordinator
import com.fersaiyan.cyanbridge.network.HeyCyanP2pLink
import com.fersaiyan.cyanbridge.shared.network.GlassesNetworkAddressing
import com.fersaiyan.cyanbridge.shared.network.GlassesWifiNotify
import com.fersaiyan.cyanbridge.shared.network.session.GlassesLinkClient
import com.fersaiyan.cyanbridge.shared.network.session.GlassesWifiErrorAction
import com.fersaiyan.cyanbridge.shared.network.session.GlassesWifiErrorContext
import com.fersaiyan.cyanbridge.shared.network.session.GlassesWifiErrorPolicy
import com.fersaiyan.cyanbridge.shared.glasses.GlassesSyncFlow
import com.fersaiyan.cyanbridge.ui.MyApplication
import com.fersaiyan.cyanbridge.ui.bleIpBridge
import com.fersaiyan.cyanbridge.ui.debug.DebugLogSupport
import com.fersaiyan.cyanbridge.ui.hasBluetooth
import com.fersaiyan.cyanbridge.ui.hasWifiP2pPermission
import com.fersaiyan.cyanbridge.ui.wifi.p2p.WifiP2pManagerSingleton
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * HeyCyan Wi-Fi media sync: the BLE transfer-mode handshake, Wi-Fi Direct peer selection and
 * group teardown, the official (HeyCyan-parity) and adaptive HTTP download flows, and the
 * full-resolution image transfer used by image questions.
 */
internal class GlassesMediaSyncCoordinator(
    private val activity: AppCompatActivity,
    private val host: GlassesMediaSyncHost,
    private val transferUi: TransferProgressPresenter,
    private val mediaLibrary: GlassesMediaLibrary,
    // P2P teardown now outlives the Activity inside GlassesNetworkSessionCoordinator; the scope
    // stays in the constructor so the Activity wiring is unchanged.
    @Suppress("unused") private val glassesTeardownScope: CoroutineScope,
) {
    var downloadP2pConnected = false
        private set
    private var downloadFlowMode = GlassesSyncFlow.CUSTOM
    private var downloadBleIp: String? = null
    private var downloadWifiIp: String? = null
    private var downloadPhoneIsGroupOwner: Boolean? = null
    var downloadInProgress = false
        private set
    var downloadAttemptJob: Job? = null
        private set
    private var downloadSessionJob: Job? = null
    private var downloadSessionScope: CoroutineScope? = null
    private var downloadSessionId: Long = 0L
    private var vendorAlbumDownloader: VendorAlbumDownloader? = null
    private var downloadResolvedHttpIp: String? = null
    /** The media-sync claim on the glasses link, owned by [GlassesNetworkSessionCoordinator]. */
    private var downloadP2pLink: HeyCyanP2pLink? = null
    private val downloadWifiP2pManager: WifiP2pManagerSingleton?
        get() = downloadP2pLink?.manager
    private var downloadP2pTeardownInProgress = false
    private var downloadExitTransferRequested = false
    private var downloadCancelledByUser = false
    private var lastDownloadBleIpAtMs: Long = 0L
    private var downloadInitialPhaseTimeoutJob: Job? = null
    private var downloadInitialPhaseCompleted = false
    private var downloadSupportDialogShown = false
    private var downloadStartedAtMs: Long = 0L
    private var noMatchPeerCount = 0
    private var downloadP2pRestartCount = 0
    private val seenP2pPeers = mutableSetOf<String>()
    private var lastPeerSetHash: Int = 0
    private var officialSystemSuccess = false
    private var officialBleCallbackSuccess = false
    private var officialFlowRetryCount = 0
    private val officialFlowRetryLimit = 1
    private var officialDisconnectRecoveryJob: Job? = null
    private var officialMediaErrorCount = 0
    private var transferModeCommandAttempt = 0
    @Volatile
    private var transferModeCommandSequence = 0
    private var transferModeCommandSentAtMs = 0L
    private var transferModeCommandCallbackLatencyMs: Long? = null
    private var transferModeCommandCallbackReceived = false
    private var transferModeCommandEvidenceReceived = false
    private var transferModeCommandCallbackDataType: Int? = null
    private var transferModeCommandCallbackError: Int? = null
    private var transferModeCommandTimeoutJob: Job? = null
    private var officialFlowRetryRequired = false
    private var adaptiveSyncSession: AdaptiveP2pSyncSession? = null
    private var adaptiveRecoveryJob: Job? = null
    private var adaptiveActiveRoute: AdaptiveHttpRoute? = null
    private var adaptiveSuccessfulWarmupMs: Long? = null
    private var adaptiveProfileResultRecorded = false
    private var mediaDownloadPurpose = MediaDownloadPurpose.FULL_SYNC
    var highQualityImageRequest: HighQualityImageRequest? = null
        private set

    // Official app registers the notify listener with cmdType=2 for album import.
    // Keep our main listener (cmdType=100) for general events, and add a narrow
    // one for the download flow so we don't duplicate thumbnail/audio handling.
    private val downloadNotifyListener by lazy { DownloadNotifyListener() }
    private var downloadNotifyListenerRegistered = false
    private val downloadInitialPhaseTimeoutMs = 45_000L

    private val p2pNetwork = GlassesP2pNetwork(
        context = activity,
        linkClaim = { downloadP2pLink?.claim },
        downloadFlowMode = { downloadFlowMode },
        adaptiveActiveRoute = { adaptiveActiveRoute },
        adaptiveSyncSession = { adaptiveSyncSession },
        downloadBleIp = { downloadBleIp },
        downloadWifiIp = { downloadWifiIp },
    )


    enum class MediaDownloadPurpose {
        FULL_SYNC,
        IMAGE_QUESTION,
    }

    data class HighQualityImageRequest(
        val sourceTag: String,
        val captureStartedAtMs: Long,
    )

    private fun isHighQualityImageTransfer(): Boolean =
        mediaDownloadPurpose == MediaDownloadPurpose.IMAGE_QUESTION && highQualityImageRequest != null

    fun requestHighQualityImageForQuestion(sourceTag: String) {
        if (highQualityImageRequest == null) {
            highQualityImageRequest = HighQualityImageRequest(
                sourceTag = sourceTag,
                captureStartedAtMs = host.pendingImageCaptureStartedAtMs.takeIf { it > 0L }
                    ?: System.currentTimeMillis(),
            )
        }
        mediaDownloadPurpose = MediaDownloadPurpose.IMAGE_QUESTION
        activity.lifecycleScope.launch {
            // The camera command owns the vendor SDK response slot until its callback returns.
            delay(300)
            if (!isHighQualityImageTransfer()) return@launch
            startDataDownload(
                mode = GlassesSyncFlow.CUSTOM,
                purpose = MediaDownloadPurpose.IMAGE_QUESTION,
            )
        }
    }

    fun startDataDownload(
        mode: GlassesSyncFlow = GlassesSyncFlow.CUSTOM,
        retryCount: Int = 0,
        isRetry: Boolean = false,
        afterP2pTeardown: Boolean = false,
        purpose: MediaDownloadPurpose = MediaDownloadPurpose.FULL_SYNC,
    ) {
        if (host.rejectHeyCyanOnlyFeature("Wi-Fi media sync")) return

        if (!hasBluetooth(activity) || !hasWifiP2pPermission(activity)) {
            host.ensureGlassesTransportPermissions("Wi-Fi media sync") {
                startDataDownload(
                    mode = mode,
                    retryCount = retryCount,
                    isRetry = isRetry,
                    afterP2pTeardown = afterP2pTeardown,
                    purpose = purpose,
                )
            }
            return
        }

        downloadFlowMode = mode
        mediaDownloadPurpose = purpose
        officialFlowRetryCount = retryCount

        if (!afterP2pTeardown) {
        Log.i("DataDownload", "Starting BLE+WiFi P2P data download using ${mode.label}...")
        Log.i("DataDownload", "Sync session flow=${mode.label}")
        Toast.makeText(activity, "Starting sync using ${mode.label}… Please do not exit the app during transfer.", Toast.LENGTH_LONG).show()

        // Check Bluetooth connection status
        if (!BleOperateManager.getInstance().isConnected) {
            Log.e("DataDownload", "Bluetooth not connected. Please connect to glasses first.")
            if (isHighQualityImageTransfer()) {
                finishHighQualityImageFailure("Bluetooth disconnected before full-resolution image transfer could start.")
                return
            }
            Toast.makeText(
                activity,
                "Bluetooth not connected. Please connect to glasses first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Check WiFi is enabled (required for WiFi Direct / P2P)
        val wifiManager = activity.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        if (!wifiManager.isWifiEnabled) {
            Log.e("DataDownload", "WiFi is disabled. WiFi must be on for P2P sync.")
            if (isHighQualityImageTransfer()) {
                finishHighQualityImageFailure("Wi-Fi must be enabled to retrieve the full-resolution image.")
                return
            }
            Toast.makeText(
                activity,
                "Please enable WiFi to sync with glasses.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

            if (GlassesSessionCoordinator.isOwnedBy(GlassesSession.MEDIA_SYNC)) {
                if (!isRetry || host.mediaSessionLease?.let(GlassesSessionCoordinator::isActive) != true) {
                    Log.w("DataDownload", "Media sync is already active or still tearing down")
                    Toast.makeText(activity, "Media sync is already using the glasses connection.", Toast.LENGTH_SHORT).show()
                    return
                }
            } else {
                host.mediaSessionLease = host.acquireExclusiveGlassesSession(GlassesSession.MEDIA_SYNC) ?: return
            }

            downloadCancelledByUser = false
            val teardownLease = host.mediaSessionLease
            // P2P removal is asynchronous. Start a retry only after the prior group is gone,
            // otherwise its delayed removal can tear down the newly formed group.
            teardownDownloadP2pSession(
                sendExitTransfer = false,
                hideTransferUi = false,
                releaseExclusiveSession = false,
                onTeardownComplete = {
                    if (downloadCancelledByUser) {
                        Log.i("DataDownload", "Sync was cancelled while waiting for P2P teardown")
                        host.releaseExclusiveGlassesSession(teardownLease)
                    } else {
                        startDataDownload(
                            mode = mode,
                            retryCount = retryCount,
                            isRetry = true,
                            afterP2pTeardown = true,
                            purpose = purpose,
                        )
                    }
                },
            )
            return
        }

        downloadCancelledByUser = false

        // Reset state for a fresh run
        downloadP2pConnected = false
        downloadBleIp = null
        downloadWifiIp = null
        downloadPhoneIsGroupOwner = null
        downloadInProgress = false
        downloadResolvedHttpIp = null
        p2pNetwork.downloadP2pNetwork = null
        lastDownloadBleIpAtMs = 0L
        officialDisconnectRecoveryJob?.cancel()
        officialDisconnectRecoveryJob = null
        officialMediaErrorCount = 0
        resetDownloadSupportState()
        resetOfficialFlowState()
        createDownloadSession()
        if (mode == GlassesSyncFlow.CUSTOM) {
            startAdaptiveSyncSession()
        } else {
            adaptiveSyncSession = null
            adaptiveActiveRoute = null
            adaptiveSuccessfulWarmupMs = null
        }

        transferUi.resetTransferUiState()
        transferUi.setTransferUiVisible(true)
        transferUi.setTransferFlowLabel(mode)
        transferUi.setTransferDetail("Starting sync (${mode.label})...")
        startDownloadInitialPhaseWatchdog()

        if (!downloadNotifyListenerRegistered) {
            try {
                LargeDataHandler.getInstance().addOutDeviceListener(2, downloadNotifyListener)
                downloadNotifyListenerRegistered = true
                Log.i("DataDownload", "Registered download notify listener (cmdType=2)")
            } catch (e: Exception) {
                Log.e("DataDownload", "Failed to register download notify listener", e)
            }
        }

        val callback = object : WifiP2pManagerSingleton.WifiP2pCallback {
            override fun onWifiP2pEnabled() {
                Log.i("DataDownload", "WiFi P2P enabled")
            }

            override fun onWifiP2pDisabled() {
                Log.e("DataDownload", "WiFi P2P disabled")
            }

            override fun onPeersChanged(peers: Collection<WifiP2pDevice>) {
                Log.i("DataDownload", "Found ${peers.size} P2P devices")
                if (peers.isEmpty()) return

                // Debounce: Android often broadcasts PEERS_CHANGED multiple times
                // for the same peer list. Only process when the set actually changes.
                val currentHash = peers.map { it.deviceAddress }.toSet().hashCode()
                if (currentHash == lastPeerSetHash) return
                lastPeerSetHash = currentHash

                // Guard against redundant connection attempts (official app uses isP2PConnecting).
                if (downloadWifiP2pManager?.isConnecting() == true || downloadWifiP2pManager?.isConnected() == true) {
                    Log.i("DataDownload", "Already connecting/connected, skipping peer re-evaluation")
                    return
                }

                if (downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN) {
                    val target = GlassesP2pPeerSelector.selectOfficialLikelyGlassesPeer(peers)
                    if (target == null) {
                        val pairedName = try { DeviceManager.getInstance().deviceName } catch (_: Exception) { "?" }
                        val pairedMac = try { DeviceManager.getInstance().deviceAddress } catch (_: Exception) { "?" }
                        peers.forEach { seenP2pPeers.add("${it.deviceName}/${it.deviceAddress}") }
                        Log.i(
                            "DataDownload",
                            "Official flow: no exact glasses peer yet; paired=$pairedName/$pairedMac; discovered=${peers.map { "${it.deviceName}/${it.deviceAddress}" }}"
                        )
                        transferUi.setTransferDetail("Waiting for exact glasses P2P peer...")
                        return
                    }

                    Log.i(
                        "DataDownload",
                        "Official flow connecting to peer: ${target.deviceName} / ${target.deviceAddress}"
                    )
                    downloadP2pLink?.markConnectRequested()
                    downloadWifiP2pManager?.connectToDevice(target)
                    return
                }

                val target = GlassesP2pPeerSelector.selectBestLikelyGlassesPeer(peers)
                if (target == null) {
                    noMatchPeerCount++
                    val pairedName = try { DeviceManager.getInstance().deviceName } catch (_: Exception) { "?" }
                    val pairedMac = try { DeviceManager.getInstance().deviceAddress } catch (_: Exception) { "?" }
                    peers.forEach { seenP2pPeers.add("${it.deviceName}/${it.deviceAddress}") }
                    Log.i(
                        "DataDownload",
                        "No likely glasses peer yet (x$noMatchPeerCount); paired=$pairedName/$pairedMac; ignoring discovered peers: ${peers.map { "${it.deviceName}/${it.deviceAddress}" }}"
                    )
                    transferUi.setTransferDetail("Waiting for glasses P2P peer...")
                    return
                }

                noMatchPeerCount = 0
                adaptiveSyncSession?.mark(
                    AdaptiveSyncCheckpoint.MATCHING_PEER_FOUND,
                    "name=${target.deviceName.orEmpty().take(40)}",
                )
                Log.i(
                    "DataDownload",
                    "Connecting to peer: ${target.deviceName} / ${target.deviceAddress}"
                )
                downloadP2pLink?.markConnectRequested()
                downloadWifiP2pManager?.connectToDevice(target)
            }

            override fun onThisDeviceChanged(device: WifiP2pDevice) {
                Log.i(
                    "DataDownload",
                    "This device changed: ${device.deviceName} - ${device.status}"
                )
            }

            override fun onConnected(info: WifiP2pInfo) {
                Log.i(
                    "DataDownload",
                    "P2P connected: groupFormed=${info.groupFormed}, isGroupOwner=${info.isGroupOwner}"
                )
                onDownloadP2pConnected(info)
            }

            override fun onDisconnected() {
                Log.i("DataDownload", "P2P disconnected")
                downloadP2pConnected = false
                downloadP2pLink?.markGroupFormed(false)
                p2pNetwork.downloadP2pNetwork = null
                p2pNetwork.unbindProcessFromNetwork()

                val shouldRecover = when (downloadFlowMode) {
                    GlassesSyncFlow.OFFICIAL_HEYCYAN -> {
                        !downloadCancelledByUser &&
                            (!downloadInitialPhaseCompleted || downloadAttemptJob?.isActive == true || downloadInProgress)
                    }

                    GlassesSyncFlow.CUSTOM -> {
                        !downloadCancelledByUser &&
                            (isDownloadInitialPhaseActive() || downloadAttemptJob?.isActive == true || downloadInProgress)
                    }
                }
                if (shouldRecover) {
                    if (downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN) {
                        Log.i("DataDownload", "Official flow: P2P disconnected during sync; restarting discovery in 2000ms")
                        transferUi.setTransferDetail("P2P disconnected; retrying official flow...")
                        downloadWifiP2pManager?.discoverPeersStable()
                        officialDisconnectRecoveryJob?.cancel()
                        officialDisconnectRecoveryJob = CoroutineScope(Dispatchers.Main).launch {
                            delay(2000)
                            if (downloadCancelledByUser) return@launch
                            if (downloadP2pConnected) return@launch
                            downloadWifiP2pManager?.startPeerDiscovery()
                        }
                    } else {
                        Log.i("DataDownload", "P2P disconnected during sync; restarting peer discovery")
                        transferUi.setTransferDetail("P2P disconnected; retrying discovery...")
                        downloadWifiP2pManager?.discoverPeersStable()
                        downloadWifiP2pManager?.startPeerDiscovery()
                    }
                }
            }

            override fun onPeerDiscoveryStarted() {
                Log.i("DataDownload", "Peer discovery started")
                if (downloadFlowMode == GlassesSyncFlow.CUSTOM) {
                    // Process one copy of an unchanged peer set in every completed discovery
                    // round. The old cross-round hash suppressed recovery when the same TVs or
                    // printers remained visible.
                    lastPeerSetHash = 0
                }
            }

            override fun onPeerDiscoveryFailed(reason: Int) {
                Log.e("DataDownload", "Peer discovery failed: $reason")
            }

            override fun onConnectRequestSent() {
                Log.i("DataDownload", "Connect request sent")
                adaptiveSyncSession?.mark(AdaptiveSyncCheckpoint.CONNECT_REQUEST_ACCEPTED)
            }

            override fun onConnectRequestFailed(reason: Int) {
                Log.e("DataDownload", "Connect request failed: $reason")
            }

            override fun connecting() {
                Log.i("DataDownload", "Connecting to P2P device...")
            }

            override fun cancelConnect() {
                Log.i("DataDownload", "P2P connection cancelled")
            }

            override fun cancelConnectFail(reason: Int) {
                Log.e("DataDownload", "Cancel connect failed: $reason")
            }

            override fun retryAlsoFailed() {
                if (isHighQualityImageTransfer()) {
                    finishHighQualityImageFailure(
                        "Wi-Fi Direct could not connect to the glasses for the full-resolution image.",
                    )
                    return
                }
                if (downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN && officialFlowRetryCount < officialFlowRetryLimit) {
                    restartOfficialWholeFlow("P2P connection retry failed")
                    return
                }
                adaptiveSyncSession?.mark(
                    AdaptiveSyncCheckpoint.P2P_CONNECT_FAILED,
                    "connect_retry_exhausted",
                )
                setTransferDetailForSession(downloadSessionId, "P2P connect failed; adaptive discovery is continuing...")
                Log.e("DataDownload", "P2P connection retry failed")
            }
        }

        // The shared session registers the receiver and callback and clears the vendor retry
        // state; it first waits for any previous owner's group teardown to be confirmed.
        val sessionId = downloadSessionId
        GlassesNetworkSessionCoordinator.openHeyCyanLinkAsync(
            activity,
            GlassesLinkClient.HEYCYAN_MEDIA_SYNC,
            callback,
        ) { link ->
            if (link == null) {
                if (isDownloadSessionActive(sessionId)) {
                    showDownloadError("Wi-Fi Direct is still busy with another glasses session. Please retry sync.")
                }
                return@openHeyCyanLinkAsync
            }
            if (!isDownloadSessionActive(sessionId) || downloadP2pTeardownInProgress) {
                Log.i("DataDownload", "Sync ended while claiming the glasses link; releasing it")
                GlassesNetworkSessionCoordinator.closeHeyCyanLink(activity, link) {}
                return@openHeyCyanLinkAsync
            }
            downloadP2pLink = link

            // Start scanning for the glasses over WiFi Direct
            link.manager.startPeerDiscovery(
                allowDeviceResetOnTimeout = mode == GlassesSyncFlow.OFFICIAL_HEYCYAN,
            )

            transferUi.setTransferDetail(
                when (mode) {
                    GlassesSyncFlow.OFFICIAL_HEYCYAN -> "Waiting for P2P + BLE IP (HeyCyan flow)..."
                    GlassesSyncFlow.CUSTOM -> "Waiting for glasses IP and HTTP server..."
                }
            )

            // Ask the glasses (over BLE) to bring up WiFi/P2P and report their IP,
            // mirroring the official app's importAlbum() flow.
            sendTransferModeCommandWithRetry(sessionId = sessionId)
            if (mode == GlassesSyncFlow.CUSTOM) {
                startAdaptiveCheckpointRecovery(sessionId)
            }
        }
    }

    /**
     * Send transfer mode [0x02,0x01,0x04]. The adaptive flow retries once without
     * reset and permits a single reset only after a repeated refusal with no progress.
     */
    private fun sendTransferModeCommandWithRetry(
        sessionId: Long,
        attempt: Int = 1,
        maxAttempts: Int = 3,
        delayMs: Long = 2000L,
    ) {
        if (!isDownloadControlActive(sessionId)) {
            Log.i("DataDownload", "Skipping transfer-mode command for inactive session=$sessionId")
            return
        }
        if (downloadFlowMode == GlassesSyncFlow.CUSTOM) {
            val commandReserved = adaptiveSyncSession?.noteTransferCommand(attempt, maxSends = 3) == true
            if (!commandReserved) {
                Log.w("DataDownload", "Adaptive transfer-command budget exhausted; preserving current checkpoint state")
                return
            }
        }
        transferModeCommandTimeoutJob?.cancel()
        transferModeCommandSequence++
        val commandSequence = transferModeCommandSequence
        val commandSentAtMs = System.currentTimeMillis()
        transferModeCommandAttempt = attempt
        transferModeCommandSentAtMs = commandSentAtMs
        transferModeCommandCallbackReceived = false
        if (attempt == 1) {
            transferModeCommandEvidenceReceived = false
        }
        transferModeCommandCallbackDataType = null
        transferModeCommandCallbackError = null
        Log.i(
            "DataDownload",
            "Sending glassesControl[0x02,0x01,0x04] (attempt $attempt/$maxAttempts); " +
                "official callback watchdog=${TRANSFER_MODE_COMMAND_TIMEOUT_MS}ms",
        )
        transferModeCommandTimeoutJob = launchDownloadSession { watchdogSessionId ->
            delay(TRANSFER_MODE_COMMAND_TIMEOUT_MS)
            if (watchdogSessionId != sessionId || !isDownloadControlActive(sessionId)) return@launchDownloadSession
            if (commandSequence != transferModeCommandSequence) return@launchDownloadSession
            if (!HeyCyanP2pPolicy.transferCommandTimedOut(
                    transferModeCommandCallbackReceived,
                    transferModeCommandEvidenceReceived,
                )
            ) return@launchDownloadSession

            Log.w(
                "DataDownload",
                "Transfer mode command did not return within ${TRANSFER_MODE_COMMAND_TIMEOUT_MS}ms " +
                    "and no P2P/BLE-IP evidence arrived (attempt $attempt/$maxAttempts)",
            )
            withContext(Dispatchers.Main) {
                transferUi.setTransferDetail("Glasses did not acknowledge transfer mode")
                if (downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN) {
                    stopOfficialFlowForRetry(
                        "The glasses did not acknowledge transfer mode within 10 seconds.",
                        resetDeviceP2p = false,
                    )
                } else {
                    transferUi.setTransferDetail("No transfer response yet; adaptive recovery is continuing...")
                }
            }
        }
        LargeDataHandler.getInstance().glassesControl(
            byteArrayOf(0x02, 0x01, 0x04)
        ) { _, resp ->
            val isLatestCommand = commandSequence == transferModeCommandSequence
            val callbackLatencyMs = System.currentTimeMillis() - commandSentAtMs
            if (isLatestCommand) {
                transferModeCommandCallbackReceived = true
                transferModeCommandCallbackDataType = resp.dataType
                transferModeCommandCallbackError = resp.errorCode
                transferModeCommandTimeoutJob?.cancel()
                transferModeCommandTimeoutJob = null
                transferModeCommandCallbackLatencyMs = callbackLatencyMs
            }
            Log.i(
                "DataDownload",
                "glassesControl[0x02,0x01,0x04] (attempt $attempt/$maxAttempts) -> " +
                    "dataType=${resp.dataType}, error=${resp.errorCode}, latency=${callbackLatencyMs}ms, " +
                    "sequence=$commandSequence,latest=$isLatestCommand"
            )
            if (downloadFlowMode == GlassesSyncFlow.CUSTOM) {
                adaptiveSyncSession?.mark(
                    AdaptiveSyncCheckpoint.TRANSFER_CALLBACK,
                    "attempt=$attempt,sequence=$commandSequence,latest=$isLatestCommand," +
                        "data_type=${resp.dataType},error=${resp.errorCode},latency_ms=$callbackLatencyMs",
                )
            }
            if (!isLatestCommand) {
                Log.i("DataDownload", "Ignoring stale transfer-mode callback sequence=$commandSequence")
            } else if (!isDownloadControlActive(sessionId)) {
                Log.i("DataDownload", "Ignoring transfer-mode response for inactive session=$sessionId")
            } else if (resp.errorCode == -1 && attempt < maxAttempts) {
                if (
                    downloadFlowMode == GlassesSyncFlow.CUSTOM &&
                    (transferModeCommandEvidenceReceived || downloadP2pConnected || downloadInProgress)
                ) {
                    Log.i(
                        "DataDownload",
                        "Ignoring late transfer-command refusal because adaptive sync already has healthy transport evidence",
                    )
                    return@glassesControl
                }
                Log.w("DataDownload", "Transfer mode command refused (error=-1); retrying after ${delayMs}ms")
                launchDownloadSession { retrySessionId ->
                    if (retrySessionId != sessionId || !isDownloadControlActive(sessionId)) {
                        return@launchDownloadSession
                    }
                    delay(delayMs)
                    if (!isDownloadControlActive(sessionId)) {
                        return@launchDownloadSession
                    }
                    if (
                        downloadFlowMode == GlassesSyncFlow.CUSTOM &&
                        (transferModeCommandEvidenceReceived || downloadP2pConnected || downloadInProgress)
                    ) {
                        Log.i("DataDownload", "Skipping adaptive command retry because transport progressed during backoff")
                        return@launchDownloadSession
                    }
                    if (
                        downloadFlowMode == GlassesSyncFlow.CUSTOM &&
                        (adaptiveSyncSession?.transferCommandSends ?: 0) >= 3
                    ) {
                        Log.i("DataDownload", "Skipping adaptive reset because no transfer-command budget remains")
                        return@launchDownloadSession
                    }
                    // Adaptive flow first retries the command while preserving the current
                    // discovery state. It spends its one glasses reset only after a repeated
                    // refusal; the strict HeyCyan flow keeps the vendor reset behavior.
                    val shouldReset = downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN || attempt > 1
                    if (shouldReset) {
                        if (!resetTransferP2pForRetry(sessionId)) {
                            Log.w("DataDownload", "Pre-retry P2P reset did not complete; not sending another transfer command")
                            return@launchDownloadSession
                        }
                        if (downloadFlowMode == GlassesSyncFlow.CUSTOM) {
                            adaptiveSyncSession?.noteDeviceReset()
                            transferModeCommandEvidenceReceived = false
                        }
                        delay(500)
                    }
                    if (isDownloadControlActive(sessionId)) {
                        sendTransferModeCommandWithRetry(
                            sessionId = sessionId,
                            attempt = attempt + 1,
                            maxAttempts = maxAttempts,
                            delayMs = delayMs,
                        )
                    }
                }
            }
        }
    }

    private suspend fun resetTransferP2pForRetry(sessionId: Long): Boolean {
        if (!isDownloadControlActive(sessionId)) return false
        val resetAccepted = withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                try {
                    LargeDataHandler.getInstance().glassesControl(
                        byteArrayOf(0x02, 0x01, 0x0F),
                    ) { _, response ->
                        Log.d(
                            "DataDownload",
                            "Pre-retry reset [0x02,0x01,0x0F] -> error=${response.errorCode}",
                        )
                        if (continuation.isActive) {
                            // The vendor parser leaves work type 0x0F at its default errorCode=1.
                            // HeyCyan ignores these response fields; callback arrival is completion.
                            continuation.resume(
                                HeyCyanP2pPolicy.resetCallbackAllowsRetry(
                                    callbackReceived = true,
                                    parsedErrorCode = response.errorCode,
                                ),
                            ) {}
                        }
                    }
                } catch (e: Exception) {
                    Log.w("DataDownload", "Failed to send pre-retry P2P reset", e)
                    if (continuation.isActive) continuation.resume(false) {}
                }
            }
        }
        if (resetAccepted == null) {
            Log.w("DataDownload", "Pre-retry P2P reset timed out; retaining the current media session")
            return false
        }
        return resetAccepted && isDownloadControlActive(sessionId)
    }

    private fun setTransferPlan(jpg: Int, mp4: Int, opus: Int) {
        finishDownloadInitialPhase("media list resolved")
        transferUi.setTransferPlan(jpg, mp4, opus)
    }

    private fun createDownloadSession() {
        downloadSessionJob?.cancel()
        val job = SupervisorJob()
        downloadSessionJob = job
        downloadSessionScope = CoroutineScope(job + Dispatchers.IO)
        downloadSessionId++
        vendorAlbumDownloader = if (downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN) {
            VendorAlbumDownloader(
                destinationDir = File(activity.cacheDir, "heycyan_album/$downloadSessionId"),
                requestTag = "cyanbridge_heycyan_album_$downloadSessionId",
            )
        } else {
            null
        }
    }

    private fun cancelDownloadSession() {
        vendorAlbumDownloader?.cancel()
        vendorAlbumDownloader?.clear()
        vendorAlbumDownloader = null
        downloadSessionJob?.cancel()
        downloadSessionJob = null
        downloadSessionScope = null
    }

    private fun isDownloadSessionActive(sessionId: Long): Boolean {
        return sessionId == downloadSessionId &&
            downloadSessionJob?.isActive == true &&
            !downloadCancelledByUser
    }

    private fun isDownloadControlActive(sessionId: Long): Boolean {
        return isDownloadSessionActive(sessionId) &&
            !downloadP2pTeardownInProgress &&
            GlassesSessionCoordinator.currentSession() == GlassesSession.MEDIA_SYNC
    }

    private fun launchDownloadSession(block: suspend CoroutineScope.(Long) -> Unit): Job? {
        val scope = downloadSessionScope ?: return null
        val sessionId = downloadSessionId
        return scope.launch {
            block(sessionId)
        }
    }

    private fun setTransferDetailForSession(sessionId: Long, text: String) {
        if (!isDownloadSessionActive(sessionId)) return
        activity.runOnUiThread {
            if (isDownloadSessionActive(sessionId)) {
                transferUi.setTransferDetail(text)
            }
        }
    }

    private fun maybeReportFileProgress(
        sessionId: Long,
        mediaType: String,
        fileName: String,
        bytesCopied: Long,
        totalBytes: Long,
        startedAtMs: Long,
    ) {
        val now = System.currentTimeMillis()
        if (now - transferUi.lastFileProgressUiAtMs < 750L && bytesCopied < totalBytes) return
        transferUi.lastFileProgressUiAtMs = now

        val speedBps = if (startedAtMs > 0L) {
            val elapsedMs = (now - startedAtMs).coerceAtLeast(1L)
            (bytesCopied * 1000L) / elapsedMs
        } else {
            0L
        }
        val percent = if (totalBytes > 0L) ((bytesCopied * 100L) / totalBytes).coerceIn(0L, 100L) else -1L
        val (typeDone, typeTotal) = when (mediaType) {
            "photo" -> transferUi.transferDoneJpg to transferUi.transferTotalJpg
            "video" -> transferUi.transferDoneMp4 to transferUi.transferTotalMp4
            else -> transferUi.transferDoneOpus to transferUi.transferTotalOpus
        }
        val detail = if (percent >= 0L) {
            "Downloading $mediaType ${typeDone + 1}/$typeTotal: ${percent}% (${TransferProgressPresenter.formatTransferBytes(bytesCopied)}/${TransferProgressPresenter.formatTransferBytes(totalBytes)} at ${TransferProgressPresenter.formatTransferBytes(speedBps)}/s)"
        } else {
            "Downloading $mediaType ${typeDone + 1}/$typeTotal: ${TransferProgressPresenter.formatTransferBytes(bytesCopied)} at ${TransferProgressPresenter.formatTransferBytes(speedBps)}/s"
        }
        if (!isDownloadSessionActive(sessionId)) return
        activity.runOnUiThread {
            if (!isDownloadSessionActive(sessionId)) return@runOnUiThread
            transferUi.renderFileProgress(detail, bytesCopied, totalBytes)
        }
        Log.i(
            "DataDownload",
            "Media progress: type=$mediaType, file=$fileName, bytes=$bytesCopied/$totalBytes, speed=${speedBps}B/s, flow=${downloadFlowMode.label}"
        )
        if (downloadFlowMode == GlassesSyncFlow.CUSTOM && bytesCopied > 0L) {
            adaptiveSyncSession?.mark(
                AdaptiveSyncCheckpoint.MEDIA_FILE_PROGRESS,
                "type=$mediaType,bytes=$bytesCopied,total=$totalBytes",
            )
        }
    }

    private fun startDownloadInitialPhaseWatchdog() {
        downloadInitialPhaseTimeoutJob?.cancel()
        downloadInitialPhaseTimeoutJob = CoroutineScope(Dispatchers.Main).launch {
            val timeoutMs = if (downloadFlowMode == GlassesSyncFlow.CUSTOM) {
                ADAPTIVE_INITIAL_PHASE_TIMEOUT_MS
            } else {
                downloadInitialPhaseTimeoutMs
            }
            delay(timeoutMs)
            val sessionStillStuck = !downloadInitialPhaseCompleted &&
                !downloadCancelledByUser &&
                !downloadInProgress &&
                host.dashboardTransferState().isVisible

            if (!sessionStillStuck) return@launch

            val waitedSeconds = ((System.currentTimeMillis() - downloadStartedAtMs) / 1000L).coerceAtLeast(1L)
            Log.w("DataDownload", "Initial P2P sync phase timed out after ${waitedSeconds}s (flow=${downloadFlowMode.label})")

            if (isHighQualityImageTransfer()) {
                finishHighQualityImageFailure(
                    "Timed out waiting for the glasses Wi-Fi connection for the full-resolution image.",
                )
                return@launch
            }

            if (downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN && officialFlowRetryCount < officialFlowRetryLimit) {
                restartOfficialWholeFlow("initial sync timeout after ${waitedSeconds}s")
                return@launch
            }

            if (downloadFlowMode == GlassesSyncFlow.CUSTOM) {
                val checkpoint = adaptiveSyncSession?.lastCheckpoint?.name ?: "NONE"
                showDownloadError(
                    "Adaptive sync exhausted its bounded recovery budget at checkpoint $checkpoint after ${waitedSeconds}s.",
                    cleanup = true,
                )
                return@launch
            }

            transferUi.setTransferDetail("Sync is taking longer than expected")
            maybeShowP2pSyncLogHelp(
                reason = "CyanBridge got stuck before media transfer started. The sync button was pressed ${waitedSeconds}s ago and the transfer counters never advanced.",
            )
        }
    }

    private fun finishDownloadInitialPhase(reason: String) {
        if (downloadInitialPhaseCompleted) return
        downloadInitialPhaseCompleted = true
        downloadInitialPhaseTimeoutJob?.cancel()
        downloadInitialPhaseTimeoutJob = null
        Log.i("DataDownload", "Initial sync phase completed: $reason (flow=${downloadFlowMode.label})")
    }

    private fun resetDownloadSupportState() {
        downloadInitialPhaseTimeoutJob?.cancel()
        downloadInitialPhaseTimeoutJob = null
        downloadInitialPhaseCompleted = false
        downloadSupportDialogShown = false
        downloadStartedAtMs = System.currentTimeMillis()
        noMatchPeerCount = 0
        downloadP2pRestartCount = 0
        seenP2pPeers.clear()
        lastPeerSetHash = 0
        transferModeCommandTimeoutJob?.cancel()
        transferModeCommandTimeoutJob = null
        transferModeCommandAttempt = 0
        transferModeCommandSequence = 0
        transferModeCommandSentAtMs = 0L
        transferModeCommandCallbackLatencyMs = null
        transferModeCommandCallbackReceived = false
        transferModeCommandEvidenceReceived = false
        transferModeCommandCallbackDataType = null
        transferModeCommandCallbackError = null
        p2pNetwork.selectedDownloadNetworkSummary = "none"
        officialFlowRetryRequired = false
        adaptiveRecoveryJob?.cancel()
        adaptiveRecoveryJob = null
        adaptiveActiveRoute = null
        adaptiveSuccessfulWarmupMs = null
        adaptiveProfileResultRecorded = false
    }

    private fun startAdaptiveSyncSession() {
        val glassesName = runCatching { DeviceManager.getInstance().deviceName }.getOrNull()
        val glassesAddress = runCatching { DeviceManager.getInstance().deviceAddress }.getOrNull()
        val app = runCatching { MyApplication.getInstance() }.getOrNull()
        val key = AdaptiveP2pProfileStore.profileKey(
            phoneManufacturer = Build.MANUFACTURER,
            phoneModel = Build.MODEL,
            androidSdk = Build.VERSION.SDK_INT,
            glassesName = glassesName,
            glassesAddress = glassesAddress,
            hardwareVersion = app?.hardwareVersion,
            firmwareVersion = app?.firmwareVersion,
        )
        val profile = AdaptiveP2pProfileStore.load(activity, key)
        adaptiveSyncSession = AdaptiveP2pSyncSession(
            profileKey = key,
            profile = profile,
            clockMs = android.os.SystemClock::elapsedRealtime,
        ).also { session ->
            session.mark(
                AdaptiveSyncCheckpoint.BLE_READY,
                "learned_route=${profile.preferredRoute?.name ?: profile.candidateRoute?.name ?: "none"}",
            )
        }
        Log.i(
            "DataDownload",
            "Adaptive sync profile=$key preferredRoute=${profile.preferredRoute} candidateRoute=${profile.candidateRoute} " +
                "preferredWarmupMs=${profile.preferredWarmupMs} completed=${profile.completedSyncs}",
        )
    }

    private fun startAdaptiveCheckpointRecovery(sessionId: Long) {
        adaptiveRecoveryJob?.cancel()
        adaptiveRecoveryJob = launchDownloadSession { recoverySessionId ->
            if (recoverySessionId != sessionId) return@launchDownloadSession
            val startedAt = android.os.SystemClock.elapsedRealtime()

            suspend fun waitUntil(offsetMs: Long) {
                val remaining = startedAt + offsetMs - android.os.SystemClock.elapsedRealtime()
                if (remaining > 0L) delay(remaining)
            }

            waitUntil(ADAPTIVE_COMMAND_RETRY_AT_MS)
            if (!isDownloadControlActive(sessionId)) return@launchDownloadSession
            if (!transferModeCommandEvidenceReceived && transferModeCommandAttempt < 2) {
                Log.i("DataDownload", "Adaptive recovery: resending transfer command without P2P reset")
                setTransferDetailForSession(sessionId, "Retrying the glasses transfer command...")
                sendTransferModeCommandWithRetry(
                    sessionId = sessionId,
                    attempt = 2,
                    maxAttempts = 3,
                    delayMs = 2_000L,
                )
            }

            waitUntil(ADAPTIVE_DISCOVERY_RESTART_AT_MS)
            if (!isDownloadControlActive(sessionId)) return@launchDownloadSession
            if (!downloadP2pConnected) {
                Log.i("DataDownload", "Adaptive recovery: restarting Android discovery while preserving glasses state")
                adaptiveSyncSession?.noteDiscoveryRestart()
                downloadP2pRestartCount++
                lastPeerSetHash = 0
                setTransferDetailForSession(sessionId, "Restarting Android Wi-Fi Direct discovery...")
                downloadWifiP2pManager?.restartPeerDiscovery()
            }

            waitUntil(ADAPTIVE_IP_RETRY_AT_MS)
            if (!isDownloadControlActive(sessionId)) return@launchDownloadSession
            if (downloadP2pConnected && downloadBleIp.isNullOrBlank()) {
                val nextAttempt = (transferModeCommandAttempt + 1).coerceIn(2, 3)
                Log.i("DataDownload", "Adaptive recovery: P2P formed without BLE IP; requesting transfer metadata again")
                setTransferDetailForSession(sessionId, "P2P connected; requesting the glasses IP again...")
                sendTransferModeCommandWithRetry(
                    sessionId = sessionId,
                    attempt = nextAttempt,
                    maxAttempts = 3,
                    delayMs = 2_000L,
                )
            }

            waitUntil(ADAPTIVE_DEVICE_RESET_AT_MS)
            if (!isDownloadControlActive(sessionId)) return@launchDownloadSession
            if (!downloadP2pConnected && adaptiveSyncSession?.deviceResets == 0) {
                Log.i("DataDownload", "Adaptive recovery: spending the bounded glasses P2P reset")
                setTransferDetailForSession(sessionId, "Resetting the glasses Wi-Fi Direct state once...")
                if (resetTransferP2pForRetry(sessionId)) {
                    adaptiveSyncSession?.noteDeviceReset()
                    adaptiveSyncSession?.noteDiscoveryRestart()
                    transferModeCommandEvidenceReceived = false
                    downloadP2pRestartCount++
                    lastPeerSetHash = 0
                    downloadWifiP2pManager?.restartPeerDiscovery()
                    delay(500L)
                    if (isDownloadControlActive(sessionId)) {
                        sendTransferModeCommandWithRetry(
                            sessionId = sessionId,
                            attempt = 3,
                            maxAttempts = 3,
                            delayMs = 2_000L,
                        )
                    }
                }
            }
        }
    }

    private fun resetOfficialFlowState() {
        officialSystemSuccess = false
        officialBleCallbackSuccess = false
        officialMediaErrorCount = 0
    }

    fun isOfficialSyncActive(): Boolean {
        return downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN &&
            !downloadCancelledByUser &&
            host.dashboardTransferState().isVisible
    }

    private fun restartOfficialWholeFlow(reason: String) {
        val nextRetry = officialFlowRetryCount + 1
        Log.w(
            "DataDownload",
            "Official flow retrying whole import sequence ($nextRetry/$officialFlowRetryLimit) because $reason"
        )
        transferUi.setTransferDetail("Official flow retrying sync...")
        startDataDownload(
            mode = GlassesSyncFlow.OFFICIAL_HEYCYAN,
            retryCount = nextRetry,
            isRetry = true,
        )
    }

    private fun maybeShowP2pSyncLogHelp(
        reason: String,
        title: String = "P2P sync is stuck",
        dismissButtonLabel: String = "Later",
    ) {
        if (downloadSupportDialogShown || activity.isFinishing || activity.isDestroyed) return
        downloadSupportDialogShown = true
        DebugLogSupport.showSupportOptionsDialog(
            activity = activity,
            title = "$title (${downloadFlowMode.label})",
            issueType = "P2P/WiFi sync issue",
            description = "Flow: ${downloadFlowMode.label}\n\n$reason",
            extraInfo = linkedMapOf(
                "transfer_detail" to host.dashboardTransferState().detail,
                "transfer_counts" to host.dashboardTransferState().countsLabel,
                "download_flow_mode" to downloadFlowMode.label,
                "download_ble_ip" to (downloadBleIp ?: ""),
                "download_wifi_ip" to (downloadWifiIp ?: ""),
                "download_http_ip" to (downloadResolvedHttpIp ?: ""),
                "download_p2p_connected" to downloadP2pConnected.toString(),
                "download_in_progress" to downloadInProgress.toString(),
                "download_phone_is_group_owner" to (downloadPhoneIsGroupOwner?.toString() ?: "unknown"),
                "transfer_mode_command_attempt" to transferModeCommandAttempt.toString(),
                "transfer_mode_command_sequence" to transferModeCommandSequence.toString(),
                "transfer_mode_callback_received" to transferModeCommandCallbackReceived.toString(),
                "transfer_mode_callback_latency_ms" to (transferModeCommandCallbackLatencyMs?.toString() ?: ""),
                "transfer_mode_callback_data_type" to (transferModeCommandCallbackDataType?.toString() ?: ""),
                "transfer_mode_callback_error" to (transferModeCommandCallbackError?.toString() ?: ""),
                "transfer_mode_evidence_received" to transferModeCommandEvidenceReceived.toString(),
                "expected_official_p2p_name" to GlassesP2pPeerSelector.expectedOfficialP2pName(),
                "selected_download_network" to p2pNetwork.selectedDownloadNetworkSummary,
                "seen_p2p_peers" to seenP2pPeers.joinToString(", "),
                "active_glasses_session" to (GlassesSessionCoordinator.currentSession()?.name ?: "none"),
                "ota_active" to host.isOtaActive().toString(),
                "adaptive_route" to (adaptiveActiveRoute?.name ?: ""),
                "adaptive_warmup_ms" to (adaptiveSuccessfulWarmupMs?.toString() ?: ""),
                "adaptive_profile_key" to (adaptiveSyncSession?.profileKey ?: ""),
                "adaptive_command_sends" to (adaptiveSyncSession?.transferCommandSends?.toString() ?: "0"),
                "adaptive_discovery_restarts" to (adaptiveSyncSession?.discoveryRestarts?.toString() ?: "0"),
                "adaptive_device_resets" to (adaptiveSyncSession?.deviceResets?.toString() ?: "0"),
                "vpn_active" to p2pNetwork.isVpnActive().toString(),
                "official_heycyan_installed" to OfficialHeyCyanApp.isInstalled(activity).toString(),
                "adaptive_trace" to (adaptiveSyncSession?.traceSummary() ?: ""),
            ),
            dismissButtonLabel = dismissButtonLabel,
        )
    }
    
    private suspend fun downloadMediaList(deviceIp: String, sessionId: Long) {
        if (!isDownloadSessionActive(sessionId)) return
        if (downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN) {
            downloadVendorMediaList(deviceIp, sessionId)
            return
        }
        try {
            coroutineContext.ensureActive()
            // Lock the device IP for the whole transfer session.
            downloadResolvedHttpIp = deviceIp
            val url = "http://$deviceIp/files/media.config"
            Log.i("DataDownload", "Downloading media list from: $url")

            withContext(Dispatchers.Main) {
                if (!isDownloadSessionActive(sessionId)) return@withContext
                transferUi.showIndeterminateProgress()
                transferUi.setTransferDetail("Fetching media list...")
            }

            var content: String? = null
            p2pNetwork.httpGet(URL(url), 10000, 30000) { stream, _ ->
                content = stream.bufferedReader().use { it.readText() }
            }

            coroutineContext.ensureActive()
            if (!isDownloadSessionActive(sessionId)) return

            content?.let { mediaConfig ->
                Log.i("DataDownload", "=== MEDIA CONFIG CONTENT ===")
                Log.i("DataDownload", mediaConfig)
                Log.i("DataDownload", "=== END MEDIA CONFIG ===")
                parseMediaList(mediaConfig, deviceIp, sessionId)
            } ?: run {
                Log.e("DataDownload", "Failed to download media list.")
                withContext(Dispatchers.Main) {
                    if (isDownloadSessionActive(sessionId)) {
                        showDownloadError("Failed to download media list.")
                    }
                }
            }
        } catch (e: CancellationException) {
            Log.i("DataDownload", "Media list download cancelled for session=$sessionId")
            throw e
        } catch (e: Exception) {
            if (!isDownloadSessionActive(sessionId)) return
            Log.e("DataDownload", "Error downloading media list: ${e.message}", e)
            withContext(Dispatchers.Main) {
                if (!isDownloadSessionActive(sessionId)) return@withContext
                when (e) {
                    is java.io.IOException -> {
                        if (e.message?.contains("Cleartext HTTP traffic") == true) {
                            showDownloadError("Network security blocked HTTP connection. Please check app settings.")
                        } else if (e.message?.contains("Failed to connect") == true) {
                            showDownloadError("Cannot connect to glasses device. Please ensure P2P connection is established.")
                        } else {
                            showDownloadError("Network error: ${e.message}")
                        }
                    }
                    else -> showDownloadError("Download failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun downloadVendorMediaList(deviceIp: String, sessionId: Long) {
        val downloader = vendorAlbumDownloader
        if (downloader == null) {
            withContext(Dispatchers.Main) {
                if (isDownloadSessionActive(sessionId)) {
                    showDownloadError("HeyCyan downloader was not initialized.")
                }
            }
            return
        }

        val url = "http://$deviceIp/files/media.config"
        Log.i("DataDownload", "HeyCyan vendor downloader fetching media list: $url")
        withContext(Dispatchers.Main) {
            if (isDownloadSessionActive(sessionId)) {
                transferUi.showIndeterminateProgress()
                transferUi.setTransferDetail("Fetching media list with HeyCyan downloader...")
            }
        }

        var lastError = "unknown error"
        repeat(2) { attemptIndex ->
            coroutineContext.ensureActive()
            if (!isDownloadSessionActive(sessionId)) return
            val attempt = attemptIndex + 1
            val result = downloader.download(url, "media.config")
            if (result.isSuccess) {
                val configFile = result.file ?: return
                val content = runCatching { configFile.readText() }.getOrNull()
                configFile.delete()
                if (content != null) {
                    Log.i("DataDownload", "HeyCyan vendor downloader fetched media.config on attempt $attempt/2")
                    parseMediaList(content, deviceIp, sessionId)
                    return
                }
                lastError = "downloaded media.config could not be read"
            } else {
                lastError = "code=${result.errorCode}, detail=${result.errorDetail}"
                Log.w(
                    "DataDownload",
                    "HeyCyan media.config download failed (attempt $attempt/2): $lastError"
                )
            }
        }

        withContext(Dispatchers.Main) {
            if (isDownloadSessionActive(sessionId)) {
                showDownloadError("Failed to download media list with HeyCyan downloader: $lastError")
            }
        }
    }
    
        private suspend fun parseMediaList(content: String, deviceIp: String, sessionId: Long) {
            // Parse the media configuration file content - this is a text file containing media file names.
            Log.i("DataDownload", "Parsing media list content...")
            
            try {
                coroutineContext.ensureActive()
                if (!isDownloadSessionActive(sessionId)) return
                // Split by line, each line should be a file name
                val lines = content.trim().lines()
                val jpgFiles = mutableListOf<String>()
                val mp4Files = mutableListOf<String>()
                val opusFiles = mutableListOf<String>()
                val vendorQueue = mutableListOf<VendorMediaItem>()
                var otherFiles = 0
                
                lines.forEach { line ->
                    val trimmedLine = line.trim()
                    if (trimmedLine.isNotEmpty()) {
                        when {
                            trimmedLine.endsWith(".jpg", ignoreCase = true) ||
                                trimmedLine.endsWith(".jpeg", ignoreCase = true) -> {
                                jpgFiles.add(trimmedLine)
                                vendorQueue.add(VendorMediaItem(trimmedLine, VendorMediaType.PHOTO))
                                Log.i("DataDownload", "Found JPG file: $trimmedLine")
                            }

                            trimmedLine.endsWith(".mp4", ignoreCase = true) -> {
                                mp4Files.add(trimmedLine)
                                vendorQueue.add(VendorMediaItem(trimmedLine, VendorMediaType.VIDEO))
                                Log.i("DataDownload", "Found MP4 file: $trimmedLine")
                            }

                            trimmedLine.endsWith(".opus", ignoreCase = true) -> {
                                opusFiles.add(trimmedLine)
                                vendorQueue.add(VendorMediaItem(trimmedLine, VendorMediaType.AUDIO))
                                Log.i("DataDownload", "Found OPUS file: $trimmedLine")
                            }

                            else -> {
                                otherFiles++
                                Log.i("DataDownload", "Found other file: $trimmedLine")
                            }
                        }
                    }
                }

                Log.i(
                    "DataDownload",
                    "Media list parsed: jpg=${jpgFiles.size}, mp4=${mp4Files.size}, opus=${opusFiles.size}, other=$otherFiles"
                )

                if (isHighQualityImageTransfer()) {
                    if (jpgFiles.isEmpty()) {
                        finishHighQualityImageFailure(
                            "The glasses did not report a full-resolution JPG in media.config.",
                        )
                    } else {
                        downloadLatestHighQualityImage(jpgFiles, deviceIp, sessionId)
                    }
                    return
                }

                withContext(Dispatchers.Main) {
                    if (!isDownloadSessionActive(sessionId)) return@withContext
                    setTransferPlan(jpgFiles.size, mp4Files.size, opusFiles.size)
                    val total = jpgFiles.size + mp4Files.size + opusFiles.size
                    transferUi.setTransferDetail("Preparing downloads (0/$total)...")
                }

                if (jpgFiles.isEmpty() && mp4Files.isEmpty() && opusFiles.isEmpty()) {
                    Log.w("DataDownload", "No JPG/MP4/OPUS files found in media.config")
                    withContext(Dispatchers.Main) {
                        if (isDownloadSessionActive(sessionId)) {
                            showDownloadError("No JPG/MP4/OPUS files found in media.config")
                        }
                    }
                    return
                }

                withContext(Dispatchers.Main) {
                    if (isDownloadSessionActive(sessionId)) {
                        Toast.makeText(
                            activity,
                            "Transferring media files. Please do not close or exit the app during transfer.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                // Download everything we understand. Keep P2P bound until all downloads finish.
                if (downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN) {
                    downloadAllMediaFilesVendor(vendorQueue, deviceIp, sessionId)
                } else {
                    downloadAllMediaFiles(jpgFiles, mp4Files, opusFiles, deviceIp, sessionId)
                }
                
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isDownloadSessionActive(sessionId)) return
                Log.e("DataDownload", "Error parsing media list: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    if (isDownloadSessionActive(sessionId)) {
                        showDownloadError("Failed to parse media list: ${e.message}")
                    }
                }
            }
        }

    private suspend fun downloadLatestHighQualityImage(
        jpgFiles: List<String>,
        deviceIp: String,
        sessionId: Long,
    ) {
        val request = highQualityImageRequest ?: return
        val latestFileName = jpgFiles.maxWithOrNull(
            compareBy<String> { GlassesMediaLibrary.parseTakenTimeMillisFromFilename(it) ?: Long.MIN_VALUE }
                .thenBy { it },
        ) ?: run {
            finishHighQualityImageFailure("No full-resolution JPG was available for this image question.")
            return
        }

        val outputDir = (activity.getExternalFilesDir("image-questions") ?: File(activity.filesDir, "image-questions"))
            .apply { mkdirs() }
        val safeName = File(latestFileName).name
        val output = File(outputDir, "AI_Full_${System.currentTimeMillis()}_$safeName")
        val partial = File(output.parentFile, "${output.name}.part")
        val url = URL("http://$deviceIp/files/$latestFileName")
        val downloadStartedAtMs = System.currentTimeMillis()

        withContext(Dispatchers.Main) {
            if (isDownloadSessionActive(sessionId)) {
                transferUi.setTransferDetail("Downloading latest full-resolution photo...")
            }
        }
        Log.i("ImageQuestion", "Requesting full-resolution HeyCyan photo: $url")

        val downloaded = runCatching {
            partial.delete()
            p2pNetwork.httpGet(url, connectTimeoutMs = 15_000, readTimeoutMs = 60_000) { input, _ ->
                partial.outputStream().buffered(128 * 1024).use { outputStream ->
                    input.copyTo(outputStream, bufferSize = 128 * 1024)
                }
            }
        }.getOrElse { error ->
            Log.e("ImageQuestion", "Full-resolution image download failed", error)
            false
        }

        if (!downloaded || !partial.exists() || partial.length() <= 0L) {
            partial.delete()
            finishHighQualityImageFailure("Could not download the latest full-resolution photo from the glasses.")
            return
        }
        if (!partial.renameTo(output)) {
            partial.delete()
            finishHighQualityImageFailure("Could not prepare the full-resolution photo for the AI provider.")
            return
        }
        if (!isDownloadSessionActive(sessionId) || !host.isDecodableImageFile(output)) {
            output.delete()
            finishHighQualityImageFailure("The full-resolution photo transfer was incomplete or invalid.")
            return
        }

        val transferDurationMs = System.currentTimeMillis() - request.captureStartedAtMs
        completeHighQualityImageTransfer(output, transferDurationMs)
    }

    private fun completeHighQualityImageTransfer(file: File, transferDurationMs: Long) {
        if (!isHighQualityImageTransfer()) {
            file.delete()
            return
        }
        highQualityImageRequest = null
        recordAdaptiveSyncSuccess()
        finishDownloadInitialPhase("full-resolution image downloaded")
        teardownDownloadP2pSession(
            sendExitTransfer = true,
            hideTransferUi = true,
            onTeardownComplete = {
                mediaDownloadPurpose = MediaDownloadPurpose.FULL_SYNC
                activity.runOnUiThread {
                    host.onImageReadyForQuestion(
                        imagePath = file.absolutePath,
                        source = ImageQuestionSource.HIGH_QUALITY,
                        transferDurationMs = transferDurationMs,
                    )
                }
            },
        )
    }

    private fun finishHighQualityImageFailure(reason: String) {
        val request = highQualityImageRequest ?: return
        recordAdaptiveSyncFailure(reason)
        host.cancelParallelAudioQuestion()
        activity.runOnUiThread {
            if (!isHighQualityImageTransfer()) return@runOnUiThread
            Log.e("ImageQuestion", "Full-resolution retrieval failed: $reason")
            finishDownloadInitialPhase("full-resolution image failed")
            teardownDownloadP2pSession(
                sendExitTransfer = true,
                hideTransferUi = true,
                onTeardownComplete = {
                    mediaDownloadPurpose = MediaDownloadPurpose.FULL_SYNC
                    activity.runOnUiThread { showHighQualityImageFailureDialog(request, reason) }
                },
            )
        }
    }

    private fun showHighQualityImageFailureDialog(
        request: HighQualityImageRequest,
        reason: String,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        check(
            ImageQuestionSourcePolicy.onHighQualityFailure() ==
                com.fersaiyan.cyanbridge.ai.image.ImageSourceResolution.AWAITING_EXPLICIT_FALLBACK_CHOICE,
        )
        AlertDialog.Builder(activity)
            .setTitle("High-quality image unavailable")
            .setMessage("$reason\n\nCyanBridge has not sent a preview automatically.")
            .setPositiveButton("Retry high quality") { _, _ ->
                when (ImageQuestionSourcePolicy.resolveHighQualityFailure(HighQualityFailureChoice.RETRY_HIGH_QUALITY)) {
                    com.fersaiyan.cyanbridge.ai.image.ImageSourceResolution.HIGH_QUALITY -> {
                        highQualityImageRequest = request
                        host.pendingImageQuestionSource = ImageQuestionSource.HIGH_QUALITY
                        requestHighQualityImageForQuestion(request.sourceTag)
                    }
                    else -> Unit
                }
            }
            .setNegativeButton("Use fast preview") { _, _ ->
                if (
                    ImageQuestionSourcePolicy.resolveHighQualityFailure(HighQualityFailureChoice.USE_FAST_PREVIEW) ==
                    com.fersaiyan.cyanbridge.ai.image.ImageSourceResolution.FAST_PREVIEW
                ) {
                    highQualityImageRequest = null
                    host.pendingImageQuestionSource = ImageQuestionSource.FAST_PREVIEW
                    host.requestImageThumbnailForQuestion(request.sourceTag)
                }
            }
            .setNeutralButton("Cancel") { _, _ ->
                if (
                    ImageQuestionSourcePolicy.resolveHighQualityFailure(HighQualityFailureChoice.CANCEL) ==
                    com.fersaiyan.cyanbridge.ai.image.ImageSourceResolution.CANCELLED
                ) {
                    highQualityImageRequest = null
                    host.clearPendingVoiceImageQuestion(request.sourceTag)
                }
            }
            .show()
    }

    private suspend fun downloadAllMediaFilesVendor(
        files: List<VendorMediaItem>,
        deviceIp: String,
        sessionId: Long,
    ) {
        val downloader = vendorAlbumDownloader ?: return
        Log.i("DataDownload", "Starting HeyCyan vendor media queue: files=${files.size}")
        officialMediaErrorCount = 0

        for (item in files) {
            coroutineContext.ensureActive()
            if (!isDownloadSessionActive(sessionId)) return

            var imported = false
            for (attempt in 1..2) {
                if (imported || !isDownloadSessionActive(sessionId) || officialMediaErrorCount > 1) break
                coroutineContext.ensureActive()

                val url = "http://$deviceIp/files/${item.fileName}"
                val startedAtMs = System.currentTimeMillis()
                withContext(Dispatchers.Main) {
                    if (isDownloadSessionActive(sessionId)) {
                        val (done, total) = vendorTypeProgress(item.type)
                        transferUi.setTransferDetail(
                            "Downloading ${item.type.progressLabel} ${done + 1}/$total with HeyCyan downloader..."
                        )
                    }
                }
                Log.i(
                    "DataDownload",
                    "HeyCyan queue downloading ${item.fileName} (attempt $attempt/2): $url"
                )

                val result = downloader.download(url, item.fileName) { downloaded, total ->
                    maybeReportFileProgress(
                        sessionId = sessionId,
                        mediaType = item.type.progressLabel,
                        fileName = item.fileName,
                        bytesCopied = downloaded,
                        totalBytes = total,
                        startedAtMs = startedAtMs,
                    )
                }

                if (result.isSuccess) {
                    val file = result.file
                    imported = file != null && mediaLibrary.importVendorMediaFile(file, item)
                    file?.delete()
                    if (imported) {
                        Log.i("DataDownload", "HeyCyan queue imported: ${item.fileName}")
                    } else {
                        officialMediaErrorCount++
                        Log.e(
                            "DataDownload",
                            "HeyCyan queue could not import ${item.fileName}; errors=$officialMediaErrorCount"
                        )
                    }
                } else {
                    officialMediaErrorCount++
                    Log.w(
                        "DataDownload",
                        "HeyCyan queue download failed for ${item.fileName} (attempt $attempt/2, errors=$officialMediaErrorCount): code=${result.errorCode}, detail=${result.errorDetail}"
                    )
                }

                if (!imported && officialMediaErrorCount <= 1) {
                    withContext(Dispatchers.Main) {
                        if (isDownloadSessionActive(sessionId)) {
                            transferUi.setTransferDetail("Download failed; retrying ${item.fileName}...")
                        }
                    }
                }
            }

            if (!imported) {
                withContext(Dispatchers.Main) {
                    if (isDownloadSessionActive(sessionId)) {
                        showDownloadError(
                            "HeyCyan flow stopped after repeated media download failures. Please retry sync."
                        )
                    }
                }
                return
            }

            withContext(Dispatchers.Main) {
                if (!isDownloadSessionActive(sessionId)) return@withContext
                transferUi.onTransferItemDone(
                    when (item.type) {
                        VendorMediaType.PHOTO -> "jpg"
                        VendorMediaType.VIDEO -> "mp4"
                        VendorMediaType.AUDIO -> "opus"
                    }
                )
                transferUi.setTransferDetail("Downloaded ${transferUi.transferItemSummary()}")
            }
        }

        withContext(Dispatchers.Main) {
            if (isDownloadSessionActive(sessionId)) {
                showDownloadSuccess("All ${files.size} files downloaded successfully!")
            }
        }
    }

    private fun vendorTypeProgress(type: VendorMediaType): Pair<Int, Int> {
        return when (type) {
            VendorMediaType.PHOTO -> transferUi.transferDoneJpg to transferUi.transferTotalJpg
            VendorMediaType.VIDEO -> transferUi.transferDoneMp4 to transferUi.transferTotalMp4
            VendorMediaType.AUDIO -> transferUi.transferDoneOpus to transferUi.transferTotalOpus
        }
    }

    private suspend fun downloadAllMediaFiles(
        jpgFiles: List<String>,
        mp4Files: List<String>,
        opusFiles: List<String>,
        deviceIp: String,
        sessionId: Long,
    ) {
            Log.i(
                "DataDownload",
                "Starting download: jpg=${jpgFiles.size}, mp4=${mp4Files.size}, opus=${opusFiles.size}"
            )

            val totalAll = jpgFiles.size + mp4Files.size + opusFiles.size
            withContext(Dispatchers.Main) {
                if (!isDownloadSessionActive(sessionId)) return@withContext
                if (totalAll > 0) {
                    transferUi.showDeterminateProgressStart()
                }
                transferUi.setTransferDetail("Downloading 0/$totalAll...")
            }
            
            var jpgSuccess = 0
            var jpgFail = 0
            var mp4Success = 0
            var mp4Fail = 0
            var opusSuccess = 0
            var opusFail = 0
            
            for ((index, fileName) in jpgFiles.withIndex()) {
                try {
                    coroutineContext.ensureActive()
                    if (!isDownloadSessionActive(sessionId)) return
                    withContext(Dispatchers.Main) {
                        if (!isDownloadSessionActive(sessionId)) return@withContext
                        transferUi.setTransferDetail("Downloading photo ${index + 1}/${jpgFiles.size}...")
                    }
                    Log.i("DataDownload", "Downloading file ${index + 1}/${jpgFiles.size}: $fileName")

                    val success = downloadSingleJpgFile(fileName, deviceIp)
                    if (success) {
                        jpgSuccess++
                        Log.i("DataDownload", "✓ Successfully downloaded: $fileName")
                    } else {
                        jpgFail++
                        Log.e("DataDownload", "✗ Failed to download: $fileName")
                    }

                    withContext(Dispatchers.Main) {
                        if (!isDownloadSessionActive(sessionId)) return@withContext
                        transferUi.onTransferItemDone("jpg")
                        transferUi.setTransferDetail("Downloaded ${transferUi.transferItemSummary()}")
                    }
                    
                    // Add a small delay to avoid excessively fast requests
                    delay(500)
                    
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isDownloadSessionActive(sessionId)) return
                    jpgFail++
                    Log.e("DataDownload", "Error downloading $fileName: ${e.message}", e)

                    withContext(Dispatchers.Main) {
                        if (!isDownloadSessionActive(sessionId)) return@withContext
                        transferUi.onTransferItemDone("jpg")
                        transferUi.setTransferDetail("Downloaded ${transferUi.transferItemSummary()} (with errors)")
                    }
                }
            }

            for ((index, fileName) in mp4Files.withIndex()) {
                try {
                    coroutineContext.ensureActive()
                    if (!isDownloadSessionActive(sessionId)) return
                    withContext(Dispatchers.Main) {
                        if (!isDownloadSessionActive(sessionId)) return@withContext
                        transferUi.setTransferDetail("Downloading video ${index + 1}/${mp4Files.size}...")
                    }
                    Log.i("DataDownload", "Downloading video ${index + 1}/${mp4Files.size}: $fileName")

                    val success = downloadSingleMp4File(fileName, deviceIp, sessionId)
                    if (success) {
                        mp4Success++
                        Log.i("DataDownload", "✓ Successfully downloaded: $fileName")
                    } else {
                        mp4Fail++
                        Log.e("DataDownload", "✗ Failed to download: $fileName")
                        if (shouldAbortOfficialMediaTransfer(fileName)) {
                            withContext(Dispatchers.Main) {
                                if (isDownloadSessionActive(sessionId)) {
                                    showDownloadError("Official flow stopped after repeated media download failures. Please retry sync.")
                                }
                            }
                            return
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (!isDownloadSessionActive(sessionId)) return@withContext
                        transferUi.onTransferItemDone("mp4")
                        transferUi.setTransferDetail("Downloaded ${transferUi.transferItemSummary()}")
                    }

                    // Videos are larger; be gentler.
                    delay(800)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isDownloadSessionActive(sessionId)) return
                    mp4Fail++
                    Log.e("DataDownload", "Error downloading $fileName: ${e.message}", e)
                    if (shouldAbortOfficialMediaTransfer(fileName)) {
                        withContext(Dispatchers.Main) {
                            if (isDownloadSessionActive(sessionId)) {
                                showDownloadError("Official flow stopped after repeated media download failures. Please retry sync.")
                            }
                        }
                        return
                    }

                    withContext(Dispatchers.Main) {
                        if (!isDownloadSessionActive(sessionId)) return@withContext
                        transferUi.onTransferItemDone("mp4")
                        transferUi.setTransferDetail("Downloaded ${transferUi.transferItemSummary()} (with errors)")
                    }
                }
            }

            for ((index, fileName) in opusFiles.withIndex()) {
                try {
                    coroutineContext.ensureActive()
                    if (!isDownloadSessionActive(sessionId)) return
                    withContext(Dispatchers.Main) {
                        if (!isDownloadSessionActive(sessionId)) return@withContext
                        transferUi.setTransferDetail("Downloading audio ${index + 1}/${opusFiles.size}...")
                    }
                    Log.i("DataDownload", "Downloading audio ${index + 1}/${opusFiles.size}: $fileName")

                    val success = downloadSingleOpusFile(fileName, deviceIp)
                    if (success) {
                        opusSuccess++
                        Log.i("DataDownload", "✓ Successfully downloaded: $fileName")
                    } else {
                        opusFail++
                        Log.e("DataDownload", "✗ Failed to download: $fileName")
                    }

                    withContext(Dispatchers.Main) {
                        if (!isDownloadSessionActive(sessionId)) return@withContext
                        transferUi.onTransferItemDone("opus")
                        transferUi.setTransferDetail("Downloaded ${transferUi.transferItemSummary()}")
                    }

                    delay(500)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isDownloadSessionActive(sessionId)) return
                    opusFail++
                    Log.e("DataDownload", "Error downloading $fileName: ${e.message}", e)

                    withContext(Dispatchers.Main) {
                        if (!isDownloadSessionActive(sessionId)) return@withContext
                        transferUi.onTransferItemDone("opus")
                        transferUi.setTransferDetail("Downloaded ${transferUi.transferItemSummary()} (with errors)")
                    }
                }
            }
            
            // Show final result
            val totalSuccess = jpgSuccess + mp4Success + opusSuccess
            val totalFail = jpgFail + mp4Fail + opusFail
            Log.i(
                "DataDownload",
                "Download completed: jpg=$jpgSuccess/${jpgFiles.size} ok, mp4=$mp4Success/${mp4Files.size} ok, opus=$opusSuccess/${opusFiles.size} ok, failed=$totalFail"
            )
            
            withContext(Dispatchers.Main) {
                if (!isDownloadSessionActive(sessionId)) return@withContext
                if (totalFail == 0) {
                    showDownloadSuccess("All $totalSuccess files downloaded successfully!")
                } else {
                    showDownloadError("Download completed with errors: $totalSuccess successful, $totalFail failed")
                }
            }
    }

    private fun shouldAbortOfficialMediaTransfer(fileName: String): Boolean {
        if (downloadFlowMode != GlassesSyncFlow.OFFICIAL_HEYCYAN) return false
        officialMediaErrorCount++
        Log.w(
            "DataDownload",
            "Official flow media error $officialMediaErrorCount/2 for $fileName; aborting=${officialMediaErrorCount > 1}"
        )
        return officialMediaErrorCount > 1
    }
    
    private suspend fun downloadSingleJpgFile(fileName: String, deviceIp: String): Boolean {
        return retryAdaptiveMediaFile(fileName) {
            downloadSingleJpgFileOnce(fileName, deviceIp)
        }
    }

    private suspend fun downloadSingleJpgFileOnce(fileName: String, deviceIp: String): Boolean {
        return try {
            val url = "http://$deviceIp/files/$fileName"
            Log.i("DataDownload", "Downloading: $url")

            var saved: GallerySaveResult? = null
            p2pNetwork.httpGet(URL(url), 10000, 30000) { stream, contentLength ->
                val takenMs = GlassesMediaLibrary.parseTakenTimeMillisFromFilename(fileName) ?: System.currentTimeMillis()
                saved = mediaLibrary.saveJpegToGallery(stream, fileName, takenMs, contentLength)
            }

            val savedResult = saved
            if (savedResult != null && savedResult.bytes > 0) {
                Log.i("DataDownload", "File downloaded: $fileName (${savedResult.bytes} bytes)")
            }
            if (savedResult?.success == true) {
                Log.i("DataDownload", "Saved to gallery: name=$fileName uri=${savedResult.uri}")
                true
            } else {
                Log.e("DataDownload", "Failed to download/save: $fileName")
                false
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("DataDownload", "Error downloading $fileName: ${e.message}", e)
            false
        }
    }

    private suspend fun downloadSingleMp4File(fileName: String, deviceIp: String, sessionId: Long): Boolean {
        return retryAdaptiveMediaFile(fileName) {
            downloadSingleMp4FileOnce(fileName, deviceIp, sessionId)
        }
    }

    private suspend fun downloadSingleMp4FileOnce(fileName: String, deviceIp: String, sessionId: Long): Boolean {
        return try {
            val url = "http://$deviceIp/files/$fileName"
            Log.i("DataDownload", "Downloading: $url")

            var saved: GallerySaveResult? = null
            val startedAtMs = System.currentTimeMillis()
            p2pNetwork.httpGet(URL(url), 15000, 180000) { stream, contentLength ->
                val takenMs = GlassesMediaLibrary.parseTakenTimeMillisFromFilename(fileName) ?: System.currentTimeMillis()
                saved = mediaLibrary.saveMp4ToGallery(stream, fileName, takenMs, contentLength) { bytesCopied, totalBytes ->
                    maybeReportFileProgress(
                        sessionId = sessionId,
                        mediaType = "video",
                        fileName = fileName,
                        bytesCopied = bytesCopied,
                        totalBytes = totalBytes,
                        startedAtMs = startedAtMs,
                    )
                }
            }

            val savedResult = saved
            if (savedResult != null && savedResult.bytes > 0) {
                Log.i("DataDownload", "File downloaded: $fileName (${savedResult.bytes} bytes)")
            }
            if (savedResult?.success == true) {
                Log.i("DataDownload", "Saved to gallery: name=$fileName uri=${savedResult.uri}")
                true
            } else {
                Log.e("DataDownload", "Failed to download/save: $fileName")
                false
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("DataDownload", "Error downloading $fileName: ${e.message}", e)
            false
        }
    }

    private suspend fun downloadSingleOpusFile(fileName: String, deviceIp: String): Boolean {
        return retryAdaptiveMediaFile(fileName) {
            downloadSingleOpusFileOnce(fileName, deviceIp)
        }
    }

    private suspend fun downloadSingleOpusFileOnce(fileName: String, deviceIp: String): Boolean {
        return try {
            val url = "http://$deviceIp/files/$fileName"
            Log.i("DataDownload", "Downloading: $url")

            var saved: GallerySaveResult? = null
            var payloadBytes: ByteArray? = null
            var rawBytesSize = 0
            var payloadNote = "raw"
            val takenMs = GlassesMediaLibrary.parseTakenTimeMillisFromFilename(fileName) ?: System.currentTimeMillis()
            p2pNetwork.httpGet(URL(url), 15000, 120000) { stream, contentLength ->
                val rawBytes = GlassesMediaLibrary.readAllBytes(stream)
                if (rawBytes.isEmpty()) {
                    throw java.io.EOFException("empty response body")
                }
                if (contentLength >= 0L && rawBytes.size.toLong() != contentLength) {
                    throw java.io.EOFException(
                        "truncated response body ${rawBytes.size}/$contentLength bytes",
                    )
                }
                rawBytesSize = rawBytes.size
                val wrapped = OggOpusWrapper.wrapOpusIfNeeded(rawBytes)
                payloadBytes = wrapped.first
                payloadNote = wrapped.second
                saved = mediaLibrary.saveOpusToLibrary(
                    payloadBytes = wrapped.first,
                    rawBytesSize = rawBytes.size,
                    payloadNote = wrapped.second,
                    displayName = fileName,
                    takenTimeMs = takenMs,
                )
            }

            val savedResult = saved
            if (savedResult != null && savedResult.bytes > 0) {
                Log.i("DataDownload", "File downloaded: $fileName (${savedResult.bytes} bytes)")
            }
            if (savedResult?.success == true) {
                payloadBytes?.let { bytes ->
                    runCatching {
                        val persisted = GlassesSyncedAudioIngestor.persistDownloadedAudio(
                            context = activity.applicationContext,
                            displayName = fileName,
                            payloadBytes = bytes,
                            takenTimeMs = takenMs,
                        )
                        if (persisted.createdSessionId != null) {
                            Log.i(
                                "DataDownload",
                                "Synced audio persisted for recordings/transcription: sessionId=${persisted.createdSessionId} path=${persisted.localPath}"
                            )
                        }
                    }.onFailure {
                        Log.e("DataDownload", "Failed to persist synced audio session for $fileName: ${it.message}", it)
                    }
                }
                Log.i("DataDownload", "Saved to library: name=$fileName uri=${savedResult.uri}")
                true
            } else {
                Log.e("DataDownload", "Failed to download/save: $fileName (raw=$rawBytesSize mode=$payloadNote)")
                false
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("DataDownload", "Error downloading $fileName: ${e.message}", e)
            false
        }
    }

    private suspend fun retryAdaptiveMediaFile(
        fileName: String,
        transfer: suspend () -> Boolean,
    ): Boolean {
        if (downloadFlowMode != GlassesSyncFlow.CUSTOM) return transfer()

        val retryDelaysMs = longArrayOf(0L, 1_000L, 2_000L, 4_000L)
        for ((index, delayMs) in retryDelaysMs.withIndex()) {
            coroutineContext.ensureActive()
            if (delayMs > 0L) {
                adaptiveSyncSession?.mark(
                    AdaptiveSyncCheckpoint.MEDIA_FILE_RETRY,
                    "type=${File(fileName).extension.lowercase(Locale.US).take(8)}," +
                        "attempt=${index + 1},delay_ms=$delayMs,p2p=$downloadP2pConnected",
                )
                setTransferDetailForSession(
                    downloadSessionId,
                    "Retrying ${File(fileName).name} in ${delayMs / 1_000}s...",
                )
                delay(delayMs)
            }
            if (!downloadP2pConnected) {
                Log.w(
                    "DataDownload",
                    "Adaptive file attempt ${index + 1}/${retryDelaysMs.size} waiting for P2P reconnection: $fileName",
                )
                continue
            }
            if (transfer()) return true
            Log.w(
                "DataDownload",
                "Adaptive file attempt ${index + 1}/${retryDelaysMs.size} failed without resetting P2P: $fileName",
            )
        }
        return false
    }

    private fun sendExitTransferModeIfRequested() {
        if (!downloadExitTransferRequested) return
        downloadExitTransferRequested = false
        // Tell the glasses to exit transfer mode (official app does this after downloads finish).
        try {
            LargeDataHandler.getInstance().glassesControl(
                byteArrayOf(0x02, 0x01, 0x09)
            ) { _, resp ->
                Log.i(
                    "DataDownload",
                    "glassesControl[0x02,0x01,0x09] -> dataType=${resp.dataType}, error=${resp.errorCode}",
                )
            }
        } catch (e: Exception) {
            Log.w("DataDownload", "Failed to send exit-transfer command [0x02,0x01,0x09]", e)
        }
    }

    fun abandonDownloadP2pForBluetoothDisconnect() {
        val lease = host.mediaSessionLease
        if (
            lease == null &&
            downloadP2pLink == null &&
            !downloadNotifyListenerRegistered &&
            !downloadP2pTeardownInProgress
        ) {
            return
        }

        Log.i("DataDownload", "Bluetooth disconnected; abandoning media-sync P2P activity.resources")
        downloadCancelledByUser = true
        downloadAttemptJob?.cancel()
        downloadAttemptJob = null
        cancelDownloadSession()
        transferModeCommandTimeoutJob?.cancel()
        transferModeCommandTimeoutJob = null
        downloadInitialPhaseTimeoutJob?.cancel()
        downloadInitialPhaseTimeoutJob = null
        officialDisconnectRecoveryJob?.cancel()
        officialDisconnectRecoveryJob = null
        p2pNetwork.unbindProcessFromNetwork()

        if (downloadNotifyListenerRegistered) {
            runCatching { LargeDataHandler.getInstance().removeOutDeviceListener(2) }
            downloadNotifyListenerRegistered = false
        }

        // Bluetooth is gone: drop the link without waiting for group removal. The next claim
        // removes any group it left behind before connecting again.
        downloadP2pLink?.let { link -> GlassesNetworkSessionCoordinator.abandonHeyCyanLink(activity, link) }
        downloadP2pLink = null
        downloadP2pConnected = false
        downloadInProgress = false
        p2pNetwork.downloadP2pNetwork = null
        downloadResolvedHttpIp = null
        downloadP2pTeardownInProgress = false
        downloadExitTransferRequested = false
        host.releaseExclusiveGlassesSession(lease)
        transferUi.setTransferUiVisible(false)
        transferUi.resetTransferUiState()
    }
    
    private fun teardownDownloadP2pSession(
        sendExitTransfer: Boolean,
        hideTransferUi: Boolean,
        releaseExclusiveSession: Boolean = true,
        onTeardownComplete: (() -> Unit)? = null,
    ) {
        val teardownLease = host.mediaSessionLease
        if (sendExitTransfer) {
            downloadExitTransferRequested = true
        }
        if (downloadP2pTeardownInProgress) {
            sendExitTransferModeIfRequested()
            Log.w("DataDownload", "P2P teardown is already in progress; waiting for its result")
            return
        }
        downloadAttemptJob?.cancel()
        downloadAttemptJob = null
        cancelDownloadSession()
        transferModeCommandTimeoutJob?.cancel()
        transferModeCommandTimeoutJob = null
        downloadInitialPhaseTimeoutJob?.cancel()
        downloadInitialPhaseTimeoutJob = null
        p2pNetwork.unbindProcessFromNetwork()

        if (hideTransferUi) {
            activity.runOnUiThread {
                transferUi.setTransferUiVisible(false)
                transferUi.resetTransferUiState()
            }
        }

        // Stop receiving download-mode notify frames.
        if (downloadNotifyListenerRegistered) {
            try {
                LargeDataHandler.getInstance().removeOutDeviceListener(2)
                downloadNotifyListenerRegistered = false
                Log.i("DataDownload", "Unregistered download notify listener (cmdType=2)")
            } catch (e: Exception) {
                Log.w("DataDownload", "Failed to unregister download notify listener", e)
            }
        }

        sendExitTransferModeIfRequested()

        val link = downloadP2pLink

        val finishTeardown = {
            downloadP2pLink = null
            downloadP2pConnected = false
            downloadInProgress = false
            p2pNetwork.downloadP2pNetwork = null
            downloadResolvedHttpIp = null
            downloadP2pTeardownInProgress = false
            if (releaseExclusiveSession) {
                host.releaseExclusiveGlassesSession(teardownLease)
            }
            onTeardownComplete?.invoke()
            Unit
        }

        if (link == null) {
            finishTeardown()
            return
        }

        // Mirror official app: cancel the P2P connection as part of cleanup. The shared session
        // keeps the media-sync lease until the group removal is confirmed (retrying it while
        // quarantined), because a delayed removal could tear down a newly formed group.
        downloadP2pTeardownInProgress = true
        GlassesNetworkSessionCoordinator.closeHeyCyanLink(activity, link) {
            if (downloadP2pLink === link) {
                Log.i("DataDownload", "Confirmed P2P group is gone after media-sync teardown")
                finishTeardown()
            }
        }
    }

    private fun cleanupP2pAfterDownload() {
        teardownDownloadP2pSession(
            sendExitTransfer = true,
            hideTransferUi = true,
        )
    }

    fun cancelDataDownloadAttempt(reason: String, showToast: Boolean) {
        Log.i("DataDownload", "$reason (flow=${downloadFlowMode.label})")
        maybeShowP2pSyncLogHelp(
            reason = "The user stopped the P2P sync before media transfer completed. Sharing logs can help diagnose why the sync needed to be cancelled.",
            title = "Sync stopped",
            dismissButtonLabel = "Close",
        )
        downloadCancelledByUser = true
        finishDownloadInitialPhase("cancelled by user")
        transferUi.setTransferDetail("Stopping sync...")
        if (downloadP2pTeardownInProgress) {
            // A pre-start retry owns the teardown callback. Keep its confirmed-disconnect
            // barrier, but make the cancellation visible immediately.
            transferUi.setTransferUiVisible(false)
            transferUi.resetTransferUiState()
        }
        teardownDownloadP2pSession(
            sendExitTransfer = true,
            hideTransferUi = true,
        )
        if (showToast) {
            Toast.makeText(activity, reason, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDownloadSuccess(message: String) {
        recordAdaptiveSyncSuccess()
        finishDownloadInitialPhase("download completed")
        cleanupP2pAfterDownload()
        Log.i("DataDownload", "SUCCESS: $message (flow=${downloadFlowMode.label})")
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
    
    private fun showDownloadError(message: String, cleanup: Boolean = true) {
        if (isHighQualityImageTransfer()) {
            recordAdaptiveSyncFailure(message)
            finishHighQualityImageFailure(message)
            return
        }
        recordAdaptiveSyncFailure(message)
        if (!downloadInitialPhaseCompleted) {
            maybeShowP2pSyncLogHelp(
                reason = "CyanBridge failed during the initial P2P sync steps before any media transfer progress was shown. Error: $message",
            )
        }
        finishDownloadInitialPhase("error: $message")
        if (cleanup) {
            cleanupP2pAfterDownload()
        }
        Log.e("DataDownload", "ERROR: $message (flow=${downloadFlowMode.label})")
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    private fun recordAdaptiveSyncSuccess() {
        if (downloadFlowMode != GlassesSyncFlow.CUSTOM || adaptiveProfileResultRecorded) return
        val session = adaptiveSyncSession ?: return
        val route = session.successfulRoute ?: adaptiveActiveRoute ?: return
        val warmupMs = session.successfulWarmupMs ?: adaptiveSuccessfulWarmupMs ?: 1_000L
        adaptiveProfileResultRecorded = true
        session.mark(AdaptiveSyncCheckpoint.COMPLETE, "route=${route.name},warmup_ms=$warmupMs")
        val updated = AdaptiveP2pProfileStore.recordSuccess(activity, session.profileKey, route, warmupMs)
        Log.i(
            "DataDownload",
            "Adaptive profile learned success: route=$route warmupMs=$warmupMs " +
                "candidateSuccesses=${updated.candidateSuccesses} preferred=${updated.preferredRoute}",
        )
    }

    private fun recordAdaptiveSyncFailure(message: String) {
        if (downloadFlowMode != GlassesSyncFlow.CUSTOM || adaptiveProfileResultRecorded) return
        val session = adaptiveSyncSession ?: return
        val failedAfter = session.lastCheckpoint
        adaptiveProfileResultRecorded = true
        session.mark(
            AdaptiveSyncCheckpoint.FAILED,
            "after=${failedAfter?.name ?: "NONE"},reason=${message.take(120)}",
        )
        val updated = AdaptiveP2pProfileStore.recordFailure(activity, session.profileKey)
        Log.i(
            "DataDownload",
            "Adaptive profile recorded failure: checkpoint=$failedAfter " +
                "consecutiveFailures=${updated.consecutiveFailures}",
        )
    }

    fun onDownloadBleIp(ip: String) {
        val now = System.currentTimeMillis()
        val ipChanged = ip != downloadBleIp
        if (ip == downloadBleIp && (now - lastDownloadBleIpAtMs) < 1200L) {
            Log.i("DataDownload", "Ignoring duplicate BLE IP report: $ip")
            return
        }
        lastDownloadBleIpAtMs = now
        Log.i("DataDownload", "BLE reported device WiFi IP: $ip")
        markTransferModeEvidence("BLE 0x08 IP")
        downloadBleIp = ip
        if (downloadFlowMode == GlassesSyncFlow.CUSTOM) {
            adaptiveSyncSession?.mark(AdaptiveSyncCheckpoint.BLE_IP_RECEIVED, "ip=$ip")
        }
        if (downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN) {
            officialBleCallbackSuccess = true
            Log.i("DataDownload", "Official flow BLE readiness satisfied")
        }

        // If we're stuck scanning/probing without a good route, restart the resolver now that
        // we have the authoritative device IP from BLE.
        if (ipChanged && downloadAttemptJob?.isActive == true && !downloadInProgress) {
            Log.i("DataDownload", "New BLE IP arrived; restarting HTTP resolver")
            downloadAttemptJob?.cancel()
            downloadAttemptJob = null
        }
        maybeStartHttpDownload("BLE")
    }

    private fun onDownloadP2pConnected(info: WifiP2pInfo) {
        if (info.groupFormed) markTransferModeEvidence("P2P group formed")
        downloadP2pConnected = info.groupFormed
        downloadP2pLink?.markGroupFormed(info.groupFormed)
        downloadWifiIp = info.groupOwnerAddress?.hostAddress
        downloadPhoneIsGroupOwner = info.isGroupOwner
        if (downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN) {
            officialSystemSuccess = info.groupFormed
            officialDisconnectRecoveryJob?.cancel()
            officialDisconnectRecoveryJob = null
            downloadWifiP2pManager?.resetPeerDiscovery()
            Log.i(
                "DataDownload",
                "Official flow P2P readiness satisfied: systemSuccess=$officialSystemSuccess, phoneIsGroupOwner=${info.isGroupOwner}"
            )
            p2pNetwork.downloadP2pNetwork = null
            p2pNetwork.selectedDownloadNetworkSummary = "plain system routing (HeyCyan parity)"
            Log.i("DataDownload", "Official flow: skipping explicit P2P network binding to mirror vendor app")
        } else {
            p2pNetwork.downloadP2pNetwork = p2pNetwork.findLikelyP2pNetwork()
            adaptiveSyncSession?.mark(
                AdaptiveSyncCheckpoint.P2P_GROUP_FORMED,
                "phone_group_owner=${info.isGroupOwner},group_owner_ip=${downloadWifiIp.orEmpty()}",
            )
            if (p2pNetwork.downloadP2pNetwork != null) {
                adaptiveSyncSession?.mark(
                    AdaptiveSyncCheckpoint.P2P_NETWORK_IDENTIFIED,
                    p2pNetwork.selectedDownloadNetworkSummary,
                )
            }
        }
        Log.i(
            "DataDownload",
            "onDownloadP2pConnected: flow=${downloadFlowMode.label}, p2pConnected=$downloadP2pConnected, isGroupOwner=${info.isGroupOwner}, groupOwnerIp=$downloadWifiIp"
        )
        maybeStartHttpDownload("P2P")
    }

    private fun markTransferModeEvidence(source: String) {
        if (transferModeCommandEvidenceReceived) return
        transferModeCommandEvidenceReceived = true
        transferModeCommandTimeoutJob?.cancel()
        transferModeCommandTimeoutJob = null
        Log.i("DataDownload", "Transfer mode independently confirmed by $source")
        if (downloadFlowMode == GlassesSyncFlow.CUSTOM) {
            adaptiveSyncSession?.mark(AdaptiveSyncCheckpoint.TRANSFER_EVIDENCE, source)
        }
    }

    private fun maybeStartHttpDownload(source: String) {
        if (downloadCancelledByUser) {
            Log.i("DataDownload", "Ignoring HTTP start trigger from $source after user stop")
            return
        }
        if (downloadInProgress || downloadAttemptJob?.isActive == true) {
            Log.i("DataDownload", "Download already in progress, ignoring trigger from $source")
            return
        }

        if (!downloadP2pConnected) {
            Log.i("DataDownload", "Ignoring HTTP start trigger from $source; P2P not connected yet")
            return
        }

        when (downloadFlowMode) {
            GlassesSyncFlow.OFFICIAL_HEYCYAN -> maybeStartOfficialHttpDownload(source)
            GlassesSyncFlow.CUSTOM -> maybeStartCustomHttpDownload(source)
        }
    }

    private fun maybeStartOfficialHttpDownload(source: String) {
        if (!officialSystemSuccess) {
            Log.i(
                "DataDownload",
                "Ignoring HTTP start trigger from $source; HeyCyan flow is waiting for P2P system success"
            )
            return
        }

        val bleIp = downloadBleIp
        if (!officialBleCallbackSuccess || bleIp.isNullOrBlank()) {
            transferUi.setTransferDetail("Waiting for BLE-reported glasses IP...")
            Log.i(
                "DataDownload",
                "Ignoring HTTP start trigger from $source; HeyCyan flow is waiting for BLE 0x08 IP notify"
            )
            return
        }

        val targetIp = if (downloadPhoneIsGroupOwner == false && !downloadWifiIp.isNullOrBlank()) {
            downloadWifiIp!!
        } else {
            bleIp
        }

        Log.i(
            "DataDownload",
            "Official flow HTTP start trigger from $source. flow=${downloadFlowMode.label}, phoneIsGroupOwner=$downloadPhoneIsGroupOwner, bleIp=$downloadBleIp, groupOwnerIp=$downloadWifiIp, targetIp=$targetIp"
        )

        downloadAttemptJob = launchDownloadSession { sessionId ->
            // PictureFragment waits before handing media.config to AlbumDepository.
            delay(1000)
            if (!isActive || !downloadP2pConnected || downloadCancelledByUser || !isDownloadSessionActive(sessionId)) return@launchDownloadSession
            officialDisconnectRecoveryJob?.cancel()
            officialDisconnectRecoveryJob = null
            downloadWifiP2pManager?.resetPeerDiscovery()
            resetOfficialFlowState()
            downloadResolvedHttpIp = targetIp
            downloadInProgress = true
            Log.i("DataDownload", "Official flow resolved glasses HTTP IP: $targetIp")
            downloadMediaList(targetIp, sessionId)
        }
    }

    private fun maybeStartCustomHttpDownload(source: String) {
        val deviceIps = GlassesNetworkAddressing.glassesHttpTargets(
            listOf(downloadBleIp, bleIpBridge.ip.value),
            phoneIsGroupOwner = downloadPhoneIsGroupOwner,
        )
        if (deviceIps.isEmpty()) {
            transferUi.setTransferDetail("Waiting for BLE-reported glasses IP...")
            Log.i("DataDownload", "Ignoring HTTP start trigger from $source; waiting for device IP notify")
            return
        }

        val bridgeIp = bleIpBridge.ip.value
        Log.i(
            "DataDownload",
            "HTTP start trigger from $source. flow=${downloadFlowMode.label}, p2p=$downloadP2pConnected, bleIp=$downloadBleIp, groupOwnerIp=$downloadWifiIp, bleBridgeIp=$bridgeIp"
        )

        downloadAttemptJob = launchDownloadSession { sessionId ->
            val adaptive = adaptiveSyncSession ?: return@launchDownloadSession
            val routeOrder = adaptive.profile.routeOrder()
            val warmups = adaptive.profile.warmupScheduleMs()
            val httpRecoveryStartedAt = android.os.SystemClock.elapsedRealtime()
            var stickyEndpoint: Pair<String, AdaptiveHttpRoute>? = null
            var lastFailure = "HTTP endpoint was unreachable"

            for (warmupMs in warmups) {
                if (!isActive || !isDownloadSessionActive(sessionId) || !downloadP2pConnected) {
                    return@launchDownloadSession
                }
                setTransferDetailForSession(
                    sessionId,
                    "Adaptive HTTP check in ${warmupMs / 1000.0}s...",
                )
                val remainingWarmup = httpRecoveryStartedAt + warmupMs - android.os.SystemClock.elapsedRealtime()
                if (remainingWarmup > 0L) delay(remainingWarmup)

                val endpoints = stickyEndpoint?.let(::listOf) ?: deviceIps.flatMap { ip ->
                    routeOrder.map { route -> ip to route }
                }
                for ((candidateIp, route) in endpoints) {
                    if (!isActive || !isDownloadSessionActive(sessionId) || !downloadP2pConnected) {
                        return@launchDownloadSession
                    }
                    setTransferDetailForSession(
                        sessionId,
                        "Trying ${adaptiveRouteLabel(route)} to the glasses HTTP server...",
                    )
                    Log.i(
                        "DataDownload",
                        "Adaptive media.config attempt: ip=$candidateIp route=$route warmupMs=$warmupMs",
                    )
                    val result = p2pNetwork.fetchAdaptiveMediaConfig(candidateIp, route)
                    lastFailure = result.failureDetail
                    if (result.serverReached) {
                        stickyEndpoint = candidateIp to route
                        adaptive.mark(
                            AdaptiveSyncCheckpoint.MEDIA_CONFIG_HEADERS,
                            "route=${route.name},status=${result.statusCode ?: -1},detail=${result.failureDetail.take(80)}",
                        )
                    }
                    val content = result.content
                    if (content != null) {
                        adaptiveRecoveryJob?.cancel()
                        adaptiveRecoveryJob = null
                        adaptiveActiveRoute = route
                        adaptiveSuccessfulWarmupMs = warmupMs
                        adaptive.noteHttpSuccess(route, warmupMs)
                        applyAdaptiveHttpRoute(route)
                        downloadResolvedHttpIp = candidateIp
                        downloadInProgress = true
                        Log.i(
                            "DataDownload",
                            "Adaptive flow resolved media.config: ip=$candidateIp route=$route warmupMs=$warmupMs",
                        )
                        parseMediaList(content, candidateIp, sessionId)
                        return@launchDownloadSession
                    }
                    // A response code or response-body failure proves the route. Preserve the
                    // group and retry only this endpoint after the next firmware warm-up delay.
                    if (result.serverReached) break
                }
            }

            adaptive.mark(AdaptiveSyncCheckpoint.FAILED, "http=$lastFailure")
            withContext(Dispatchers.Main) {
                if (!isDownloadSessionActive(sessionId)) return@withContext
                val hint = " If HeyCyan is installed, force-stop it and retry."
                showDownloadError(
                    "Adaptive sync reached P2P and the glasses IP, but media.config never completed: $lastFailure.$hint",
                    cleanup = true,
                )
            }
        }
    }

    private fun adaptiveRouteLabel(route: AdaptiveHttpRoute): String = when (route) {
        AdaptiveHttpRoute.P2P_NETWORK -> "the Android P2P network"
        AdaptiveHttpRoute.LOCAL_ADDRESS -> "a P2P-local socket"
        AdaptiveHttpRoute.SYSTEM -> "system routing"
    }

    private fun applyAdaptiveHttpRoute(route: AdaptiveHttpRoute) {
        when (route) {
            AdaptiveHttpRoute.P2P_NETWORK -> {
                p2pNetwork.downloadP2pNetwork = p2pNetwork.findLikelyP2pNetwork() ?: p2pNetwork.downloadP2pNetwork
                p2pNetwork.bindProcessToNetwork(p2pNetwork.downloadP2pNetwork)
            }
            AdaptiveHttpRoute.LOCAL_ADDRESS,
            AdaptiveHttpRoute.SYSTEM,
            -> p2pNetwork.unbindProcessFromNetwork()
        }
        p2pNetwork.selectedDownloadNetworkSummary = "adaptive_route=${route.name}; $p2pNetwork.selectedDownloadNetworkSummary"
    }

    private fun isDownloadInitialPhaseActive(): Boolean {
        return !downloadInitialPhaseCompleted &&
            !downloadCancelledByUser &&
            host.dashboardTransferState().isVisible
    }

    private inner class DownloadNotifyListener : GlassesDeviceNotifyListener() {
        @RequiresApi(Build.VERSION_CODES.O)
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            // Only handle download-relevant notifications here to avoid duplicating
            // other flows already handled by MyDeviceNotifyListener.
            when (val notify = GlassesWifiNotify.parse(response.loadData) ?: return) {
                is GlassesWifiNotify.GlassesIp -> {
                    Log.i("DeviceNotify", "(download) BLE reported WiFi IP: ${notify.ip}")
                    onDownloadBleIp(notify.ip)
                }

                is GlassesWifiNotify.WifiError -> {
                    Log.e("DeviceNotify", "(download) P2P/WiFi error from device: ${notify.code} (raw=${notify.raw ?: 0})")
                    if (notify.isNoisyVendorError) {
                        maybeResetP2pAfterError255("download")
                    }
                }

                else -> Unit
            }
        }
    }

    fun maybeResetP2pAfterError255(source: String) {
        // Only react while we're actually in (or attempting) a download session. Otherwise
        // resets can interfere with normal camera/recording usage.
        val official = downloadFlowMode == GlassesSyncFlow.OFFICIAL_HEYCYAN
        val sessionActive = downloadP2pConnected || downloadInProgress || downloadAttemptJob?.isActive == true ||
            if (official) isOfficialSyncActive() else isDownloadInitialPhaseActive()
        val action = GlassesWifiErrorPolicy.decide(
            context = if (official) GlassesWifiErrorContext.OFFICIAL_MEDIA_SYNC else GlassesWifiErrorContext.ADAPTIVE_MEDIA_SYNC,
            errorCode = GlassesWifiNotify.NOISY_WIFI_ERROR_CODE,
            sessionActive = sessionActive,
        )
        when (action) {
            GlassesWifiErrorAction.STOP_FOR_RETRY_WITH_DEVICE_RESET -> stopOfficialFlowForRetry(
                "The glasses reported Wi-Fi Direct error 255.",
                resetDeviceP2p = true,
            )
            GlassesWifiErrorAction.DEFER_TO_ADAPTIVE_RECOVERY -> {
                // Error 255 is noisy on the vendor firmware. Preserve any progress and let the
                // bounded adaptive timeline decide whether its single reset is actually needed.
                adaptiveSyncSession?.mark(
                    AdaptiveSyncCheckpoint.DEVICE_ERROR,
                    "device_error_255_source=$source",
                )
                Log.i("DataDownload", "Adaptive flow observed error=255; deferring reset to checkpoint recovery")
            }
            else -> Log.i("DataDownload", "Ignoring error=255 reset (source=$source) outside download session")
        }
    }

    private fun stopOfficialFlowForRetry(message: String, resetDeviceP2p: Boolean) {
        if (downloadFlowMode != GlassesSyncFlow.OFFICIAL_HEYCYAN || downloadCancelledByUser) return
        if (officialFlowRetryRequired) return
        officialFlowRetryRequired = true
        Log.w("DataDownload", "HeyCyan flow stopped for manual retry: $message")
        if (!downloadInitialPhaseCompleted) {
            maybeShowP2pSyncLogHelp(
                reason = "HeyCyan-compatible sync stopped before media transfer. Error: $message",
            )
        }
        finishDownloadInitialPhase("official flow retry required: $message")
        transferUi.setTransferDetail("$message Retry sync.")
        if (resetDeviceP2p) {
            WifiP2pManagerSingleton.getInstance(activity).resetDeviceP2p()
        }
        teardownDownloadP2pSession(
            sendExitTransfer = false,
            hideTransferUi = true,
        )
        Toast.makeText(activity, "$message Please retry sync.", Toast.LENGTH_LONG).show()
    }

    /** Activity teardown while the HeyCyan media sync still owns the glasses session. */
    fun stopForActivityDestroy() {
        downloadCancelledByUser = true
        teardownDownloadP2pSession(
            sendExitTransfer = true,
            hideTransferUi = false,
        )
    }

    companion object {
        private const val TRANSFER_MODE_COMMAND_TIMEOUT_MS = 10_000L
        private const val ADAPTIVE_INITIAL_PHASE_TIMEOUT_MS = 85_000L
        private const val ADAPTIVE_COMMAND_RETRY_AT_MS = 12_000L
        private const val ADAPTIVE_DISCOVERY_RESTART_AT_MS = 20_000L
        private const val ADAPTIVE_IP_RETRY_AT_MS = 28_000L
        private const val ADAPTIVE_DEVICE_RESET_AT_MS = 36_000L
    }
}
