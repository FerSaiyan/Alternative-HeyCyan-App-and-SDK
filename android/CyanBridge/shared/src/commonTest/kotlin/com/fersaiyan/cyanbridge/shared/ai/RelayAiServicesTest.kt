package com.fersaiyan.cyanbridge.shared.ai

import kotlin.test.Test
import kotlin.test.assertEquals

class RelayAiServicesTest {
    @Test
    fun replyTextKeepsEscapedQuotesAndNewlines() {
        assertEquals("Ele disse \"oi\"\nTchau", relayText("""{"reply":"Ele disse \"oi\"\nTchau"}""", "reply"))
    }

    @Test
    fun firstMatchingStringKeyWins() {
        assertEquals("texto", relayText("""{"message":{"role":"assistant"},"response":"texto"}""", "reply", "message", "response"))
    }

    @Test
    fun bodyIsReturnedWhenNoKeyMatches() {
        assertEquals("plain answer", relayText("plain answer", "reply"))
        assertEquals("""{"other":1}""", relayText("""{"other":1}""", "reply"))
    }
}
