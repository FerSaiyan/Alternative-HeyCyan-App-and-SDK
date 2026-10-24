package com.fersaiyan.cyanbridge.ai.live

/** States surfaced by the preview UI; shared with the screen's ViewModel. */
typealias GeminiLiveState = com.fersaiyan.cyanbridge.shared.live.GeminiLiveState

object PcmResampler {
    /** Linear, mono PCM16 resampling for a future glasses PCM source. */
    fun resampleMono16(input: ShortArray, inputRate: Int, outputRate: Int): ShortArray {
        require(inputRate > 0 && outputRate > 0)
        if (input.isEmpty() || inputRate == outputRate) return input.copyOf()
        val outputSize = ((input.size.toLong() * outputRate) / inputRate).toInt().coerceAtLeast(1)
        return ShortArray(outputSize) { index ->
            val source = index.toDouble() * inputRate / outputRate
            val before = source.toInt().coerceIn(0, input.lastIndex)
            val after = (before + 1).coerceAtMost(input.lastIndex)
            val fraction = source - before
            (input[before] + (input[after] - input[before]) * fraction).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
