package com.fersaiyan.cyanbridge.shared.audio

import com.fersaiyan.cyanbridge.shared.devices.GlassesAudioInput
import com.fersaiyan.cyanbridge.shared.devices.GlassesAudioOutput
import com.fersaiyan.cyanbridge.shared.devices.GlassesAudioProfile

/** A Bluetooth device the platform offers for communication audio (microphone plus speaker). */
data class AudioRouteCandidate(
    val id: Int,
    val name: String,
    val type: Type,
) {
    enum class Type { BLUETOOTH_SCO, BLE_HEADSET }
}

/** What the platform reports about Bluetooth audio right now. */
data class AudioRouteEnvironment(
    /** True when the platform can select a communication device directly (Android 12+). */
    val supportsCommunicationDevice: Boolean,
    val communicationCandidates: List<AudioRouteCandidate>,
    /** Any Bluetooth headset input/output is present (or the platform offers SCO off-call). */
    val bluetoothLikelyConnected: Boolean,
)

sealed class AudioRouteSelection {
    data class Selected(val candidate: AudioRouteCandidate) : AudioRouteSelection()
    data object NoCandidate : AudioRouteSelection()

    /** Several headsets are offered and none matches the preferred device name. */
    data class Ambiguous(val candidates: List<AudioRouteCandidate>) : AudioRouteSelection()
}

/** The routing steps to apply for one session joining the shared route. */
data class AudioRoutePlan(
    val enterCommunicationMode: Boolean,
    val communicationDevice: AudioRouteCandidate?,
    /** Start legacy Bluetooth SCO; also the fallback when selecting [communicationDevice] fails. */
    val legacySco: Boolean,
    val ambiguous: Boolean = false,
) {
    val changesRoute: Boolean get() = enterCommunicationMode || communicationDevice != null || legacySco

    companion object {
        val NONE = AudioRoutePlan(enterCommunicationMode = false, communicationDevice = null, legacySco = false)
    }
}

/** What the shared route currently has applied on the platform. */
data class AudioRouteState(
    val communicationMode: Boolean = false,
    val communicationDevice: AudioRouteCandidate? = null,
    val legacySco: Boolean = false,
) {
    val bluetooth: Boolean get() = communicationDevice != null || legacySco

    fun plus(other: AudioRouteState) = AudioRouteState(
        communicationMode = communicationMode || other.communicationMode,
        communicationDevice = communicationDevice ?: other.communicationDevice,
        legacySco = legacySco || other.legacySco,
    )

    companion object {
        val IDLE = AudioRouteState()
    }
}

/** How long to wait for the Bluetooth route to come up before recording. */
data class AudioRouteWait(
    /** Wait for the selected communication device to become current (polling). 0 = do not wait. */
    val communicationDeviceTimeoutMs: Long,
    /** Wait for the legacy SCO link to report connected. 0 = do not wait. */
    val legacyScoTimeoutMs: Long,
    /** Extra settle time once a Bluetooth route is confirmed, before the first audio. */
    val settleMs: Long,
) {
    companion object {
        /** Fire and forget: the session starts audio immediately (Gemini Live, plugin recognizers). */
        val NONE = AudioRouteWait(0, 0, 0)

        /**
         * The confirmation used for one-shot spoken input: 3 s for the communication device, 6 s
         * for a slow legacy SCO link, then 500 ms so the cue or first syllable is not clipped.
         */
        val CONFIRMED = AudioRouteWait(communicationDeviceTimeoutMs = 3_000, legacyScoTimeoutMs = 6_000, settleMs = 500)

        /** Meeting recording and ambient probes: confirmed SCO, short fixed settle after selection. */
        val RECORDING = AudioRouteWait(communicationDeviceTimeoutMs = 3_000, legacyScoTimeoutMs = 6_000, settleMs = 250)
    }
}

object AudioRoutePolicy {
    /**
     * Applies the selected glasses' audio profile to the route a feature asked for: glasses that
     * stream microphone PCM through their SDK must not also get the Bluetooth headset route
     * (it duplicates audio and pulls output off their media profile).
     */
    fun forDevice(requested: AudioRoutePreference, device: GlassesAudioProfile?): AudioRoutePreference = when {
        requested == AudioRoutePreference.SYSTEM_DEFAULT -> requested
        device?.input == GlassesAudioInput.VENDOR_PCM_STREAM -> AudioRoutePreference.DEVICE_SDK
        else -> requested
    }

