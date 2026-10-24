package com.fersaiyan.cyanbridge.ai.live

/**
 * Short outgoing-audio delay line that can hold a turn's trailing silence back from Gemini's
 * server VAD until a fresh visual context has been sent, so the turn is not finalized without
 * its image. Every operation is serialized on one lock and forwards packets in socket order.
 */
internal class GeminiLiveVisualAudioHold {
    private val lock = Any()
    private val delayedAudio = ArrayDeque<ByteArray>()
    private var audioDeferredForVisualContext = false
    private var visualAudioHoldStarted = false
    private var heldVisualSilenceChunks = 0

    /** Arms the hold while [isLive]; [onArmed] runs only when a new hold starts. */
    fun defer(isLive: () -> Boolean, onArmed: () -> Unit) {
        synchronized(lock) {
            if (!isLive()) return
            if (!audioDeferredForVisualContext) {
                audioDeferredForVisualContext = true
                visualAudioHoldStarted = false
                heldVisualSilenceChunks = 0
                onArmed()
            }
        }
    }

    /** Releases a held tail through [send]; [onReleased] receives the released chunk count. */
    fun release(isLive: () -> Boolean, send: (ByteArray) -> Unit, onReleased: (Int) -> Unit) {
        synchronized(lock) {
            if (!audioDeferredForVisualContext) return
            audioDeferredForVisualContext = false
            visualAudioHoldStarted = false
            heldVisualSilenceChunks = 0
            if (!isLive()) {
                delayedAudio.clear()
                return
            }
            val chunks = delayedAudio.size
            while (delayedAudio.isNotEmpty()) send(delayedAudio.removeFirst())
            onReleased(chunks)
        }
    }

    /** Queues one captured packet and streams the oldest one once the delay line is full. */
    fun offer(bytes: ByteArray, isSpeaking: () -> Boolean, send: (ByteArray) -> Unit) {
        synchronized(lock) {
            val speaking = isSpeaking()
            if (audioDeferredForVisualContext && visualAudioHoldStarted && speaking) {
                repeat(heldVisualSilenceChunks.coerceAtMost(delayedAudio.size)) {
                    delayedAudio.removeLast()
                }
                heldVisualSilenceChunks = 0
                visualAudioHoldStarted = false
            }

            if (!audioDeferredForVisualContext || !visualAudioHoldStarted || speaking) {
                delayedAudio.addLast(bytes.copyOf())
            }
            if (audioDeferredForVisualContext && !speaking && !visualAudioHoldStarted) {
                visualAudioHoldStarted = true
                // The detector changes state on the fifteenth silent chunk. Those chunks are
                // already inside the delayed tail, so retain exactly that tail and no more.
                heldVisualSilenceChunks = VISUAL_TRAILING_SILENCE_CHUNKS.coerceAtMost(delayedAudio.size)
            }
            val canStream = !audioDeferredForVisualContext ||
                (!visualAudioHoldStarted && speaking)
            if (canStream && delayedAudio.size > AUDIO_DELAY_CHUNKS) {
                send(delayedAudio.removeFirst())
            }
        }
    }

    /** Drops any hold and sends everything queued while [isLive]; otherwise discards it. */
    fun flush(isLive: () -> Boolean, send: (ByteArray) -> Unit) {
        synchronized(lock) {
            audioDeferredForVisualContext = false
            visualAudioHoldStarted = false
            heldVisualSilenceChunks = 0
            if (isLive()) {
                while (delayedAudio.isNotEmpty()) send(delayedAudio.removeFirst())
            } else {
                delayedAudio.clear()
            }
        }
    }

    /** Drops any hold and discards everything queued. */
    fun clear() {
        synchronized(lock) {
            audioDeferredForVisualContext = false
            visualAudioHoldStarted = false
            heldVisualSilenceChunks = 0
            delayedAudio.clear()
        }
    }

    companion object {
        // Keep more than the detector's 600 ms trailing-silence window. During a still transfer,
        // speech streams normally while this tail is held back from Gemini's 500 ms server VAD.
        const val AUDIO_DELAY_CHUNKS = 16
        const val VISUAL_TRAILING_SILENCE_CHUNKS = 15
    }
}
