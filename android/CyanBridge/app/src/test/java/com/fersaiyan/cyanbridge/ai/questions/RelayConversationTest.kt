package com.fersaiyan.cyanbridge.ai.questions

import com.fersaiyan.cyanbridge.shared.ai.RelayTurn
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayConversationTest {
    @Test fun `the heard line is taken off the reply and becomes the history of follow-ups`() {
        assertEquals(
            "[answer] They are yellow." to "What color are these socks?",
            RelayConversation.split("[answer] They are yellow.\n[heard] What color are these socks?"),
        )
        assertEquals("[ignore]" to null, RelayConversation.split("[ignore]"))

        RelayConversation.start(photo = "q.jpg")
        RelayConversation.record("What color are these socks?", "[camera]")
        // The [camera] reply was answered with the recording again: that answer is what follow-ups see.
        RelayConversation.replaceLastAnswer("They are yellow.")
        assertEquals(emptyList<RelayTurn>(), RelayConversation.history(excludingCurrent = true))
        assertEquals(
            listOf(RelayTurn(true, "What color are these socks?"), RelayTurn(false, "They are yellow.")),
            RelayConversation.history(),
        )
        RelayConversation.record(null, "[answer] Bright yellow toe socks.")
        assertEquals(RelayTurn(true, "(a spoken question)"), RelayConversation.history()[2])
        assertEquals("q.jpg", RelayConversation.photo)
    }
}
