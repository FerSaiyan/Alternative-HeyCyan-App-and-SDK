package com.fersaiyan.cyanbridge.glasses.offlineauth

import android.util.Log
import com.fersaiyan.cyanbridge.shared.prefs.AppPrefs
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassModelControlResponse
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
        Log.i(TAG, "offline unlock result=$result steps=${machine.steps}")
        return result
    }

    companion object {
        const val TAG = "HeyCyanOfflineAuth"
    }
}

/**
 * Vendor-SDK-backed transport: sends the add.78 on-wire frames (0xBC command transport for
 * cmd-47/71/41; FE DC BA RCSP for device-auth) through `LargeDataHandler.glassesControl` and bridges
 * the response back to the state machine as the UNWRAPPED inner payload.
 *
 * RECIPE / HARDWARE SEAM: `glassesControl` surfaces a typed [GlassModelControlResponse], not raw
 * bytes. Per add.78, the cmd-47 capability byte-14, the RCSP challenge/auth-ok, and the cmd-71 bind
 * status live in the raw reply bytes — which should be read from the raw response channel (a raw
 * `addOutDeviceListener`/`ILargeDataResponse`) and unwrapped with [HeyCyanAuthProtocol.parseBcFrame].
 * The single place to finalize that wiring is [rawPayloadOf]. add.78 also flags the exact step-3 RCSP
 * challenge-derivation bytes and the final overlay relay offset as overlay-resident residuals to
 * validate on the owner device. Everything above this class (protocol, codec, crypto, state machine)
 * is already exercised end-to-end by the simulator and needs no change when the raw channel is wired.
 */
internal class SdkGlassesAuthTransport(
    private val promptFrontButton: () -> Unit,
    private val commandTimeoutMs: Long = HeyCyanAuthProtocol.BIND_BUTTON_WINDOW_MS + 5_000L,
) : GlassesAuthTransport {

    override fun isConnected(): Boolean = BleOperateManager.getInstance().isConnected

    override fun onPromptFrontButton() = promptFrontButton()

    override suspend fun send(frame: ByteArray): AuthResponse = withTimeout(commandTimeoutMs) {
        suspendCancellableCoroutine { cont ->
            runCatching {
                LargeDataHandler.getInstance().glassesControl(frame) { cmdType, response ->
                    if (cont.isActive) cont.resume(responseFrom(cmdType, response))
                }
            }.onFailure { cont.resumeIfActive(it) }
        }
    }

    private fun CancellableContinuation<AuthResponse>.resumeIfActive(t: Throwable) {
        if (isActive) resumeWithException(t)
    }

    /**
     * RECIPE: map the SDK's typed response to the raw [AuthResponse] the state machine expects.
     * [rawPayloadOf] is where add.78's exact byte offsets for cmd-71/113 are read; until then it maps
     * the fields the typed response does expose so the production call compiles and logs usefully.
     */
    private fun responseFrom(cmdType: Int, response: GlassModelControlResponse): AuthResponse =
        AuthResponse(opcode = cmdType, payload = rawPayloadOf(response))

    private fun rawPayloadOf(response: GlassModelControlResponse): ByteArray {
        // HARDWARE SEAM: replace with the raw auth/bind payload from the channel add.78 identifies,
        // unwrapped via HeyCyanAuthProtocol.parseBcFrame (0xBC) / the FE DC BA reply. The typed response
        // only exposes dataType/errorCode/workType; errorCode == 0 is the SDK's success marker, surfaced
        // here as a deterministic success byte so the production call compiles and logs before the raw
        // channel is wired. This path MUST be completed and validated on the owner device.
        val ok = response.errorCode == 0
        return byteArrayOf(if (ok) HeyCyanAuthProtocol.BIND_RESULT_OK.toByte() else HeyCyanAuthProtocol.BIND_RESULT_REJECTED.toByte())
    }
}
