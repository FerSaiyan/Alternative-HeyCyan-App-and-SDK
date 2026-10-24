package com.fersaiyan.cyanbridge.media.sync

import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueMediaProfile
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueMediaSync
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueMediaType
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueWifiTransport
import com.fersaiyan.cyanbridge.devices.moyoung.MoyoungW620Manager
import com.fersaiyan.cyanbridge.devices.moyoung.MoyoungWifiCredentials
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsLocalHotspot
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsMediaSync
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsMediaType
import com.fersaiyan.cyanbridge.glasses.GlassesSession
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.fersaiyan.cyanbridge.shared.glasses.GlassesSyncFlow
import com.fersaiyan.cyanbridge.shared.prefs.MediaPrefs
import com.fersaiyan.cyanbridge.shared.prefs.edit
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.fersaiyan.cyanbridge.ui.hasBluetooth
import com.fersaiyan.cyanbridge.ui.hasWifiP2pPermission
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Eyevue, TuneBuds and MoYoung / W620 media sync over each vendor's own Wi-Fi transport. */
internal class VendorMediaSyncController(
    private val activity: AppCompatActivity,
    private val host: VendorMediaSyncHost,
    private val transferUi: TransferProgressPresenter,
    private val mediaLibrary: GlassesMediaLibrary,
) {
    var eyevueMediaJob: Job? = null
        private set
    private var eyevueMediaTransport: EyevueWifiTransport? = null
    private var eyevueMediaCancelled = false
    var tuneBudsMediaJob: Job? = null
        private set
    private var tuneBudsMediaHotspot: TuneBudsLocalHotspot? = null
    private var tuneBudsMediaCancelled = false
    private var moyoungMediaJob: Job? = null
    private var moyoungMediaCancelled = false

    fun startEyevueMediaSync() {
        if (eyevueMediaJob?.isActive == true) return
        if (!hasBluetooth(activity) || !hasWifiP2pPermission(activity)) {
            host.ensureGlassesTransportPermissions("Eyevue media sync") {
                startEyevueMediaSync()
            }
            return
        }

        val manager = host.getOrCreateEyevueManager()
        if (!manager.isConnected()) {
            Toast.makeText(activity, "Connect to Eyevue over Bluetooth first.", Toast.LENGTH_LONG).show()
            return
        }
        if (GlassesSessionCoordinator.currentSession() != null) {
            Toast.makeText(activity, "Another glasses session is already active.", Toast.LENGTH_SHORT).show()
            return
        }

        val lease = host.acquireExclusiveGlassesSession(GlassesSession.MEDIA_SYNC) ?: return
        host.mediaSessionLease = lease
        eyevueMediaCancelled = false
        transferUi.setTransferUiVisible(true)
        transferUi.setTransferFlowLabel(GlassesSyncFlow.CUSTOM)
        transferUi.setTransferDetail("Starting Eyevue media sync...")
        transferUi.resetTransferUiState()
        transferUi.setTransferUiVisible(true)

        val transport = EyevueWifiTransport(activity)
        eyevueMediaTransport = transport
        val temporaryDirectory = File(activity.cacheDir, "eyevue_media_${System.currentTimeMillis()}")
        eyevueMediaJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            var result: Result<Int>? = null
            try {
                val project = manager.awaitProject()
                    ?: throw IOException("Eyevue did not report its project/model; refusing to guess AP vs P2P")
                val profile = EyevueMediaProfile.fromProject(project)
                val sync = EyevueMediaSync(manager, transport, temporaryDirectory)
                result = sync.sync(
                    profile = profile,
                    onState = { syncState ->
                        withContext(Dispatchers.Main) {
                            if (eyevueMediaCancelled) return@withContext
                            if (syncState.total > 0) {
                                transferUi.transferTotalJpg = syncState.total
                                transferUi.transferTotalMp4 = 0
                                transferUi.transferTotalOpus = 0
                                transferUi.transferDoneJpg = syncState.completed
                                transferUi.transferDoneMp4 = 0
                                transferUi.transferDoneOpus = 0
                            }
                            transferUi.renderTransferProgress()
                            transferUi.setTransferDetail("${syncState.detail} (${profile.mode.name})")
                        }
                    },
                    onProgress = { item, downloaded, total ->
                        if (total > 0L) {
                            withContext(Dispatchers.Main) {
                                if (!eyevueMediaCancelled) {
                                    transferUi.setTransferDetail(
                                        "Downloading ${item.fileName}: " +
                                            "${downloaded / 1024} / ${total / 1024} KB",
                                    )
                                }
                            }
                        }
                    },
                    onFile = { item, file ->
                        val vendorItem = VendorMediaItem(
                            fileName = item.fileName,
                            type = when (item.type) {
                                EyevueMediaType.PHOTO -> VendorMediaType.PHOTO
                                EyevueMediaType.VIDEO -> VendorMediaType.VIDEO
                                EyevueMediaType.AUDIO -> VendorMediaType.AUDIO
                            },
                        )
                        mediaLibrary.importVendorMediaFile(file, vendorItem)
                    },
                )
            } finally {
                withContext(Dispatchers.Main) {
                    val completed = result?.getOrNull()
                    val failureDetail = result?.exceptionOrNull()?.message
                    val cancelled = eyevueMediaCancelled
                    eyevueMediaJob = null
                    eyevueMediaTransport = null
                    transferUi.setTransferUiVisible(false)
                    host.releaseExclusiveGlassesSession(lease)
                    if (!cancelled) {
                        if (completed != null) {
                            Toast.makeText(
                                activity,
                                "Eyevue sync complete: $completed files",
                                Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            Toast.makeText(
                                activity,
                                "Eyevue media sync failed: " + (failureDetail?.take(140) ?: "unknown error"),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
        }
    }

    fun stopEyevueMediaSync() {
        if (eyevueMediaJob?.isActive != true) return
        eyevueMediaCancelled = true
        host.getOrCreateEyevueManager().finishTransfer()
        eyevueMediaTransport?.disconnect()
        eyevueMediaJob?.cancel()
        transferUi.setTransferUiVisible(false)
        host.releaseExclusiveGlassesSession(host.mediaSessionLease)
        host.mediaSessionLease = null
    }

    fun startTuneBudsMediaSync() {
        if (tuneBudsMediaJob != null) return
        if (!hasBluetooth(activity) || !hasWifiP2pPermission(activity)) {
            host.ensureGlassesTransportPermissions("TuneBuds media sync") {
                startTuneBudsMediaSync()
            }
            return
        }

        val manager = host.getOrCreateTuneBudsManager()
        if (!manager.isConnected()) {
            Toast.makeText(activity, "Connect TuneBuds glasses first.", Toast.LENGTH_LONG).show()
            return
        }
        if (manager.state.value.isVideoRecording || manager.state.value.isAudioRecording ||
            host.isTuneBudsAiPhotoInProgress() || host.isImageQuestionWorkActive()
        ) {
            Toast.makeText(activity, "Stop recording or photo capture before syncing TuneBuds media.", Toast.LENGTH_LONG).show()
            return
        }
        if (GlassesSessionCoordinator.currentSession() != null) {
            Toast.makeText(activity, "Another glasses session is already active.", Toast.LENGTH_SHORT).show()
            return
        }

        val lease = host.acquireExclusiveGlassesSession(GlassesSession.MEDIA_SYNC) ?: return
        host.mediaSessionLease = lease
        tuneBudsMediaCancelled = false
        transferUi.resetTransferUiState()
        transferUi.setTransferUiVisible(true)
        transferUi.setTransferFlowLabel(GlassesSyncFlow.CUSTOM)
        transferUi.setTransferDetail("Starting TuneBuds media sync...")

        val hotspot = TuneBudsLocalHotspot(activity)
        tuneBudsMediaHotspot = hotspot
        val temporaryDirectory = File(activity.cacheDir, "tunebuds_media_${System.currentTimeMillis()}")
        tuneBudsMediaJob = activity.lifecycleScope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
            var result: Result<Int>? = null
            try {
                val sync = TuneBudsMediaSync(manager, hotspot, temporaryDirectory, context = activity)
                result = sync.sync(
                    onState = { syncState ->
                        withContext(Dispatchers.Main) {
                            if (tuneBudsMediaCancelled) return@withContext
                            if (syncState.total > 0) {
                                transferUi.transferTotalJpg = syncState.totalsByType[TuneBudsMediaType.PHOTO] ?: 0
                                transferUi.transferTotalMp4 = syncState.totalsByType[TuneBudsMediaType.VIDEO] ?: 0
                                transferUi.transferTotalOpus = syncState.totalsByType[TuneBudsMediaType.AUDIO] ?: 0
                                transferUi.transferDoneJpg = syncState.completedByType[TuneBudsMediaType.PHOTO] ?: 0
                                transferUi.transferDoneMp4 = syncState.completedByType[TuneBudsMediaType.VIDEO] ?: 0
                                transferUi.transferDoneOpus = syncState.completedByType[TuneBudsMediaType.AUDIO] ?: 0
                            }
                            transferUi.renderTransferProgress()
                            transferUi.setTransferDetail(
                                syncState.lastError?.let { "${syncState.detail}: $it" }
                                    ?: syncState.detail,
                            )
                        }
                    },
                    onProgress = { item, downloaded, total ->
                        if (total > 0L) {
                            withContext(Dispatchers.Main) {
                                if (!tuneBudsMediaCancelled) {
                                    transferUi.setTransferDetail(
                                        "Downloading ${item.fileName}: ${downloaded / 1024} / ${total / 1024} KB",
                                    )
                                }
                            }
                        }
                    },
                    onFile = { item, file ->
                        mediaLibrary.importVendorMediaFile(
                            file,
                            VendorMediaItem(
                                fileName = item.fileName,
                                type = when (item.type) {
                                    TuneBudsMediaType.PHOTO -> VendorMediaType.PHOTO
                                    TuneBudsMediaType.VIDEO -> VendorMediaType.VIDEO
                                    TuneBudsMediaType.AUDIO -> VendorMediaType.AUDIO
                                },
                            ),
                        )
                    },
                )
            } finally {
                withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
                    val completed = result?.getOrNull()
                    val cancelled = tuneBudsMediaCancelled
                    tuneBudsMediaJob = null
                    tuneBudsMediaHotspot = null
                    transferUi.setTransferUiVisible(false)
                    host.releaseExclusiveGlassesSession(lease)
                    host.mediaSessionLease = null
                    if (!cancelled) {
                        Toast.makeText(
                            activity,
                            completed?.let { "TuneBuds sync complete: $it files" }
                                ?: "TuneBuds media sync failed. Check the transfer details and retry.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    fun stopTuneBudsMediaSync() {
        if (tuneBudsMediaJob?.isActive != true) return
        tuneBudsMediaCancelled = true
        // The sync job owns cancellation-safe camera/hotspot cleanup and its lease.
        tuneBudsMediaJob?.cancel()
        transferUi.setTransferUiVisible(false)
    }

    fun startMoyoungW620MediaSync() {
        if (moyoungMediaJob?.isActive == true) return
        if (!hasBluetooth(activity) || !hasWifiP2pPermission(activity)) {
            host.ensureGlassesTransportPermissions("MoYoung media sync") {
                startMoyoungW620MediaSync()
            }
            return
        }

        val manager = host.getOrCreateMoyoungW620Manager()
        if (!manager.isConnected()) {
            Toast.makeText(activity, "Connect MoYoung / W620 glasses first.", Toast.LENGTH_LONG).show()
            return
        }
        if (GlassesSessionCoordinator.currentSession() != null) {
            Toast.makeText(activity, "Another glasses session is already active.", Toast.LENGTH_SHORT).show()
            return
        }

        showMoyoungWifiCredentialsDialog(manager)
    }

    private fun showMoyoungWifiCredentialsDialog(manager: MoyoungW620Manager) {
        val wifi = MediaPrefs.MoyoungW620
        val ssidInput = android.widget.EditText(activity).apply {
            hint = "Glasses Wi-Fi name (SSID)"
            setText(wifi.fileWifiSsid[activity])
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val passwordInput = android.widget.EditText(activity).apply {
            hint = "Glasses Wi-Fi password"
            setText(wifi.fileWifiPassword[activity])
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val padding = (24 * activity.resources.displayMetrics.density).toInt()
        val fields = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
            addView(ssidInput)
            addView(passwordInput)
        }
        AlertDialog.Builder(activity)
            .setTitle("W620 Wi-Fi credentials")
            .setMessage(
                "Confirm the access-point details for these glasses. Glass-01 / 12345678 " +
                    "are upstream example defaults and may not match every W620.",
            )
            .setView(fields)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start sync") { _, _ ->
                val credentials = MoyoungWifiCredentials(
                    ssid = ssidInput.text.toString().trim(),
                    password = passwordInput.text.toString(),
                )
                if (credentials.ssid.isBlank()) {
                    Toast.makeText(activity, "Enter the glasses Wi-Fi name.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                wifi.edit(activity) {
                    this[wifi.fileWifiSsid] = credentials.ssid
                    this[wifi.fileWifiPassword] = credentials.password
                }
                beginMoyoungW620MediaSync(manager, credentials)
            }
            .show()
    }

    private fun beginMoyoungW620MediaSync(
        manager: MoyoungW620Manager,
        wifiCredentials: MoyoungWifiCredentials,
    ) {
        if (moyoungMediaJob?.isActive == true) return

        val lease = host.acquireExclusiveGlassesSession(GlassesSession.MEDIA_SYNC) ?: return
        host.mediaSessionLease = lease
        moyoungMediaCancelled = false
        transferUi.resetTransferUiState()
        transferUi.setTransferUiVisible(true)
        transferUi.setTransferFlowLabel(GlassesSyncFlow.CUSTOM)
        transferUi.setTransferDetail("Starting MoYoung / W620 media sync...")

        val temporaryDirectory = File(activity.cacheDir, "moyoung_media_${System.currentTimeMillis()}")
        moyoungMediaJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            var result: Result<Int>? = null
            try {
                result = runCatching {
                    val files = manager.downloadMedia(
                        targetDirectory = temporaryDirectory,
                        wifiCredentials = wifiCredentials,
                    ) { current, total ->
                        activity.runOnUiThread {
                            if (!moyoungMediaCancelled) {
                                transferUi.setTransferDetail(
                                    if (total > 0) "Downloading from W620: $current / $total"
                                    else "Downloading from W620...",
                                )
                            }
                        }
                    }
                    val items = files.mapNotNull { file ->
                        val type = when (file.extension.lowercase()) {
                            "jpg", "jpeg", "png", "heic" -> VendorMediaType.PHOTO
                            "mp4", "mov", "m4v" -> VendorMediaType.VIDEO
                            "opus", "ogg", "wav" -> VendorMediaType.AUDIO
                            else -> null
                        } ?: return@mapNotNull null
                        file to VendorMediaItem(file.name, type)
                    }
                    withContext(Dispatchers.Main) {
                        transferUi.transferTotalJpg = items.count { it.second.type == VendorMediaType.PHOTO }
                        transferUi.transferTotalMp4 = items.count { it.second.type == VendorMediaType.VIDEO }
                        transferUi.transferTotalOpus = items.count { it.second.type == VendorMediaType.AUDIO }
                        transferUi.renderTransferProgress()
                    }
                    var imported = 0
                    items.forEach { (file, item) ->
                        if (mediaLibrary.importVendorMediaFile(file, item)) imported++
                        withContext(Dispatchers.Main) {
                            when (item.type) {
                                VendorMediaType.PHOTO -> transferUi.transferDoneJpg++
                                VendorMediaType.VIDEO -> transferUi.transferDoneMp4++
                                VendorMediaType.AUDIO -> transferUi.transferDoneOpus++
                            }
                            transferUi.renderTransferProgress()
                        }
                    }
                    imported
                }
            } finally {
                manager.stopMediaSync()
                temporaryDirectory.deleteRecursively()
                withContext(Dispatchers.Main) {
                    val completed = result?.getOrNull()
                    val failure = result?.exceptionOrNull()
                    val cancelled = moyoungMediaCancelled
                    moyoungMediaJob = null
                    transferUi.setTransferUiVisible(false)
                    host.releaseExclusiveGlassesSession(lease)
                    host.mediaSessionLease = null
                    if (!cancelled) {
                        if (failure != null) {
                            Log.e("MoyoungW620", "Media sync failed", failure)
                        }
                        Toast.makeText(
                            activity,
                            completed?.let { "MoYoung / W620 sync complete: $it files" }
                                ?: "MoYoung / W620 sync failed: ${failure?.message ?: "unknown error"}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    fun stopMoyoungW620MediaSync() {
        if (moyoungMediaJob?.isActive != true) return
        moyoungMediaCancelled = true
        host.getOrCreateMoyoungW620Manager().stopMediaSync()
        moyoungMediaJob?.cancel()
        transferUi.setTransferUiVisible(false)
        host.releaseExclusiveGlassesSession(host.mediaSessionLease)
        host.mediaSessionLease = null
    }

    /** Activity teardown: cancel running vendor sync jobs without touching the transfer UI. */
    fun cancelSyncJobsForDestroy(moyoungW620Manager: MoyoungW620Manager?) {
        if (eyevueMediaJob?.isActive == true) {
            eyevueMediaCancelled = true
            eyevueMediaTransport?.disconnect()
            eyevueMediaJob?.cancel()
        }
        if (tuneBudsMediaJob?.isActive == true) {
            tuneBudsMediaCancelled = true
            tuneBudsMediaJob?.cancel()
        }
        if (moyoungMediaJob?.isActive == true) {
            moyoungMediaCancelled = true
            moyoungW620Manager?.stopMediaSync()
            moyoungMediaJob?.cancel()
        }
    }

    fun disconnectEyevueTransport() {
        eyevueMediaTransport?.disconnect()
    }
}
