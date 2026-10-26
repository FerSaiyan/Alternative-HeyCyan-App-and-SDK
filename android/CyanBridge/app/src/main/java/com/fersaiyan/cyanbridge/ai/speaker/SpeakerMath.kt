package com.fersaiyan.cyanbridge.ai.speaker

import com.fersaiyan.cyanbridge.media.autocapture.SpeechActivityDetector
import kotlin.math.abs
import kotlin.math.sqrt

/** Windowing, templates and scoring shared by enrollment and verification. JVM-testable. */
object SpeakerMath {
    const val SAMPLE_RATE = 16_000
    const val FRAME_SAMPLES = 320
    /** Same voiced threshold as the question endpoint, so enrollment and runtime agree on "speech". */
    const val VOICED_RMS = 250.0
    /** Scale used by the host exports and AudioSpeakerLiteRtProbeTest. */
    const val PCM_SCALE = 32767f

    /** Trims leading/trailing non-speech frames, keeping [padFrames] of context on each side. */
    fun trimToSpeech(pcm: ShortArray, padFrames: Int = 5): ShortArray {
        val frames = pcm.size / FRAME_SAMPLES
        var first = -1
        var last = -1
        for (f in 0 until frames) {
            if (SpeechActivityDetector.rms(pcm, f * FRAME_SAMPLES, FRAME_SAMPLES) >= VOICED_RMS) {
                if (first < 0) first = f
                last = f
            }
        }
        if (first < 0) return ShortArray(0)
        val start = (first - padFrames).coerceAtLeast(0) * FRAME_SAMPLES
        val end = ((last + 1 + padFrames) * FRAME_SAMPLES).coerceAtMost(pcm.size)
        return pcm.copyOfRange(start, end)
    }

    fun voicedMs(pcm: ShortArray): Long {
        var voiced = 0
        for (f in 0 until pcm.size / FRAME_SAMPLES) {
            if (SpeechActivityDetector.rms(pcm, f * FRAME_SAMPLES, FRAME_SAMPLES) >= VOICED_RMS) voiced++
        }
        return voiced * FRAME_SAMPLES * 1000L / SAMPLE_RATE
    }

    /**
     * Fixed-size model windows with [hopSamples] overlap. Speech shorter than one window but at
     * least [minSamples] long is tiled (repeat-padded) rather than zero-padded, because the
     * in-graph mean normalisation would otherwise be dominated by silence. The tail gets its own
     * end-aligned window when more than half a hop would otherwise be dropped.
     */
    fun windows(
        pcm: ShortArray,
        windowSamples: Int,
        hopSamples: Int = SAMPLE_RATE,
        minSamples: Int = SAMPLE_RATE,
    ): List<FloatArray> {
        if (pcm.size < minSamples) return emptyList()
        if (pcm.size <= windowSamples) {
            return listOf(FloatArray(windowSamples) { pcm[it % pcm.size] / PCM_SCALE })
        }
        val starts = mutableListOf<Int>()
        var start = 0
        while (start + windowSamples <= pcm.size) {
            starts += start
            start += hopSamples
        }
        val tailStart = pcm.size - windowSamples
        if (tailStart - starts.last() > hopSamples / 2) starts += tailStart
        return starts.map { s -> FloatArray(windowSamples) { pcm[s + it] / PCM_SCALE } }
    }

    fun l2Normalize(v: FloatArray): FloatArray {
        val n = sqrt(v.fold(0.0) { acc, x -> acc + x * x }).toFloat().coerceAtLeast(1e-12f)
        return FloatArray(v.size) { v[it] / n }
    }

    /** Mean of L2-normalised embeddings, renormalised (the recommendation in AUDIO_SPEAKER_FILTER.md). */
    fun template(embeddings: List<FloatArray>): FloatArray {
        require(embeddings.isNotEmpty()) { "No embeddings" }
        val dims = embeddings.first().size
        require(embeddings.all { it.size == dims }) { "Mixed embedding sizes" }
        val sum = FloatArray(dims)
        for (e in embeddings) {
            val n = l2Normalize(e)
            for (i in 0 until dims) sum[i] += n[i]
        }
        return l2Normalize(sum)
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Size mismatch ${a.size} != ${b.size}" }
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return (dot / (sqrt(na) * sqrt(nb)).coerceAtLeast(1e-12)).toFloat()
    }

    /**
     * Leave-one-out agreement: each phrase scored against the template of the others. A low
     * minimum means one phrase probably has a different voice or heavy noise in it.
     */
    fun leaveOneOutScores(phraseEmbeddings: List<List<FloatArray>>): List<Float> {
        require(phraseEmbeddings.size >= 2) { "Need at least two phrases" }
        return phraseEmbeddings.indices.map { i ->
            val others = phraseEmbeddings.filterIndexed { j, _ -> j != i }.flatten()
            cosine(template(phraseEmbeddings[i]), template(others))
        }
    }

    fun clippedFraction(pcm: ShortArray, level: Int = 32_000): Float {
        if (pcm.isEmpty()) return 0f
        return pcm.count { abs(it.toInt()) >= level }.toFloat() / pcm.size
    }
}

/** Why an enrollment phrase should be recorded again. */
enum class PhraseIssue { NO_SPEECH, TOO_SHORT, CLIPPED }

object EnrollmentQuality {
    const val MIN_VOICED_MS = 1_200L
    const val MAX_CLIPPED_FRACTION = 0.01f
    /** Uncalibrated sanity bound; same-session, same-microphone phrases normally agree far above it. */
    const val MIN_LEAVE_ONE_OUT = 0.35f

    fun check(pcm: ShortArray): PhraseIssue? {
        val voiced = SpeakerMath.voicedMs(pcm)
        return when {
            voiced == 0L -> PhraseIssue.NO_SPEECH
            voiced < MIN_VOICED_MS -> PhraseIssue.TOO_SHORT
            SpeakerMath.clippedFraction(pcm) > MAX_CLIPPED_FRACTION -> PhraseIssue.CLIPPED
            else -> null
        }
    }
}
