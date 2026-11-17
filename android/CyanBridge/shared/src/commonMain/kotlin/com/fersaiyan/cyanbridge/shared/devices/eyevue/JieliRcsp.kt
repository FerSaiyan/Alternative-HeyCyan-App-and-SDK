package com.fersaiyan.cyanbridge.shared.devices.eyevue

import kotlin.random.Random

/**
 * JieLi RCSP, the EyeVue (BR28) firmware-update and target-info protocol on GATT service AE00
 * (AE01 write, AE02 notify). Ported from the owner's reverse-engineered reference
 * (firmware_dump add.175 `jl_rcsp_ota_add175.py`, checked there against the vendor app and its
 * native auth library).
 *
 * Frame: `FE DC BA | flags | opcode | u16 BE len | body | EF`. flags bit7 = command, bit6 = the
 * command expects a response. Command body = sn [, xmOpcode for opcode 0x01], payload; response
 * body = status, sn [, xmOpcode], payload.
 */
data class RcspPacket(
    val opcode: Int,
    val payload: ByteArray = ByteArray(0),
    val isCommand: Boolean = true,
    val hasResponse: Boolean = true,
    val sn: Int = 0,
    val status: Int = 0,
    val xmOpcode: Int = 0,
)

/** The RCSP 0x03 target-info answer (the fields the app uses). */
data class RcspTargetInfo(
    val versionCode: Int? = null,
    val doubleBackup: Boolean = false,
    val needBootLoader: Boolean = false,
    val mandatory: Boolean = false,
    val vid: Int? = null,
    val pid: Int? = null,
    val projectCode: String? = null,
    val customVersion: String? = null,
) {
    /** Firmware version as the vendor shows it (`1.3.6.0`). */
    val version: String? get() = versionCode?.let(JieliRcsp::versionName)
}

object JieliRcsp {
    const val SERVICE_UUID = "0000ae00-0000-1000-8000-00805f9b34fb"
    const val WRITE_UUID = "0000ae01-0000-1000-8000-00805f9b34fb"
    const val NOTIFY_UUID = "0000ae02-0000-1000-8000-00805f9b34fb"

    const val OP_DATA = 0x01
    const val OP_GET_TARGET_INFO = 0x03
    const val OP_SWITCH_COMM_WAY = 0x0B
    const val OP_SET_MTU = 0xD1
    const val OP_E1_FILE_INFO_OFFSET = 0xE1
    const val OP_E2_INQUIRE_CAN_UPDATE = 0xE2
    const val OP_E3_ENTER_UPDATE = 0xE3
    const val OP_E4_EXIT_UPDATE = 0xE4
    const val OP_E5_BLOCK = 0xE5
    const val OP_E6_UPDATE_STATUS = 0xE6
    const val OP_E7_REBOOT = 0xE7
    const val OP_E8_CONTENT_SIZE = 0xE8

    private val PREFIX = byteArrayOf(0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte())
    private const val END = 0xEF

    /** RcspAuth reset: `FE DC BA C0 06 00 02 00 01 EF`, sent raw before the challenge. */
    val AUTH_RESET_CMD: ByteArray = hex("FEDCBAC00600020001EF")
    val AUTH_OK: ByteArray = byteArrayOf(0x02) + "pass".encodeToByteArray()
    val DEFAULT_LINK_KEY: ByteArray = hex("06775f87918dd423005df1d8cf0c142b")
    val DEFAULT_AUTH_ADDR: ByteArray = hex("112233332211")

    val E2_CODES = mapOf(
        0 to "ok", 1 to "low battery", 2 to "corrupted upgrade file", 3 to "same version",
        4 to "TWS not connected", 5 to "not in charging bin", 6 to "device is being upgraded", 7 to "device dual mode",
    )
    val E6_CODES = mapOf(
        0 to "success", 1 to "data verification failure", 2 to "update failed", 3 to "key mismatch",
        4 to "corrupted upgrade file", 5 to "wrong file type", 6 to "data length error", 7 to "flash IO error",
        8 to "command processing timeout", 9 to "same upgrade file", 128 to "loader downloaded, continue",
        254 to "reboot to continue",
    )

