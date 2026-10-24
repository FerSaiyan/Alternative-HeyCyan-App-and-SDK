package com.fersaiyan.cyanbridge.shared.ai

import com.fersaiyan.cyanbridge.shared.ai.OpenAiCompatible.EncodedAttachment
import com.fersaiyan.cyanbridge.shared.ai.OpenAiCompatible.StreamLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenAiCompatibleTest {
    @Test
    fun urlsAddV1OnlyToABareHost() {
        assertEquals("http://h:8080/v1/chat/completions", OpenAiCompatible.chatCompletionsUrl("http://h:8080/"))
        assertEquals("http://h:8080/v1/chat/completions", OpenAiCompatible.chatCompletionsUrl("http://h:8080/v1"))
        assertEquals("http://h/api/chat/completions", OpenAiCompatible.chatCompletionsUrl("http://h/api"))
        assertEquals("http://h/v1/chat/completions", OpenAiCompatible.chatCompletionsUrl("http://h/v1/chat/completions"))
        assertEquals("http://h:8080/v1/models", OpenAiCompatible.modelsUrl("http://h:8080"))
        assertEquals("http://h/v1/models", OpenAiCompatible.modelsUrl("http://h/v1/chat/completions"))
        assertEquals("https://or.ai/api/v1/models", OpenAiCompatible.modelsUrl("https://or.ai/api/v1"))
        assertEquals("http://h/api/models", OpenAiCompatible.modelsUrl("http://h/api/"))
    }

    @Test
    fun textPayloadKeepsStringContentAndOnlySendsStreamWhenTrue() {
        val payload = OpenAiCompatible.buildChatCompletionPayload(
            model = " m ",
            messages = listOf(mapOf("role" to " System ", "content" to "s"), mapOf("content" to "Hello")),
            maxTokens = 32,
            temperature = 0.2,
        )
        assertEquals(
            """{"model":"m","messages":[{"role":"system","content":"s"},{"role":"user","content":"Hello"}],""" +
                """"max_tokens":32,"temperature":0.2}""",
            payload.toString(),
        )
        val streamed = OpenAiCompatible.buildChatCompletionPayload("m", emptyList(), 1, 0.0, stream = true)
        assertEquals("""{"model":"m","messages":[],"max_tokens":1,"temperature":0.0,"stream":true}""", streamed.toString())
    }

    @Test
    fun mediaGoesOnTheLastUserMessage() {
        val payload = OpenAiCompatible.buildChatCompletionPayload(
            model = "m",
            messages = listOf(
                mapOf("role" to "user", "content" to "first"),
                mapOf("role" to "user", "content" to ""),
                mapOf("role" to "assistant", "content" to "a"),
            ),
            maxTokens = 8,
            temperature = 0.7,
            images = listOf({ EncodedAttachment("AQID", "image/png") }),
            audio = { EncodedAttachment("BAU=", "wav") },
        )
        assertEquals(
            """{"model":"m","messages":[{"role":"user","content":"first"},{"role":"user","content":[""" +
                """{"type":"image_url","image_url":{"url":"data:image/png;base64,AQID"}},""" +
                """{"type":"input_audio","input_audio":{"data":"BAU=","format":"wav"}}]},""" +
                """{"role":"assistant","content":"a"}],"max_tokens":8,"temperature":0.7}""",
            payload.toString(),
        )
    }

    @Test
    fun invalidPayloadsAreRejected() {
        assertEquals(
            "Media attachments require at least one user message",
            assertFailsWith<IllegalArgumentException> {
                OpenAiCompatible.buildChatCompletionPayload(
                    "m", listOf(mapOf("role" to "system", "content" to "s")), 8, 0.7,
                    images = listOf({ EncodedAttachment("", "image/png") }),
                )
            }.message,
        )
        assertFailsWith<IllegalArgumentException> { OpenAiCompatible.buildChatCompletionPayload(" ", emptyList(), 8, 0.7) }
        assertFailsWith<IllegalArgumentException> { OpenAiCompatible.buildChatCompletionPayload("m", emptyList(), 0, 0.7) }
        assertTrue(assertFailsWith<IllegalArgumentException> { OpenAiCompatible.audioFormat("m4a") }.message!!.contains("WAV or MP3"))
        assertEquals("image/jpeg", OpenAiCompatible.imageMimeType("jpeg"))
        assertEquals(
            "Unsupported image format '.tiff'. Use JPEG, PNG, WebP, GIF, BMP, or HEIC.",
            assertFailsWith<IllegalArgumentException> { OpenAiCompatible.imageMimeType("tiff") }.message,
        )
    }

    @Test
    fun completionReplyIsTrimmedAndEmptyRepliesFail() {
        assertEquals("hi", OpenAiCompatible.parseChatCompletion("""{"choices":[{"message":{"content":" hi "}}]}"""))
        assertEquals("No choices in remote response", assertFailsWith<IllegalStateException> { OpenAiCompatible.parseChatCompletion("{}") }.message)
        assertEquals("Empty choices array", assertFailsWith<IllegalStateException> { OpenAiCompatible.parseChatCompletion("""{"choices":[]}""") }.message)
        assertEquals(
            "Empty content in remote response",
            assertFailsWith<IllegalStateException> { OpenAiCompatible.parseChatCompletion("""{"choices":[{"message":{"content":" "}}]}""") }.message,
        )
    }

    @Test
    fun streamLinesYieldContentDeltas() {
        assertEquals(StreamLine.Token("Hel"), OpenAiCompatible.parseStreamLine("""data: {"choices":[{"delta":{"content":"Hel"}}]}"""))
        assertEquals(StreamLine.Done, OpenAiCompatible.parseStreamLine("data: [DONE]"))
        assertEquals(StreamLine.Skip, OpenAiCompatible.parseStreamLine(": keep-alive"))
        assertEquals(StreamLine.Skip, OpenAiCompatible.parseStreamLine("data:"))
        assertEquals(StreamLine.Skip, OpenAiCompatible.parseStreamLine("""data: {"choices":[{"delta":{"role":"assistant"}}]}"""))
        assertEquals(StreamLine.Skip, OpenAiCompatible.parseStreamLine("""data: {"choices":[{"delta":{"content":" "}}]}"""))
        assertEquals(StreamLine.Skip, OpenAiCompatible.parseStreamLine("data: not json"))
    }

    @Test
    fun healthSummaryListsUpToFiveModels() {
        assertEquals("HTTP 401: nope", OpenAiCompatible.healthSummary(401, "nope"))
        assertEquals("OK (server reachable)", OpenAiCompatible.healthSummary(200, "<html>"))
        assertEquals(
            "OK (6 models: a, b, c, d, e)",
            OpenAiCompatible.healthSummary(200, """{"data":[{"id":"a"},{"id":"b"},{"id":"c"},{"id":"d"},{"id":"e"},{"id":"f"}]}"""),
        )
    }
}
