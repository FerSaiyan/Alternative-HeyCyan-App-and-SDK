package com.fersaiyan.cyanbridge.ai.speaker

import android.content.Context
import com.fersaiyan.cyanbridge.localmodels.engine.NpuArtifacts

/**
 * A LiteRT speaker-embedding graph with its filterbank inside the graph:
 * `float32[1, windowSamples]` mono PCM in [-1, 1] -> `float32[1, dims]`.
 * See docs/AUDIO_SPEAKER_FILTER.md for the exports and their measured accuracy.
 *
 * Embeddings from different models (or from the CPU and NPU variants of one model) are
 * not comparable, so every template records the [id] it was built with, and each model has
 * its own thresholds.
 */
data class SpeakerModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val dims: Int,
    val license: String,
    /**
     * "Answer only my voice" thresholds on the question score (mean window cosine against the
     * template). Below [blockBelow] the speaker is confidently someone else and the question is
     * not answered; at or above [wearerAt] it is the wearer; in between it is answered as
     * uncertain. Fitted in docs/AUDIO_SPEAKER_FILTER.md ("Answer only my voice").
     */
    val blockBelow: Float,
    val wearerAt: Float,
    val sampleRate: Int = 16_000,
    val windowSamples: Int = 32_000,
    /** Null until the artifact is hosted; until then it is installed by staging the file. */
    val sourceUrl: String? = null,
    /** An AOT build for this phone's NPU (LiteRT CompiledModel), not a CPU Interpreter graph. */
    val npu: Boolean = false,
)

object SpeakerModels {
    /**
     * Base URL the exports are downloaded from (`<base>/<fileName>`): the public Hugging Face repo
     * Fertroll10/cyanbridge-models, pinned to a commit so the bytes always match [SpeakerModelSpec.sha256].
     */
    private val HOSTING_BASE_URL: String? =
        "https://huggingface.co/Fertroll10/cyanbridge-models/resolve/fb76d9fdb058db0633794c25ab240112d2fb8456"

    private fun hosted(fileName: String): String? = HOSTING_BASE_URL?.let { "${it.trimEnd('/')}/$fileName" }

    /** SpeechBrain ECAPA-TDNN, 84 MB fp32. LibriSpeech EER 0.7 % with 3 phrases and 2 s windows. */
    val ECAPA = SpeakerModelSpec(
        id = "ecapa-spkrec-voxceleb-2s",
        displayName = "ECAPA-TDNN (SpeechBrain)",
        fileName = "ecapa_spkrec_voxceleb_2s.tflite",
        sizeBytes = 84_076_036L,
        sha256 = "f88f074f4446310ea6cf5d4089ddfb13bb8b7a8de76437086e47886ba5883259",
        dims = 192,
        license = "speechbrain/spkrec-ecapa-voxceleb@0f99f2d0, Apache-2.0",
        // LibriSpeech test-clean, 40 speakers, 3-phrase enrollment: blocks 76 % of other voices; wearer
        // falsely blocked 0 % clean, 2.3 % in 10 dB babble, 7 % in 5 dB babble.
        blockBelow = 0.13f,
        wearerAt = 0.38f,
        sourceUrl = hosted("ecapa_spkrec_voxceleb_2s.tflite"),
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
        // Same trials: blocks 91 % of other voices; wearer falsely blocked 0.3 % clean, 1.9 % in 10 dB
        // babble, 5 % in 5 dB babble.
        blockBelow = 0.21f,
        wearerAt = 0.44f,
        sourceUrl = hosted("wespeaker_resnet34_lm_2s_int8w.tflite"),
    )

    val all = listOf(ECAPA, WESPEAKER_INT8)

    /**
     * WeSpeaker int8 (owner, 2026-11-19): blocks more other voices than ECAPA and is 7x smaller.
     * Templates are per model, so an existing enrollment is re-embedded from its stored phrases.
     * Phones with an NPU build use [preferred] instead.
     */
    val DEFAULT = WESPEAKER_INT8

    /**
     * NPU embedding variants in cyanbridge-npu-models: Snapdragon builds use the KaldiFbankNpu front end, MediaTek and
     * Exynos builds the v3 log-approximation graph. On the 2026-11-19 LibriSpeech protocol (refit 2026-11-23,
     * /mnt/seagate/ML/speaker-npu-thresholds-20261123) both give the same embeddings (min cosine 0.99999976) and the
     * same thresholds, so they share one template id. Their embeddings differ from the int8 CPU model's (mean window
     * cosine 0.953), hence a separate id and template.
     */
    const val NPU_ID = "wespeaker-resnet34-lm-2s-npu"
    private val NPU_VARIANTS = setOf("wespeaker-npu-kaldifbanknpu", "wespeaker-npu-v3-logapprox")

