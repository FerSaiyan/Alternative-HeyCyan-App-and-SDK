package com.fersaiyan.cyanbridge.ai.live

import kotlin.math.abs

/**
 * Tiny local energy detector used only to schedule visual context around speech.
 * Gemini remains responsible for transcription and server-side VAD/turn taking.
 */
class GeminiLiveSpeechActivityDetector(
    private val startThreshold: Int = 900,
    private val activeChunksToStart: Int = 2,
    // 15 x 40 ms chunks leaves more than Gemini's 500 ms server-VAD silence window
    // in the transmitted tail before local gating suppresses subsequent silence.
    private val silentChunksToStop: Int = 15,
    private val onChanged: (Boolean) -> Unit,
) {
    private var active = false
    private var activeChunks = 0
    private var silentChunks = 0

    fun reset() {
        val wasActive = active
        active = false
        activeChunks = 0
        silentChunks = 0
        if (wasActive) onChanged(false)
    }

    fun offerPcm16Le(bytes: ByteArray, length: Int = bytes.size): Boolean {
        if (length < 2) return active
        var sum = 0L
        var samples = 0
        var index = 0
        val boundedLength = length.coerceAtMost(bytes.size)
        while (index + 1 < boundedLength) {
            val lo = bytes[index].toInt() and 0xff
            val hi = bytes[index + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            sum += abs(sample).toLong()
            samples++
            index += 2
        }
        if (samples == 0) return active
        val meanAbs = (sum / samples).toInt()
        val speechLike = meanAbs >= startThreshold

        if (!active) {
            if (speechLike) {
                activeChunks++
                if (activeChunks >= activeChunksToStart) {
                    active = true
                    silentChunks = 0
                    onChanged(true)
                }
            } else {
                activeChunks = 0
            }
            return active
        }

        if (speechLike) {
            silentChunks = 0
        } else {
            silentChunks++
            if (silentChunks >= silentChunksToStop) {
                active = false
                activeChunks = 0
                silentChunks = 0
                onChanged(false)
            }
        }
        return active
    }
}