    /** The largest attribute value ATT allows (Core spec Vol 3 Part F 3.2.9), whatever the MTU. */
    const val MAX_ATT_VALUE = 512

    /**
     * Bytes per AE01 write at the negotiated ATT [mtu]: MTU - 3, capped at [MAX_ATT_VALUE].
     * At MTU 517 a 514-byte write is rejected by the peer's GATT server.
     */
    fun attWriteSize(mtu: Int): Int = (mtu - 3).coerceIn(20, MAX_ATT_VALUE)

    fun encode(p: RcspPacket): ByteArray {
        val body = ArrayList<Byte>(p.payload.size + 3)
        if (!p.isCommand) body += p.status.toByte()
        body += p.sn.toByte()
        if (p.opcode == OP_DATA) body += p.xmOpcode.toByte()
        p.payload.forEach { body += it }
        val flags = (if (p.isCommand) 0x80 else 0) or (if (p.isCommand && p.hasResponse) 0x40 else 0)
        return PREFIX + byteArrayOf(
            flags.toByte(), p.opcode.toByte(), (body.size ushr 8).toByte(), body.size.toByte(),
        ) + body.toByteArray() + byteArrayOf(END.toByte())
    }

    /** `x.y.z.w` from the 4-nibble version code. */
    fun versionName(code: Int): String =
        "${(code shr 12) and 15}.${(code shr 8) and 15}.${(code shr 4) and 15}.${code and 15}"

    fun parseTargetInfo(data: ByteArray): RcspTargetInfo {
        var info = RcspTargetInfo()
        var i = 0
        while (i < data.size) {
            val ln = data[i].u8()
            if (ln < 1 || ln > data.size - i - 1) break
            val type = data[i + 1].u8()
            val v = data.copyOfRange(i + 2, i + 1 + ln)
            i += 1 + ln
            info = when (type) {
                5 -> if (v.size >= 2) info.copy(versionCode = be16(v, 0)) else info
                8 -> if (v.isNotEmpty()) info.copy(doubleBackup = v[0].u8() == 1, needBootLoader = v.size > 1 && v[1].u8() == 1) else info
                9 -> if (v.isNotEmpty()) info.copy(mandatory = v[0].u8() == 1) else info
                10 -> when {
                    v.size == 4 -> info.copy(vid = 1494, pid = be16(v, 2))
                    v.size >= 6 -> info.copy(vid = be16(v, 0), pid = be16(v, 2))
                    else -> info
                }
                12 -> info.copy(projectCode = v.decodeLatin1())
                31 -> info.copy(customVersion = v.decodeLatin1())
                else -> info
            }
        }
        return info
    }

    // ── E1 link authentication (Bluetooth SAFER+ Ar/Ar' with the JieLi default key) ──

    private val EXP = IntArray(256)
    private val LOG = IntArray(256)
    private val XMASK = BooleanArray(16) { it in intArrayOf(0, 3, 4, 7, 8, 11, 12, 15) }
    private val PERM = intArrayOf(8, 11, 12, 15, 2, 1, 6, 5, 10, 9, 14, 13, 0, 7, 4, 3)

    init {
        var v = 1
        for (i in 0 until 256) {
            EXP[i] = v and 0xFF
            LOG[v and 0xFF] = i
            v = (v * 45) % 257
        }
    }

    private fun modPow(base: Int, exponent: Int, modulus: Int): Int {
        var result = 1L
        var b = base.toLong() % modulus
        var e = exponent
        while (e > 0) {
            if (e and 1 == 1) result = result * b % modulus
            b = b * b % modulus
            e = e shr 1
        }
        return result.toInt()
    }

    private fun keySchedule(key: IntArray): List<IntArray> {
        var k = IntArray(17) { if (it < 16) key[it] else 0 }
        key.forEach { k[16] = k[16] xor it }
        val out = mutableListOf(key.copyOf())
        for (p in 2..17) {
            k = IntArray(17) { ((k[it] shl 3) or (k[it] ushr 5)) and 0xFF }
            val current = k
            out += IntArray(16) { i ->
                (current[(p - 1 + i) % 17] + modPow(45, modPow(45, 17 * p + i + 1, 257), 257) % 256) and 0xFF
            }
        }
        return out
    }

