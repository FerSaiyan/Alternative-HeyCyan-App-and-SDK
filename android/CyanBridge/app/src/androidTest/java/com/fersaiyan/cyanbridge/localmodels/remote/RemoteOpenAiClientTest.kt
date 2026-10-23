package com.fersaiyan.cyanbridge.localmodels.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
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
        val image = temporaryJpegAttachment(width = 1600, height = 800)
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
                "url",
                parts.getJSONObject(1).getJSONObject("image_url").keys().asSequence().single(),
            )
            assertEquals(
                "image/jpeg",
                parts.getJSONObject(1).getJSONObject("image_url").getString("url").substringAfter("data:").substringBefore(';'),
            )
            val imageData = parts.getJSONObject(1).getJSONObject("image_url")
                .getString("url")
                .substringAfter(',')
            val resizedBitmap = Base64.getDecoder().decode(imageData).let { bytes ->
                checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            }
            assertTrue(maxOf(resizedBitmap.width, resizedBitmap.height) <= 1024)
            resizedBitmap.recycle()
        } finally {
            image.delete()
        }
    }

    @Test
    fun payloadLoggingPreservesImageStructureAndRedactsBase64() {
        val image = temporaryJpegAttachment(width = 64, height = 64)
        try {
            val payload = RemoteOpenAiClient.buildChatCompletionPayload(
                model = "vision-model",
                messages = listOf(mapOf("role" to "user", "content" to "Describe this")),
                maxTokens = 32,
                temperature = 0.2,
                imagePaths = listOf(image.absolutePath),
            )

            val loggedPayload = RemoteOpenAiClient.payloadForLogging(payload)
            assertTrue(loggedPayload.contains("\"type\":\"image_url\""))
            assertTrue(loggedPayload.contains("\"image_url\":{\"url\":\"data:image/jpeg;base64,<redacted>\"}"))
            assertTrue(loggedPayload.contains("\"text\":\"<redacted>\""))
            assertTrue(!loggedPayload.contains("Describe this"))
            val actualUrl = payload.userContentParts()
                .getJSONObject(1)
                .getJSONObject("image_url")
                .getString("url")
            assertTrue(actualUrl.startsWith("data:image/jpeg;base64,"))
            assertTrue(!loggedPayload.contains(actualUrl.substringAfter(',')))
        } finally {
            image.delete()
        }
    }

    @Test
    fun imageFallbackPayloadsTrySeparateFieldThenBase64Prompt() {
        val payload = JSONObject()
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray()
                                .put(JSONObject().put("type", "text").put("text", "Describe this"))
                                .put(
                                    JSONObject()
                                        .put("type", "image_url")
                                        .put(
                                            "image_url",
                                            JSONObject().put("url", "data:image/jpeg;base64,IMAGE_DATA"),
                                        ),
                                ),
                        ),
                ),
            )
        val fallbacks = RemoteOpenAiClient.imageFallbackPayloads(payload)

        assertEquals(2, fallbacks.size)
        val separateField = fallbacks[0]
        assertEquals("Describe this", separateField.getJSONArray("messages")
            .getJSONObject(0).getString("content"))
        assertEquals("IMAGE_DATA", separateField.getString("image"))
        assertTrue(!RemoteOpenAiClient.payloadForLogging(separateField).contains("IMAGE_DATA"))

        val plainText = fallbacks[1].getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(plainText.startsWith("Describe this"))
        assertTrue(plainText.endsWith("IMAGE_DATA"))
        assertTrue(!RemoteOpenAiClient.payloadForLogging(fallbacks[1]).contains("IMAGE_DATA"))
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

    private fun temporaryJpegAttachment(width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val jpegBytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
            output.toByteArray()
        }
        bitmap.recycle()
        return temporaryAttachment(".jpg", jpegBytes)
    }

    private fun org.json.JSONObject.userContentParts(): JSONArray {
        return getJSONArray("messages")
            .getJSONObject(0)
            .getJSONArray("content")
    }
}
