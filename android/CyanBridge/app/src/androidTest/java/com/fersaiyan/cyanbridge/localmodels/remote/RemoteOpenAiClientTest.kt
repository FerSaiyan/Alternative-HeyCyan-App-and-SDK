package com.fersaiyan.cyanbridge.localmodels.remote

import java.io.File
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteOpenAiClientTest {
    @Test
    fun textOnlyPayloadKeepsStringContentAndStreamingFlag() {
        val payload = RemoteOpenAiClient.buildChatCompletionPayload(
            model = "test-model",
            messages = listOf(mapOf("role" to "user", "content" to "Hello")),
            maxTokens = 32,
            temperature = 0.2,
            stream = true,
        )

        val message = payload.getJSONArray("messages").getJSONObject(0)
        assertEquals("Hello", message.getString("content"))
        assertTrue(payload.getBoolean("stream"))
    }

    @Test
    fun imageAttachmentUsesOpenAiImageUrlDataPart() {
        val image = temporaryAttachment(".png", byteArrayOf(1, 2, 3))
        try {
            val payload = RemoteOpenAiClient.buildChatCompletionPayload(
                model = "vision-model",
                messages = listOf(mapOf("role" to "user", "content" to "Describe this")),
                maxTokens = 32,
                temperature = 0.2,
                imagePaths = listOf(image.absolutePath),
            )

            val parts = payload.userContentParts()
            assertEquals("text", parts.getJSONObject(0).getString("type"))
            assertEquals("image_url", parts.getJSONObject(1).getString("type"))
            assertEquals(
                "data:image/png;base64,${Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))}",
                parts.getJSONObject(1).getJSONObject("image_url").getString("url"),
            )
        } finally {
            image.delete()
        }
    }

    @Test
    fun audioAttachmentUsesInputAudioDataPart() {
        val audio = temporaryAttachment(".wav", byteArrayOf(4, 5, 6))
        try {
            val payload = RemoteOpenAiClient.buildChatCompletionPayload(
                model = "audio-model",
                messages = listOf(mapOf("role" to "user", "content" to "Transcribe this")),
                maxTokens = 32,
                temperature = 0.2,
                audioPath = audio.absolutePath,
            )

            val part = payload.userContentParts().getJSONObject(1)
            assertEquals("input_audio", part.getString("type"))
            assertEquals("wav", part.getJSONObject("input_audio").getString("format"))
            assertEquals(
                Base64.getEncoder().encodeToString(byteArrayOf(4, 5, 6)),
                part.getJSONObject("input_audio").getString("data"),
            )
        } finally {
            audio.delete()
        }
    }

    @Test
    fun invalidAttachmentsAreRejectedInsteadOfDropped() {
        val error = runCatching {
            RemoteOpenAiClient.buildChatCompletionPayload(
                model = "vision-model",
                messages = listOf(mapOf("role" to "user", "content" to "Look")),
                maxTokens = 32,
                temperature = 0.2,
                imagePaths = listOf("/does/not/exist.png"),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("Cannot read image attachment"))
    }

    @Test
    fun unsupportedRemoteAudioFormatIsRejected() {
        val audio = temporaryAttachment(".m4a", byteArrayOf(7, 8))
        try {
            val error = runCatching {
                RemoteOpenAiClient.buildChatCompletionPayload(
                    model = "audio-model",
                    messages = listOf(mapOf("role" to "user", "content" to "Listen")),
                    maxTokens = 32,
                    temperature = 0.2,
                    audioPath = audio.absolutePath,
                )
            }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("WAV or MP3"))
        } finally {
            audio.delete()
        }
    }

    private fun temporaryAttachment(suffix: String, bytes: ByteArray): File {
        return File.createTempFile("remote-openai-attachment", suffix).apply {
            writeBytes(bytes)
        }
    }

    private fun JsonObject.getJSONArray(name: String): JSONArray = JSONObject(toString()).getJSONArray(name)

    private fun JsonObject.getBoolean(name: String): Boolean = JSONObject(toString()).getBoolean(name)

    private fun JsonObject.userContentParts(): JSONArray {
        return getJSONArray("messages")
            .getJSONObject(0)
            .getJSONArray("content")
    }
}
