package com.fersaiyan.cyanbridge.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.shared.audio.AudioRouteCandidate
import com.fersaiyan.cyanbridge.shared.audio.AudioRouteEnvironment
import com.fersaiyan.cyanbridge.shared.audio.AudioRoutePlan
import com.fersaiyan.cyanbridge.shared.audio.AudioRouteState
import com.fersaiyan.cyanbridge.shared.audio.AudioRouteWait
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** The outcome of joining the shared phone route. */
data class AudioRouteResult(
    /** A Bluetooth headset carries the session's audio (selected, and confirmed when waited for). */
    val bluetoothActive: Boolean,
    /** The headset microphone to pass to setPreferredDevice, when one is visible. */
    val inputDevice: AudioDeviceInfo?,
    /** Headsets were offered for communication audio (whether or not one was selected). */
    val bluetoothCandidatesPresent: Boolean,
    /** Several headsets were offered and none matched the preferred device name. */
    val ambiguous: Boolean,
) {
    companion object {
        val PHONE = AudioRouteResult(bluetoothActive = false, inputDevice = null, bluetoothCandidatesPresent = false, ambiguous = false)
    }
}

/**
 * The one implementation of the Bluetooth communication-route dance: communication mode,
 * setCommunicationDevice on Android 12+, legacy SCO otherwise or as fallback, confirmation waits and
 * restoration. Only [AudioSessionCoordinator] calls it, so the process applies routing once and
 * restores it when the last session leaves.
 */
