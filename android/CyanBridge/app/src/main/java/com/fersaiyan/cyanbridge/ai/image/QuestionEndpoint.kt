package com.fersaiyan.cyanbridge.ai.image

/** A question ends on sustained silence, never on the speech-onset deadline. */
class QuestionEndpoint(
    private val onsetTimeoutMs: Long = 3_300,
    private val silenceMs: Long = 1_800,
    private val minimumOnsetMs: Long = 80,
    private val resourceLimitMs: Long = 120_000,
) {
    enum class Decision { CONTINUE, NO_SPEECH, COMPLETE, LIMIT_REACHED }
    private var activeMs = 0L
    private var silentMs = 0L
    var heardSpeech = false
        private set

    fun advance(voiced: Boolean, frameMs: Long, elapsedMs: Long): Decision {
        if (voiced) {
            activeMs += frameMs
            silentMs = 0
            if (activeMs >= minimumOnsetMs) heardSpeech = true
        } else {
            activeMs = 0
            silentMs += frameMs
        }
        // A resource limit is an error, not a completed (silently truncated) question.
        if (elapsedMs >= resourceLimitMs) return Decision.LIMIT_REACHED
        if (heardSpeech && silentMs >= silenceMs) return Decision.COMPLETE
        if (!heardSpeech && elapsedMs >= onsetTimeoutMs) return Decision.NO_SPEECH
        return Decision.CONTINUE
    }
}