    /** blockBelow is limited by the narrowband (8 kHz) wearer rule: 0.91 % falsely blocked at 0.20, 1.05 % at 0.21. */
    private const val NPU_BLOCK_BELOW = 0.20f
    /** 0.081 % of other speakers accepted in clean audio. */
    private const val NPU_WEARER_AT = 0.44f

    /**
     * The NPU build of WeSpeaker for this phone (about 18x faster than the CPU model, so the check can score more of
     * the question without delaying the answer). Its embeddings differ from the int8 model's, hence its own id and
     * thresholds (fitted 2026-11-23 with the 2026-11-19 protocol, docs/AUDIO_SPEAKER_FILTER.md).
     */
    fun npuFor(context: Context): SpeakerModelSpec? {
        val artifact = NpuArtifacts.forDevice(context, NpuArtifacts.Model.WESPEAKER) ?: return null
        if (artifact.variant !in NPU_VARIANTS) return null
        return SpeakerModelSpec(
            id = NPU_ID,
            displayName = "WeSpeaker ResNet34-LM (NPU)",
            fileName = artifact.localName,
            sizeBytes = artifact.bytes,
            sha256 = artifact.sha256,
            dims = 256,
            license = "Wespeaker/wespeaker-voxceleb-resnet34-LM@f0c48c29, CC BY 4.0",
            blockBelow = NPU_BLOCK_BELOW,
            wearerAt = NPU_WEARER_AT,
            sourceUrl = artifact.url,
            npu = true,
        )
    }

    /**
     * The model this phone uses (owner, 2026-11-23): its NPU build when there is one, else [DEFAULT]. A phone whose
     * NPU build failed to load stays on [DEFAULT] (see [markNpuBroken]); switching re-embeds the stored phrases.
     */
    fun preferred(context: Context): SpeakerModelSpec =
        npuFor(context)?.takeUnless { npuBroken(context) } ?: DEFAULT

    private const val PREFS = "speaker_models"
    private const val KEY_NPU_BROKEN = "npu_broken_build"

    /** Remembers (per app build) that the NPU model failed to load here, so [preferred] falls back to the CPU model. */
    fun markNpuBroken(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_NPU_BROKEN, com.fersaiyan.cyanbridge.BuildConfig.VERSION_CODE).apply()
    }

    private fun npuBroken(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_NPU_BROKEN, -1) ==
            com.fersaiyan.cyanbridge.BuildConfig.VERSION_CODE

    fun findById(id: String?): SpeakerModelSpec? = all.firstOrNull { it.id == id }
}

/** What the speaker check decided about one question. */
enum class SpeakerVerdict { WEARER, UNCERTAIN, NOT_WEARER }

/**
 * "Answer only my voice": everything except the per-model thresholds ([SpeakerModelSpec.blockBelow],
 * [SpeakerModelSpec.wearerAt]). JVM-testable.
 */
object SpeakerGatePolicy {
    /** Less voiced speech than this is not scored, and the question is answered. */
    const val MIN_SPEECH_MS = 500L

    /**
     * Shortest trimmed question that is scored (tiled to one 2 s window). Enrollment keeps 1 s;
     * questions are often shorter ("What time is it?"), and the thresholds were fitted with them.
     */
    const val MIN_SCORED_SAMPLES = 12_800

    /** At most this many 2 s windows are scored per question, which bounds the added latency. */
    const val MAX_WINDOWS = 3

    fun verdict(spec: SpeakerModelSpec, score: Float): SpeakerVerdict = when {
        score < spec.blockBelow -> SpeakerVerdict.NOT_WEARER
        score >= spec.wearerAt -> SpeakerVerdict.WEARER
        else -> SpeakerVerdict.UNCERTAIN
    }

    /** Evenly spread choice of at most [max] windows, so a long question is sampled start to end. */
    fun <T> spread(windows: List<T>, max: Int = MAX_WINDOWS): List<T> {
        if (windows.size <= max) return windows
        if (max == 1) return listOf(windows[windows.size / 2])
        return (0 until max).map { windows[Math.round(it * (windows.size - 1) / (max - 1).toFloat())] }
    }
}
