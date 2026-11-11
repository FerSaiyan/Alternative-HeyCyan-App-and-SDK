package com.fersaiyan.cyanbridge.shared.plugins.orallesion

/**
 * The model's answer split as `episode_harness_v1.py` reads it: `## Heading` (or `**Heading:**`)
 * sections under canonical keys, the hypothesis names of the differential and the questions.
 */
data class OralLesionAnswer(
    val sections: Map<String, String>,
    val hypotheses: List<String>,
    val questions: List<String>,
) {
    val findings: String get() = sections[FINDINGS].orEmpty()
    val nextStep: String get() = sections[NEXT].orEmpty()

    companion object {
        const val FINDINGS = "findings"
        const val DIFFERENTIAL = "differential"
        const val QUESTIONS = "questions"
        const val UNRESOLVED = "unresolved"
        const val NEXT = "next"
        const val CHANGE = "change"

        private val SECTION_ALIASES = linkedMapOf(
            FINDINGS to listOf("findings", "achados", "descrição", "description", "visible findings"),
            DIFFERENTIAL to listOf(
                "diagnostic hypotheses", "hipóteses diagnósticas", "hipótese diagnóstica", "differential",
                "diagnóstico diferencial", "hypotheses",
            ),
            QUESTIONS to listOf("questions for the dentist", "perguntas ao dentista", "perguntas", "questions"),
            UNRESOLVED to listOf("still unresolved", "em aberto", "unresolved"),
            NEXT to listOf("next step", "próximo passo", "conduta"),
            CHANGE to listOf("what changed", "o que mudou"),
        )
        private val HEADING = Regex("""^\s*(?:#{1,6}\s*|\*\*)([^#*:]+?)(?:\*\*)?:?\s*(?:\*\*)?\s*$""")
        private val ITEM = Regex("""^\s*(?:\d+[.)]|[-*•])\s*(.+)$""")
        private val BOLD = Regex("""\*\*(.+?)\*\*""")
        private val NAME_SPLIT = Regex("""\s+[—–-]\s+|:\s""")
        private val WHY = Regex("""\s*\((?:why|motivo|porque)[^)]*\)\s*$""", RegexOption.IGNORE_CASE)
        // Python's \w for the Latin letters headings use (no \p{L}: Kotlin/Native regex portability).
        private val NON_WORD = Regex("""[^0-9A-Za-z_\u00C0-\u024F]+""")

        private fun norm(text: String) = NON_WORD.replace(text.lowercase(), " ").trim()

        fun parse(text: String): OralLesionAnswer {
            val sections = linkedMapOf<String, MutableList<String>>()
            var current: String? = null
            for (line in text.lines()) {
                val head = HEADING.find(line)?.groupValues?.get(1)?.let(::norm)
                val key = head?.let { h ->
                    SECTION_ALIASES.entries.firstOrNull { (_, aliases) ->
                        aliases.any { alias -> norm(alias).let { h == it || h.startsWith(it) } }
                    }?.key
                }
                if (key != null) {
                    current = key
                    sections.getOrPut(key) { mutableListOf() }
                } else if (current != null) {
                    sections.getValue(current) += line
                }
            }
            val joined = sections.mapValues { (_, lines) -> lines.joinToString("\n").trim() }
            return OralLesionAnswer(joined, hypotheses(joined[DIFFERENTIAL].orEmpty()), questions(joined[QUESTIONS].orEmpty()))
        }

        private fun hypotheses(block: String): List<String> = block.lines().mapNotNull { line ->
            val item = ITEM.find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            val name = BOLD.find(item)?.groupValues?.get(1) ?: item.split(NAME_SPLIT, limit = 2).first()
            name.trim().takeIf { it.isNotEmpty() }
        }

        private fun questions(block: String): List<String> = block.lines().mapNotNull { line ->
            val item = ITEM.find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            WHY.replace(item, "").trim().takeIf { it.isNotEmpty() }
        }
    }
}

