package com.fersaiyan.cyanbridge.ai.speaker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

sealed interface TemplateResult {
    data class Ready(val template: SpeakerTemplate) : TemplateResult
    /** Enrollment is saved; the template is built once the model is installed. */
    data class ModelMissing(val spec: SpeakerModelSpec) : TemplateResult
    data object NotEnrolled : TemplateResult
    data class Failed(val message: String) : TemplateResult
}

/** Speaker similarity of one utterance against the enrolled template. Numbers only. */
data class SpeakerScore(
    val modelId: String,
    /** Mean window cosine against the template. */
    val score: Float,
    val maxWindow: Float,
    val windows: Int,
    val speechMs: Long,
    val elapsedMs: Long,
)

/**
 * Entry point for the wearer's voice profile. Answers are NOT gated on it yet: [observe] only
 * scores an utterance and appends the numbers to a private calibration log, so a threshold can
 * later be fitted on the real glasses microphone (LibriSpeech thresholds do not transfer).
 */
object VoiceProfile {
    private const val TAG = "VoiceProfile"
    private const val OBSERVATION_LOG_LIMIT = 500
    /** Placeholder between the fixture's other-speaker max (~0.29) and target min (~0.51). Uncalibrated. */
    const val PROVISIONAL_THRESHOLD = 0.40f

    private val embedderLock = Mutex()
    private var embedder: SpeakerEmbedder? = null

    fun store(context: Context) = VoiceEnrollmentStore(context.applicationContext)

    fun isEnrolled(context: Context) = store(context).isEnrolled()

    fun delete(context: Context) {
        store(context).deleteAll()
        Log.i(TAG, "Voice profile deleted")
    }

    /** Embeds each phrase's speech windows. Used for the template and the enrollment consistency check. */
    suspend fun embedPhrases(context: Context, phrases: List<ShortArray>, spec: SpeakerModelSpec): List<List<FloatArray>>? =
        withEmbedder(context, spec) { model ->
            phrases.map { pcm ->
                SpeakerMath.windows(SpeakerMath.trimToSpeech(pcm), spec.windowSamples).map(model::embed)
            }
        }

    /** Builds (or returns) the template for [spec] from the stored enrollment phrases. */
    suspend fun ensureTemplate(context: Context, spec: SpeakerModelSpec = SpeakerModels.DEFAULT): TemplateResult {
        val store = store(context)
        store.loadTemplate(spec.id)?.let { return TemplateResult.Ready(it) }
        val info = store.info() ?: return TemplateResult.NotEnrolled
        val phrases = store.loadPhrases(info.phraseCount) ?: return TemplateResult.NotEnrolled
        return try {
            val perPhrase = embedPhrases(context, phrases, spec) ?: return TemplateResult.ModelMissing(spec)
            saveTemplate(context, spec, perPhrase)
        } catch (error: Exception) {
            Log.w(TAG, "Template build failed: ${error.javaClass.simpleName}")
            TemplateResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /** Saves the template for [spec] from per-phrase window embeddings of the committed enrollment. */
    fun saveTemplate(context: Context, spec: SpeakerModelSpec, perPhrase: List<List<FloatArray>>): TemplateResult {
        val all = perPhrase.flatten()
        if (all.isEmpty()) return TemplateResult.Failed("No usable speech in the enrollment phrases")
        val leaveOneOut = perPhrase.filter { it.isNotEmpty() }
            .takeIf { it.size >= 2 }?.let(SpeakerMath::leaveOneOutScores).orEmpty()
        val template = SpeakerTemplate(
            modelId = spec.id,
            vector = SpeakerMath.template(all),
            windows = all.size,
            minLeaveOneOut = leaveOneOut.minOrNull() ?: Float.NaN,
            createdAtMs = System.currentTimeMillis(),
        )
        store(context).saveTemplate(template)
        Log.i(TAG, "Template ready model=${spec.id} windows=${all.size} minLeaveOneOut=${fmt(template.minLeaveOneOut)}")
        return TemplateResult.Ready(template)
    }

    /** Scores [pcm] against the enrolled template, or returns null when there is nothing to score. */
    suspend fun score(context: Context, pcm: ShortArray, spec: SpeakerModelSpec = SpeakerModels.DEFAULT): SpeakerScore? {
        val template = (ensureTemplate(context, spec) as? TemplateResult.Ready)?.template ?: return null
        val speech = SpeakerMath.trimToSpeech(pcm)
        val windows = SpeakerMath.windows(speech, spec.windowSamples)
        if (windows.isEmpty()) return null
        val started = System.nanoTime()
        val scores = withEmbedder(context, spec) { model ->
            windows.map { SpeakerMath.cosine(model.embed(it), template.vector) }
        } ?: return null
        return SpeakerScore(
            modelId = spec.id,
            score = scores.average().toFloat(),
            maxWindow = scores.max(),
            windows = scores.size,
            speechMs = speech.size * 1000L / SpeakerMath.SAMPLE_RATE,
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
        )
    }

    /**
     * Observation only: scores a question recording and appends numbers to the private
     * calibration log. Never changes whether or how the question is answered.
     */
    suspend fun observe(context: Context, pcm: ShortArray, flow: String): SpeakerScore? {
        if (!isEnrolled(context)) return null
        val result = runCatching { score(context, pcm) }
            .onFailure { Log.w(TAG, "Speaker observation failed: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return null
        val wouldAccept = result.score >= PROVISIONAL_THRESHOLD
        Log.i(
            TAG,
            "SPEAKER_OBSERVE flow=$flow model=${result.modelId} score=${fmt(result.score)} " +
                "max=${fmt(result.maxWindow)} windows=${result.windows} speechMs=${result.speechMs} " +
                "elapsedMs=${result.elapsedMs} provisionalAccept=$wouldAccept",
        )
        appendObservation(store(context).observationLog(), flow, result)
        return result
    }

    /** Releases the cached interpreter (for example on low memory). */
    suspend fun release() = embedderLock.withLock {
        embedder?.close()
        embedder = null
    }

    private suspend fun <T> withEmbedder(context: Context, spec: SpeakerModelSpec, block: (SpeakerEmbedder) -> T): T? =
        withContext(Dispatchers.Default) {
            embedderLock.withLock {
                val current = embedder?.takeIf { it.spec.id == spec.id } ?: run {
                    embedder?.close()
                    embedder = null
                    val file = SpeakerModelStore.resolve(context, spec) ?: return@withLock null
                    LiteRtSpeakerEmbedder(spec, file).also { embedder = it }
                }
                block(current)
            }
        }

    private fun appendObservation(log: File, flow: String, score: SpeakerScore) {
        runCatching {
            log.parentFile?.mkdirs()
            val lines = if (log.isFile) log.readLines().takeLast(OBSERVATION_LOG_LIMIT - 1) else emptyList()
            val header = "timeMs,flow,model,score,max,windows,speechMs"
            val row = listOf(
                System.currentTimeMillis(), flow, score.modelId, fmt(score.score), fmt(score.maxWindow),
                score.windows, score.speechMs,
            ).joinToString(",")
            log.writeText((listOf(header) + lines.filter { it != header } + row).joinToString("\n", postfix = "\n"))
        }
    }

    private fun fmt(value: Float) = String.format(Locale.US, "%.4f", value)
}
