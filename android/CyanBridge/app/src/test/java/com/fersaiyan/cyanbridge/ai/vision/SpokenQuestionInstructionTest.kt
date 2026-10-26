package com.fersaiyan.cyanbridge.ai.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenQuestionInstructionTest {
    @Test fun `describes the recording, follows the spoken language and forbids echoing it`() {
        val text = ImageQuestionDefaults.spokenQuestionInstruction("pt-BR")
        assertTrue(text.startsWith("You are the voice assistant in the user's smart glasses."))
        assertTrue("in the language they spoke (if unsure, in Portuguese)" in text)
        assertTrue("Never repeat, quote or transcribe their words." in text)
        assertFalse("attached audio" in text)
    }

    @Test fun `the decide-once turn may write the question down`() {
        assertFalse("Never repeat" in ImageQuestionDefaults.spokenQuestionInstruction("en", allowQuestionEcho = true))
    }
}
