package com.fersaiyan.cyanbridge.glasses.offlineauth

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.oudmon.ble.base.bluetooth.LargeDataParser
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.queue.BleDataBean
import com.oudmon.ble.base.bluetooth.queue.BleThreadManager
import com.oudmon.ble.base.communication.LargeDataHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Raw HeyCyan vendor-characteristic channel for the offline-auth frames the typed vendor API cannot
 * carry. `LargeDataHandler.glassesControl` only ever sends cmd 0x41 and only surfaces a typed
 * [com.oudmon.ble.base.communication.bigData.resp.GlassModelControlResponse]; the auth flow must send
 * cmd 0x47/0x71 **and** the FE-DC-BA RCSP frames, and must read their raw reply bytes (cmd-47 byte-14,
 * the RCSP challenge/auth-ok, the cmd-71 bind status).
 *
 * Both directions ride the vendor pair BigData uses (service de5bf728 / write de5bf72a / notify
 * de5bf729), so this stays within the SDK's own connection — no second GATT client:
 *  - WRITE: `BleThreadManager.addData(BleDataBean(frame, packageLen))` enqueues the frame verbatim;
 *    `BleConsumer` chunks it and writes it to de5bf72a (getNoRspInstance, WRITE_NO_RESPONSE). It does
 *    NOT re-wrap (the 0xBC/CRC header is only added by the higher-level handlers, never in the queue),
 *    so a fully-formed frame — 0xBC or FE-DC-BA — is written as-is.
 *  - READ: raw de5bf729 notifies reach [onNotify] (fed from `MyBluetoothReceiver.onCharacteristicChange`).
 *
 * Replies can still be split by a small link MTU (the 21-byte cap reply on a 20-byte link), see [reassemble]; and
 * the vendor SDK parses the same notifies, so RCSP traffic must reset its parser ([resetVendorParser]).
 */
object HeyCyanAuthRawChannel {

    private const val TAG = "HeyCyanAuthRaw"

    /** JPackageManager.getLength() default on the vendor stack; auth frames are far smaller. */
    private const val PACKAGE_LENGTH = 244

    private val BC_HEADER = HeyCyanAuthProtocol.BC_HEADER
    private val RCSP_B0 = 0xFE
    private val RCSP_B1 = 0xDC
    private val RCSP_B2 = 0xBA

    /** Gated BigData commands whose 1-byte `03` reply is the pre-auth/pre-bind NAK. */
    private val GATED_NAK_CMDS = intArrayOf(0x41, 0x45)
    private const val NAK_BYTE = 0x03

    private val lock = Any()
    private var waiter: CompletableDeferred<ByteArray>? = null
    private var expect: ((ByteArray) -> Boolean)? = null

    /** Observed NAK callback, so a gated command that gets `03` can trigger an unlock + one retry. */
    @Volatile
    private var nakListener: ((Int) -> Unit)? = null

    fun setNakListener(listener: ((Int) -> Unit)?) {
        nakListener = listener
    }

    /** True when the vendor link is up (required before any raw write). */
    fun isConnected(): Boolean = runCatching { BleOperateManager.getInstance().isConnected }.getOrDefault(false)

    /**
     * Write [frame] (a fully-formed 0xBC or FE-DC-BA frame) and await the next matching reply notify.
     * [matches] selects the reply (e.g. "a BC frame with opcode 0x47"); times out via [timeoutMs].
     */
    suspend fun exchange(frame: ByteArray, timeoutMs: Long, matches: (ByteArray) -> Boolean): ByteArray {
        val deferred = CompletableDeferred<ByteArray>()
        synchronized(lock) {
            waiter = deferred
            expect = matches
        }
        writeRaw(frame)
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (t: TimeoutCancellationException) {
            synchronized(lock) {
                if (waiter === deferred) {
                    waiter = null
                    expect = null
                }
            }
            throw t
        }
    }

    private fun writeRaw(frame: ByteArray) {
        // Posted before the write, so it runs before the reply's notify broadcast is delivered.
        resetVendorParser()
        runCatching { LargeDataHandler.getInstance().packageLength() } // refresh negotiated length
        BleThreadManager.getInstance().addData(BleDataBean(frame, PACKAGE_LENGTH))
        Log.d(TAG, "wrote raw frame len=${frame.size} head=0x%02X".format(frame.firstOrNull()?.toInt()?.and(0xFF) ?: 0))
    }

    /** A 0xBC frame whose payload spans several notifications (link MTU smaller than the frame). */
    private var partial: ByteArray? = null
    private const val BC_HEADER_LENGTH = 6

