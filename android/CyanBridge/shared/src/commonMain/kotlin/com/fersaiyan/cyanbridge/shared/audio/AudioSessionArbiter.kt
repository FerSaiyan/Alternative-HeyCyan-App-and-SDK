package com.fersaiyan.cyanbridge.shared.audio

/** What happens when a newcomer session meets one that already exists. */
enum class AudioConflictOutcome {
    /** Both run. The platform decides which capture hears the microphone. */
    COEXIST,

    /** The holder pauses its microphone and resumes once the newcomer ends. */
    SUSPEND_HOLDER,

    /** The holder is ended; its owner must stop and close it. */
    END_HOLDER,

    /** The newcomer is granted in a suspended state and starts once the holder ends. */
    WAIT_NEWCOMER,

    /** The newcomer is refused. */
    DENY_NEWCOMER,
}

/**
 * Microphone arbitration rules. The same table is meant to back every platform executor
 * (Android AudioManager today, AVAudioSession on iOS later).
 *
 * | newcomer \ holder        | VOICE_COMMAND | CONVERSATION | RECORDING | CONT_RECOGNITION | AMBIENT |
 * |--------------------------|---------------|--------------|-----------|------------------|---------|
 * | VOICE_COMMAND (90)       | deny          | suspend      | coexist   | suspend          | end     |
 * | CONVERSATION (80)        | wait          | deny         | coexist   | suspend          | end     |
 * | RECORDING (60)           | coexist       | coexist      | deny      | coexist          | end     |
 * | CONTINUOUS_RECOGNITION   | wait          | wait         | coexist   | deny             | end     |
 * | AMBIENT_LISTENING (10)   | deny          | deny         | deny      | deny             | deny    |
 *
 * Playback never conflicts. The rules, in order:
 * 1. Sessions that do not use the microphone coexist with everything.
 * 2. The same kind never runs twice: the newcomer is denied (one plugin recognizer, one Live
 *    session, one meeting recording, one spoken prompt at a time).
 * 3. A recording coexists with every interactive kind, so a meeting is never cut by a voice
 *    question, but it ends an ambient probe like everything else does.
 * 4. A higher-priority newcomer suspends a resumable holder and ends a non-resumable one.
 * 5. A lower-priority newcomer waits if it can resume later, and is denied otherwise.
 */
object AudioArbitrationPolicy {
    fun resolve(newcomer: AudioSessionKind, holder: AudioSessionKind): AudioConflictOutcome {
        if (!newcomer.usesMicrophone || !holder.usesMicrophone) return AudioConflictOutcome.COEXIST
        if (newcomer == holder) return AudioConflictOutcome.DENY_NEWCOMER
        val recording = AudioSessionKind.RECORDING
        val ambient = AudioSessionKind.AMBIENT_LISTENING
        if ((newcomer == recording && holder != ambient) || (holder == recording && newcomer != ambient)) {
            return AudioConflictOutcome.COEXIST
        }
        return if (newcomer.priority > holder.priority) {
            if (holder.resumable) AudioConflictOutcome.SUSPEND_HOLDER else AudioConflictOutcome.END_HOLDER
        } else {
            if (newcomer.resumable) AudioConflictOutcome.WAIT_NEWCOMER else AudioConflictOutcome.DENY_NEWCOMER
        }
    }
}

/** Lifecycle of one session: ACTIVE <-> SUSPENDED -> ENDED. */
enum class AudioSessionState { ACTIVE, SUSPENDED, ENDED }

/** A state change the platform executor must deliver to a session's owner. */
data class AudioSessionTransition(
    val sessionId: Long,
    val owner: String,
    val kind: AudioSessionKind,
    val state: AudioSessionState,
)

sealed class AudioArbitration {
    /** The session exists; [state] is ACTIVE or SUSPENDED (waiting for the microphone). */
    data class Granted(
        val sessionId: Long,
        val state: AudioSessionState,
        val transitions: List<AudioSessionTransition>,
    ) : AudioArbitration()

    data class Denied(
        val blockingOwner: String,
        val blockingKind: AudioSessionKind,
    ) : AudioArbitration() {
        val reason: String get() = "microphone is held by $blockingOwner (${blockingKind.name.lowercase()})"
    }
}

