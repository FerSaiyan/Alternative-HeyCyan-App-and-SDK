package com.fersaiyan.cyanbridge.glasses.offlineauth

import kotlin.random.Random

/**
 * In-JVM simulated HeyCyan post-auth (M02S/A02S) unit, speaking the real add.78 wire framing
 * (0xBC transport + CRC-16/MODBUS for commands; FE DC BA RCSP for device-auth) and modeling the
 * add.75 camera gate: the camera (opcode 0x41) is served only once the BT link is encrypted AND
 * device-auth (RCSP symmetric handshake with the default link key) is satisfied AND the unit is bound
 * to us.
 *
 * It returns the UNWRAPPED inner payload to the state machine (as the real transport will after it
 * strips the 0xBC/FE-DC-BA framing). The simulator and the app under test share the same
 * [HeyCyanSymmetricAuth], so a passing test proves the state machine + bind flow flips the gate given
 * the symmetric handshake; it does not assert the exact RCSP challenge-derivation bytes (add.78 step-3
 * residual), which must be validated from stock RcspAuth on the owner device.
 */
class SimulatedHeyCyanGlasses(
    private val capabilityByte: Int = HeyCyanAuthProtocol.CAP_M02S_BOTH_REQUIRED,
    private var bindStatus: Int = HeyCyanAuthProtocol.BIND_STATUS_UNBOUND,
    private val userPressesFrontButton: Boolean = true,
    private val symmetricAuth: HeyCyanSymmetricAuth = HeyCyanSymmetricAuth(),
    private val connected: Boolean = true,
    random: Random = Random(0x4357),
) : GlassesAuthTransport {

    private val challenge: ByteArray = ByteArray(HeyCyanAuthProtocol.CHALLENGE_LEN).also { random.nextBytes(it) }
    private var linkEncrypted = true // BT LESC pairing assumed complete before auth begins
    private var deviceAuthSatisfied = false
    private var frontButtonPressed = false

    /** Observable firmware gate: true only when the camera (0x41) would actually be served. */
    fun cameraServes(): Boolean =
        linkEncrypted && deviceAuthSatisfied && bindStatus == HeyCyanAuthProtocol.BIND_STATUS_BOUND_SELF

    /** What a 0x41 camera command returns: refused until the gate opens. */
    fun requestCameraCapture(): Boolean = cameraServes()

    override fun isConnected(): Boolean = connected

    override fun onPromptFrontButton() {
        if (userPressesFrontButton) frontButtonPressed = true
    }

    override suspend fun send(frame: ByteArray): AuthResponse {
        val head = frame.firstOrNull()?.toInt()?.and(0xFF)
        return when (head) {
            0xFE -> handleRcsp(frame)
            HeyCyanAuthProtocol.BC_HEADER -> handleBc(frame)
            else -> error("unknown frame header $head")
        }
    }

    // ---- FE DC BA RCSP device-auth -----------------------------------------------------------

    private fun handleRcsp(frame: ByteArray): AuthResponse {
        // Reset-auth -> issue a 17-byte [type=0]+challenge frame.
        if (frame.contentEquals(HeyCyanAuthProtocol.RCSP_RESET_AUTH)) {
            return AuthResponse(0xFE, byteArrayOf(HeyCyanAuthProtocol.RCSP_TYPE_CHALLENGE.toByte()) + challenge)
        }
        // Response frame FE DC BA [type=1]+16 -> verify with the default key, emit {0x02,...} on success.
        val respHeader = HeyCyanAuthProtocol.RCSP_HEADER.size
        if (frame.size == respHeader + 1 + HeyCyanAuthProtocol.CHALLENGE_LEN &&
            frame[respHeader].toInt() and 0xFF == HeyCyanAuthProtocol.RCSP_TYPE_RESPONSE
        ) {
            val response = frame.copyOfRange(respHeader + 1, frame.size)
            val ok = linkEncrypted && symmetricAuth.verify(challenge, response)
            if (ok) deviceAuthSatisfied = true
            return AuthResponse(
                0xFE,
                if (ok) byteArrayOf(HeyCyanAuthProtocol.RCSP_AUTH_OK.toByte(), 0, 0, 0, 0)
                else byteArrayOf(0x00, 0, 0, 0, 0),
            )
        }
        return AuthResponse(0xFE, byteArrayOf(0x00))
    }

    // ---- 0xBC commands -----------------------------------------------------------------------

    private fun handleBc(frame: ByteArray): AuthResponse {
        val parsed = HeyCyanAuthProtocol.parseBcFrame(frame)
            ?: error("bad 0xBC frame or CRC") // the firmware would drop a bad-CRC frame
        return when (parsed.opcode) {
            HeyCyanAuthProtocol.CMD_CAPABILITY -> capabilityReply()
            HeyCyanAuthProtocol.CMD_BIND -> bindReply(parsed.payload)
            HeyCyanAuthProtocol.CMD_CAMERA -> cameraReply(parsed.payload)
            else -> AuthResponse(parsed.opcode, byteArrayOf(0x00, 0x00))
        }
    }

    private fun capabilityReply(): AuthResponse {
        val payload = ByteArray(HeyCyanAuthProtocol.CAPABILITY_BYTE_INDEX + 1)
        payload[HeyCyanAuthProtocol.CAPABILITY_BYTE_INDEX] = capabilityByte.toByte()
        return AuthResponse(HeyCyanAuthProtocol.CMD_CAPABILITY, payload)
    }

    private fun bindReply(payload: ByteArray): AuthResponse {
        val mode = payload.firstOrNull()?.toInt()?.and(0xFF)
        return when (mode) {
            HeyCyanAuthProtocol.BIND_MODE_STATUS ->
                AuthResponse(HeyCyanAuthProtocol.CMD_BIND, byteArrayOf(HeyCyanAuthProtocol.BIND_MODE_STATUS.toByte(), bindStatus.toByte()))

            HeyCyanAuthProtocol.BIND_MODE_BIND -> {
                // Binds only on a real front-button press within the window and after device-auth.
                val ok = frontButtonPressed && deviceAuthSatisfied
                if (ok) bindStatus = HeyCyanAuthProtocol.BIND_STATUS_BOUND_SELF
                AuthResponse(
                    HeyCyanAuthProtocol.CMD_BIND,
                    byteArrayOf(
                        HeyCyanAuthProtocol.BIND_MODE_BIND.toByte(),
                        (if (ok) HeyCyanAuthProtocol.BIND_RESULT_OK else HeyCyanAuthProtocol.BIND_RESULT_REJECTED).toByte(),
                    ),
                )
            }

            HeyCyanAuthProtocol.BIND_MODE_UNBIND -> {
                bindStatus = HeyCyanAuthProtocol.BIND_STATUS_UNBOUND
                AuthResponse(HeyCyanAuthProtocol.CMD_BIND, byteArrayOf(HeyCyanAuthProtocol.BIND_MODE_UNBIND.toByte(), HeyCyanAuthProtocol.BIND_RESULT_OK.toByte()))
            }

            else -> AuthResponse(HeyCyanAuthProtocol.CMD_BIND, byteArrayOf(0x00, HeyCyanAuthProtocol.BIND_RESULT_REJECTED.toByte()))
        }
    }

    private fun cameraReply(payload: ByteArray): AuthResponse {
        val served = cameraServes()
        // {sub-op, status}: status 1 = served, 0 = refused (gate closed).
        return AuthResponse(
            HeyCyanAuthProtocol.CMD_CAMERA,
            byteArrayOf(payload.firstOrNull() ?: 0x02, if (served) 0x01 else 0x00),
        )
    }
}
