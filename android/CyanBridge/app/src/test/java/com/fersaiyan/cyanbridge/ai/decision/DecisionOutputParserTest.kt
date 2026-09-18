package com.fersaiyan.cyanbridge.ai.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class DecisionOutputParserTest {
    private val candidates = AssistantDecisionOptions.candidates()

    private fun check(raw: String, expected: String) {
        val parsed = try {
            DecisionOutputParser.parse(raw, candidates)
        } catch (t: Throwable) {
            println("JEV_CI FAIL raw='$raw' error=${t.message}")
            throw t
        }
        println("JEV_CI parse raw='${raw.take(120)}' -> ${parsed.label}")
        assertEquals(expected, parsed.label)
    }

    @Test fun `plain letters`() {
        check("C", "C")
        check("b", "B")
        check("  D  ", "D")
        check("A.", "A")
        check("(B)", "B")
    }

    @Test fun `answer prefixes`() {
        check("Answer: C", "C")
        check("answer c", "C")
        check("Option B", "B")
        check("``` C ```", "C")
        check("```json\n{\"choice\": \"A\"}\n```", "A")
    }

    @Test fun `reasoning think blocks stripped, last letter wins`() {
        check("<think>Option A is answering, B is image. User wants Spotify so likely C.</think> C", "C")
        check("<thinking>hmm A or C... I think C fits</thinking>\nC", "C")
        check("<think>unclosed reasoning... C", "C")
    }

    @Test fun `reasoning preamble in portuguese`() {
        check("O usuário pediu para abrir o Spotify, então a resposta é C", "C")
        check("Vamos pensar: A significa responder, C significa operar o celular. Resposta: C", "C")
    }

    @Test fun `multilingual preamble spanish french german`() {
        check("El usuario quiere abrir Spotify, por lo tanto C", "C")
        check("L'utilisateur veut ouvrir Spotify, donc C", "C")
        check("Der Benutzer möchte Spotify öffnen, also C", "C")
    }

    @Test fun `last letter wins over early mentions`() {
        // Model lists options then concludes; final answer must win.
        check("A means answer, B means image, C means operate. Final: D", "D")
    }

    @Test fun `invalid output throws with debug context`() {
        try {
            DecisionOutputParser.parse("I don't know what to do", candidates)
            fail("expected exception")
        } catch (e: IllegalArgumentException) {
            println("JEV_CI expected failure: ${e.message}")
        }
    }

    @Test fun `live candidates parse`() {
        val live = LiveControlOptions.candidates()
        assertEquals("B", DecisionOutputParser.parse("B", live).label)
        assertEquals("C", DecisionOutputParser.parse("<think>operate phone</think> C", live).label)
    }
}
