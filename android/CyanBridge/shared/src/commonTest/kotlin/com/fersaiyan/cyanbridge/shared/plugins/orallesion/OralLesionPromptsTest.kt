package com.fersaiyan.cyanbridge.shared.plugins.orallesion

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The app's prompts must reach the model byte for byte as in training and evaluation. Expected
 * values are copied from the oral-lesion project's episode-harness inputs
 * (results/episode_harness_v1/val_ptBR: turn1_inputs.jsonl, summary_inputs.jsonl and the E4B v3p
 * run's turn2_inputs.jsonl, record SMITA00260_R_DT.jpeg), with the leading "<image>\n" removed: the
 * app sends the photo as its own content part in front of the text, as the trainer's to_messages does.
 */
class OralLesionPromptsTest {
    @Test
    fun promptsMatchTheHarnessRowsByteForByte() {
        val male29 = CaseDemographics(age = 29, sex = PatientSex.MALE)
        val rows = listOf(
            OralLesionPrompts.SYSTEM to "Você é um assistente de estomatologia que apoia cirurgiões-dentistas. Use apenas as informações fornecidas, separe o que foi descrito do que ainda precisa ser verificado, faça apenas perguntas que possam mudar a avaliação e nunca invente achados, exames ou resultados de biópsia.",
            OralLesionPrompts.assessment(male29) to "Dentista: Paciente do sexo masculino, 29 anos. Avalie esta fotografia intraoral, por favor.",
            OralLesionPrompts.assessment(CaseDemographics(age = null, sex = PatientSex.UNKNOWN)) to "Dentista: Paciente do sexo não informado. Avalie esta fotografia intraoral, por favor.",
            OralLesionPrompts.answers(
                listOf(
                    DentistAnswer("O paciente relata uso de tabaco, noz de areca ou álcool?", "Hábito de mascar (Tabaco de mascar): status Usuário atual, 2 vezes ao dia, 9 anos, iniciado aos 20 anos de idade"),
                    DentistAnswer("O paciente está apresentando algum sintoma ou queixa específica?", null),
                ),
            ) to "Respostas do dentista:\n- O paciente relata uso de tabaco, noz de areca ou álcool? → Hábito de mascar (Tabaco de mascar): status Usuário atual, 2 vezes ao dia, 9 anos, iniciado aos 20 anos de idade\n- O paciente está apresentando algum sintoma ou queixa específica? → não documentado no prontuário",
            OralLesionPrompts.summary(
                male29,
                listOf(
                    "Hábito de mascar (Tabaco de mascar): status Usuário atual, 2 vezes ao dia, 9 anos, iniciado aos 20 anos de idade",
                    "O examinador registrou todos os sítios da mucosa oral como normais (boca toda)",
                ),
            ) to "Dentista: Paciente do sexo masculino, 29 anos. Informações registradas:\n- Hábito de mascar (Tabaco de mascar): status Usuário atual, 2 vezes ao dia, 9 anos, iniciado aos 20 anos de idade\n- O examinador registrou todos os sítios da mucosa oral como normais (boca toda)\n\nEscreva o registro clínico com as seções: ## Descrição da lesão, ## Hipótese diagnóstica e ## Classificação operacional.",
        )
        rows.forEach { (actual, expected) -> assertEquals(expected, actual) }
    }
}