    /**
     * The route of a spoken question: the glasses' SDK stream when their profile records questions
     * from it ([GlassesAudioProfile.questionInput]) and [sdkStreamAvailable] (the platform has the
     * stream up now), else what [forDevice] gives a voice prompt. MemoMind: the Opus recording link,
     * or the HFP headset route when the link is down, the firmware has none, or iOS uses BLE.
     */
    fun forQuestion(device: GlassesAudioProfile?, sdkStreamAvailable: Boolean): AudioRoutePreference =
        if (sdkStreamAvailable && device?.questionInput == GlassesAudioInput.VENDOR_PCM_STREAM) {
            AudioRoutePreference.DEVICE_SDK
        } else {
            forDevice(AudioRoutePreference.COMMUNICATION_BLUETOOTH_PREFERRED, device)
        }

    /** True when the glasses' SDK must be told whenever the app plays audio to them. */
    fun needsPlaybackSignal(device: GlassesAudioProfile?): Boolean =
        device?.output == GlassesAudioOutput.BLUETOOTH_WITH_PLAYBACK_SIGNAL

    /**
     * Picks the headset to route to. A candidate whose name matches [preferredName] wins; without a
     * match the first candidate is used unless [requireUnambiguous] is set and several are offered.
     */
    fun select(
        candidates: List<AudioRouteCandidate>,
        preferredName: String?,
        requireUnambiguous: Boolean,
    ): AudioRouteSelection {
        if (candidates.isEmpty()) return AudioRouteSelection.NoCandidate
        val named = preferredName?.takeIf { it.isNotBlank() }?.let { name ->
            candidates.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
        if (named != null) return AudioRouteSelection.Selected(named)
        if (requireUnambiguous && candidates.size > 1) return AudioRouteSelection.Ambiguous(candidates)
        return AudioRouteSelection.Selected(candidates.first())
    }

    /**
     * The steps a session with [preference] still needs, given what [current] already applied.
     * Sessions joining an established Bluetooth route reuse it instead of re-negotiating it.
     */
    fun plan(
        preference: AudioRoutePreference,
        environment: AudioRouteEnvironment,
        current: AudioRouteState,
        preferredName: String? = null,
        requireUnambiguous: Boolean = false,
    ): AudioRoutePlan {
        val wantsBluetooth = when (preference) {
            AudioRoutePreference.SYSTEM_DEFAULT, AudioRoutePreference.DEVICE_SDK -> return AudioRoutePlan.NONE
            AudioRoutePreference.COMMUNICATION_BLUETOOTH_PREFERRED -> true
            AudioRoutePreference.BLUETOOTH_IF_CONNECTED -> environment.bluetoothLikelyConnected
        }
        // A phone-only recording keeps normal audio mode; everything else talks in communication mode.
        val needsCommunicationMode = preference == AudioRoutePreference.COMMUNICATION_BLUETOOTH_PREFERRED || wantsBluetooth
        val enterMode = needsCommunicationMode && !current.communicationMode
        if (!wantsBluetooth || current.bluetooth) {
            return AudioRoutePlan(enterMode, communicationDevice = null, legacySco = false)
        }
        if (environment.supportsCommunicationDevice) {
            return when (val selection = select(environment.communicationCandidates, preferredName, requireUnambiguous)) {
                is AudioRouteSelection.Selected -> AudioRoutePlan(enterMode, selection.candidate, legacySco = false)
                is AudioRouteSelection.Ambiguous -> AudioRoutePlan(enterMode, null, legacySco = false, ambiguous = true)
                AudioRouteSelection.NoCandidate -> AudioRoutePlan(
                    enterMode,
                    communicationDevice = null,
                    // Without a communication device only a classic SCO link can carry the mic.
                    legacySco = environment.bluetoothLikelyConnected,
                )
            }
        }
        return AudioRoutePlan(enterMode, communicationDevice = null, legacySco = true)
    }
}

/**
 * Reference-counted record of who is using the shared phone route. The first session applies
 * routing; later sessions add only what is missing; the route is restored when the last one leaves.
 */
class AudioRouteLedger {
    private val users = mutableMapOf<Long, AudioRouteState>()

    var applied: AudioRouteState = AudioRouteState.IDLE
        private set

    /** Records that [sessionId] uses the route and that [added] was applied on its behalf. */
    fun join(sessionId: Long, added: AudioRouteState) {
        users[sessionId] = (users[sessionId] ?: AudioRouteState.IDLE).plus(added)
        applied = applied.plus(added)
    }

    fun isUser(sessionId: Long): Boolean = sessionId in users

    val userCount: Int get() = users.size

    /**
     * Removes [sessionId]. Returns what must be undone on the platform: everything applied when
     * this was the last user, otherwise nothing.
     */
    fun leave(sessionId: Long): AudioRouteState {
        if (users.remove(sessionId) == null) return AudioRouteState.IDLE
        if (users.isNotEmpty()) return AudioRouteState.IDLE
        val undo = applied
        applied = AudioRouteState.IDLE
        return undo
    }
}
