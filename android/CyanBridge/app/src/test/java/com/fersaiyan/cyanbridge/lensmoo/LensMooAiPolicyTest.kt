package com.fersaiyan.cyanbridge.lensmoo

import org.junit.Assert.*
import org.junit.Test

class LensMooAiPolicyTest {
    @Test fun glassesPromptRequestsRussianAndPreservesQuestion() {
        val question = mapOf("role" to "user", "content" to "Что передо мной?")
        val messages = lensMooMessages(listOf(question))
        assertEquals("system", messages.first()["role"])
        assertTrue(messages.first()["content"]!!.contains("русском"))
        assertEquals(question, messages.last())
    }
    @Test fun localGemmaWorksWithoutRemoteConfiguration() {
        assertTrue(lensMooAiReady(false, false, true))
    }
    @Test fun selectedCloudWorksWithoutInstalledModel() {
        assertTrue(lensMooAiReady(true, true, false))
    }
    @Test fun invalidCloudDoesNotSilentlyUseLocalModel() {
        assertFalse(lensMooAiReady(true, false, true))
    }
    @Test fun textOnlyOrMissingLocalModelIsRejected() {
        assertFalse(lensMooAiReady(false, true, false))
    }
}
