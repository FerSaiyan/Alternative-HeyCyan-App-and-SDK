package com.fersaiyan.cyanbridge.shared.plugins.orallesion

import kotlinx.serialization.Serializable

/**
 * A `.litertlm` the bench can run, pinned by revision and SHA-256 (both mandatory). [gated] models
 * come from the owner's gated Hugging Face repo and need the user's own token with granted access;
 * a 401/403 sends the user to [repoUrl] to request it, never to a public model of the same name.
 * [appCatalogId] names the app catalog's entry for the same stock file, reused when it is installed
 * and its SHA-256 matches.
 */
data class OralLesionModel(
    val id: String,
    val displayName: String,
    val description: String,
    val repo: String,
    val revision: String,
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
    val gated: Boolean,
    val fineTuned: Boolean,
    val appCatalogId: String? = null,
) {
    val url: String get() = "https://huggingface.co/$repo/resolve/$revision/$path"
    val repoUrl: String get() = "https://huggingface.co/$repo"
    val fileName: String get() = path.substringAfterLast('/')
}

object OralLesionModels {
    const val REPO = "Fertroll10/oral-lesion-classification-gemma4"
    const val REPO_URL = "https://huggingface.co/$REPO"

    val stockE4b = OralLesionModel(
        id = "gemma4-e4b-stock",
        displayName = "Gemma 4 E4B (Google, baseline)",
        description = "Unmodified Google model, for comparison.",
        repo = "litert-community/gemma-4-E4B-it-litert-lm",
        revision = "8b77b27bfeb8d39447ac174c07d5bcde70e45d81",
        path = "gemma-4-E4B-it.litertlm",
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        sizeBytes = 3_659_530_240L,
        gated = false,
        fineTuned = false,
        appCatalogId = "gemma4-e4b-it-litert",
    )

    val stockE2b = OralLesionModel(
        id = "gemma4-e2b-stock",
        displayName = "Gemma 4 E2B (Google, baseline)",
        description = "Unmodified Google model, for comparison.",
        repo = "litert-community/gemma-4-E2B-it-litert-lm",
        revision = "3d48132a00cf76de57d926de30f5c5c5ed9b8c0d",
        path = "gemma-4-E2B-it.litertlm",
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        sizeBytes = 2_588_147_712L,
        gated = false,
        fineTuned = false,
        appCatalogId = "gemma4-e2b-it-litert",
    )

    /** The fine-tuned models first; GPU builds only for now. */
    val entries: List<OralLesionModel> = listOf(stockE4b, stockE2b)

    val default: OralLesionModel get() = entries.first()

    fun byId(id: String?): OralLesionModel? = entries.firstOrNull { it.id == id }
}

/** One turn's timings; the token rates come from LiteRT-LM's benchmark counters when available. */
@Serializable
data class OralLesionTurnMetrics(
    val turn: String,
    val latencyMs: Long,
    val ttftMs: Long? = null,
    val prefillTokens: Int? = null,
    val decodeTokens: Int? = null,
    val prefillTokensPerSecond: Double? = null,
    val decodeTokensPerSecond: Double? = null,
)

@Serializable
data class OralLesionQa(val question: String, val answer: String? = null)

@Serializable
data class OralLesionGuardrailRecord(
    val turn: String,
    val refer: Boolean,
    val urgent: Boolean,
    val modelAdvisedReferral: Boolean,
    val modelUrgencyLanguage: Boolean,
    /** The guardrail fired and the model's text had not advised biopsy or referral. */
    val addedReferral: Boolean,
    val differentialClasses: List<String?>,
    val redFlags: List<String>,
)

/** The dentist's later judgement of the case; every field optional. */
@Serializable
data class OralLesionDentistFields(
    val finalDiagnosis: String = "",
    val biopsyResult: String = "",
    /** "useful", "wrong" or "unsafe" (empty when not rated). */
    val rating: String = "",
    val notes: String = "",
)

/** One bench case: a JSONL row in app-private storage, its photo stored next to it. Nothing is uploaded. */
@Serializable
data class OralLesionBenchCase(
    val caseId: String,
    val createdAtMs: Long,
    val modelId: String,
    val modelSha256: String,
    val modelFile: String,
    val backend: String,
    val soc: String,
    val device: String,
    val appVersion: String,
    val photoSource: String,
    val photoFile: String,
    val age: Int? = null,
    val sex: String,
    val site: String = "",
    val turn1Prompt: String,
    val turn1Output: String,
    val qa: List<OralLesionQa> = emptyList(),
    val turn2Prompt: String? = null,
    val turn2Output: String? = null,
    val summaryPrompt: String? = null,
    val summaryOutput: String? = null,
    val metrics: List<OralLesionTurnMetrics> = emptyList(),
    val guardrail: List<OralLesionGuardrailRecord> = emptyList(),
    val dentist: OralLesionDentistFields = OralLesionDentistFields(),
)

fun GuardrailResult.record(turn: String) = OralLesionGuardrailRecord(
    turn = turn,
    refer = refer,
    urgent = urgent,
    modelAdvisedReferral = modelAdvisedReferral,
    modelUrgencyLanguage = modelUrgencyLanguage,
    addedReferral = addedReferral,
    differentialClasses = differentialClasses.map { it?.name?.lowercase() },
    redFlags = redFlags.map { it.name.lowercase() },
)
