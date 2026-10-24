package com.fersaiyan.cyanbridge.wifiadb

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.audio.MeetingCapturePrefs
import com.fersaiyan.cyanbridge.glasses.GlassesSession
import com.fersaiyan.cyanbridge.glasses.GlassesSessionLease
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCaptureService
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.shared.glasses.WifiAdbDebugUiState
import com.fersaiyan.cyanbridge.ui.hasBluetooth
import com.fersaiyan.cyanbridge.ui.hasWifiP2pPermission
import com.oudmon.ble.base.bluetooth.BleOperateManager
import kotlinx.coroutines.launch

/** Starts the debug-only HeyCyan Wi-Fi ADB session and mirrors its state into the dashboard. */
internal class WifiAdbDebugCoordinator(
    private val activity: AppCompatActivity,
    private val controllerProvider: () -> WifiAdbDebugController,
    private val host: Host,
) {
    interface Host {
        var dashboardState: GlassesDashboardUiState
        var wifiAdbDebugSessionLease: GlassesSessionLease?
        fun isHeyCyanSelected(): Boolean
        fun ensureGlassesTransportPermissions(feature: String, onGranted: () -> Unit)
        fun acquireExclusiveGlassesSession(session: GlassesSession): GlassesSessionLease?
        fun releaseExclusiveGlassesSession(lease: GlassesSessionLease?)
    }

    private val wifiAdbDebugController: WifiAdbDebugController get() = controllerProvider()

    private var dashboardState: GlassesDashboardUiState
        get() = host.dashboardState
        set(value) {
            host.dashboardState = value
        }

    private fun isHeyCyanSelected(): Boolean = host.isHeyCyanSelected()

    fun startWifiAdbDebug() {
        if (!isHeyCyanSelected()) return
        if (!BuildConfig.DEBUG || wifiAdbDebugController.isActive) return
        if (!hasBluetooth(activity) || !hasWifiP2pPermission(activity)) {
            host.ensureGlassesTransportPermissions("Wi-Fi ADB debug") {
                startWifiAdbDebug()
            }
            return
        }
        if (!BleOperateManager.getInstance().isConnected) {
            Toast.makeText(activity, "Connect the glasses over Bluetooth first.", Toast.LENGTH_LONG).show()
            return
        }
        if (AutoAudioCaptureService.isRunning()) {
            Toast.makeText(activity, "Stop automatic capture before starting Wi-Fi ADB.", Toast.LENGTH_LONG).show()
            return
        }
        if (MeetingCapturePrefs.getState(activity).isRecording) {
            Toast.makeText(activity, "Stop recording before starting Wi-Fi ADB.", Toast.LENGTH_LONG).show()
            return
        }
        val lease = host.acquireExclusiveGlassesSession(GlassesSession.WIFI_ADB_DEBUG) ?: return
        host.wifiAdbDebugSessionLease = lease
        wifiAdbDebugController.start {
            host.releaseExclusiveGlassesSession(lease)
        }
    }

    fun observeWifiAdbDebugState() {
        activity.lifecycleScope.launch {
            wifiAdbDebugController.state.collect { runtime ->
                dashboardState = dashboardState.copy(
                    wifiAdbDebug = WifiAdbDebugUiState(
                        isAvailable = BuildConfig.DEBUG && isHeyCyanSelected() && runtime.isAvailable,
                        stateLabel = runtime.stateLabel,
                        detail = runtime.detail,
                        glassesIp = runtime.glassesIp,
                        relayEndpoints = runtime.relayEndpoints,
                        preferredCommand = runtime.preferredCommand,
                        canStart = runtime.canStart,
                        canStop = runtime.canStop,
                    ),
                )
            }
        }
    }
}
