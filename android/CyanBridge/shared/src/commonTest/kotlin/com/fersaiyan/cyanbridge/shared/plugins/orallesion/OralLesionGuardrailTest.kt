package com.fersaiyan.cyanbridge.shared.plugins.orallesion

import kotlin.test.Test
import kotlin.test.assertEquals

/** The referral guardrail of 2026-11-05 (refer = OPMD/cancer anywhere or referral advice; urgent = cancer, urgency words or red flags). */
class OralLesionGuardrailTest {
    private data class Row(val name: String, val text: String, val flags: Set<RedFlag>, val refer: Boolean, val urgent: Boolean, val added: Boolean)

    @Test
    fun guardrailTable() {
        val rows = listOf(
            Row("healthy, routine", answer("**Mucosa oral normal** — a favor: papilas uniformes.", "Exame intraoral de rotina."), emptySet(), false, false, false),
            Row("OPMD second, no advice", answer("**Ceratose friccional** — a favor: borda dentária.\n2. **Leucoplasia** — contra: placa fina.", "Reavaliar em 2 semanas."), emptySet(), true, false, true),
            Row("cancer named, biopsy advised", answer("**Leucoplasia**\n2. **Carcinoma espinocelular** — a favor: borda endurecida.", "Não aguardar as respostas: biópsia incisional."), emptySet(), true, true, false),
            Row("benign, biopsy advised", answer("**Fibroma traumático** — a favor: nódulo séssil.", "Biópsia excisional."), emptySet(), true, false, false),
            Row("benign, dentist reports induration", answer("**Úlcera traumática**", "Remover o fator traumático."), setOf(RedFlag.INDURATION), true, true, true),
            Row("urgency words", answer("**Úlcera traumática**", "Reavaliação urgente em 7 dias."), emptySet(), true, true, false),
            Row("potentially malignant is not cancer", answer("**Desordem oral potencialmente maligna**", "Acompanhar."), emptySet(), true, false, true),
            Row("history line is not a diagnosis", answer("Paciente sem histórico de câncer\n2. **Língua geográfica**", "Orientar o paciente."), emptySet(), false, false, false),
        )
        rows.forEach { row ->
            val result = OralLesionGuardrail.evaluate(row.text, row.flags)
            assertEquals(Triple(row.refer, row.urgent, row.added), Triple(result.refer, result.urgent, result.addedReferral), row.name)
        }
        val record = OralLesionGuardrail.evaluateRecord("## Descrição da lesão\nPlaca branca.\n## Hipótese diagnóstica\nLeucoplasia\n## Classificação operacional\nopmd")
        assertEquals(true to false, record.refer to record.urgent, "record")
    }

    @Test
    fun redFlagsFromSpokenAnswers() {
        val rows = listOf(
            Triple("A lesão sangra ao toque?", "não", emptySet()),
            Triple("A lesão sangra ao toque?", "sim, às vezes", setOf(RedFlag.BLEEDING)),
            Triple("Há quanto tempo existe a úlcera?", "há três semanas", setOf(RedFlag.ULCER_OVER_TWO_WEEKS)),
            Triple("Há quanto tempo a úlcera está presente?", "10 dias", emptySet()),
            Triple("Como está a palpação?", "lesão endurecida e linfonodo submandibular fixo", setOf(RedFlag.INDURATION, RedFlag.FIXED_NODE)),
            Triple("Há linfonodos palpáveis?", "sem linfonodos palpáveis, sem endurecimento", emptySet<RedFlag>()),
        )
        rows.forEach { (question, answer, flags) -> assertEquals(flags, OralLesionGuardrail.redFlagsIn(question, answer), answer) }
    }

    /** The parser finds the questions episode_harness_v1.parse_questions found in E4B v3p's turn-1 answer. */
    @Test
    fun parserReadsQuestionsLikeTheHarness() {
        val turn1 = "## Achados\nSuperfície dorsal da língua apresentando arquitetura anatômica normal, incluindo papilas filiformes e fungiformes uniformemente distribuídas, sulco mediano e leve saburra fisiológica. Nenhuma ulceração focal, massa, eritema ou placas brancas distintas são visíveis.\n\n## Hipóteses diagnósticas\n1. **Mucosa oral normal** — a favor: Arquitetura papilar uniforme, coloração rósea normal e ausência de lesões focais, ulcerações ou alterações de cor.\n\n## Perguntas ao dentista\n- O paciente relata uso de tabaco, noz de areca ou álcool? (motivo: Identifica fatores de risco basais para patologias da mucosa oral.)\n- O paciente está apresentando algum sintoma ou queixa específica? (motivo: Determina se há sintomas subclínicos ou queixas que não são visíveis na fotografia.)\n\n## Em aberto\nCondição clínica dos demais sítios da mucosa oral e presença de sintomas subjetivos.\n\n## Próximo passo\nRealizar um exame intraoral completo de rotina e obter a história médica e de hábitos."
        assertEquals(listOf("O paciente relata uso de tabaco, noz de areca ou álcool?", "O paciente está apresentando algum sintoma ou queixa específica?"), OralLesionAnswer.parse(turn1).questions)
        assertEquals(listOf("Mucosa oral normal"), OralLesionAnswer.parse(turn1).hypotheses)
    }

    private fun answer(differential: String, next: String) =
        "## Achados\nLesão na borda lateral da língua.\n\n## Hipóteses diagnósticas\n1. $differential\n\n## Próximo passo\n$next"
}