/** Findings the dentist reports that make a case urgent whatever the model said. */
enum class RedFlag(val factLabel: String) {
    INDURATION("Endurecimento (induração) à palpação"),
    BLEEDING("Sangramento"),
    FIXED_NODE("Linfonodo fixo"),
    ULCER_OVER_TWO_WEEKS("Úlcera que não cicatriza há mais de 2 semanas"),
}

/** Operational classes of the oral-lesion project. */
enum class LesionClass { HEALTHY, BENIGN, OPMD, CANCER }

/**
 * The outcome of [OralLesionGuardrail.evaluate]. [refer] and [urgent] are decided outside the model;
 * [modelAdvisedReferral] tells whether the model's own text already advised biopsy or referral.
 */
data class GuardrailResult(
    val refer: Boolean,
    val urgent: Boolean,
    val differentialClasses: List<LesionClass?>,
    val modelAdvisedReferral: Boolean,
    val modelUrgencyLanguage: Boolean,
    val redFlags: Set<RedFlag>,
) {
    val fired: Boolean get() = refer || urgent

    /** The guardrail added the referral: the model's text did not advise biopsy or referral. */
    val addedReferral: Boolean get() = fired && !modelAdvisedReferral

    /** What the app shows and speaks first when the guardrail fired, or null. */
    val line: String?
        get() = when {
            urgent -> OralLesionGuardrail.URGENT_LINE
            refer -> OralLesionGuardrail.REFER_LINE
            else -> null
        }
}

/**
 * The referral guardrail validated on the saved harness predictions on 2026-11-05
 * (results/gemma4_vlm/2026-11-05_triage_rescore, `guard_full`): refer when OPMD or cancer appears
 * anywhere in the parsed differential or the text advises biopsy/referral; urgent when cancer
 * appears anywhere in the differential or the text uses urgency language. Here urgent also fires
 * on red flags the dentist reports. The class lexicon is that analysis' `lexicon.py`.
 */
object OralLesionGuardrail {
    const val REFER_LINE = "Atenção: encaminhar para biópsia/especialista."
    const val URGENT_LINE = "Urgente: encaminhar para biópsia/especialista sem demora."

    // triage_rescore.py URGENT (the harness's referral words) and URGENCY_LANG.
    private val REFERRAL_TEXT = Regex("""biops|biópsi|refer|encaminh|urgent|urgênc|imediat|immediate|especialista|specialist|oncolog""")
    private val URGENCY_TEXT = Regex("""urgent|urgênc|imediat|immediate|priorit|o quanto antes|sem demora|brevidade|oncolog""")

    fun evaluate(modelText: String, redFlags: Set<RedFlag> = emptySet()): GuardrailResult {
        val answer = OralLesionAnswer.parse(modelText)
        val classes = answer.hypotheses.map(::hypothesisClass)
        val guardText = (if (answer.nextStep.isNotEmpty()) {
            answer.nextStep + "\n" + answer.sections[OralLesionAnswer.UNRESOLVED].orEmpty()
        } else {
            modelText
        }).lowercase()
        val advised = REFERRAL_TEXT.containsMatchIn(guardText)
        val urgency = URGENCY_TEXT.containsMatchIn(guardText)
        val named = classes.filterNotNull().toSet()
        return GuardrailResult(
            refer = LesionClass.OPMD in named || LesionClass.CANCER in named || advised || redFlags.isNotEmpty(),
            urgent = LesionClass.CANCER in named || urgency || redFlags.isNotEmpty(),
            differentialClasses = classes,
            modelAdvisedReferral = advised,
            modelUrgencyLanguage = urgency,
            redFlags = redFlags,
        )
    }

