package com.fersaiyan.cyanbridge.glasses.offlineauth

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates WHEN the HeyCyan post-update command gate is opened, so users never
 * need the official HeyCyan app:
 *
 *  - [onHeyCyanConnected]: once per (re)connect, query the capability and — only if the unit reports
 *    device-auth/bind required — run the offline unlock. A unit that advertises no requirement is left
 *    untouched (NOT_REQUIRED), so ordinary glasses are undisturbed.
 *  - [onGatedNak]: reactive path. When a gated command (0x41/0x45) gets the 1-byte `03` NAK, run the
 *    unlock and, on success, retry that command exactly once.
 *
 * Enabled by DEFAULT ([enabled] = true). The `offline_native_auth_enabled` preference remains as a
 * manual off switch a settings toggle can map to [enabled]; it is no longer required to be ON.
 *
 * The unlock itself is pure (coordinator + state machine + transport seam), so this controller is unit
 * tested against the simulated glasses with no device.
 */
class HeyCyanGateController(
    private val coordinatorFactory: () -> HeyCyanOfflineAuthCoordinator = {
        HeyCyanOfflineAuthCoordinator(isEnabled = { true })
    },
) {
    @Volatile
    var enabled: Boolean = DEFAULT_ENABLED

    private val unlocking = AtomicBoolean(false)

    @Volatile
    private var connectionUnlockDone = false

    /**
     * The user missed the front-button window on this connection. Background gated commands (sent every ~20 s)
     * then stop re-prompting; a reconnect or a user action ([allowRetryAfterMissedPress], e.g. starting a sync)
     * tries again. Seen on a test rig: without it the user got a new prompt + "Button not pressed" every 40 s.
     */
    @Volatile
    private var missedPress = false

    /** New BLE connection: clear the per-connection latch and the recorded gate state. */
    fun onConnectionReset() {
        connectionUnlockDone = false
        missedPress = false
        HeyCyanGateState.clear()
    }

    /** A user action (e.g. a sync tap) may prompt again after a missed press. */
    fun allowRetryAfterMissedPress() {
        missedPress = false
    }

    /**
     * Run the unlock once for this connection. [prompt] fires when the ~20 s front-button window opens;
     * [onResult] reports the terminal result (for a success/failure popup). Safe to call more than once
     * per connection — only the first attempt that needs work runs.
     */
    suspend fun onHeyCyanConnected(
        prompt: () -> Unit = {},
        onResult: (OfflineUnlockResult) -> Unit = {},
    ): OfflineUnlockResult {
        if (!enabled) return OfflineUnlockResult.DISABLED
        if (connectionUnlockDone) return OfflineUnlockResult.ALREADY_UNLOCKED
        val result = runUnlock(prompt)
        if (result == OfflineUnlockResult.IN_PROGRESS) return result
        if (result == OfflineUnlockResult.BIND_REJECTED) missedPress = true
        // Latch only on a settled outcome so a transient error can be retried on the next trigger.
        if (result != OfflineUnlockResult.TRANSPORT_ERROR && result != OfflineUnlockResult.NOT_CONNECTED) {
            connectionUnlockDone = true
        }
        onResult(result)
        return result
    }

    /**
     * React to a gated-command `03` NAK: run the unlock, then retry the command once on success.
     * [retry] re-issues the command that was NAKed (e.g. the sync transfer command).
     */
    suspend fun onGatedNak(
        cmdId: Int,
        prompt: () -> Unit = {},
        onResult: (OfflineUnlockResult) -> Unit = {},
        retry: (suspend () -> Unit)? = null,
    ): OfflineUnlockResult {
        HeyCyanGateState.recordNak(cmdId)
        if (!enabled) return OfflineUnlockResult.DISABLED
        if (missedPress) {
            Log.i(TAG, "gated command 0x%02X NAKed after a missed press; waiting for a reconnect or user action".format(cmdId))
            return OfflineUnlockResult.BIND_REJECTED
        }
        Log.i(TAG, "gated command 0x%02X returned the 03 NAK; attempting offline unlock".format(cmdId))
        val result = runUnlock(prompt)
        // A concurrent trigger is already unlocking: no popup, no latch, no retry (the running one reports).
        if (result == OfflineUnlockResult.IN_PROGRESS) return result
        if (result == OfflineUnlockResult.BIND_REJECTED) missedPress = true
        connectionUnlockDone = connectionUnlockDone ||
            result == OfflineUnlockResult.SUCCESS || result == OfflineUnlockResult.ALREADY_UNLOCKED
        onResult(result)
        if (result == OfflineUnlockResult.SUCCESS || result == OfflineUnlockResult.ALREADY_UNLOCKED) {
            runCatching { retry?.invoke() }
                .onFailure { Log.w(TAG, "post-unlock command retry failed", it) }
        }
        return result
    }

    private suspend fun runUnlock(prompt: () -> Unit): OfflineUnlockResult {
        if (!unlocking.compareAndSet(false, true)) {
            Log.i(TAG, "offline unlock already in progress; skipping concurrent trigger")
            return OfflineUnlockResult.IN_PROGRESS
        }
        return try {
            coordinatorFactory().attemptOfflineUnlock(onPromptFrontButton = prompt)
        } finally {
            unlocking.set(false)
        }
    }

    companion object {
        const val TAG = "HeyCyanGate"

        /** The automatic gate unlock is on by default (the pref remains as a manual off switch). */
        const val DEFAULT_ENABLED = true
    }
}
