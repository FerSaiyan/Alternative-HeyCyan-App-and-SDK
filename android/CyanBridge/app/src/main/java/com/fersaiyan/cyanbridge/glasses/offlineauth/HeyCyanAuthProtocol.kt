package com.fersaiyan.cyanbridge.glasses.offlineauth

/**
 * HeyCyan (JieLi/Oudmon) native **offline** auth protocol: transport codec, opcodes, and frame
 * codecs, per the add.78 unlock recipe (heycyan_unlock_trace_2026-10-28) reconciled with the add.75
 * gate verdict (m02s_auth_verify_2026-10-28).
 *
 * This restores the device owner's own access to their own glasses through the device's own
 * **symmetric** handshake (the public SDK-default link key). It is NOT the HeyCyan cloud auth path
 * (server token), and — per add.78 CORRECTION 1 — it does NOT rely on cmd-114 (0x72), which is a
 * pure parameter store with no compare/crypto/auth-bit (so no "signature" can or need be produced
 * for it).
 *
 * All pure Kotlin (no Android, no vendor AAR) so it is JVM-unit-testable and drivable by both the
 * real [GlassesAuthTransport] and the simulated glasses.
 */
object HeyCyanAuthProtocol {

    // ---- Oudmon 0xBC command transport (add.78, app-verified e5/p.java) -----------------------
    // Framing: BC | opcode(1) | len(2 LE) | CRC16-MODBUS-of-payload(2 LE) | payload
    // Empty payload => len 00 00, CRC field FF FF.

    const val BC_HEADER = 0xBC

    /** cmd-71 / 0x47: capability probe. Reply byte-14 carries the auth capability bitfield. */
    const val CMD_CAPABILITY = 0x47

    /** cmd-113 / 0x71: user-bind control (status / front-button bind / unbind). */
    const val CMD_BIND = 0x71

    /** Camera control opcode. Photo = {02,01,01}; video-start = {02,01,02}. */
    const val CMD_CAMERA = 0x41

    /**
     * cmd-114 / 0x72: parameter store ONLY. add.78 CORRECTION 1: pure 10-byte store, no compare, no
     * crypto, no auth/bind bit — a dead end for auth. Kept as a named constant so no one re-adds it
     * to the auth flow.
     */
    const val CMD_PARAM_STORE_DEAD_END = 0x72

    // ---- cmd-47 capability reply (byte-14 of the unwrapped payload) ---------------------------

    const val CAPABILITY_BYTE_INDEX = 14
    const val CAP_DEVICE_AUTH_REQUIRED = 0x40
    const val CAP_BIND_REQUIRED = 0x10
    const val CAP_PRESENT = 0x80

    /** M02S/A02S post-auth units report 0xD0 = present(0x80) | device-auth(0x40) | bind(0x10). */
    const val CAP_M02S_BOTH_REQUIRED = 0xD0

    // ---- cmd-71 bind modes & status (reply status at payload offset 1) ------------------------

    const val BIND_MODE_STATUS = 3
    const val BIND_MODE_BIND = 2
    const val BIND_MODE_UNBIND = 4
    const val BIND_BUTTON_WINDOW_MS = 20_000L

    const val BIND_STATUS_UNBOUND = 0
    const val BIND_STATUS_BOUND_SELF = 1
    const val BIND_STATUS_BOUND_OTHER = 2

    const val BIND_RESULT_OK = 1
    const val BIND_RESULT_REJECTED = 0

    /** Default controller id used for bind when the app has not assigned one. */
    val DEFAULT_USER_ID: ByteArray = byteArrayOf(0x01)

    // ---- JieLi RCSP device-auth (FE DC BA channel, NOT 0xBC) ----------------------------------
    // add.78 step 3: stock jl_bt_ota RcspAuth. Default link key loaded by FUN_0600fd1a.

    val RCSP_HEADER: ByteArray = byteArrayOf(0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte())

