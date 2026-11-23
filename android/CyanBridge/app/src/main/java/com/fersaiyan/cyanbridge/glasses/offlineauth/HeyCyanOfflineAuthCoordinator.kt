package com.fersaiyan.cyanbridge.glasses.offlineauth

import android.util.Log
import com.fersaiyan.cyanbridge.shared.prefs.AppPrefs

/**
 * Additive, opt-in entry point for the HeyCyan **native offline unlock**.
 *
 * Default behavior on the shared `cyan-refactor` branch is unchanged: unless the owner turns on
 * [AppPrefs.DeviceProfile.offlineNativeAuthEnabled], [attemptOfflineUnlock] short-circuits to
 * [OfflineUnlockResult.DISABLED] and nothing is sent. When enabled, and only when the glasses
 * themselves report device-auth/bind required (cmd-71 byte-14 0x40/0x10) with the HeyCyan server
 * unavailable or declined, it completes the device's own symmetric handshake + local front-button
 * bind — no server, no downgrade, no reflash.
 *
 * It restores the owner's own access via the device's own symmetric handshake; it does NOT forge
 * HeyCyan's server signature and does NOT mint server-auth tokens.
 */
class HeyCyanOfflineAuthCoordinator(
    /** Overridable for tests; defaults to the opt-in preference. */
    private val isEnabled: () -> Boolean = { AppPrefs.DeviceProfile.offlineNativeAuthEnabled.get() },
    /** Overridable for tests; defaults to the vendor-SDK-backed transport. */
    private val transportFactory: (onPromptFrontButton: () -> Unit) -> GlassesAuthTransport = { prompt ->
        SdkGlassesAuthTransport(prompt)
    },
    private val symmetricAuth: HeyCyanSymmetricAuth = HeyCyanSymmetricAuth(),
) {
    /**
     * Runs the offline unlock if enabled. [onPromptFrontButton] is invoked (on the caller's context)
     * when the ~20 s front-button bind window opens, so the UI can tell the owner to press the
     * glasses' front button. Returns the terminal [OfflineUnlockResult].
     */
    suspend fun attemptOfflineUnlock(onPromptFrontButton: () -> Unit = {}): OfflineUnlockResult {
        if (!isEnabled()) {
            Log.i(TAG, "offline native auth disabled; not attempting")
            return OfflineUnlockResult.DISABLED
        }
        val transport = transportFactory(onPromptFrontButton)
        val machine = HeyCyanOfflineAuthStateMachine(transport, symmetricAuth)
        val result = machine.unlock()
        HeyCyanGateState.recordRun(machine.lastCapabilityByte, machine.deviceAuthOk, machine.bindOk, result)
        Log.i(TAG, "offline unlock result=$result steps=${machine.steps}")
        return result
    }

    companion object {
        const val TAG = "HeyCyanOfflineAuth"
    }
}

/**
 * Vendor-SDK-backed transport: writes the on-wire auth frames (0xBC command transport for cmd-47/71;
 * FE-DC-BA RCSP for device-auth) as RAW bytes on the vendor characteristic pair via
 * [HeyCyanAuthRawChannel] and returns the UNWRAPPED inner reply payload to the state machine.
 *
 * Why not `glassesControl`: that vendor API always frames with cmd 0x41 and only surfaces the typed
 * [com.oudmon.ble.base.communication.bigData.resp.GlassModelControlResponse], so it can neither send
 * cmd-47/71 nor the FE-DC-BA frames, nor expose the raw bytes the auth flow reads (cap byte-14, the
 * RCSP challenge/auth-ok, the bind status). [HeyCyanAuthRawChannel] enqueues the fully-formed frame
 * verbatim to de5bf72a and reads the raw de5bf729 reply — staying on the SDK's own connection.
 */
internal class SdkGlassesAuthTransport(
    private val promptFrontButton: () -> Unit,
    private val channel: HeyCyanAuthRawChannel = HeyCyanAuthRawChannel,
    /** Short request/response commands (cap query, bind status, RCSP). */
    private val commandTimeoutMs: Long = 6_000L,
    /** The bind-arm (mode-2) reply can lag a touch; the actual press is polled by the state machine. */
    private val bindArmTimeoutMs: Long = 8_000L,
) : GlassesAuthTransport {

    override fun isConnected(): Boolean = channel.isConnected()

    override fun onPromptFrontButton() = promptFrontButton()

    override suspend fun send(frame: ByteArray): AuthResponse {
        return when (val head = frame.firstOrNull()?.toInt()?.and(0xFF)) {
            0xFE -> {
                // FE-DC-BA RCSP: reply is FE DC BA | type | ...; return [type, ...] (after the 3-byte header).
                val reply = channel.exchange(frame, commandTimeoutMs) { channel.isRcspReply(it) }
                AuthResponse(opcode = 0xFE, payload = reply.copyOfRange(HeyCyanAuthProtocol.RCSP_HEADER.size, reply.size))
            }
            HeyCyanAuthProtocol.BC_HEADER -> {
                val request = HeyCyanAuthProtocol.parseBcFrame(frame)
                    ?: error("malformed outbound 0xBC frame")
                val op = request.opcode
                val isBindArm = op == HeyCyanAuthProtocol.CMD_BIND &&
                    request.payload.firstOrNull()?.toInt()?.and(0xFF) == HeyCyanAuthProtocol.BIND_MODE_BIND
                val timeout = if (isBindArm) bindArmTimeoutMs else commandTimeoutMs
                val reply = channel.exchange(frame, timeout) { channel.isBcReply(it, op) }
                val parsed = HeyCyanAuthProtocol.parseBcFrame(reply)
                    ?: error("malformed 0xBC reply for op=0x%02X".format(op))
                AuthResponse(opcode = parsed.opcode, payload = parsed.payload)
            }
            else -> error("unknown auth frame header $head")
        }
    }
}
