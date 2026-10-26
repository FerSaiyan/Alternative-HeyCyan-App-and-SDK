package com.fersaiyan.cyanbridge.localmodels.engine

import org.junit.Assert.*
import org.junit.Test

class DecideOncePromptTest {
    @Test fun `route and heard question come from the decision turn`() {
        val camera = "Q: What color are these socks?\n⊲B⊳"
        assertEquals(DecideOnceRoute.CAMERA, DecideOncePrompt.parseRoute(camera))
        assertEquals("What color are these socks?", DecideOncePrompt.heardQuestion(camera))
        assertEquals(DecideOnceRoute.ANSWER, DecideOncePrompt.parseRoute("Q: Qual é a capital da Austrália?\n⊲A⊳ "))
    }

    @Test fun `the first label wins when the model keeps writing after it`() {
        assertEquals(DecideOnceRoute.ANSWER, DecideOncePrompt.parseRoute("Q: Capital of Australia?\n⊲A⊳ Canberra. ⊲B⊳"))
        assertEquals(DecideOnceRoute.CAMERA, DecideOncePrompt.parseRoute("Q: What color are these socks?\n⊲B⊳ They look yellow."))
    }

    @Test(expected = IllegalStateException::class)
    fun `a decision without a label is rejected rather than guessed`() {
        DecideOncePrompt.parseRoute("Q: What color are these socks?")
    }

    @Test fun `log shape hides the words`() {
        assertEquals("x: x x x x?\\n⊲x⊳", DecideOncePrompt.shape("Q: What color are these?\n⊲B⊳"))
    }

    @Test fun `constrained regex accepts exactly one question line and one label`() {
        val regex = Regex(DecideOncePrompt.RESPONSE_REGEX)
        assertTrue(regex.matches("Q: What color are these socks?\n⊲B⊳"))
        assertTrue(regex.matches("Q: 这是几路车？\n⊲A⊳"))
        assertFalse(regex.matches("Q: two\nlines\n⊲A⊳"))
        assertFalse(regex.matches("Q: What color?\n⊲B⊳ yellow"))
        assertFalse(regex.matches("⊲B⊳"))
    }

    @Test fun `label is stripped from the spoken answer`() {
        assertEquals("Canberra is the capital.", DecideOncePrompt.stripLabel("⊲A⊳ Canberra is the capital."))
        assertEquals("", DecideOncePrompt.stripLabel("⊲B⊳"))
    }

    @Test fun `a repeated label at the start of a stream is never spoken`() {
        val spoken = StringBuilder()
        val sink = DecideOncePrompt.labelStrippingSink { spoken.append(it) }
        listOf("⊲", "B", "⊳The ", "socks are ", "yellow.").forEach(sink)
        assertEquals("The socks are yellow.", spoken.toString())

        val plain = StringBuilder()
        val passthrough = DecideOncePrompt.labelStrippingSink { plain.append(it) }
        listOf("The ", "socks").forEach(passthrough)
        assertEquals("The socks", plain.toString())
    }

    @Test fun `echoed decision text is removed from the spoken answer`() {
        assertEquals("The capital of Australia is Canberra.",
            DecideOncePrompt.cleanAnswer("Q: What's the capital of Australia?\n⊲A⊳\nThe capital of Australia is Canberra."))
        assertEquals("The socks are yellow.", DecideOncePrompt.cleanAnswer("Q: What color are these socks?\nThe socks are yellow."))
        assertEquals("Quiet places help.", DecideOncePrompt.cleanAnswer("Quiet places help."))

        val spoken = StringBuilder()
        val sink = DecideOncePrompt.labelStrippingSink { spoken.append(it) }
        listOf("Q", ": What's the ", "capital?\n", "⊲A", "⊳\nThe capital ", "is Canberra.").forEach(sink)
        assertEquals("The capital is Canberra.", spoken.toString())
    }

    @Test fun `a malformed label falls back to answering`() {
        assertEquals(DecideOnceRoute.ANSWER to false, DecideOncePrompt.parseRouteOrAnswer("Q: Qual é a cor?\n⊲\uFFFD\uFFFD⊳"))
        assertEquals(DecideOnceRoute.CAMERA to true, DecideOncePrompt.parseRouteOrAnswer("Q: Qual é a cor?\n⊲B⊳"))
    }
}
