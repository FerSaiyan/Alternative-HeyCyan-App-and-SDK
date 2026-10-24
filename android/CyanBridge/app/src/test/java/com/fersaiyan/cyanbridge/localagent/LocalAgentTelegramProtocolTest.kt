package com.fersaiyan.cyanbridge.localagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalAgentTelegramProtocolTest {

    @Test
    fun `only explicit supported commands can create a remote task`() {
        assertNull(LocalAgentTelegramProtocol.parseCommand("Open Settings"))
        assertNull(LocalAgentTelegramProtocol.parseCommand("/task"))
        assertNull(LocalAgentTelegramProtocol.parseCommand("/delete_everything now"))

        val command = LocalAgentTelegramProtocol.parseCommand("/task Open Settings")
        assertTrue(command is LocalAgentTelegramProtocol.Command.Task)
        assertEquals("Open Settings", (command as LocalAgentTelegramProtocol.Command.Task).goal)
        assertNull(LocalAgentTelegramProtocol.parseCommand("/task@other_bot Open Settings"))
    }

    @Test
    fun `allowed chat comparison is exact after numeric normalization`() {
        assertTrue(LocalAgentTelegramProtocol.isAllowedChat("-1001234567890", "-1001234567890"))
        assertFalse(LocalAgentTelegramProtocol.isAllowedChat("123", "124"))
        assertFalse(LocalAgentTelegramProtocol.isAllowedChat("123", "not-a-chat"))
    }

    @Test
    fun `update parser retains non text updates so the poll cursor can advance`() {
        val updates = LocalAgentTelegramProtocol.parseUpdates(
            """
            {"ok":true,"result":[
              {"update_id":11,"message":{"chat":{"id":123},"text":"/status"}},
              {"update_id":12,"message":{"chat":{"id":123},"photo":[]}}
            ]}
            """.trimIndent(),
        )

        assertEquals(2, updates.size)
        assertEquals(11L, updates.first().updateId)
        assertEquals("123", updates.first().chatId)
        assertNull(updates.last().text)
    }

    @Test
    fun `bot token validation rejects unsafe path content`() {
        assertTrue(LocalAgentTelegramProtocol.isValidBotToken("123456:abcdefghijklmnopqrstuvwxyz_-123456789"))
        assertFalse(LocalAgentTelegramProtocol.isValidBotToken("123456:token/with/slash"))
    }
}
