package com.fersaiyan.cyanbridge.shared.audio

import kotlin.math.roundToLong
import kotlin.math.sqrt

/** PCM energy primitives shared by loop monitoring, ambient probes and question endpointing. */
object PcmEnergy {
    fun rms(samples: ShortArray, offset: Int = 0, count: Int = samples.size - offset): Double {
        require(offset >= 0 && count >= 0 && offset + count <= samples.size)
        if (count == 0) return 0.0
        var sum = 0.0
        for (i in offset until offset + count) {
            val sample = samples[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / count)
    }
}

/**
 * Lightweight speech activity detector based on RMS energy over ~20 ms frames.
 *
 * Feed it PCM mono 16-bit samples; [hasSignificantSpeech] decides whether the window contained
 * speech: at least [minVoicedFraction] of frames reached [rmsThreshold], or the peak frame reached
 * twice the threshold.
 */
class SpeechActivityDetector(
    private val sampleRateHz: Int = 16_000,
    private val rmsThreshold: Double = 250.0,
    private val minVoicedFraction: Double = 0.15,
) {
    var totalFrames = 0
        private set
    var voicedFrames = 0
        private set
    var maxRms = 0.0
        private set

    fun feed(samples: ShortArray, offset: Int = 0, count: Int = samples.size - offset) {
        if (count <= 0) return
        val frameSize = (sampleRateHz / 50).coerceAtLeast(160)
        val end = offset + count
        var i = offset
        while (i < end) {
            val frameEnd = minOf(i + frameSize, end)
            val rms = PcmEnergy.rms(samples, i, frameEnd - i)
            if (rms > maxRms) maxRms = rms
            if (rms >= rmsThreshold) voicedFrames++
            totalFrames++
            i = frameEnd
        }
    }

    fun hasSignificantSpeech(): Boolean {
        if (totalFrames == 0) return false
        val fraction = voicedFrames.toDouble() / totalFrames
        return fraction >= minVoicedFraction || maxRms >= rmsThreshold * 2.0
    }

    fun reset() {
        totalFrames = 0
        voicedFrames = 0
        maxRms = 0.0
    }

    /** Fraction of voiced frames and peak RMS, e.g. `frames=50 voiced=10 (20%) maxRms=812`. */
    fun debugInfo(): String {
        val percent = if (totalFrames > 0) (voicedFrames * 100.0 / totalFrames).roundToLong() else 0L
        return "frames=$totalFrames voiced=$voicedFrames ($percent%) maxRms=${maxRms.roundToLong()}"
    }
}

/** A spoken question ends on sustained silence, never on the speech-onset deadline. */
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

    companion object {
        /** RMS a 16 kHz question frame must reach to count as voiced. */
        const val VOICED_RMS_THRESHOLD = 250.0
    }
}