    /**
     * The clinical record (summary): its operational class line and its hypothesis section, plus
     * referral or urgency words anywhere in it and the dentist's red flags.
     */
    fun evaluateRecord(recordText: String, redFlags: Set<RedFlag> = emptySet()): GuardrailResult {
        val operational = RECORD_CLASS.find(recordText.trim())?.groupValues?.get(1)?.let { RECORD_CLASSES[it] }
        val hypothesis = RECORD_HYPOTHESIS.find(recordText)?.groupValues?.get(1)?.trim().orEmpty()
        val classes = listOf(operational, hypothesis.takeIf { it.isNotEmpty() }?.let(::hypothesisClass))
        val text = recordText.lowercase()
        val advised = REFERRAL_TEXT.containsMatchIn(text)
        val urgency = URGENCY_TEXT.containsMatchIn(text)
        val named = classes.filterNotNull().toSet()
        return GuardrailResult(
            refer = LesionClass.OPMD in named || LesionClass.CANCER in named || advised || redFlags.isNotEmpty(),
            urgent = LesionClass.CANCER in named || urgency || redFlags.isNotEmpty(),
            differentialClasses = classes,
            modelAdvisedReferral = advised,
            modelUrgencyLanguage = urgency,
            redFlags = redFlags,
        )
    }

    private val RECORD_CLASS = Regex("""(healthy|benign_lesion|opmd|cancer)\s*$""")
    private val RECORD_CLASSES = mapOf(
        "healthy" to LesionClass.HEALTHY,
        "benign_lesion" to LesionClass.BENIGN,
        "opmd" to LesionClass.OPMD,
        "cancer" to LesionClass.CANCER,
    )
    private val RECORD_HYPOTHESIS = Regex("""##\s*Hipótese diagnóstica\s*\n([\s\S]*?)(?=\n##|$)""")

    /** Class of one hypothesis: the most severe class it names; null when it names none (lexicon.py hyp_class). */
    fun hypothesisClass(name: String): LesionClass? {
        val text = name.lowercase()
        if (NOT_A_DX.containsMatchIn(text) && !LESION_WORD.containsMatchIn(text)) return null
        var found = classesIn(text)
        if (LesionClass.HEALTHY in found && found.size > 1 && PHYSIOLOGIC.containsMatchIn(text)) found = setOf(LesionClass.HEALTHY)
        return found.maxByOrNull { it.ordinal }
    }

    private fun classesIn(text: String): Set<LesionClass> = buildSet {
        if (CANCER.containsMatchIn(BENIGN_NEO.replace(POT_MAL.replace(text, " "), " "))) add(LesionClass.CANCER)
        if (OPMD.containsMatchIn(text)) add(LesionClass.OPMD)
        if (BENIGN.containsMatchIn(text)) add(LesionClass.BENIGN)
        if (HEALTHY.containsMatchIn(text)) add(LesionClass.HEALTHY)
    }

    /**
     * Red flags in one answer of the dentist (its question gives the context): terms in the answer
     * that are not negated, or the question's terms when the answer is a plain yes. The screen shows
     * them as ticked boxes the dentist can untick.
     */
    fun redFlagsIn(question: String, answer: String): Set<RedFlag> {
        val q = question.lowercase()
        val a = answer.lowercase().trim()
        if (a.isEmpty()) return emptySet()
        val flags = mutableSetOf<RedFlag>()
        val affirmative = AFFIRMATIVE.containsMatchIn(a) && !NEGATIVE_START.containsMatchIn(a)
        for ((flag, pattern) in FLAG_TERMS) {
            if (pattern.findAll(a).any { !negated(a, it.range.first) }) flags += flag
            else if (affirmative && pattern.containsMatchIn(q)) flags += flag
        }
        val ulcer = ULCER.containsMatchIn(q) || ULCER.containsMatchIn(a)
        if (ulcer && (NOT_HEALING.containsMatchIn(a) || (affirmative && NOT_HEALING.containsMatchIn(q)) || longerThanTwoWeeks(a))) {
            flags += RedFlag.ULCER_OVER_TWO_WEEKS
        }
        return flags
    }

    private fun negated(text: String, at: Int): Boolean {
        val before = text.substring(0, at).split(Regex("""\s+""")).filter { it.isNotEmpty() }.takeLast(3)
        return before.any { it.trim(',', '.', ';', ':') in NEGATIONS }
    }

