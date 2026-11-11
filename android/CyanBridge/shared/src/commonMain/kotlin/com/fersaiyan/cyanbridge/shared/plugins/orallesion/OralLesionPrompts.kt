package com.fersaiyan.cyanbridge.shared.plugins.orallesion

/** Patient sex in the words of the training data. */
enum class PatientSex(val ptBr: String) {
    MALE("masculino"),
    FEMALE("feminino"),
    UNKNOWN("não informado"),
}

data class CaseDemographics(val age: Int?, val sex: PatientSex)

/** One model question and the dentist's answer; a null or blank answer is "not documented". */
data class DentistAnswer(val question: String, val answer: String?)

/**
 * The exact pt-BR prompts the oral-lesion Gemma 4 models were trained and evaluated on: the system
 * turn of `assemble_public_synthetic_sft_v1.py`, and `PROMPTS`, `demographics_text` and
 * `render_answers` of `build_image_episodes_v1.py`, as `episode_harness_v1.py` feeds them (turn 1,
 * turn 2 in the same conversation, and the summary as its own one-photo conversation). The photo is
 * a separate content part in front of the user text: the training rows' `<image>\n` prefix.
 *
 * One changed character changes what the model sees; OralLesionPromptsTest pins these against rows
 * of the harness inputs.
 */
object OralLesionPrompts {
    const val SYSTEM =
        "Você é um assistente de estomatologia que apoia cirurgiões-dentistas. Use apenas as informações " +
            "fornecidas, separe o que foi descrito do que ainda precisa ser verificado, faça apenas perguntas que " +
            "possam mudar a avaliação e nunca invente achados, exames ou resultados de biópsia."

    /** The answer the training episodes give for a question the record does not answer. */
    const val NOT_DOCUMENTED = "não documentado no prontuário"

    /** The harness sends at most this many turn-1 questions to the dentist. */
    const val MAX_QUESTIONS = 5

    fun demographics(case: CaseDemographics): String =
        if (case.age != null && case.age > 0) {
            "Paciente do sexo ${case.sex.ptBr}, ${case.age} anos."
        } else {
            "Paciente do sexo ${case.sex.ptBr}."
        }

    /** Turn 1: the photo and this text. */
    fun assessment(case: CaseDemographics): String =
        "Dentista: ${demographics(case)} Avalie esta fotografia intraoral, por favor."

    /** Turn 2, in the turn-1 conversation: one line per question asked, in order. */
    fun answers(answers: List<DentistAnswer>): String {
        val lines = answers.joinToString("\n") { item ->
            "- ${item.question.trim()} → ${item.answer?.trim()?.takeIf { it.isNotEmpty() } ?: NOT_DOCUMENTED}"
        }
        return "Respostas do dentista:\n${lines.ifEmpty { "-" }}"
    }

    /** The clinical record: a new conversation with the photo, the demographics and every recorded fact. */
    fun summary(case: CaseDemographics, facts: List<String>): String {
        val recorded = facts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n") { "- $it" }.ifEmpty { "-" }
        return "Dentista: ${demographics(case)} Informações registradas:\n$recorded\n\n" +
            "Escreva o registro clínico com as seções: ## Descrição da lesão, ## Hipótese diagnóstica e ## Classificação operacional."
    }

    /**
     * The facts of a case for [summary], written like the training records ("Label: value"): the
     * photographed site, each answered question, and the red flags the dentist confirmed. The site is
     * not part of turn 1: the training turn 1 has only age and sex.
     */
    fun caseFacts(site: String?, answers: List<DentistAnswer>, redFlags: Set<RedFlag>): List<String> = buildList {
        site?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Local da lesão: $it") }
        answers.forEach { item ->
            val answer = item.answer?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            add("${item.question.trim().trimEnd('?', ' ', '.')}: $answer")
        }
        RedFlag.entries.filter { it in redFlags }.forEach { add("${it.factLabel}: sim") }
    }
}
