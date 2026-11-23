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

    @Test fun `without the Local Agent the route list has no phone route and answers phone requests`() {
        val phone = ImageQuestionDefaults.spokenDecisionRoutes(phoneTasks = true)
        val none = ImageQuestionDefaults.spokenDecisionRoutes(phoneTasks = false)
        assertTrue("[phone] then the task" in phone)
        assertFalse("[phone]" in none)
        for (tag in listOf("[camera]", "[search]", "[live]", "[ignore]", "[answer]")) assertTrue(tag, tag in none)
        assertTrue("say in one short sentence that you can't control their phone" in none)
        assertTrue("-> [answer] Sorry, I can't control your phone." in none)
    }
}