    private fun longerThanTwoWeeks(text: String): Boolean {
        if (MORE_THAN_TWO_WEEKS.containsMatchIn(text) || MONTHS_OR_YEARS.containsMatchIn(text)) return true
        DAYS.findAll(text).forEach { if (it.groupValues[1].toInt() > 14) return true }
        WEEKS.findAll(text).forEach { if (it.groupValues[1].toInt() > 2) return true }
        return WORD_WEEKS.containsMatchIn(text)
    }

    private val NEGATIONS = setOf("não", "nao", "sem", "nega", "negou", "nenhum", "nenhuma", "ausente", "ausência", "nunca")
    private val AFFIRMATIVE = Regex("""^(sim|positivo|isso|exato|correto|tem|há|apresenta|confirmo|confirmado)\b""")
    private val NEGATIVE_START = Regex("""^(não|nao|nega|negativo|sem|nenhum|nenhuma|nunca)\b""")
    private val FLAG_TERMS = listOf(
        RedFlag.INDURATION to Regex("""endurec|indura|consistência (firme|endurecida|pétrea)|firme à palpação|dura à palpação|lesão dura"""),
        RedFlag.BLEEDING to Regex("""sangra|sangramento|sangrante|hemorrag"""),
        RedFlag.FIXED_NODE to Regex(
            """(linfonodo|gânglio|ganglio|íngua|linfadenopatia)[^.;]{0,40}(fix|aderid|endurecid|pétre)|(fixo|aderido)[^.;]{0,20}(linfonodo|gânglio)""",
        ),
    )
    private val ULCER = Regex("""úlcera|ulcera|ferida""")
    private val NOT_HEALING = Regex("""não cicatriz|nao cicatriz|não cura|não fecha|não sara|persist""")
    private val MORE_THAN_TWO_WEEKS = Regex("""mais de (duas|2) semanas|mais de (quinze|15) dias""")
    private val MONTHS_OR_YEARS = Regex("""\b(\d+|um|uma|dois|duas|três|vários|alguns)\s+(mês|meses|mes|ano|anos)\b""")
    private val DAYS = Regex("""\b(\d{1,3})\s*dias\b""")
    private val WEEKS = Regex("""\b(\d{1,2})\s*semanas?\b""")
    private val WORD_WEEKS = Regex("""\b(três|quatro|cinco|seis|sete|oito|várias)\s+semanas\b""")

