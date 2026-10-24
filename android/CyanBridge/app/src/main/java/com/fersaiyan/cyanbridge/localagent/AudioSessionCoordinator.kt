package com.fersaiyan.cyanbridge.localagent

import com.fersaiyan.cyanbridge.audio.AudioSessionCoordinator as ProcessAudioSessions

/**
 * The speech-playing flag under its original name, kept for callers that have not moved to
 * [com.fersaiyan.cyanbridge.audio.AudioSessionCoordinator] yet (MainActivity's TTS).
 */
object AudioSessionCoordinator {
    fun isBusy(): Boolean = ProcessAudioSessions.isSpeechBusy()

    fun markBusy() = ProcessAudioSessions.markSpeechBusy()

    fun markIdle() = ProcessAudioSessions.markSpeechIdle()
}
