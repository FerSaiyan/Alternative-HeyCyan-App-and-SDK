package com.fersaiyan.cyanbridge.shared.audio

/**
 * The kind of audio work a feature wants to do. The kind alone decides microphone priority,
 * whether the session can pause and resume when preempted, and whether it needs the microphone.
 *
 * Priorities are compared by [AudioArbitrationPolicy]; see that type for the full conflict table.
 */
enum class AudioSessionKind(
    val priority: Int,
    val usesMicrophone: Boolean,
    val resumable: Boolean,
) {
    /** One-shot spoken input the user is waiting on: a voice question or a spoken approval. */
    VOICE_COMMAND(priority = 90, usesMicrophone = true, resumable = false),

    /** Full-duplex conversation (Gemini Live). Pauses its microphone while preempted. */
    CONVERSATION(priority = 80, usesMicrophone = true, resumable = true),

    /** Long user-started recording (meeting capture). Coexists with interactive sessions. */
    RECORDING(priority = 60, usesMicrophone = true, resumable = false),

    /** Always-on recognition for voice plugins. Pauses while a higher session owns the microphone. */
    CONTINUOUS_RECOGNITION(priority = 40, usesMicrophone = true, resumable = true),

    /** Background speech-energy probe. Never displaces another session and ends when one starts. */
    AMBIENT_LISTENING(priority = 10, usesMicrophone = true, resumable = false),

    /** Speech or PCM output only. Never takes the microphone and never conflicts. */
    PLAYBACK(priority = 0, usesMicrophone = false, resumable = false),
}

/** How a session wants phone audio routed while it is active. */
enum class AudioRoutePreference {
    /** Leave Android's current routing alone (phone microphone and the current media output). */
    SYSTEM_DEFAULT,

    /**
     * Enter communication mode and use a Bluetooth headset (glasses or earbuds) microphone and
     * speaker when one is available; otherwise keep communication mode on the phone.
     */
    COMMUNICATION_BLUETOOTH_PREFERRED,

    /**
     * Use a Bluetooth headset microphone only when a headset appears connected; otherwise leave
     * routing untouched so long phone-microphone recordings keep normal audio mode.
     */
    BLUETOOTH_IF_CONNECTED,

    /** Audio arrives through a device SDK (for example Mentra Live PCM); phone routing is untouched. */
    DEVICE_SDK,
}

/** Audio focus a session holds while active. Mirrors the platform focus gains in use today. */
enum class AudioFocusMode {
    NONE,
    TRANSIENT,
    TRANSIENT_EXCLUSIVE,
}

/**
 * A typed request for an audio session. Each subtype fixes its [kind]; features pick routing and
 * focus within the bounds of that kind.
 */
sealed class AudioSessionRequest {
    abstract val owner: String
    abstract val kind: AudioSessionKind
    abstract val route: AudioRoutePreference
    abstract val focus: AudioFocusMode

    /** One-shot spoken input (question capture, spoken approval). */
    data class VoiceCapture(
        override val owner: String,
        override val route: AudioRoutePreference = AudioRoutePreference.COMMUNICATION_BLUETOOTH_PREFERRED,
        override val focus: AudioFocusMode = AudioFocusMode.NONE,
    ) : AudioSessionRequest() {
        override val kind: AudioSessionKind get() = AudioSessionKind.VOICE_COMMAND
    }

    /** Full-duplex microphone plus speech output (Gemini Live). */
    data class Conversation(
        override val owner: String,
        override val route: AudioRoutePreference = AudioRoutePreference.COMMUNICATION_BLUETOOTH_PREFERRED,
        override val focus: AudioFocusMode = AudioFocusMode.TRANSIENT_EXCLUSIVE,
    ) : AudioSessionRequest() {
        override val kind: AudioSessionKind get() = AudioSessionKind.CONVERSATION
    }

    /** Long user-started recording to a file (meeting capture). */
    data class Recording(
        override val owner: String,
        override val route: AudioRoutePreference = AudioRoutePreference.BLUETOOTH_IF_CONNECTED,
    ) : AudioSessionRequest() {
        override val kind: AudioSessionKind get() = AudioSessionKind.RECORDING
        override val focus: AudioFocusMode get() = AudioFocusMode.NONE
    }

    /** Continuous speech recognition for a voice plugin. */
    data class ContinuousRecognition(
        override val owner: String,
        override val route: AudioRoutePreference = AudioRoutePreference.COMMUNICATION_BLUETOOTH_PREFERRED,
    ) : AudioSessionRequest() {
        override val kind: AudioSessionKind get() = AudioSessionKind.CONTINUOUS_RECOGNITION
        override val focus: AudioFocusMode get() = AudioFocusMode.NONE
    }

    /** Background speech-energy probe that stores no audio. */
    data class AmbientListening(
        override val owner: String,
        override val route: AudioRoutePreference = AudioRoutePreference.BLUETOOTH_IF_CONNECTED,
    ) : AudioSessionRequest() {
        override val kind: AudioSessionKind get() = AudioSessionKind.AMBIENT_LISTENING
        override val focus: AudioFocusMode get() = AudioFocusMode.NONE
    }

    /** Speech (TTS) or PCM output. */
    data class Playback(
        override val owner: String,
        override val focus: AudioFocusMode = AudioFocusMode.NONE,
    ) : AudioSessionRequest() {
        override val kind: AudioSessionKind get() = AudioSessionKind.PLAYBACK
        override val route: AudioRoutePreference get() = AudioRoutePreference.SYSTEM_DEFAULT
    }
}
