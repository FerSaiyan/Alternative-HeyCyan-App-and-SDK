package com.fersaiyan.cyanbridge.glasses.offlineauth

/** Terminal outcome of an offline unlock attempt. */
enum class OfflineUnlockResult {
    /** Device-auth and bind satisfied offline; the camera gate should now be open. */
    SUCCESS,

    /** The unit does not require device-auth/bind (e.g. a pre-auth unit); nothing to do. */
    NOT_REQUIRED,

    /** Already authed + bound to us; the gate is already open. */
    ALREADY_UNLOCKED,

    /** The RCSP symmetric handshake was rejected by the glasses (key/cipher mismatch). */
    AUTH_FAILED,

    /** The user did not press the front button within the bind window. */
    BIND_REJECTED,

    /** The unit is bound to another controller id; the owner must unbind on-device first. */
    BOUND_TO_OTHER,

    /** Not connected over BLE. */
    NOT_CONNECTED,

    /** A BLE transport failure/timeout occurred mid-flow. */
    TRANSPORT_ERROR,

    /** The opt-in offline-auth setting is off; the path was not attempted (default on the shared branch). */
    DISABLED,
}

/**
 * Drives the HeyCyan native **offline** unlock, per the add.75 verdict + add.78 recipe:
 *
 *   capability query (cmd-71)
 *     -> if device-auth required: RCSP symmetric challenge-response with the default link key
 *     -> if bind required: query bind status (cmd-113/3); if unbound, request bind (cmd-113/2) and
 *        wait for the real front-button press; handle already-bound-self / bound-to-another-id
 *
 * It never contacts a server and never forges a server token. It is pure (suspend + a transport
 * seam), so the same code runs against the real SDK and against the simulated glasses in tests.
 */
class HeyCyanOfflineAuthStateMachine(
    private val transport: GlassesAuthTransport,
    private val symmetricAuth: HeyCyanSymmetricAuth = HeyCyanSymmetricAuth(),
) {
    /** Human-readable step log for diagnostics and test assertions. */
    val steps: List<String> get() = _steps
    private val _steps = mutableListOf<String>()

    private fun log(step: String) {
        _steps += step
    }

    suspend fun unlock(): OfflineUnlockResult {
        _steps.clear()
        if (!transport.isConnected()) {
            log("not-connected")
            return OfflineUnlockResult.NOT_CONNECTED
        }
        return try {
            runUnlock()
        } catch (t: Throwable) {
            log("transport-error:${t.message}")
            OfflineUnlockResult.TRANSPORT_ERROR
        }
    }

    private suspend fun runUnlock(): OfflineUnlockResult {
        // 1) Capability query (cmd-71): does this unit require device-auth and/or bind?
        val capResp = transport.send(HeyCyanAuthProtocol.capabilityQueryFrame())
        val capByte = HeyCyanAuthProtocol.capabilityByteOf(capResp.payload)
            ?: capResp.payload.firstOrNull()?.toInt()?.and(0xFF)
            ?: run {
                log("capability-empty")
                return OfflineUnlockResult.TRANSPORT_ERROR
            }
        val requirement = HeyCyanAuthProtocol.parseCapability(capByte)
        log("capability=0x%02X authReq=%b bindReq=%b".format(capByte, requirement.deviceAuthRequired, requirement.bindRequired))
        if (!requirement.anyRequired) {
            return OfflineUnlockResult.NOT_REQUIRED
        }

        // 2) Device-auth: RCSP symmetric challenge-response with the default link key.
        if (requirement.deviceAuthRequired) {
            when (val r = runDeviceAuth()) {
                OfflineUnlockResult.SUCCESS -> Unit // continue to bind
                else -> return r
            }
        }

        // 3) Bind (local, front-button consent).
        if (requirement.bindRequired) {
            when (val r = runBind()) {
                OfflineUnlockResult.SUCCESS, OfflineUnlockResult.ALREADY_UNLOCKED -> Unit
                else -> return r
            }
        }

        log("unlock-complete")
        return OfflineUnlockResult.SUCCESS
    }

    /** Returns [OfflineUnlockResult.SUCCESS] when the handshake is accepted, else a failure result. */
    private suspend fun runDeviceAuth(): OfflineUnlockResult {
        // RCSP (FE DC BA): reset-auth triggers the device's challenge.
        val challengeResp = transport.send(HeyCyanAuthProtocol.authResetFrame())
        val challenge = normalizeChallenge(challengeResp.payload)
        if (challenge == null) {
            log("challenge-bad-len:${challengeResp.payload.size}")
            return OfflineUnlockResult.AUTH_FAILED
        }
        val response = symmetricAuth.respondTo(challenge)
        val authResp = transport.send(HeyCyanAuthProtocol.authResponseFrame(response))
        val ok = authResp.payload.firstOrNull()?.toInt()?.and(0xFF) == HeyCyanAuthProtocol.RCSP_AUTH_OK
        log("device-auth=${if (ok) "ok" else "fail"}")
        return if (ok) OfflineUnlockResult.SUCCESS else OfflineUnlockResult.AUTH_FAILED
    }

    /** Accepts a 16-byte challenge or a 17-byte [type=0]+16 RCSP challenge frame. */
    private fun normalizeChallenge(payload: ByteArray): ByteArray? = when {
        payload.size == HeyCyanAuthProtocol.CHALLENGE_LEN -> payload
        payload.size == HeyCyanAuthProtocol.CHALLENGE_LEN + 1 &&
            payload[0].toInt() and 0xFF == HeyCyanAuthProtocol.RCSP_TYPE_CHALLENGE ->
            payload.copyOfRange(1, payload.size)
        else -> null
    }

    private suspend fun runBind(): OfflineUnlockResult {
        // Query current bind status (cmd-113/3): reply status byte at payload offset 1.
        val statusResp = transport.send(HeyCyanAuthProtocol.bindStatusFrame())
        when (HeyCyanAuthProtocol.bindStatusOf(statusResp.payload)) {
            HeyCyanAuthProtocol.BIND_STATUS_BOUND_SELF -> {
                log("bind-status=bound-self")
                return OfflineUnlockResult.ALREADY_UNLOCKED
            }
            HeyCyanAuthProtocol.BIND_STATUS_BOUND_OTHER -> {
                log("bind-status=bound-other")
                return OfflineUnlockResult.BOUND_TO_OTHER
            }
            HeyCyanAuthProtocol.BIND_STATUS_UNBOUND -> log("bind-status=unbound")
            else -> log("bind-status=unknown")
        }

        // Request bind (cmd-113/2): the glasses wait ~20 s for a real front-button press.
        transport.onPromptFrontButton()
        log("bind-prompt-front-button")
        val bindResp = transport.send(HeyCyanAuthProtocol.bindRequestFrame())
        val ok = HeyCyanAuthProtocol.bindStatusOf(bindResp.payload) == HeyCyanAuthProtocol.BIND_RESULT_OK
        log("bind=${if (ok) "ok" else "rejected"}")
        return if (ok) OfflineUnlockResult.SUCCESS else OfflineUnlockResult.BIND_REJECTED
    }
}
