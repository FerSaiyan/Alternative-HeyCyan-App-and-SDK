package com.fersaiyan.cyanbridge.glassessim

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fersaiyan.cyanbridge.ui.wifi.p2p.WifiP2pManagerSingleton
import java.net.InetAddress

/**
 * DEBUG-ONLY media-sync lifecycle harness (add.221).
 *
 * add.219 wired the BLE->TCP notify bridge ([GlassesSimBridge]) and proved the (patched) vendor
 * cmd-65 parser runs live in ART on an AVD. What it left owed was a FULL UI-driven media sync per
 * flow: the coordinator ([com.fersaiyan.cyanbridge.media.sync.GlassesMediaSyncCoordinator]) only
 * advances into the HTTP media download after TWO lifecycle events that an Android emulator CANNOT
 * physically produce:
 *
 *   1. A connected vendor BLE GATT link  (`BleOperateManager.isConnected`, backed by
 *      `BleBaseControl.mIsConnected`). netsim exposes no connectable vendor GATT (add.72 / add.218),
 *      so the real connect path never completes on an AVD.
 *   2. A formed Wi-Fi Direct (Wi-Fi P2P) group  (`WifiP2pInfo.groupFormed == true`). The emulator
 *      has no `p2p0` interface; `WifiP2pManager.discoverPeers` / `connect` never form a group.
 *
 * This harness SIMULATES exactly those two un-emulatable gates (and nothing above them), so the rest
 * of the pipeline — the real vendor cmd-65 parse, the real coordinator state machine, the real
 * OkHttp / httpGet media.config fetch, the real media-file download, and the real completion
 * callbacks — all run for real against a reachable `/files/` server. Everything the harness forces
 * is a state the glasses hardware itself would have produced; it fabricates no media and no result.
 *
 * It is `src/debug` only: no release counterpart, no main-code reference, inert until armed over adb
 * via [GlassesSimBridgeReceiver]. `WifiP2pManagerSingleton.onConnectionInfoAvailable` is `internal`
 * and visible here because the debug variant compiles into the same module as main.
 */
object GlassesSimHarness {
    private const val TAG = "GlassesSimHarness"
    private val main = Handler(Looper.getMainLooper())

