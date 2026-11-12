package com.fersaiyan.cyanbridge.glassessim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DEBUG-ONLY glasses BLE/SPP -> TCP redirection shim (add.219 / add.72 design).
 *
 * The AVD has no usable app-level BLE and netsim exposes no connectable vendor GATT server
 * (add.72 / add.218), so the real app cannot receive glasses cmd-65 notifies over BLE on an
 * emulator. This shim redirects the glasses control plane to the glasses simulator over a
 * loopback TCP socket (`adb reverse tcp:<port>` analogue), letting the real app on an AVD
 * receive cmd-65 notifies and exercise the real vendor parse path WITHOUT real hardware.
 *
 * Faithfulness: an inbound sim frame is injected EXACTLY the way the vendor SDK dispatches a
 * real BLE characteristic notification. `BleOperateManager.mySendBroadcast(...)` emits a
 * `LocalBroadcastManager` Intent with action `com.swatchdevice.pro.characteristic_changed_qc`
 * and extras ADDRESS / CHARACTER_UUID / VALUE; replaying that same Intent drives BOTH the
 * vendor's internal `QCBluetoothCallbackBigDataCloneReceiver` (the real cmd-65 parser, now
 * patched) AND the app's own `MyBluetoothReceiver` (the add.218 app-seam guard). Outbound app
 * control writes are observed via the vendor's `characteristic_write_qc` broadcast and mirrored
 * to the sim, so the sim can drive request/response (e.g. reply to a glassesControl request).
 *
 * This file lives in `src/debug` ONLY. It is never compiled into release; there is no release
 * counterpart and no main-code reference, so release behaviour is unchanged. It is inert until
 * explicitly armed (see [GlassesSimBridgeReceiver]).
 *
 * Wire framing matches the sim CONTRACT.md CTRL plane: length-prefixed `[u16 BE len][payload]`.
 */
class GlassesSimBridge(
    private val appContext: Context,
    private val host: String,
    private val port: Int,
    private val deviceAddress: String = DEFAULT_ADDRESS,
) {
    private val running = AtomicBoolean(false)
    private var socket: Socket? = null
    private var reader: Thread? = null
    private var out: DataOutputStream? = null

    // Mirror app->glasses control writes to the sim so it can respond.
    private val outboundTap = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val data = intent?.getByteArrayExtra(EXTRA_DATA) ?: return
            runCatching {
                synchronized(this@GlassesSimBridge) {
                    out?.let { o -> writeFrame(o, data) }
                }
            }.onFailure { Log.w(TAG, "outbound mirror failed: ${it.javaClass.simpleName}") }
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        LocalBroadcastManager.getInstance(appContext)
            .registerReceiver(outboundTap, IntentFilter(ACTION_CHAR_WRITE))
        reader = Thread({ runLoop() }, "glasses-sim-bridge").apply { isDaemon = true; start() }
        Log.w(TAG, "armed -> $host:$port (notify uuid=$NOTIFY_UUID addr=$deviceAddress)")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { LocalBroadcastManager.getInstance(appContext).unregisterReceiver(outboundTap) }
        runCatching { socket?.close() }
        socket = null
        out = null
        Log.w(TAG, "disarmed")
    }

    private fun runLoop() {
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket = s
            val input = DataInputStream(s.getInputStream())
            synchronized(this) { out = DataOutputStream(s.getOutputStream()) }
            Log.w(TAG, "connected to sim CTRL $host:$port")
            while (running.get()) {
                val len = (input.readUnsignedByte() shl 8) or input.readUnsignedByte() // u16 BE
                if (len <= 0 || len > MAX_FRAME) {
                    Log.w(TAG, "bad frame length $len; dropping link")
                    break
                }
                val payload = ByteArray(len)
                input.readFully(payload)
                injectNotify(payload)
            }
        } catch (t: Throwable) {
            if (running.get()) Log.w(TAG, "link error: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { socket?.close() }
        }
    }

    /**
     * Inject a sim frame as if it were a real BLE characteristic notification, via the exact
     * LocalBroadcast the vendor stack emits. Drives the real vendor parser + the app guard.
     */
    private fun injectNotify(payload: ByteArray) {
        val intent = Intent(ACTION_CHAR_CHANGED).apply {
            putExtra(EXTRA_ADDRESS, deviceAddress)
            putExtra(EXTRA_CHARACTER_UUID, NOTIFY_UUID)
            putExtra(EXTRA_VALUE, payload)
        }
        LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent)
    }

    private fun writeFrame(o: DataOutputStream, payload: ByteArray) {
        o.writeByte((payload.size ushr 8) and 0xFF)
        o.writeByte(payload.size and 0xFF)
        o.write(payload)
        o.flush()
    }

    companion object {
        private const val TAG = "GlassesSimBridge"

        // Vendor broadcast contract (decompiled from glasses_sdk_20250723_v01.aar:
        // QCBluetoothCallbackCloneReceiver.onReceive / BleOperateManager.mySendBroadcast).
        const val ACTION_CHAR_CHANGED = "com.swatchdevice.pro.characteristic_changed_qc"
        const val ACTION_CHAR_WRITE = "com.swatchdevice.pro.characteristic_write_qc"
        const val EXTRA_ADDRESS = "ADDRESS"
        const val EXTRA_CHARACTER_UUID = "CHARACTER_UUID"
        const val EXTRA_VALUE = "VALUE"
        const val EXTRA_DATA = "DATA"

        // Oudmon glasses CTRL notify characteristic (Constants: de5bf729...).
        const val NOTIFY_UUID = "de5bf729-d711-4e47-af26-65e3012a5dc7"

        // Synthetic HeyCyan sim identity (add.72 device table).
        const val DEFAULT_ADDRESS = "D4:9E:3F:00:02:01"

        private const val CONNECT_TIMEOUT_MS = 4000
        private const val MAX_FRAME = 64 * 1024
    }
}
