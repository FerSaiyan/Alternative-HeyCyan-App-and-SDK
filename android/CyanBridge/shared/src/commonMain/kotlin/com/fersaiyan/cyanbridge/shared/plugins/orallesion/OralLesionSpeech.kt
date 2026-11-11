package com.fersaiyan.cyanbridge.shared.plugins.orallesion

/**
 * The short spoken versions of the model's answers (the screen shows the full text): the
 * guardrail's referral line first, the research-use notice in the first reply of a session, the
 * main finding, the top two hypotheses. The questions are spoken afterwards, one at a time.
 */
object OralLesionSpeech {
    const val RESEARCH_NOTICE = "Uso em pesquisa, não é diagnóstico."

    fun assessment(answer: OralLesionAnswer, guardrail: GuardrailResult, firstOfSession: Boolean): String = join(
        guardrail.line,
        RESEARCH_NOTICE.takeIf { firstOfSession },
        firstSentence(answer.findings)?.let { "Achado principal: $it" },
        topHypotheses(answer.hypotheses),
    )

    fun revision(answer: OralLesionAnswer, guardrail: GuardrailResult, firstOfSession: Boolean): String = join(
        guardrail.line,
        RESEARCH_NOTICE.takeIf { firstOfSession },
        topHypotheses(answer.hypotheses),
        firstSentence(answer.nextStep)?.let { "Próximo passo: $it" },
    )

    fun question(index: Int, total: Int, question: String): String = "Pergunta ${index + 1} de $total: $question"

    /** The record's hypothesis and class lines, e.g. "Hipótese: leucoplasia. Classe: lesão potencialmente maligna." */
    fun summary(text: String, guardrail: GuardrailResult?): String {
        val sections = Regex("""^##\s*(.+)$""", RegexOption.MULTILINE).findAll(text).toList()
        fun body(index: Int): String {
            val start = sections[index].range.last + 1
            val end = sections.getOrNull(index + 1)?.range?.first ?: text.length
            return text.substring(start, end).trim()
        }
        val hypothesis = sections.indices.firstOrNull { sections[it].groupValues[1].lowercase().startsWith("hipótese") }?.let(::body)
        val operational = Regex("""(healthy|benign_lesion|opmd|cancer)\s*$""").find(text.trim())?.groupValues?.get(1)
        return join(
            guardrail?.line,
            hypothesis?.let(::firstSentence)?.let { "Hipótese: $it" },
            operational?.let { "Classe: ${CLASS_PT[it]}." },
        )
    }

    private fun topHypotheses(hypotheses: List<String>): String? = when (hypotheses.size) {
        0 -> null
        1 -> "Hipótese principal: ${hypotheses[0]}."
        else -> "Hipóteses principais: ${hypotheses[0]} e ${hypotheses[1]}."
    }

    /** First sentence of [text] without Markdown, capped for speech. */
    fun firstSentence(text: String): String? {
        val plain = text.replace(Regex("""[*#_`]"""), "").replace(Regex("""^\s*(?:\d+[.)]|[-•])\s*""", RegexOption.MULTILINE), "")
            .replace(Regex("""\s+"""), " ").trim()
        if (plain.isEmpty()) return null
        val end = Regex("""[.!?](\s|$)""").find(plain)?.range?.first?.plus(1) ?: plain.length
        val sentence = plain.substring(0, end).trim()
        return if (sentence.length <= MAX_SENTENCE) sentence else sentence.take(MAX_SENTENCE).substringBeforeLast(' ') + "…"
    }

    private fun join(vararg parts: String?): String = parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" ") {
        val trimmed = it.trim()
        if (trimmed.last() in ".!?…") trimmed else "$trimmed."
    }

    private const val MAX_SENTENCE = 220
    private val CLASS_PT = mapOf(
        "healthy" to "mucosa normal",
        "benign_lesion" to "lesão benigna",
        "opmd" to "desordem oral potencialmente maligna",
        "cancer" to "suspeita de câncer",
    )
}

/** Age, sex and site dictated in one sentence, e.g. "mulher, 54 anos, borda lateral da língua". */
data class SpokenDemographics(val age: Int?, val sex: PatientSex?, val site: String?)

object OralLesionDictation {
    private val AGE = Regex("""\b(\d{1,3})\s*anos?\b""")
    private val MALE = Regex("""\b(sexo masculino|masculino|homem|paciente homem)\b""")
    private val FEMALE = Regex("""\b(sexo feminino|feminino|mulher|paciente mulher)\b""")
    private val FILLER = Regex("""\b(paciente|de idade|idade|com|do sexo|sexo|lesão (na|no|em)|local|região|na|no|em)\b""")

    fun parse(text: String): SpokenDemographics {
        val lower = text.lowercase()
        val age = AGE.find(lower)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..120 }
        val sex = when {
            FEMALE.containsMatchIn(lower) -> PatientSex.FEMALE
            MALE.containsMatchIn(lower) -> PatientSex.MALE
            else -> null
        }
        val rest = listOf(AGE, MALE, FEMALE).fold(lower) { acc, re -> re.replace(acc, " ") }
        val site = rest.split(',', ';', '.').map { FILLER.replace(it, " ").replace(Regex("""\s+"""), " ").trim() }
            .filter { it.length >= 3 }
            .joinToString(", ")
            .takeIf { it.isNotEmpty() }
        return SpokenDemographics(age, sex, site)
    }
}