    /**
     * SIMULATE gate (1): a connected + services-discovered vendor BLE GATT link.
     *
     * `BleBaseControl.mIsConnected` has no public setter (the vendor only sets it from inside the
     * real GATT callback), so we reflect it on — the single, documented concession for the gate the
     * AVD cannot satisfy. We then drive the vendor's own faithful connected / service-discovered
     * broadcasts (`bleGattConnected` -> `com.swatchdevice.pro.sdk.ble.gatt_connected`,
     * `bleServiceDiscovered`) so the app's connection lifecycle observers fire exactly as on real
     * hardware.
     */
    fun markConnected(mac: String) {
        runCatching {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            val device = runCatching { adapter?.getRemoteDevice(mac) }.getOrNull()

            // Reflect BleBaseControl.getInstance().mIsConnected = true (+ connecting = false).
            val baseCls = Class.forName("com.oudmon.ble.base.bluetooth.BleBaseControl")
            val base = baseCls.getMethod("getInstance").invoke(null)
            setBool(baseCls, base, "mIsConnected", true)
            setBool(baseCls, base, "connecting", false)
            if (device != null) setField(baseCls, base, "bleConnectDevice", device)

            // Drive the vendor's own connected lifecycle (ready + faithful broadcasts).
            val opCls = Class.forName("com.oudmon.ble.base.bluetooth.BleOperateManager")
            val op = opCls.getMethod("getInstance").invoke(null)
            runCatching { opCls.getMethod("setReady", Boolean::class.javaPrimitiveType).invoke(op, true) }
            if (device != null) {
                runCatching {
                    opCls.getMethod("bleGattConnected", android.bluetooth.BluetoothDevice::class.java)
                        .invoke(op, device)
                }
            }
            runCatching {
                opCls.getMethod("bleServiceDiscovered", Int::class.javaPrimitiveType, String::class.java)
                    .invoke(op, 0, mac)
            }
            val connected = opCls.getMethod("isConnected").invoke(op) as? Boolean
            Log.w(TAG, "markConnected($mac): simulated vendor GATT connected+services-discovered; isConnected=$connected")
        }.onFailure { Log.w(TAG, "markConnected failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /**
     * SIMULATE gate (2): a formed Wi-Fi Direct group. Build a [WifiP2pInfo] exactly as the framework
     * would after a group forms (groupFormed=true, groupOwnerAddress=[ip]) and hand it to the app's
     * singleton through its `internal onConnectionInfoAvailable` seam — the same method the real
     * `WIFI_P2P_CONNECTION_CHANGED` -> `requestConnectionInfo` callback invokes. The coordinator's
     * registered `WifiP2pCallback.onConnected` then runs for real, flipping `downloadP2pConnected`
     * and triggering `maybeStartHttpDownload`.
     */
    fun injectP2pGroup(context: Context, ip: String, isGroupOwner: Boolean) {
        runCatching {
            val info = WifiP2pInfo().apply {
                groupFormed = true
                this.isGroupOwner = isGroupOwner
                groupOwnerAddress = InetAddress.getByName(ip)
            }
            main.post {
                runCatching {
                    WifiP2pManagerSingleton.getInstance(context.applicationContext)
                        .onConnectionInfoAvailable(info)
                    Log.w(TAG, "injectP2pGroup: simulated Wi-Fi Direct group formed (owner=$ip, phoneIsGroupOwner=$isGroupOwner)")
                }.onFailure { Log.w(TAG, "injectP2pGroup post failed: ${it.javaClass.simpleName}: ${it.message}") }
            }
        }.onFailure { Log.w(TAG, "injectP2pGroup failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /**
     * Drive the REAL media-sync entry the sync-flow picker calls: `MainActivity.startDataDownload`.
     * This is the identical code path the UI exercises (the picker's `onSyncFlowSelected` lambda),
     * reached here over an adb intent instead of a screen tap. No app behaviour is bypassed.
     */
    fun startSync(flowName: String) {
        main.post {
            runCatching {
                val activity = currentActivity() ?: run {
                    Log.w(TAG, "startSync: no foreground MainActivity"); return@post
                }
                val flow = flowEnum(flowName) ?: run {
                    Log.w(TAG, "startSync: unknown flow $flowName"); return@post
                }
                val m = activity.javaClass.getDeclaredMethod("startDataDownload", flow.javaClass)
                m.isAccessible = true
                m.invoke(activity, flow)
                Log.w(TAG, "startSync: invoked MainActivity.startDataDownload(${(flow as Enum<*>).name})")
            }.onFailure { Log.w(TAG, "startSync failed: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    /**
     * Deliver the BLE-reported glasses Wi-Fi IP directly into the coordinator seam
     * (`MainActivity.onDownloadBleIp`). The authentic route is a 0x73/0x08 notify over the bridge
     * (driven by the host feed); this direct call is a robustness fallback for when the official
     * flow's main-handler skip or notify routing would otherwise swallow it, and is logged as such.
     */
    fun deliverBleIp(ip: String) {
        main.post {
            runCatching {
                val activity = currentActivity() ?: run {
                    Log.w(TAG, "deliverBleIp: no foreground MainActivity"); return@post
                }
                val m = activity.javaClass.getDeclaredMethod("onDownloadBleIp", String::class.java)
                m.isAccessible = true
                m.invoke(activity, ip)
                Log.w(TAG, "deliverBleIp: delivered BLE IP $ip to coordinator (harness direct)")
            }.onFailure { Log.w(TAG, "deliverBleIp failed: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    private fun flowEnum(name: String): Any? {
        val norm = when (name.lowercase()) {
            "official", "official_heycyan", "heycyan" -> "OFFICIAL_HEYCYAN"
            "custom" -> "CUSTOM"
            "adaptive" -> "ADAPTIVE"
            else -> name.uppercase()
        }
        return runCatching {
            val cls = Class.forName("com.fersaiyan.cyanbridge.shared.glasses.GlassesSyncFlow")
            @Suppress("UNCHECKED_CAST")
            java.lang.Enum.valueOf(cls as Class<out Enum<*>>, norm)
        }.getOrNull()
    }

    /** Foreground Activity via ActivityThread reflection (debug-only). */
    private fun currentActivity(): android.app.Activity? = runCatching {
        val atCls = Class.forName("android.app.ActivityThread")
        val at = atCls.getMethod("currentActivityThread").invoke(null)
        val activitiesField = atCls.getDeclaredField("mActivities").apply { isAccessible = true }
        val activities = activitiesField.get(at) as? Map<*, *> ?: return@runCatching null
        for (record in activities.values) {
            record ?: continue
            val rCls = record.javaClass
            val pausedF = rCls.getDeclaredField("paused").apply { isAccessible = true }
            if (pausedF.getBoolean(record)) continue
            val actF = rCls.getDeclaredField("activity").apply { isAccessible = true }
            val act = actF.get(record) as? android.app.Activity ?: continue
            if (act.javaClass.name.endsWith("MainActivity")) return@runCatching act
        }
        // Fall back to any resumed activity.
        for (record in activities.values) {
            record ?: continue
            val actF = record.javaClass.getDeclaredField("activity").apply { isAccessible = true }
            (actF.get(record) as? android.app.Activity)?.let { return@runCatching it }
        }
        null
    }.getOrNull()

    private fun setBool(cls: Class<*>, target: Any, field: String, value: Boolean) {
        val f = cls.getDeclaredField(field); f.isAccessible = true; f.setBoolean(target, value)
    }

    private fun setField(cls: Class<*>, target: Any, field: String, value: Any) {
        val f = cls.getDeclaredField(field); f.isAccessible = true; f.set(target, value)
    }
}
