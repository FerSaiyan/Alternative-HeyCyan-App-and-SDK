package com.fersaiyan.cyanbridge.ai.transcription

import kotlin.math.sqrt

/**
 * Lightweight PCM silence compactor for "needle in a haystack" recordings.
 *
 * Input must be mono 16-bit PCM samples.
 */
object SilenceCompactor {
    fun compactMonoPcm(
        samples: ShortArray,
        sampleRateHz: Int,
        preserveProbeOnNoSpeech: Boolean = true,
    ): ShortArray {
        if (samples.isEmpty()) return samples

        val sr = sampleRateHz.coerceAtLeast(8_000)
        val frameSamples = (sr / 50).coerceAtLeast(160) // ~20 ms
        val frameCount = (samples.size + frameSamples - 1) / frameSamples
        if (frameCount <= 1) return samples

        val rms = DoubleArray(frameCount)
        var maxRms = 0.0
        for (i in 0 until frameCount) {
            val start = i * frameSamples
            val end = minOf(samples.size, start + frameSamples)
            var sumSq = 0.0
            var n = 0
            for (p in start until end) {
                val v = samples[p].toDouble()
                sumSq += v * v
                n++
            }
            val value = if (n > 0) sqrt(sumSq / n) else 0.0
            rms[i] = value
            if (value > maxRms) maxRms = value
        }

        // Near-complete silence: keep a short probe window so the model can still emit NO_SPEECH.
        if (maxRms < 120.0) {
            if (!preserveProbeOnNoSpeech) return shortArrayOf()
            val keep = minOf(samples.size, sr * 2)
            return samples.copyOf(keep)
        }

        val sorted = rms.copyOf().also { it.sort() }
        val p20 = sorted[((sorted.size - 1) * 0.2).toInt()]
        var threshold = maxOf(180.0, p20 * 2.5)
        threshold = minOf(threshold, maxRms * 0.55)

        val voiced = BooleanArray(frameCount)
        var voicedFrames = 0
        for (i in 0 until frameCount) {
            if (rms[i] >= threshold) {
                voiced[i] = true
                voicedFrames++
            }
        }

        if (voicedFrames == 0) {
            if (!preserveProbeOnNoSpeech) return shortArrayOf()
            val keep = minOf(samples.size, sr * 2)
            return samples.copyOf(keep)
        }

        // Keep context around speech regions.
        val keepFrames = voiced.copyOf()
        val pre = 8   // ~160 ms
        val post = 15 // ~300 ms
        for (i in 0 until frameCount) {
            if (!voiced[i]) continue
            val from = (i - pre).coerceAtLeast(0)
            val to = (i + post).coerceAtMost(frameCount - 1)
            for (j in from..to) keepFrames[j] = true
        }

        var keptSamples = 0
        for (i in 0 until frameCount) {
            if (!keepFrames[i]) continue
            val start = i * frameSamples
            val end = minOf(samples.size, start + frameSamples)
            keptSamples += (end - start)
        }

        if (keptSamples <= 0) {
            if (!preserveProbeOnNoSpeech) return shortArrayOf()
            val keep = minOf(samples.size, sr * 2)
            return samples.copyOf(keep)
        }

        // If very little reduction, skip rewriting.
        if (keptSamples >= (samples.size * 0.92).toInt()) {
            return samples
        }

        val out = ShortArray(keptSamples)
        var w = 0
        for (i in 0 until frameCount) {
            if (!keepFrames[i]) continue
            val start = i * frameSamples
            val end = minOf(samples.size, start + frameSamples)
            for (p in start until end) out[w++] = samples[p]
        }
        return if (w == out.size) out else out.copyOf(w)
    }
}
