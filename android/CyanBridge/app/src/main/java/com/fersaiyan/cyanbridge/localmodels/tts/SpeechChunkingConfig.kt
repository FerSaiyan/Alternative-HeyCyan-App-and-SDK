package com.fersaiyan.cyanbridge.localmodels.tts

/**
 * Configuration parameters for the multilingual streaming speech chunker.
 */
data class SpeechChunkingConfig(
    // The first audible clause should arrive quickly; later chunks can be larger for prosody.
    val firstChunkMinCodePoints: Int = 12,
    val normalChunkMinCodePoints: Int = 35,
    val firstChunkPreferredMaxCodePoints: Int = 52,
    val preferredChunkMaxCodePoints: Int = 120,
    val hardChunkMaxCodePoints: Int = 180,
    val candidateBoundaryDelayMs: Long = 100L,
    val firstChunkIdleFlushMs: Long = 250L,
    val normalChunkIdleFlushMs: Long = 800L,
    val maxPendingTtsChunks: Int = 2,
)