    private fun ar(key: IntArray, input: IntArray, prime: Boolean): IntArray {
        val ks = keySchedule(key)
        var x = input.copyOf()
        val x0 = input.copyOf()
        for (r in 0 until 8) {
            if (prime && r == 2) x = IntArray(16) { if (XMASK[it]) x[it] xor x0[it] else (x[it] + x0[it]) and 0xFF }
            val k1 = ks[2 * r]
            val k2 = ks[2 * r + 1]
            x = IntArray(16) { if (XMASK[it]) x[it] xor k1[it] else (x[it] + k1[it]) and 0xFF }
            x = IntArray(16) { if (XMASK[it]) EXP[x[it]] else LOG[x[it]] }
            x = IntArray(16) { if (XMASK[it]) (x[it] + k2[it]) and 0xFF else x[it] xor k2[it] }
            for (j in 0 until 4) {
                val y = IntArray(16)
                for (i in 0 until 16 step 2) {
                    y[i] = (2 * x[i] + x[i + 1]) and 0xFF
                    y[i + 1] = (x[i] + x[i + 1]) and 0xFF
                }
                x = if (j == 3) y else IntArray(16) { y[PERM[it]] }
            }
        }
        val k = ks[16]
        return IntArray(16) { if (XMASK[it]) x[it] xor k[it] else (x[it] + k[it]) and 0xFF }
    }

    fun e1(rand16: ByteArray, key: ByteArray = DEFAULT_LINK_KEY, addr: ByteArray = DEFAULT_AUTH_ADDR): ByteArray {
        require(rand16.size == 16 && key.size == 16 && addr.size == 6)
        val r = IntArray(16) { rand16[it].u8() }
        val k = IntArray(16) { key[it].u8() }
        val a = ar(k, r, prime = false)
        val y = IntArray(16) { ((a[it] xor r[it]) + addr[it % 6].u8()) and 0xFF }
        val off = intArrayOf(233, 229, 223, 193, 179, 167, 149, 131)
        val kt = IntArray(16) { i ->
            if ((i < 8) == (i % 2 == 0)) (k[i] + off[i % 8]) and 0xFF else k[i] xor off[i % 8]
        }
        return ar(kt, y, prime = true).let { out -> ByteArray(16) { out[it].toByte() } }
    }

    /** `01 || E1(challenge[1..16])`: the answer to a 17-byte `00 || rand` challenge. */
    fun authResponse(challenge17: ByteArray): ByteArray = byteArrayOf(0x01) + e1(challenge17.copyOfRange(1, 17))

    internal fun be16(b: ByteArray, o: Int) = (b[o].u8() shl 8) or b[o + 1].u8()
    internal fun be32(b: ByteArray, o: Int): Long =
        (b[o].u8().toLong() shl 24) or (b[o + 1].u8().toLong() shl 16) or (b[o + 2].u8().toLong() shl 8) or b[o + 3].u8().toLong()
    internal fun Byte.u8() = toInt() and 0xFF
    private fun ByteArray.decodeLatin1() = map { (it.toInt() and 0xFF).toChar() }.joinToString("")

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** Reassembles RCSP frames from notifications (drops garbage and over-long frames). */
class RcspParser(private val maxMtu: Int = 530) {
    private var buf = ByteArray(0)

    fun feed(data: ByteArray): List<RcspPacket> {
        buf += data
        val out = mutableListOf<RcspPacket>()
        while (true) {
            val i = indexOfPrefix()
            if (i < 0) {
                val keep = when {
                    buf.size >= 2 && buf[buf.size - 2] == 0xFE.toByte() && buf.last() == 0xDC.toByte() -> 2
                    buf.isNotEmpty() && buf.last() == 0xFE.toByte() -> 1
                    else -> 0
                }
                buf = buf.copyOfRange(buf.size - keep, buf.size)
                return out
            }
            if (i > 0) buf = buf.copyOfRange(i, buf.size)
            if (buf.size < 7) return out
            val len = JieliRcsp.be16(buf, 5)
            if (len > maxMtu - 8) {
                buf = buf.copyOfRange(1, buf.size)
                continue
            }
            if (buf.size < 8 + len) return out
            if ((buf[7 + len].toInt() and 0xFF) != 0xEF) {
                buf = buf.copyOfRange(1, buf.size)
                continue
            }
            out += parse(buf.copyOfRange(3, 7 + len))
            buf = buf.copyOfRange(8 + len, buf.size)
        }
    }