    /**
     * Reset-auth command (verbatim from add.78 / RcspAuth): FE DC BA C0 06 00 02 00 01 EF.
     * Triggers the device to issue its challenge.
     */
    val RCSP_RESET_AUTH: ByteArray = byteArrayOf(
        0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0xC0.toByte(),
        0x06, 0x00, 0x02, 0x00, 0x01, 0xEF.toByte(),
    )

    /** 17-byte nonce frame type: challenge (device -> phone). */
    const val RCSP_TYPE_CHALLENGE = 0x00

    /** 17-byte nonce frame type: response (phone -> device). */
    const val RCSP_TYPE_RESPONSE = 0x01

    /** Device auth-ok marker (first byte of the 5-byte ok reply {0x02,...}). */
    const val RCSP_AUTH_OK = 0x02

    const val CHALLENGE_LEN = 16

    /**
     * SDK-default JieLi link key (public; execution-confirmed loaded by A02S FUN_0600fd1a).
     * 06 77 5f 87 91 8d d4 23 00 5d f1 d8 cf 0c 14 2b
     */
    val DEFAULT_LINK_KEY: ByteArray = byteArrayOf(
        0x06, 0x77, 0x5f.toByte(), 0x87.toByte(), 0x91.toByte(), 0x8d.toByte(), 0xd4.toByte(), 0x23,
        0x00, 0x5d, 0xf1.toByte(), 0xd8.toByte(), 0xcf.toByte(), 0x0c, 0x14, 0x2b,
    )

    // ---- CRC-16/MODBUS (poly 0xA001, init 0xFFFF, reflected) ----------------------------------

    fun crc16Modbus(payload: ByteArray): Int {
        var crc = 0xFFFF
        for (b in payload) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x0001 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }

    // ---- 0xBC frame codec --------------------------------------------------------------------

    /** Wraps [payload] under opcode into the full on-wire 0xBC frame: BC|op|lenLo|lenHi|crcLo|crcHi|payload. */
    fun bcFrame(opcode: Int, payload: ByteArray): ByteArray {
        val len = payload.size
        val crc = if (payload.isEmpty()) 0xFFFF else crc16Modbus(payload)
        val frame = ByteArray(6 + len)
        frame[0] = BC_HEADER.toByte()
        frame[1] = opcode.toByte()
        frame[2] = (len and 0xFF).toByte()
        frame[3] = ((len ushr 8) and 0xFF).toByte()
        frame[4] = (crc and 0xFF).toByte()
        frame[5] = ((crc ushr 8) and 0xFF).toByte()
        payload.copyInto(frame, 6)
        return frame
    }

    /** A decoded 0xBC frame. */
    data class BcFrame(val opcode: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is BcFrame && opcode == other.opcode && payload.contentEquals(other.payload))