    // lexicon.py: OPMD_TERMS, CANCER_TERMS, BENIGN_TERMS, HEALTHY_TERMS, NOT_A_DX, BENIGN_NEO, POT_MAL.
    private const val A = "[aáàâã]"
    private const val E = "[eéê]"
    private const val I = "[ií]"
    private const val O = "[oóôõ]"
    private const val U = "[uú]"
    private val OPMD = Regex(
        listOf(
            "potencialmente malign", "potentially malignant", """\bdopm\b""", """\bopmd\b""", """\bdpm\b""", "desordem oral potencial",
            "leuc[o]?plasi", "leuk[o]?plaki", "leukplaki", "eritroplasi", "erythroplaki", "eritroleuc", "erythroleuk",
            "l${I}quen plano", "lichen planus", """\blpo\b""", """\bolp\b""", "liquen[oó]ide", "lichenoid", """\bolr\b""",
            "fibrose submucosa", "submucous fibrosis", """\bfso\b""", """\bosf\b""",
            "queilite act${I}nica", "actinic cheilitis", "ceratose act${I}nica", "queratose act${I}nica",
            "displasia", "dysplasia", "hiperplasia verruc", "verrucous hyperplasia",
            "l${U}pus eritematoso disc", "discoid lupus", """\bdle\b""", """\blvp\b""", """\bpvl\b""",
        ).joinToString("|"),
    )
    private val CANCER = Regex(
        listOf(
            "carcinoma", "c${A}ncer", "cancer", "malign", "neopl${A}si[ac] malign", "neopl${A}sic",
            "neoplas", "melanoma", "linfoma", "lymphoma", "sarcoma", """\bcec\b""", """\bscc\b""", "tumor maligno", "les${A}o_neopl",
        ).joinToString("|"),
    )
    private val BENIGN = Regex(
        listOf(
            "benign", "les${A}o_benign", "candid", "eritema multiforme", "erythema multiforme", "vesicob", "vesiculob",
            """\bdvb\b""", """\bvbd\b""", "p${E}nfig", "pemphig", "penfig", "${U}lcera", "ulcer", "ulcera[cç]", "aft[ao]s",
            """\bafta""", "aphth", """\brau\b""", """\buar\b""", "fibroma", "fibromat", "hiperplasia", "hyperplasia", "granuloma",
            "mucocele", "r${A}nula", "cisto", "cyst", "lipoma", "papiloma", "papilloma", "verruga", "condiloma",
            "l${I}ngua geogr", "geogr[aá]phic tongue", "geogrphic", "eritema migrat", "glossite", "glossitis",
            "l${I}ngua pilosa", "hairy tongue", "saburr", "coated tongue", "l${I}ngua fissurada", "fissured tongue",
            "ceratose", "queratose", "keratosis", "hiperceratose", "hyperkeratosis", "melanose", "melanosis", "m${A}cula melan",
            "melanotic", "pigmenta", "pigmentation", "despigment", "depigment", "nevo", "nevus", "hemangioma", "vascular",
            "varic", "herp", "viral", "infec[cç]", "infection", "f${U}ngic", "fungal", "micose", "mycosis", "paracoccid",
            "histoplasm", "tubercul", "estomatite", "stomatitis", "mucosite", "mucositis", "queilite angular", "angular cheilitis",
            "alerg", "allerg", "dermatite de contato", "contact", "tatuagem", "tattoo", "leucoedema", "leukoedema", "eros[aã]o",
            "erosion", "abscess", "papilite", "papillitis", "reativ", "reacion", "reactive", "traum", "eritema", "erythema",
            "mrg", """\bfep\b""", "p${O}lipo", "polyp", "exostose", "torus", "toro ", "adenoma", "les${A}o branca",
            "mordisc", "morsicatio", "atrof", "deficiencia|defici${E}ncia", "grânulos de fordyce|fordyce",
            "palato d[eo] fumante", "smoker'?s palate", "estomatite nicot${I}nica", "les${A}o friccional", "ep${U}lide",
            "osteoma", "despapil", "depapil", "mordedura", "hematoma", "v${A}ric", "variz", "pet${E}quia", "neuroma",
            "gengivite", "dermatite", "inflama", "neoplasia benigna", "benign neoplasm",
        ).joinToString("|"),
    )
    private val HEALTHY = Regex(
        listOf(
            """(mucosa|l${I}ngua|gengiva|palato|l${A}bio|labial|jugal|dorso|ventre|assoalho|rebordo|bucal)\b[^.;]*\bnorma(l|is)\b""",
            """\bnormal (oral )?mucosa""", "nenhuma les${A}o", "sem les${O}?(es)?", "sem les${A}o", "no (visible )?lesion",
            "sa${U}d${A}vel", """\bhealthy\b""", "dentro d[ao]s? (padr|limite)", "normalidade", "sem altera[cç]",
            "variante anat|varia[cç][aã]o anat|variation of normal", """^\s*normal\s*$""",
        ).joinToString("|"),
    )
    private val NOT_A_DX = Regex(
        """^\s*(sem |nenhum |n[aã]o |hist[oó]ric|o examinador|paciente|localiza|textura|superf|bordas?|tamanho|""" +
            """aparência|a superf|a textura|grande|pequen|exof|cor )""",
    )
    private val LESION_WORD = Regex("""les[aã]o|lesion""")
    private val BENIGN_NEO = Regex("""neoplasia benigna|benign neoplasm|tumor benigno""")
    private val POT_MAL = Regex("""potencialmente malign\w*|potentially malignant""")
    private val PHYSIOLOGIC = Regex("""fisiol|physiol|com saburra fisiol""")
}
