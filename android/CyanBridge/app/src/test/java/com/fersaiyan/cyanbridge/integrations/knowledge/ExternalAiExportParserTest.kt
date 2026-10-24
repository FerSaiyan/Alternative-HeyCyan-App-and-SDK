package com.fersaiyan.cyanbridge.integrations.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExternalAiExportParserTest {
    @Test
    fun parsesChatGptConversationMappingAndSeparatesUserText() {
        val json = """
            [
              {
                "id": "c1",
                "title": "Physics notes",
                "mapping": {
                  "a": {"message": {"author": {"role": "user"}, "create_time": 10, "content": {"parts": ["I prefer concise equations."]}}},
                  "b": {"message": {"author": {"role": "assistant"}, "create_time": 11, "content": {"parts": ["You always love tensors."]}}}
                }
              }
            ]
        """.trimIndent().toByteArray()

        val parsed = ExternalAiExportParser.parseJson(json, "conversations.json")
        assertEquals(KnowledgeSource.CHATGPT, parsed.source)
        assertEquals(1, parsed.documents.size)
        val doc = parsed.documents.single()
        assertTrue(doc.text.contains("## User"))
        assertTrue(doc.text.contains("## Assistant"))
        assertTrue(doc.userAuthoredText.contains("I prefer concise equations."))
        assertFalse(doc.userAuthoredText.contains("You always love tensors."))
    }

    @Test
    fun parsesClaudeChatMessages() {
        val json = """
            [
              {
                "uuid": "claude-1",
                "name": "Planning",
                "chat_messages": [
                  {"sender": "human", "text": "My project is CyanBridge."},
                  {"sender": "assistant", "text": "I can help with it."}
                ]
              }
            ]
        """.trimIndent().toByteArray()

        val parsed = ExternalAiExportParser.parseJson(json, "claude-export.json")
        assertEquals(KnowledgeSource.CLAUDE, parsed.source)
        val doc = parsed.documents.single()
        assertEquals("Planning", doc.title)
        assertEquals("My project is CyanBridge.", doc.userAuthoredText)
    }

    @Test
    fun chunksLongImportedTextWithOverlap() {
        val text = (1..900).joinToString(" ") { "token$it" }
        val chunks = ImportedKnowledgeIndex.chunk(text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 2400 })
    }
}
