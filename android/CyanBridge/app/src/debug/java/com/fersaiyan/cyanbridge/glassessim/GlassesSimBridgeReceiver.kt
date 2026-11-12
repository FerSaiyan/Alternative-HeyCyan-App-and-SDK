package com.fersaiyan.cyanbridge.glassessim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * DEBUG-ONLY control surface to arm/disarm [GlassesSimBridge] from the host over adb, so a live
 * E2E can redirect the glasses control plane to the simulator without touching app launch code.
 *
 * Declared only in `src/debug/AndroidManifest.xml`; it does not exist in release builds.
 *
 * Arm (after `adb reverse tcp:9900 tcp:<sim CTRL port>`):
 *   adb shell am broadcast -a com.fersaiyan.cyanbridge.debug.SIM_BRIDGE \
 *       -n com.fersaiyan.cyanbridge/.glassessim.GlassesSimBridgeReceiver \
 *       --es cmd start --es host 127.0.0.1 --ei port 9900
 * Disarm:
 *   adb shell am broadcast -a com.fersaiyan.cyanbridge.debug.SIM_BRIDGE \
 *       -n com.fersaiyan.cyanbridge/.glassessim.GlassesSimBridgeReceiver --es cmd stop
 */
class GlassesSimBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext
        when (intent.getStringExtra("cmd")) {
            "start" -> {
                val host = intent.getStringExtra("host") ?: "127.0.0.1"
                val port = intent.getIntExtra("port", -1)
                if (port <= 0) {
                    Log.w(TAG, "start ignored: missing/invalid port")
                    return
                }
                synchronized(lock) {
                    active?.stop()
                    active = GlassesSimBridge(app, host, port).also { it.start() }
                }
            }
            "stop" -> synchronized(lock) {
                active?.stop()
                active = null
            }
            // add.221 media-sync lifecycle harness commands (debug-only).
            "connect" -> {
                val mac = intent.getStringExtra("mac") ?: GlassesSimBridge.DEFAULT_ADDRESS
                GlassesSimHarness.markConnected(mac)
            }
            "sync" -> {
                val flow = intent.getStringExtra("flow") ?: "custom"
                GlassesSimHarness.startSync(flow)
            }
            "bleip" -> {
                val ip = intent.getStringExtra("ip")
                if (ip.isNullOrBlank()) Log.w(TAG, "bleip ignored: missing ip")
                else GlassesSimHarness.deliverBleIp(ip)
            }
            "p2p" -> {
                val ip = intent.getStringExtra("ip") ?: "127.0.0.1"
                val owner = intent.getBooleanExtra("owner", false)
                GlassesSimHarness.injectP2pGroup(app, ip, owner)
            }
            else -> Log.w(TAG, "unknown cmd")
        }
    }

    companion object {
        private const val TAG = "GlassesSimBridgeRecv"
        const val ACTION = "com.fersaiyan.cyanbridge.debug.SIM_BRIDGE"
        private val lock = Any()
        private var active: GlassesSimBridge? = null
    }
}