        override fun hashCode(): Int = 31 * opcode + payload.contentHashCode()
    }

    /** Parses and CRC-verifies an on-wire 0xBC frame, or returns null if malformed/bad CRC. */
    fun parseBcFrame(frame: ByteArray): BcFrame? {
        if (frame.size < 6 || frame[0].toInt() and 0xFF != BC_HEADER) return null
        val opcode = frame[1].toInt() and 0xFF
        val len = (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8)
        if (frame.size != 6 + len) return null
        val crc = (frame[4].toInt() and 0xFF) or ((frame[5].toInt() and 0xFF) shl 8)
        val payload = frame.copyOfRange(6, 6 + len)
        val expected = if (payload.isEmpty()) 0xFFFF else crc16Modbus(payload)
        if (crc != expected) return null
        return BcFrame(opcode, payload)
    }

    // ---- Command frame builders --------------------------------------------------------------

    /** Capability probe: BC 47 02 00 <crc> 01 00. */
    fun capabilityQueryFrame(): ByteArray = bcFrame(CMD_CAPABILITY, byteArrayOf(0x01, 0x00))

    /** Bind status query (mode 3): BC 71 ... 03 idLen <userId>. */
    fun bindStatusFrame(userId: ByteArray = DEFAULT_USER_ID): ByteArray =
        bcFrame(CMD_BIND, bindPayload(BIND_MODE_STATUS, userId))

    /** Bind request (mode 2, front-button): BC 71 ... 02 idLen <userId>. */
    fun bindRequestFrame(userId: ByteArray = DEFAULT_USER_ID): ByteArray =
        bcFrame(CMD_BIND, bindPayload(BIND_MODE_BIND, userId))

    /** Unbind (mode 4). */
    fun unbindFrame(userId: ByteArray = DEFAULT_USER_ID): ByteArray =
        bcFrame(CMD_BIND, bindPayload(BIND_MODE_UNBIND, userId))

    private fun bindPayload(mode: Int, userId: ByteArray): ByteArray =
        ByteArray(2 + userId.size).also {
            it[0] = mode.toByte()
            it[1] = userId.size.toByte()
            userId.copyInto(it, 2)
        }

    /** Photo capture: BC 41 03 00 <crc> 02 01 01. */
    fun photoFrame(): ByteArray = bcFrame(CMD_CAMERA, byteArrayOf(0x02, 0x01, 0x01))

    /** Video-start: BC 41 03 00 <crc> 02 01 02. */
    fun videoStartFrame(): ByteArray = bcFrame(CMD_CAMERA, byteArrayOf(0x02, 0x01, 0x02))

    // ---- RCSP device-auth frame builders (FE DC BA) ------------------------------------------

    /** Reset-auth: triggers the device to issue its challenge. */
    fun authResetFrame(): ByteArray = RCSP_RESET_AUTH.copyOf()

    /**
     * RCSP response frame: FE DC BA | [RCSP_TYPE_RESPONSE] | <16-byte response>. The inner nonce frame
     * is the add.78 "[type]+16data" form; it is carried on the FE DC BA channel.
     * RECIPE RESIDUAL (add.78 step 3): the exact FE DC BA sub-framing of the 17-byte nonce frame is
     * overlay-resident and not byte-traced; the one place to finalize it is here. The response
     * derivation is stock RcspAuth with the default key (see [HeyCyanSymmetricAuth]).
     */
    fun authResponseFrame(response: ByteArray): ByteArray {
        require(response.size == CHALLENGE_LEN) { "auth response must be $CHALLENGE_LEN bytes" }
        return ByteArray(RCSP_HEADER.size + 1 + CHALLENGE_LEN).also {
            RCSP_HEADER.copyInto(it, 0)
            it[RCSP_HEADER.size] = RCSP_TYPE_RESPONSE.toByte()
            response.copyInto(it, RCSP_HEADER.size + 1)
        }
    }

    // ---- Response parsers --------------------------------------------------------------------

    data class AuthRequirement(val deviceAuthRequired: Boolean, val bindRequired: Boolean) {
        val anyRequired: Boolean get() = deviceAuthRequired || bindRequired
    }

    fun parseCapability(capabilityByte: Int): AuthRequirement = AuthRequirement(
        deviceAuthRequired = capabilityByte and CAP_DEVICE_AUTH_REQUIRED != 0,
        bindRequired = capabilityByte and CAP_BIND_REQUIRED != 0,
    )

    /** Pulls byte-14 out of an unwrapped cmd-47 reply payload, or null when too short. */
    fun capabilityByteOf(payload: ByteArray): Int? =
        if (payload.size > CAPABILITY_BYTE_INDEX) payload[CAPABILITY_BYTE_INDEX].toInt() and 0xFF else null

    /** Bind/status reply: status byte at payload offset 1 (add.78 CORRECTION 2: {sub-op, status}). */
    fun bindStatusOf(payload: ByteArray): Int? =
        if (payload.size > 1) payload[1].toInt() and 0xFF else null
}
