package com.fersaiyan.cyanbridge.ai.speaker

/**
 * A LiteRT speaker-embedding graph with its filterbank inside the graph:
 * `float32[1, windowSamples]` mono PCM in [-1, 1] -> `float32[1, dims]`.
 * See docs/AUDIO_SPEAKER_FILTER.md for the exports and their measured accuracy.
 *
 * Embeddings from different models (or from the CPU and NPU variants of one model) are
 * not comparable, so every template records the [id] it was built with.
 */
data class SpeakerModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val dims: Int,
    val license: String,
    val sampleRate: Int = 16_000,
    val windowSamples: Int = 32_000,
    /** Null until the artifact is published; until then it is installed by staging the file. */
    val sourceUrl: String? = null,
)

object SpeakerModels {
    /** SpeechBrain ECAPA-TDNN, 84 MB fp32. LibriSpeech EER 0.7 % with 3 phrases and 2 s windows. */
    val ECAPA = SpeakerModelSpec(
        id = "ecapa-spkrec-voxceleb-2s",
        displayName = "ECAPA-TDNN (SpeechBrain)",
        fileName = "ecapa_spkrec_voxceleb_2s.tflite",
        sizeBytes = 84_076_036L,
        sha256 = "f88f074f4446310ea6cf5d4089ddfb13bb8b7a8de76437086e47886ba5883259",
        dims = 192,
        license = "speechbrain/spkrec-ecapa-voxceleb@0f99f2d0, Apache-2.0",
    )

    /** WeSpeaker ResNet34-LM, ResNet weights int8, 11.6 MB. EER 0.5 % with 3 phrases and 2 s windows. */
    val WESPEAKER_INT8 = SpeakerModelSpec(
        id = "wespeaker-resnet34-lm-2s-int8w",
        displayName = "WeSpeaker ResNet34-LM (int8)",
        fileName = "wespeaker_resnet34_lm_2s_int8w.tflite",
        sizeBytes = 11_632_752L,
        sha256 = "56afd34101f2ffd9f6b016daaf6b8538576712edf2ba78ff096e18a7c1b4c53a",
        dims = 256,
        license = "Wespeaker/wespeaker-voxceleb-resnet34-LM@f0c48c29, CC BY 4.0",
    )

    val all = listOf(ECAPA, WESPEAKER_INT8)

    /** ECAPA by request; WeSpeaker int8 is the measured upgrade (smaller and more accurate). */
    val DEFAULT = ECAPA

    fun findById(id: String?): SpeakerModelSpec? = all.firstOrNull { it.id == id }
}