internal class BluetoothCommunicationRoute(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousMode: Int? = null

    class Snapshot(val environment: AudioRouteEnvironment, val devices: Map<Int, AudioDeviceInfo>)

    fun snapshot(): Snapshot {
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val devices = if (modern) {
            runCatching {
                audioManager.availableCommunicationDevices.filter { it.isBluetoothHeadset() }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val candidates = devices.map {
            AudioRouteCandidate(
                id = it.id,
                name = it.productName?.toString().orEmpty(),
                type = if (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    AudioRouteCandidate.Type.BLUETOOTH_SCO
                } else {
                    AudioRouteCandidate.Type.BLE_HEADSET
                },
            )
        }
        return Snapshot(
            AudioRouteEnvironment(modern, candidates, bluetoothHeadsetLikelyConnected()),
            devices.associateBy { it.id },
        )
    }

    /**
     * Applies [plan] without waiting and returns what was actually applied. A refused
     * communication device falls back to legacy SCO when a headset appears connected.
     */
    fun apply(plan: AudioRoutePlan, snapshot: Snapshot, owner: String): AudioRouteState {
        var applied = AudioRouteState.IDLE
        if (plan.enterCommunicationMode) {
            if (previousMode == null) previousMode = audioManager.mode
            runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
                .onSuccess { applied = applied.copy(communicationMode = true) }
        }
        var legacySco = plan.legacySco
        plan.communicationDevice?.let { candidate ->
            val device = snapshot.devices[candidate.id]
            val selected = device != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                runCatching { audioManager.setCommunicationDevice(device) }.getOrDefault(false)
            Log.i(TAG, "$owner communication device type=${device?.type} name=${candidate.name} selected=$selected")
            if (selected) {
                applied = applied.copy(communicationDevice = candidate)
            } else {
                legacySco = legacySco || snapshot.environment.bluetoothLikelyConnected
            }
        }
        if (legacySco) {
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }.onSuccess {
                applied = applied.copy(legacySco = true)
            }.onFailure {
                Log.w(TAG, "$owner legacy Bluetooth SCO unavailable", it)
            }
        }
        return applied
    }

    /**
     * Runs [applyRoute] (which plans and calls [apply]) with an SCO state receiver registered first,
     * then waits for the Bluetooth link according to [wait]. Returns what was applied and whether
     * Bluetooth is confirmed (or, for steps without a wait, requested).
     */
    suspend fun applyAndAwait(
        wait: AudioRouteWait,
        owner: String,
        applyRoute: () -> AudioRouteState,
    ): Pair<AudioRouteState, Boolean> {
        val scoConnected = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                Log.i(TAG, "$owner Bluetooth SCO state=$state sticky=$isInitialStickyBroadcast")
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> scoConnected.complete(true)
                    AudioManager.SCO_AUDIO_STATE_ERROR -> scoConnected.complete(false)
                    // The sticky state predates this request; only a fresh disconnect is a failure.
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> if (!isInitialStickyBroadcast) scoConnected.complete(false)
                }
            }
        }
        val waits = wait.communicationDeviceTimeoutMs > 0 || wait.legacyScoTimeoutMs > 0
        var registered = false
        try {
            if (waits) {
                registered = runCatching {
                    ContextCompat.registerReceiver(
                        appContext,
                        receiver,
                        IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                }.isSuccess
            }
            val applied = applyRoute()
            val confirmed = when {
                applied.communicationDevice != null -> if (wait.communicationDeviceTimeoutMs <= 0) {
                    true
                } else {
                    isBluetoothCommunicationDeviceSelected() || withTimeoutOrNull(wait.communicationDeviceTimeoutMs) {
                        while (!isBluetoothCommunicationDeviceSelected() && !scoConnected.isCompleted) delay(100L)
                        isBluetoothCommunicationDeviceSelected() || scoConnected.await()
                    } == true
                }
                applied.legacySco -> if (wait.legacyScoTimeoutMs <= 0 || !registered) {
                    true
                } else {
                    withTimeoutOrNull(wait.legacyScoTimeoutMs) { scoConnected.await() } == true
                }
                else -> false
            }
            if (confirmed && wait.settleMs > 0) delay(wait.settleMs)
            return applied to confirmed
        } finally {
            if (registered) runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    /** Undoes [state]: clears the communication device, stops SCO, leaves communication mode. */
    fun restore(state: AudioRouteState, owner: String) {
        if (state.communicationDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        }
        if (state.legacySco) {
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
            }
        }
        if (state.communicationMode) {
            // Never restore communication mode itself: it can only be a stale leftover.
            val mode = previousMode?.takeUnless { it == AudioManager.MODE_IN_COMMUNICATION } ?: AudioManager.MODE_NORMAL
            runCatching { audioManager.mode = mode }
            previousMode = null
        }
        if (state != AudioRouteState.IDLE) Log.i(TAG, "$owner restored audio route: ${summary()}")
    }

    /** The headset microphone matching the selected communication device, else any headset input. */
    fun bluetoothInputDevice(state: AudioRouteState): AudioDeviceInfo? {
        val inputs = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList() }
            .getOrDefault(emptyList())
            .filter { it.isBluetoothHeadset() }
        val name = state.communicationDevice?.name
        return inputs.firstOrNull { name != null && it.productName?.toString() == name }
            ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: inputs.firstOrNull()
    }

    fun isBluetoothCommunicationDeviceSelected(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching { audioManager.communicationDevice?.isBluetoothHeadset() == true }.getOrDefault(false)
    }

    fun summary(): String {
        val communicationDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.communicationDevice?.let { "${it.type}:${it.productName}" } }.getOrNull() ?: "none"
        } else {
            "unsupported"
        }
        val devices = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS)
                .joinToString { "${it.type}:${it.productName}" }
        }.getOrDefault("")
        @Suppress("DEPRECATION")
        return "mode=${audioManager.mode}, sco=${audioManager.isBluetoothScoOn}, " +
            "communication=$communicationDevice, devices=[$devices]"
    }

    private fun bluetoothHeadsetLikelyConnected(): Boolean = try {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        inputs.any { it.isBluetoothHeadset() } ||
            outputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.isBluetoothHeadset() } ||
            // Fallback hint: if SCO is available off-call routing can be attempted.
            audioManager.isBluetoothScoAvailableOffCall
    } catch (_: Throwable) {
        false
    }

    private fun AudioDeviceInfo.isBluetoothHeadset(): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    private companion object {
        const val TAG = "AudioSession"
    }
}