    private fun indexOfPrefix(): Int {
        for (i in 0..buf.size - 3) {
            if (buf[i] == 0xFE.toByte() && buf[i + 1] == 0xDC.toByte() && buf[i + 2] == 0xBA.toByte()) return i
        }
        return -1
    }

    private fun parse(f: ByteArray): RcspPacket {
        val flags = f[0].toInt() and 0xFF
        val op = f[1].toInt() and 0xFF
        val len = JieliRcsp.be16(f, 2)
        val isCommand = flags and 0x80 != 0
        val hasResponse = flags and 0x40 != 0
        if (len == 0) return RcspPacket(op, ByteArray(0), isCommand, hasResponse)
        var i = 4
        var status = 0
        if (!isCommand) {
            status = f[4].toInt() and 0xFF
            i = 5
        }
        val sn = f[i].toInt() and 0xFF
        i++
        var xm = 0
        if (op == JieliRcsp.OP_DATA) {
            xm = f[i].toInt() and 0xFF
            i++
        }
        return RcspPacket(op, f.copyOfRange(i, 4 + len), isCommand, hasResponse, sn, status, xm)
    }
}

/**
 * The app side of a JieLi RCSP session, as a synchronous state machine (mirrors the vendor
 * upgrade steps). Feed every AE02 notification to [onBytes] and write everything [drainOutbox]
 * returns to AE01. With [readInfoOnly] it authenticates, reads the target info and stops.
 *
 * Link drops are part of an update: after [State.RECONNECT] (single-bank loader switch) or
 * [State.REBOOTING] the glasses restart; reconnect and call [onReconnected].
 *
 * Authentication is per link, like the vendor SDK (`isDeviceAuthPass`: once a device passed
 * RcspAuth it skips auth until it disconnects). A second sender on a link that already passed
 * auth starts with `onConnected(linkAuthenticated = true)`; [linkAuthenticated] tells the caller
 * when the current link has passed.
 */
class RcspOtaSender(
    private val file: ByteArray,
    private val readInfoOnly: Boolean = false,
    private val useAuth: Boolean = true,
    private val random: Random = Random.Default,
    private val mtu: Int = 530,
) {
    enum class State { IDLE, AUTH1, AUTH2, INFO, E1, WAIT_DEVICE, TRANSFER, SWITCH, RECONNECT, REBOOTING, VERIFY, DONE }

    var state = State.IDLE
        private set

    /** null while running; "ok" or "error: ..." when [state] is DONE. */
    var result: String? = null
        private set
    var info: RcspTargetInfo = RcspTargetInfo()
        private set
    var versionBefore: String? = null
        private set
    var bytesSent = 0L
        private set
    var contentSize = 0L
        private set

    private var parser = RcspParser(mtu)
    private var sn = random.nextInt(1, 256)
    private val waiting = mutableMapOf<Pair<Int, Int>, ((RcspPacket) -> Unit)?>()
    private val outbox = ArrayDeque<ByteArray>()
    private var myChallenge = ByteArray(0)
    private var resume: State? = null

    /** True once the current link passed RCSP auth (or needs none). Reset by a reconnect. */
    var linkAuthenticated = false
        private set

    fun drainOutbox(): List<ByteArray> = List(outbox.size) { outbox.removeFirst() }

    /** Starts on a connected link; [linkAuthenticated] when an earlier session already authenticated it. */
    fun onConnected(linkAuthenticated: Boolean = false) = startSession(resumeState = null, authenticated = linkAuthenticated)

    /** After the glasses dropped the link as part of the update: authenticate again and continue. */
    fun onReconnected() {
        if (state == State.DONE) return
        val next = when (state) {
            State.REBOOTING, State.VERIFY -> State.VERIFY
            else -> State.RECONNECT
        }
        startSession(resumeState = next, authenticated = false)
    }

    fun onBytes(data: ByteArray) {
        if (state == State.AUTH1 || state == State.AUTH2) return auth(data)
        parser.feed(data).forEach(::onPacket)
    }

    val progressPercent: Int?
        get() = if (contentSize > 0) (bytesSent * 100 / contentSize).toInt().coerceIn(0, 100) else null

    /**
     * Whether stopping now leaves the installed firmware intact: before the glasses enter update
     * mode, or during a dual-bank transfer (the vendor SDK only sends E4 exit for dual-bank
     * devices: "single flash ota cannot be interrupted").
     */
    val canCancel: Boolean
        get() = when (state) {
            State.IDLE, State.AUTH1, State.AUTH2, State.INFO, State.E1 -> true
            State.TRANSFER -> info.doubleBackup
            else -> false
        }

    /** Stops the session when [canCancel]: a dual-bank transfer is ended with E4 exit; returns false otherwise. */
    fun cancel(): Boolean {
        if (!canCancel) return false
        if (state == State.TRANSFER) command(JieliRcsp.OP_E4_EXIT_UPDATE, ByteArray(0), null)
        fail("cancelled")
        return true
    }

    private fun startSession(resumeState: State?, authenticated: Boolean) {
        parser = RcspParser(mtu)
        waiting.clear()
        resume = resumeState
        linkAuthenticated = authenticated || !useAuth
        if (!linkAuthenticated) {
            state = State.AUTH1
            outbox += JieliRcsp.AUTH_RESET_CMD
            myChallenge = byteArrayOf(0) + random.nextBytes(16)
            outbox += myChallenge
        } else {
            syncInfo()
        }
    }

    private fun auth(data: ByteArray) {
        when (state) {
            State.AUTH1 -> if (data.size == 17 && data[0].toInt() == 1) {
                if (!data.contentEquals(JieliRcsp.authResponse(myChallenge))) return fail("auth: device response mismatch")
                outbox += JieliRcsp.AUTH_OK
                state = State.AUTH2
            }
            State.AUTH2 -> when {
                data.size == 17 && data[0].toInt() == 0 -> outbox += JieliRcsp.authResponse(data)
                data.size >= 5 && data.copyOf(5).contentEquals(JieliRcsp.AUTH_OK) -> {
                    linkAuthenticated = true
                    syncInfo()
                }
            }
            else -> Unit
        }
    }

    private fun syncInfo() {
        state = resume ?: State.INFO
        resume = null
        command(JieliRcsp.OP_GET_TARGET_INFO, byteArrayOf(-1, -1, -1, -1, 0), ::onInfo)
    }

    private fun onInfo(p: RcspPacket) {
        info = JieliRcsp.parseTargetInfo(p.payload)
        if (versionBefore == null) versionBefore = info.version
        when {
            readInfoOnly || state == State.VERIFY -> finish("ok")
            state == State.RECONNECT -> if (info.mandatory) {
                command(JieliRcsp.OP_E3_ENTER_UPDATE, ByteArray(0), ::onE3)
            } else {
                command(JieliRcsp.OP_E1_FILE_INFO_OFFSET, ByteArray(0), ::onE1)
            }
            else -> {
                state = State.E1
                command(JieliRcsp.OP_E1_FILE_INFO_OFFSET, ByteArray(0), ::onE1)
            }
        }
    }

    private fun onE1(p: RcspPacket) {
        val (off, len) = if (p.payload.size >= 6) JieliRcsp.be32(p.payload, 0).toInt() to JieliRcsp.be16(p.payload, 4) else 0 to 0
        val flag = if (off == 0 && len == 0) byteArrayOf(0) else file.copyOfRange(off, minOf(off + len, file.size))
        command(JieliRcsp.OP_E2_INQUIRE_CAN_UPDATE, flag, ::onE2)
    }

    private fun onE2(p: RcspPacket) {
        val code = p.payload.firstOrNull()?.toInt()?.and(0xFF) ?: 1
        if (code != 0) return fail("E2 refused: $code ${JieliRcsp.E2_CODES[code] ?: "?"}")
        when {
            info.doubleBackup || info.mandatory -> command(JieliRcsp.OP_E3_ENTER_UPDATE, ByteArray(0), ::onE3)
            info.needBootLoader -> state = State.WAIT_DEVICE // the glasses start pulling the loader themselves
            else -> readyReconnect()
        }
    }

    private fun onE3(p: RcspPacket) {
        val code = p.payload.firstOrNull()?.toInt()?.and(0xFF) ?: 7
        if (code != 0) return fail("E3 refused: $code")
        state = State.TRANSFER
    }

    private fun readyReconnect() {
        state = State.SWITCH
        command(JieliRcsp.OP_SWITCH_COMM_WAY, byteArrayOf(0, 1)) { state = State.RECONNECT }
    }

    private fun onE6(p: RcspPacket) {
        val r = p.payload.firstOrNull()?.toInt()?.and(0xFF) ?: -1
        when (r) {
            0 -> {
                state = State.REBOOTING
                command(JieliRcsp.OP_E7_REBOOT, byteArrayOf(0), null)
            }
            128 -> if (info.doubleBackup) command(JieliRcsp.OP_E3_ENTER_UPDATE, ByteArray(0), ::onE3) else readyReconnect()
            else -> fail("E6 result $r ${JieliRcsp.E6_CODES[r] ?: "?"}")
        }
    }

    private fun onPacket(p: RcspPacket) {
        if (state == State.DONE) return
        if (!p.isCommand) {
            val callback = waiting.remove(p.opcode to p.sn)
            if (p.status != 0 && p.opcode != JieliRcsp.OP_E7_REBOOT) {
                return fail("status ${p.status} for 0x${p.opcode.toString(16)}")
            }
            callback?.invoke(p)
            return
        }
        when (p.opcode) {
            JieliRcsp.OP_E8_CONTENT_SIZE -> {
                contentSize = if (p.payload.size >= 4) JieliRcsp.be32(p.payload, 0) else 0
                respond(p, ByteArray(0))
            }
            JieliRcsp.OP_E5_BLOCK -> {
                val off = JieliRcsp.be32(p.payload, 0).toInt()
                val len = JieliRcsp.be16(p.payload, 4)
                if (off == 0 && len == 0) {
                    respond(p, ByteArray(0))
                    command(JieliRcsp.OP_E6_UPDATE_STATUS, ByteArray(0), ::onE6)
                    return
                }
                if (off < 0 || off + len > file.size) return fail("E5 out of range")
                bytesSent += len
                respond(p, file.copyOfRange(off, off + len))
            }
            JieliRcsp.OP_E4_EXIT_UPDATE -> {
                respond(p, ByteArray(0))
                fail("the glasses cancelled the update (E4)")
            }
            JieliRcsp.OP_SET_MTU -> {
                val requested = JieliRcsp.be16(p.payload, 0)
                respond(p, byteArrayOf(((if (requested >= 530) requested else mtu) ushr 8).toByte(), (if (requested >= 530) requested else mtu).toByte()))
            }
            else -> if (p.hasResponse) respond(p, ByteArray(0), status = 1)
        }
    }

    private fun command(op: Int, payload: ByteArray, callback: ((RcspPacket) -> Unit)?) {
        val current = sn
        sn = (sn + 1) and 0xFF
        waiting[op to current] = callback
        outbox += JieliRcsp.encode(RcspPacket(op, payload, isCommand = true, hasResponse = true, sn = current))
    }

    private fun respond(request: RcspPacket, payload: ByteArray, status: Int = 0) {
        outbox += JieliRcsp.encode(RcspPacket(request.opcode, payload, isCommand = false, hasResponse = false, sn = request.sn, status = status))
    }

    private fun finish(value: String) {
        result = value
        state = State.DONE
    }

    private fun fail(why: String) = finish("error: $why")
}
