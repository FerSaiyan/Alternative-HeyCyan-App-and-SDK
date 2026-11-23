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

    /** Another trigger is already running the unlock on this connection; this caller must do nothing. */
    IN_PROGRESS,
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
    /** How long to wait for the front-button press after arming the bind window (~20 s on device). */
    private val bindPressWindowMs: Long = HeyCyanAuthProtocol.BIND_BUTTON_WINDOW_MS,
    /** How often to re-query bind status while waiting for the press. */
    private val bindPollIntervalMs: Long = 1_000L,
) {
    /** Human-readable step log for diagnostics and test assertions. */
    val steps: List<String> get() = _steps
    private val _steps = mutableListOf<String>()

    /** Observed gate facts for diagnostics (null until the matching step runs). */
    var lastCapabilityByte: Int? = null
        private set
    var deviceAuthOk: Boolean? = null
        private set
    var bindOk: Boolean? = null
        private set

    private fun log(step: String) {
        _steps += step
    }

    suspend fun unlock(): OfflineUnlockResult {
        _steps.clear()
        lastCapabilityByte = null
        deviceAuthOk = null
        bindOk = null
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
        lastCapabilityByte = capByte
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
        deviceAuthOk = ok
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
                bindOk = true
                return OfflineUnlockResult.ALREADY_UNLOCKED
            }
            HeyCyanAuthProtocol.BIND_STATUS_BOUND_OTHER -> {
                log("bind-status=bound-other")
                return OfflineUnlockResult.BOUND_TO_OTHER
            }
            HeyCyanAuthProtocol.BIND_STATUS_UNBOUND -> log("bind-status=unbound")
            else -> log("bind-status=unknown")
        }

        // Request bind (cmd-113/2): arms the ~20 s window and prompts the user to press the front button.
        transport.onPromptFrontButton()
        log("bind-prompt-front-button")
        val armResp = transport.send(HeyCyanAuthProtocol.bindRequestFrame())
        // Fast path: some units confirm the bind directly in the mode-2 reply (press already registered).
        if (HeyCyanAuthProtocol.bindStatusOf(armResp.payload) == HeyCyanAuthProtocol.BIND_RESULT_OK) {
            log("bind=ok")
            bindOk = true
            return OfflineUnlockResult.SUCCESS
        }
        // Otherwise the press arrives asynchronously: poll bind status until bound-self or the window ends.
        val deadline = bindPressWindowMs
        var waited = 0L
        while (waited < deadline) {
            kotlinx.coroutines.delay(bindPollIntervalMs)
            waited += bindPollIntervalMs
            val poll = transport.send(HeyCyanAuthProtocol.bindStatusFrame())
            when (HeyCyanAuthProtocol.bindStatusOf(poll.payload)) {
                HeyCyanAuthProtocol.BIND_STATUS_BOUND_SELF -> {
                    log("bind=ok")
                    bindOk = true
                    return OfflineUnlockResult.SUCCESS
                }
                HeyCyanAuthProtocol.BIND_STATUS_BOUND_OTHER -> {
                    log("bind-status=bound-other")
                    return OfflineUnlockResult.BOUND_TO_OTHER
                }
                else -> Unit // still unbound; keep waiting for the press
            }
        }
        log("bind=rejected")
        bindOk = false
        return OfflineUnlockResult.BIND_REJECTED
    }
}