    /**
     * Fed every de5bf729 notify (and characteristic read) by the BLE receiver. Never throws.
     * Replies longer than the link's notification size arrive split (e.g. the 21-byte cmd-0x47 capability reply on
     * a 20-byte link; the vendor SDK reassembles them internally, this raw path must too), so a 0xBC frame is
     * buffered until its declared payload length has arrived.
     */
    /** Uptime of the last vendor notify: proof that notifications are enabled on the current link. */
    @Volatile
    var lastNotifyUptimeMs: Long = 0L
        private set

    fun onNotify(data: ByteArray?) {
        if (data == null || data.isEmpty()) return
        lastNotifyUptimeMs = android.os.SystemClock.uptimeMillis()
        if (isRcspReply(data)) resetVendorParser()
        val frame = reassemble(data) ?: return
        handleFrame(frame)
    }

    /** The complete frame [data] finishes, or null while a 0xBC frame is still incomplete. */
    internal fun reassemble(data: ByteArray): ByteArray? = synchronized(lock) {
        val startsBc = (data[0].toInt() and 0xFF) == BC_HEADER
        val buffered = partial
        val candidate = when {
            buffered != null && !startsBc && !isRcspReply(data) -> buffered + data
            else -> data.also { partial = null }
        }
        if ((candidate[0].toInt() and 0xFF) != BC_HEADER || candidate.size < BC_HEADER_LENGTH) {
            partial = null
            return@synchronized candidate
        }
        val declared = (candidate[2].toInt() and 0xFF) or ((candidate[3].toInt() and 0xFF) shl 8)
        if (candidate.size < BC_HEADER_LENGTH + declared) {
            partial = candidate
            null
        } else {
            partial = null
            candidate
        }
    }

    private fun handleFrame(data: ByteArray) {
        // Reactive NAK detection runs regardless of an in-flight exchange.
        detectNak(data)?.let { cmd -> runCatching { nakListener?.invoke(cmd) } }

        val pending: CompletableDeferred<ByteArray>?
        val predicate: ((ByteArray) -> Boolean)?
        synchronized(lock) {
            pending = waiter
            predicate = expect
        }
        if (pending == null || predicate == null) return
        if (!predicate(data)) return
        synchronized(lock) {
            if (waiter === pending) {
                waiter = null
                expect = null
            }
        }
        pending.complete(data.copyOf())
    }

    /** Returns the gated cmd id when [data] is a 1-byte `03` NAK for a gated command, else null. */
    private fun detectNak(data: ByteArray): Int? {
        val parsed = HeyCyanAuthProtocol.parseBcFrame(data) ?: return null
        if (parsed.payload.size == 1 && (parsed.payload[0].toInt() and 0xFF) == NAK_BYTE &&
            parsed.opcode in GATED_NAK_CMDS.toList()
        ) {
            return parsed.opcode
        }
        return null
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * The vendor SDK reassembles de5bf729 notifies in [LargeDataParser] (its own receiver, fed the same broadcasts).
     * It treats any frame that does not start with 0xBC while idle as a continuation: an RCSP reply (`FE DC BA …`)
     * sets its `end` flag to false with a length that never matches, and from then on every 0xBC notify is appended
     * to that dead buffer. Seen on a test rig: after the unlock, the cmd-65 replies and the 0x73/0x08 Wi-Fi IP
     * notify never reached the app and media sync timed out. Reset it after RCSP traffic, on the main thread where
     * LocalBroadcastManager delivers the notifies. `end` is package-private (kept by `-keep class com.oudmon.**`).
     */
    internal fun resetVendorParser() {
        mainHandler.post {
            runCatching {
                val parser = LargeDataParser.getInstance()
                parser.tempData = ByteArray(0)
                parser.dataLength = 0
                LargeDataParser::class.java.getDeclaredField("end").apply { isAccessible = true }.setBoolean(parser, true)
            }.onFailure { Log.w(TAG, "Vendor BigData parser reset failed", it) }
        }
    }

    // ---- reply matchers used by the transport ----------------------------------------------------

    fun isRcspReply(data: ByteArray): Boolean =
        data.size >= 4 &&
            (data[0].toInt() and 0xFF) == RCSP_B0 &&
            (data[1].toInt() and 0xFF) == RCSP_B1 &&
            (data[2].toInt() and 0xFF) == RCSP_B2

    fun isBcReply(data: ByteArray, opcode: Int): Boolean {
        if (data.isEmpty() || (data[0].toInt() and 0xFF) != BC_HEADER) return false
        val parsed = HeyCyanAuthProtocol.parseBcFrame(data) ?: return false
        return parsed.opcode == opcode
    }
}
