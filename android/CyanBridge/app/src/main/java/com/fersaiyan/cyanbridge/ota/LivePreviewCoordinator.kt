package com.fersaiyan.cyanbridge.ota

import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueLivePreviewManager
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueLivePreviewPolicy
import com.fersaiyan.cyanbridge.devices.eyevue.EyevueManager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsLiveForegroundService
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsLivePreviewManager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsManager
import com.fersaiyan.cyanbridge.glasses.GlassesSession
import com.fersaiyan.cyanbridge.glasses.GlassesSessionLease
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCaptureService
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.ui.ensureNotificationPermission
import com.fersaiyan.cyanbridge.ui.hasBluetooth
import com.fersaiyan.cyanbridge.ui.hasNotificationPermission
import com.fersaiyan.cyanbridge.ui.hasWifiP2pPermission
import com.oudmon.ble.base.bluetooth.BleOperateManager
import kotlinx.coroutines.launch

/**
 * Starts the HeyCyan, Eyevue or TuneBuds live preview under the shared glasses session lease
 * and mirrors the HeyCyan preview state into the dashboard.
 */
internal class LivePreviewCoordinator(
    private val activity: AppCompatActivity,
    private val livePreviewManagerProvider: () -> LivePreviewManager,
    private val host: Host,
) {
    interface Host {
        var dashboardState: GlassesDashboardUiState
        var livePreviewSessionLease: GlassesSessionLease?
        var heyCyanLivePlayer: org.videolan.libvlc.MediaPlayer?
        var livePreviewDialog: AlertDialog?
        var eyevueLiveAudioMuted: Boolean
        var tuneBudsLiveAudioMuted: Boolean
        fun rejectHeyCyanOnlyFeature(feature: String): Boolean
        fun isHeyCyanSelected(): Boolean
        fun isTuneBudsSelected(): Boolean
        fun ensureGlassesTransportPermissions(feature: String, onGranted: () -> Unit)
        fun acquireExclusiveGlassesSession(session: GlassesSession): GlassesSessionLease?
        fun releaseExclusiveGlassesSession(lease: GlassesSessionLease?)
        /** HeyCyan Wi-Fi media sync is in progress or still holds its P2P group. */
        fun isHeyCyanWifiSyncActive(): Boolean
        fun getOrCreateEyevueManager(): EyevueManager
        fun getOrCreateEyevueLivePreviewManager(): EyevueLivePreviewManager
        fun isEyevueMediaSyncActive(): Boolean
        fun getOrCreateTuneBudsManager(): TuneBudsManager
        fun getOrCreateTuneBudsLivePreviewManager(): TuneBudsLivePreviewManager
        fun isTuneBudsMediaSyncActive(): Boolean
        fun isTuneBudsAiPhotoInProgress(): Boolean
        fun isImageQuestionWorkActive(): Boolean
    }

    private val livePreviewManager: LivePreviewManager get() = livePreviewManagerProvider()
    private var heyCyanLiveStopReceiver: android.content.BroadcastReceiver? = null

    private var dashboardState: GlassesDashboardUiState
        get() = host.dashboardState
        set(value) {
            host.dashboardState = value
        }

    private var livePreviewSessionLease: GlassesSessionLease?
        get() = host.livePreviewSessionLease
        set(value) {
            host.livePreviewSessionLease = value
        }

    private var livePreviewDialog: AlertDialog?
        get() = host.livePreviewDialog
        set(value) {
            host.livePreviewDialog = value
        }

    fun startLivePreview() {
        if (host.rejectHeyCyanOnlyFeature("Live preview")) return
        if (!hasBluetooth(activity) || !hasWifiP2pPermission(activity)) {
            host.ensureGlassesTransportPermissions("HeyCyan live preview") { startLivePreview() }
            return
        }
        if (!hasNotificationPermission(activity)) {
            ensureNotificationPermission(activity, "HeyCyan live preview") { startLivePreview() }
            return
        }
        if (!BleOperateManager.getInstance().isConnected) {
            Toast.makeText(activity, "Connect to HeyCyan over Bluetooth first.", Toast.LENGTH_LONG).show()
            return
        }
        if (livePreviewManager.isActive) return
        if (host.isHeyCyanWifiSyncActive()) {
            Toast.makeText(activity, "Stop the current Wi-Fi sync before starting live preview.", Toast.LENGTH_LONG).show()
            return
        }
        if (AutoAudioCaptureService.isRunning()) {
            Toast.makeText(activity, "Stop auto audio capture before starting live preview.", Toast.LENGTH_LONG).show()
            return
        }

        val lease = host.acquireExclusiveGlassesSession(GlassesSession.LIVE_PREVIEW) ?: return
        livePreviewSessionLease = lease
        livePreviewManager.setAudioMuted(true)

        if (heyCyanLiveStopReceiver == null) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                    livePreviewManager.stop()
                }
            }
            heyCyanLiveStopReceiver = receiver
            androidx.core.content.ContextCompat.registerReceiver(
                activity,
                receiver,
                android.content.IntentFilter(
                    com.fersaiyan.cyanbridge.devices.heycyan.HeyCyanLiveForegroundService.ACTION_HEYCYAN_LIVE_STOP,
                ),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }

        com.fersaiyan.cyanbridge.devices.heycyan.HeyCyanLiveForegroundService.start(activity)
        livePreviewManager.start {
            com.fersaiyan.cyanbridge.devices.heycyan.HeyCyanLiveForegroundService.stop(activity)
            host.releaseExclusiveGlassesSession(lease)
            if (livePreviewSessionLease === lease) livePreviewSessionLease = null
        }
    }

    fun startEyevueLivePreview() {
        if (!EyevueLivePreviewPolicy.isSupported(Build.VERSION.SDK_INT)) {
            Toast.makeText(activity, "Eyevue live preview requires Android 10 or newer.", Toast.LENGTH_LONG).show()
            return
        }
        if (!hasBluetooth(activity) || !hasWifiP2pPermission(activity)) {
            host.ensureGlassesTransportPermissions("Eyevue live preview") {
                startEyevueLivePreview()
            }
            return
        }
        if (!hasNotificationPermission(activity)) {
            ensureNotificationPermission(activity, "Eyevue live preview") {
                startEyevueLivePreview()
            }
            return
        }
        val manager = host.getOrCreateEyevueManager()
        if (!manager.isConnected()) {
            Toast.makeText(activity, "Connect to Eyevue over Bluetooth first.", Toast.LENGTH_LONG).show()
            return
        }
        val liveManager = host.getOrCreateEyevueLivePreviewManager()
        if (liveManager.isActive) return
        if (host.isEyevueMediaSyncActive()) {
            Toast.makeText(activity, "Stop Eyevue media sync before starting live preview.", Toast.LENGTH_LONG).show()
            return
        }
        if (AutoAudioCaptureService.isRunning()) {
            Toast.makeText(activity, "Stop auto audio capture before starting live preview.", Toast.LENGTH_LONG).show()
            return
        }

        val lease = host.acquireExclusiveGlassesSession(GlassesSession.LIVE_PREVIEW) ?: return
        livePreviewSessionLease = lease
        // Foreground service keeps the session + relay alive when this app is
        // backgrounded (e.g. opening VLC). Same shape as the Gemini live one.
        com.fersaiyan.cyanbridge.devices.eyevue.EyevueLiveForegroundService.start(activity)
        liveManager.onRelayUrlChanged = { url ->
            com.fersaiyan.cyanbridge.devices.eyevue.EyevueLiveForegroundService.updateUrl(activity, url)
        }
        host.eyevueLiveAudioMuted = false
        liveManager.setAudioMuted(false)
        liveManager.start(
            onSessionFinished = {
                liveManager.onRelayUrlChanged = null
                com.fersaiyan.cyanbridge.devices.eyevue.EyevueLiveForegroundService.stop(activity)
                host.releaseExclusiveGlassesSession(lease)
                if (livePreviewSessionLease === lease) livePreviewSessionLease = null
            },
        )
    }

    fun startTuneBudsLivePreview() {
        if (!host.isTuneBudsSelected()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(activity, "TuneBuds live preview requires Android 8 or newer.", Toast.LENGTH_LONG).show()
            return
        }
        if (!hasBluetooth(activity) || !hasWifiP2pPermission(activity)) {
            host.ensureGlassesTransportPermissions("TuneBuds live preview") { startTuneBudsLivePreview() }
            return
        }
        if (!hasNotificationPermission(activity)) {
            ensureNotificationPermission(activity, "TuneBuds live preview") { startTuneBudsLivePreview() }
            return
        }
        val manager = host.getOrCreateTuneBudsManager()
        if (!manager.isConnected()) {
            Toast.makeText(activity, "Connect to TuneBuds over Bluetooth first.", Toast.LENGTH_LONG).show()
            return
        }
        val liveManager = host.getOrCreateTuneBudsLivePreviewManager()
        if (liveManager.isActive) return
        if (host.isTuneBudsMediaSyncActive() || host.isTuneBudsAiPhotoInProgress() || host.isImageQuestionWorkActive() || AutoAudioCaptureService.isRunning() ||
            manager.state.value.isVideoRecording || manager.state.value.isAudioRecording
        ) {
            Toast.makeText(activity, "Stop capture, recording, or media sync before starting live preview.", Toast.LENGTH_LONG).show()
            return
        }
        val lease = host.acquireExclusiveGlassesSession(GlassesSession.LIVE_PREVIEW) ?: return
        livePreviewSessionLease = lease
        if (!TuneBudsLiveForegroundService.start(activity)) {
            host.releaseExclusiveGlassesSession(lease)
            Toast.makeText(activity, "Could not start TuneBuds live service.", Toast.LENGTH_LONG).show()
            return
        }
        host.tuneBudsLiveAudioMuted = false
        liveManager.setAudioMuted(false)
        liveManager.onRelayUrlChanged = { url -> TuneBudsLiveForegroundService.updateUrl(activity, url) }
        liveManager.start {
            liveManager.onRelayUrlChanged = null
            TuneBudsLiveForegroundService.stop(activity)
            host.releaseExclusiveGlassesSession(lease)
        }
    }

    fun observeLivePreviewState() {
        activity.lifecycleScope.launch {
            var lastLabel = ""
            livePreviewManager.uiState.collect { lp ->
                if (!host.isHeyCyanSelected()) return@collect
                if (lp.stateLabel != lastLabel) {
                    Log.i(
                        "LivePreview",
                        "HeyCyan state='${lp.stateLabel}' scanning=${lp.isScanning} playing=${lp.isPlaying} url=${lp.streamUrl}",
                    )
                    lastLabel = lp.stateLabel
                }
                dashboardState = dashboardState.copy(
                    livePreview = com.fersaiyan.cyanbridge.shared.glasses.LivePreviewUiState(
                        isAvailable = true,
                        stateLabel = lp.stateLabel,
                        detail = lp.detail,
                        isScanning = lp.isScanning,
                        isPlaying = lp.isPlaying,
                        streamUrl = lp.streamUrl,
                        canStart = lp.canStart,
                        canStop = lp.canStop,
                    ),
                )
                host.heyCyanLivePlayer = if (lp.isPlaying) livePreviewManager.getPlayer() else null
                livePreviewDialog?.let { dialog ->
                    livePreviewDialog = null
                    runCatching { dialog.dismiss() }
                }
            }
        }
    }

    fun stopLivePreview() {
        livePreviewManager.stop()
        host.heyCyanLivePlayer = null
        com.fersaiyan.cyanbridge.devices.heycyan.HeyCyanLiveForegroundService.stop(activity)
        livePreviewDialog?.let { dialog ->
            livePreviewDialog = null
            dialog.dismiss()
        }
    }

    fun unregisterHeyCyanLiveStopReceiver() {
        heyCyanLiveStopReceiver?.let { runCatching { activity.unregisterReceiver(it) } }
        heyCyanLiveStopReceiver = null
    }
}
