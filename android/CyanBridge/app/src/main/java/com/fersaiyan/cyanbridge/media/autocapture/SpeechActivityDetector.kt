package com.fersaiyan.cyanbridge.media.autocapture

/**
 * Lightweight real-time speech activity detector based on RMS energy.
 *
 * Designed for the last-minute-of-loop monitoring in auto-capture.
 * Feed it PCM mono 16-bit samples periodically; call [hasSignificantSpeech]
 * at the end to decide whether to extend the loop.
 */
class SpeechActivityDetector(
    private val sampleRateHz: Int = 16_000,
    private val rmsThreshold: Double = 250.0,
    private val minVoicedFraction: Double = 0.15,
) {
    private var totalFrames = 0
    private var voicedFrames = 0
    private var maxRms = 0.0

    /**
     * Feed a batch of PCM mono 16-bit samples.
     * Call this periodically during the last minute of the loop.
     */
    fun feed(samples: ShortArray) {
        if (samples.isEmpty()) return

        val frameSize = (sampleRateHz / 50).coerceAtLeast(160) // ~20 ms frames
        var i = 0
        while (i < samples.size) {
            val end = minOf(i + frameSize, samples.size)
            var sumSq = 0.0
            val n = end - i
            for (j in i until end) {
                val v = samples[j].toDouble()
                sumSq += v * v
            }
            val rms = if (n > 0) kotlin.math.sqrt(sumSq / n) else 0.0
            if (rms > maxRms) maxRms = rms
            if (rms >= rmsThreshold) voicedFrames++
            totalFrames++
            i = end
        }
    }

    /**
     * Returns true if significant speech was detected in the monitored window.
     *
     * Criteria:
     * - At least [minVoicedFraction] of frames exceeded [rmsThreshold]
     * - OR peak RMS is very high (>= 2x threshold), indicating loud speech
     */
    fun hasSignificantSpeech(): Boolean {
        if (totalFrames == 0) return false
        val fraction = voicedFrames.toDouble() / totalFrames
        return fraction >= minVoicedFraction || maxRms >= rmsThreshold * 2.0
    }

    /** Reset internal counters for a fresh monitoring window. */
    fun reset() {
        totalFrames = 0
        voicedFrames = 0
        maxRms = 0.0
    }

    /** Debug info: fraction of voiced frames and peak RMS. */
    fun debugInfo(): String =
        "frames=$totalFrames voiced=$voicedFrames (${if (totalFrames > 0) String.format("%.0f", voicedFrames * 100.0 / totalFrames) else "0"}%) maxRms=${String.format("%.0f", maxRms)}"
}
