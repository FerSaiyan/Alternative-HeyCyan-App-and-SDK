package com.fersaiyan.cyanbridge.refactorparity

import com.fersaiyan.cyanbridge.shared.ai.OpenAiCompatible
import com.fersaiyan.cyanbridge.shared.ai.OpenAiCompatible.EncodedAttachment
import com.fersaiyan.cyanbridge.shared.ai.OpenAiCompatible.StreamLine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Compares the shared OpenAI-compatible protocol code with the org.json code it replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteOpenAiLegacyParityTest {
    private val image = EncodedAttachment("AQID/+==", "image/jpeg")
    private val audio = EncodedAttachment("BAU=", "mp3")

    @Test
    fun payloadsMatchLegacy() {
        val conversations = listOf(
            listOf(mapOf("role" to "System", "content" to "be \"short\""), mapOf("role" to "User", "content" to "Olá / hi")),
            listOf(mapOf("role" to "user", "content" to "a"), mapOf("role" to "assistant", "content" to "b"), mapOf("content" to "")),
            listOf(mapOf("role" to " ", "content" to "x\ny")),
        )
        conversations.forEach { messages ->
            listOf(
                Triple(emptyList<EncodedAttachment>(), null as EncodedAttachment?, false),
                Triple(listOf(image), null, true),
                Triple(listOf(image, image), audio, false),
                Triple(emptyList(), audio, true),
            ).forEach { (images, sound, stream) ->
                val legacy = legacyPayload("m1", messages, 512, 0.3, stream, images, sound)
                val shared = OpenAiCompatible.buildChatCompletionPayload(
                    "m1", messages, 512, 0.3, stream, images.map { { it } }, sound?.let { { it } },
                )
                assertEquals(legacy.toString(), JSONObject(shared.toString()).toString())
            }
        }
    }

    @Test
    fun repliesMatchLegacy() {
        listOf(
            """{"choices":[{"message":{"content":" hi "}}]}""",
            """{"choices":[{"message":{"content":[{"type":"text","text":"x"}]}}]}""",
            """{"choices":[{"message":{"content":42}}]}""",
        ).forEach { body ->
            val legacy = JSONObject(body).getJSONArray("choices").getJSONObject(0).optJSONObject("message")
                ?.optString("content")?.trim()?.takeIf { it.isNotBlank() }
            assertEquals(body, legacy, OpenAiCompatible.parseChatCompletion(body))
        }
        listOf(
            """data: {"choices":[{"delta":{"content":"Hel"}}]}""",
            """data:{"choices":[{"delta":{"content":null}}]}""",
            """data: {"choices":[{"delta":{}}]}""",
            """data: {"choices":[]}""",
            """data: {"choices":[{"delta":{"content":" lo"}}]}""",
            "data: oops",
            "event: x",
        ).forEach { line ->
            val legacy = legacyStreamChunk(line)
            val shared = (OpenAiCompatible.parseStreamLine(line) as? StreamLine.Token)?.text.orEmpty()
            assertEquals(line, legacy, shared)
        }
        listOf(
            """{"data":[{"id":"a"},{"name":"no id"},{"id":"c"}]}""",
            """{"data":[]}""",
            "{}",
        ).forEach { body -> assertEquals(body, legacyHealth(body), OpenAiCompatible.healthSummary(200, body)) }
    }

    // ---- Legacy code, copied from RemoteOpenAiClient before the move (attachments pre-encoded). ----

    private fun legacyPayload(
        model: String,
        messages: List<Map<String, String>>,
        maxTokens: Int,
        temperature: Double,
        stream: Boolean,
        images: List<EncodedAttachment>,
        audio: EncodedAttachment?,
    ): JSONObject {
        val hasAudio = audio != null
        val hasMedia = images.isNotEmpty() || hasAudio
        val userMessageIndex = messages.indexOfLast {
            it["role"]?.trim()?.lowercase(Locale.US).let { role -> role.isNullOrBlank() || role == "user" }
        }
        val messagesArray = JSONArray()
        messages.forEachIndexed { index, message ->
            val role = message["role"]?.trim()?.lowercase(Locale.US).orEmpty().ifBlank { "user" }
            val text = message["content"].orEmpty()
            val jsonMessage = JSONObject().put("role", role)
            if (index != userMessageIndex || !hasMedia) {
                jsonMessage.put("content", text)
            } else {
                val contentParts = JSONArray()
                if (text.isNotBlank()) contentParts.put(JSONObject().put("type", "text").put("text", text))
                images.forEach { image ->
                    contentParts.put(
                        JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", "data:${image.mimeType};base64,${image.base64}")),
                    )
                }
                if (audio != null) {
                    contentParts.put(
                        JSONObject()
                            .put("type", "input_audio")
                            .put("input_audio", JSONObject().put("data", audio.base64).put("format", audio.mimeType)),
                    )
                }
                jsonMessage.put("content", contentParts)
            }
            messagesArray.put(jsonMessage)
        }
        return JSONObject()
            .put("model", model.trim())
            .put("messages", messagesArray)
            .put("max_tokens", maxTokens)
            .put("temperature", temperature)
            .apply { if (stream) put("stream", true) }
    }

    private fun legacyStreamChunk(l: String): String {
        if (!l.startsWith("data:")) return ""
        val data = l.removePrefix("data:").trim()
        if (data == "[DONE]" || data.isBlank()) return ""
        val chunk = runCatching {
            val obj = JSONObject(data)
            val choices = obj.optJSONArray("choices") ?: return@runCatching ""
            val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: return@runCatching ""
            delta.optString("content", "")
        }.getOrDefault("")
        return if (chunk.isNotBlank()) chunk else ""
    }

    private fun legacyHealth(body: String): String {
        val obj = runCatching { JSONObject(body) }.getOrNull()
        val models = obj?.optJSONArray("data")
        val count = models?.length() ?: 0
        return if (count > 0) {
            val names = (0 until count).mapNotNull { i -> models?.optJSONObject(i)?.optString("id") }.take(5).joinToString(", ")
            "OK ($count models: $names)"
        } else {
            "OK (server reachable)"
        }
    }
}
