package com.fersaiyan.cyanbridge.localmodels.remote

import java.io.File
import java.util.Base64
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteOpenAiClientTest {
    @Test
    fun remote_backend_is_active_without_a_local_runtime_selection() {
        assertTrue(
            isRemoteOpenAiActive(
                enabled = true,
                baseUrl = "http://100.100.10.20:8080",
                model = "gemma-3n-e2b-it",
            ),
        )
        assertFalse(isRemoteOpenAiActive(false, "http://100.100.10.20:8080", "gemma-3n-e2b-it"))
        assertFalse(isRemoteOpenAiActive(true, "", "gemma-3n-e2b-it"))
        assertFalse(isRemoteOpenAiActive(true, "http://100.100.10.20:8080", ""))
    }

    @Test
    fun tailscale_credentials_allow_magic_dns_but_not_lookalike_domains() {
        assertTrue(RemoteOpenAiPrefs.isCredentialTransportAllowed("http://desktop.example-tailnet.ts.net:8080/v1"))
        assertFalse(RemoteOpenAiPrefs.isCredentialTransportAllowed("http://desktop.ts.net.evil.example:8080/v1"))
    }

    @Test
    fun query_and_fragment_are_rejected_in_base_urls() {
        listOf("http://100.100.10.20:8080?token=x", "http://100.100.10.20:8080/#settings").forEach { url ->
            try {
                RemoteOpenAiClient.buildChatCompletionsUrl(url)
                fail("Expected URL to be rejected: $url")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message?.contains("query string or fragment") == true)
            }
        }
    }

    @Test
    fun image_attachment_is_read_from_disk_into_an_image_url_data_part() {
        val image = File.createTempFile("remote-openai-attachment", ".png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val payload = RemoteOpenAiClient.buildChatCompletionPayload(
                model = "vision-model",
                messages = listOf(mapOf("role" to "user", "content" to "Describe this")),
                maxTokens = 32,
                temperature = 0.2,
                imagePaths = listOf(image.absolutePath),
            )

            val part = payload["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[1].jsonObject
            assertEquals("image_url", part["type"]!!.jsonPrimitive.content)
            assertEquals(
                "data:image/png;base64,${Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))}",
                part["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
            )
        } finally {
            image.delete()
        }
    }

    @Test
    fun unreadable_attachments_are_rejected_instead_of_dropped() {
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
}