data class AudioSessionSnapshot(
    val sessionId: Long,
    val owner: String,
    val kind: AudioSessionKind,
    val state: AudioSessionState,
)

/**
 * The single owner of microphone arbitration state. Not thread-safe: platform executors call it
 * under their own lock and deliver the returned transitions outside that lock.
 */
class AudioSessionArbiter(
    private val policy: (AudioSessionKind, AudioSessionKind) -> AudioConflictOutcome = AudioArbitrationPolicy::resolve,
) {
    private class Entry(val id: Long, val owner: String, val kind: AudioSessionKind, var state: AudioSessionState)

    private val entries = mutableListOf<Entry>()
    private var nextId = 0L

    fun request(request: AudioSessionRequest): AudioArbitration = request(request.owner, request.kind)

    fun request(owner: String, kind: AudioSessionKind): AudioArbitration {
        // A refusal is checked against waiting sessions too, so two of the same kind never pile up.
        entries.firstOrNull { policy(kind, it.kind) == AudioConflictOutcome.DENY_NEWCOMER }?.let {
            return AudioArbitration.Denied(it.owner, it.kind)
        }
        val active = entries.filter { it.state == AudioSessionState.ACTIVE }
        val id = ++nextId
        if (active.any { policy(kind, it.kind) == AudioConflictOutcome.WAIT_NEWCOMER }) {
            entries += Entry(id, owner, kind, AudioSessionState.SUSPENDED)
            return AudioArbitration.Granted(id, AudioSessionState.SUSPENDED, emptyList())
        }
        val transitions = displace(kind, active)
        entries += Entry(id, owner, kind, AudioSessionState.ACTIVE)
        return AudioArbitration.Granted(id, AudioSessionState.ACTIVE, transitions)
    }

    /**
     * Ends [sessionId] and resumes waiting sessions that can now run, highest priority first.
     * Returns the transitions of other sessions (never the released one).
     */
    fun release(sessionId: Long): List<AudioSessionTransition> {
        val removed = entries.removeAll { it.id == sessionId }
        if (!removed) return emptyList()
        return resumeWaiting()
    }

    fun state(sessionId: Long): AudioSessionState =
        entries.firstOrNull { it.id == sessionId }?.state ?: AudioSessionState.ENDED

    fun snapshot(): List<AudioSessionSnapshot> =
        entries.map { AudioSessionSnapshot(it.id, it.owner, it.kind, it.state) }

    fun hasActive(kind: AudioSessionKind): Boolean =
        entries.any { it.kind == kind && it.state == AudioSessionState.ACTIVE }

    private fun displace(kind: AudioSessionKind, active: List<Entry>): List<AudioSessionTransition> {
        val transitions = mutableListOf<AudioSessionTransition>()
        active.forEach { holder ->
            when (policy(kind, holder.kind)) {
                AudioConflictOutcome.SUSPEND_HOLDER -> {
                    holder.state = AudioSessionState.SUSPENDED
                    transitions += holder.transition()
                }
                AudioConflictOutcome.END_HOLDER -> {
                    entries.remove(holder)
                    holder.state = AudioSessionState.ENDED
                    transitions += holder.transition()
                }
                else -> Unit
            }
        }
        return transitions
    }

    private fun resumeWaiting(): List<AudioSessionTransition> {
        val transitions = mutableListOf<AudioSessionTransition>()
        val waiting = entries
            .filter { it.state == AudioSessionState.SUSPENDED }
            .sortedWith(compareByDescending<Entry> { it.kind.priority }.thenBy { it.id })
        for (candidate in waiting) {
            if (candidate.state != AudioSessionState.SUSPENDED || candidate !in entries) continue
            val active = entries.filter { it.state == AudioSessionState.ACTIVE }
            val blocked = active.any {
                val outcome = policy(candidate.kind, it.kind)
                outcome == AudioConflictOutcome.WAIT_NEWCOMER || outcome == AudioConflictOutcome.DENY_NEWCOMER
            }
            if (blocked) continue
            transitions += displace(candidate.kind, active)
            candidate.state = AudioSessionState.ACTIVE
            transitions += candidate.transition()
        }
        return transitions
    }

    private fun Entry.transition() = AudioSessionTransition(id, owner, kind, state)
}
