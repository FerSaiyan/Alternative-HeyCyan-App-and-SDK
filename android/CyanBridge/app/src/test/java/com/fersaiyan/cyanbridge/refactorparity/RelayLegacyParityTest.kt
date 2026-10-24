package com.fersaiyan.cyanbridge.refactorparity

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.ai.router.CliRelayBackend
import com.fersaiyan.cyanbridge.ai.router.RelayErrorLocalizer
import com.fersaiyan.cyanbridge.shared.ai.RelayClient
import com.fersaiyan.cyanbridge.shared.ai.RelayMedia
import com.fersaiyan.cyanbridge.shared.ai.RelayServerCapabilities as SharedCapabilities
import com.fersaiyan.cyanbridge.shared.ai.RelaySettingsSnapshot
import com.fersaiyan.cyanbridge.shared.json.parseJsonObject
import com.fersaiyan.cyanbridge.shared.platform.HttpRequest
import com.fersaiyan.cyanbridge.shared.platform.HttpResponse
import com.fersaiyan.cyanbridge.shared.platform.HttpTransport
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compares the shared relay client with verbatim copies of the org.json code it replaced
 * (CliRelayClient and RelayErrorLocalizer before the move to shared).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelayLegacyParityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val systemPrompt = "Answer in one line. Use \"quotes\" / slashes\nand é."
    private val messages = listOf(
        mapOf("role" to "user", "content" to "Oi"),
        mapOf("role" to "assistant", "content" to "Olá! 😀"),
        mapOf("content" to "no role"),
    )

    private class Recorder : HttpTransport {
        val bodies = mutableListOf<String>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            bodies += request.body.orEmpty()
            return HttpResponse(200, emptyMap(), """{"reply":"ok","status":"up","backend":"gemini"}""")
        }
    }

    private fun sharedBodies(block: suspend RelayClient.() -> Unit): List<String> {
        val recorder = Recorder()
        val client = RelayClient(
            RelaySettingsSnapshot("https://relay.test", CliRelayBackend.GEMINI, systemPrompt),
            recorder,
            sleep = {},
        )
        runBlocking { client.block() }
        return recorder.bodies
    }

    /** org.json re-serializes both sides, so only escaping style could differ, and it is normalized. */
    private fun assertSameJson(legacy: JSONObject, shared: String) {
        assertEquals(legacy.toString(), JSONObject(shared).toString())
    }

    @Test
    fun requestBodiesMatchTheLegacyOrgJsonBodies() {
        val image = byteArrayOf(-1, -40, -1, 0, 1, 2, 3, 63, 62)
        val shared = sharedBodies {
            chat("chat-1", "Oi", messages, " model-a ").getOrThrow()
            voiceQuery("Que horas são?", modelOverride = "").getOrThrow()
            voiceQuery("p", backendOverride = CliRelayBackend.CODEX, modelOverride = "m").getOrThrow()
            imageQuery(RelayMedia("photo.jpg") { image }, " describe ", modelOverride = "vision").getOrThrow()
            imageQuery(RelayMedia("photo.jpg") { image }, null).getOrThrow()
            audioQuery(RelayMedia("q.wav") { image }, "  ", "m").getOrThrow()
            healthCheck().getOrThrow()
        }

        val legacyMessages = JSONArray()
        legacyMessages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        for (m in messages) {
            legacyMessages.put(JSONObject().put("role", m["role"]).put("content", m["content"]))
        }
        val legacy = listOf(
            JSONObject()
                .put("backend", "gemini")
                .put("chatId", "chat-1")
                .put("prompt", "Oi")
                .put("messages", legacyMessages)
                .put("system_prompt", systemPrompt)
                .put("model", "model-a"),
            JSONObject().put("backend", "gemini").put("prompt", "Que horas são?").put("system_prompt", systemPrompt),
            JSONObject().put("backend", "codex").put("prompt", "p").put("system_prompt", systemPrompt).put("model", "m"),
            JSONObject()
                .put("backend", "gemini")
                .put("filename", "photo.jpg")
                .put("imageBase64", Base64.encodeToString(image, Base64.NO_WRAP))
                .put("system_prompt", systemPrompt)
                .put("prompt", "describe")
                .put("model", "vision"),
            JSONObject()
                .put("backend", "gemini")
                .put("filename", "photo.jpg")
                .put("imageBase64", Base64.encodeToString(image, Base64.NO_WRAP))
                .put("system_prompt", systemPrompt),
            JSONObject()
                .put("filename", "q.wav")
                .put("audioBase64", Base64.encodeToString(image, Base64.NO_WRAP))
                .put("system_prompt", systemPrompt)
                .put("model", "m"),
            JSONObject().put("backend", "gemini"),
        )
        assertEquals(legacy.size, shared.size)
        legacy.zip(shared).forEach { (old, new) -> assertSameJson(old, new) }
    }

    @Test
    fun httpErrorTextMatchesLegacy() {
        val bodies = listOf(
            """{"error":"quota_exhausted","message":"Monthly quota exhausted for plan cheap"}""",
            """{"error":" boom "}""",
            """{"message":"only message"}""",
            """{"error":"","message":""}""",
            """{"error":{"code":7}}""",
            """{"error":null}""",
            """{"error":42,"message":true}""",
            "<html>down</html>",
            "",
        )
        bodies.forEach { body ->
            assertEquals(body, legacyHttpError(500, body), RelayClient.relayHttpErrorMessage(500, body))
        }
    }

    @Test
    fun voiceTelemetryMatchesLegacy() {
        val responses = listOf(
            """{"reply":"a b c"}""",
            """{"reply":"a b c","usage":{"prompt_tokens":12,"completion_tokens":"30"}}""",
            """{"reply":"a","input_tokens":5,"output_tokens":7,"prompt_eval_ms":250,"completion_eval_ms":"500"}""",
            """{"reply":"a","performance":{"prompt_tokens_per_sec":"80.5","gen_tps":12.25,"prefill_ms":10}}""",
            """{"reply":"a","metrics":{"prompt_ms":0,"generation_ms":-3,"decode_ms":1200.7}}""",
            """{"reply":"a","usage":{"prompt_tokens":null,"input_tokens":true},"generation_tokens_per_sec":"NaN"}""",
        )
        responses.forEach { body ->
            val legacy = legacyTelemetry("one two three four five", "x y", JSONObject(body), 1234L)
            val shared = RelayClient.parseVoiceTelemetry("one two three four five", "x y", parseJsonObject(body), 1234L)
            assertEquals(body, legacy, listOf(shared.inputTokens, shared.outputTokens, shared.promptTokensPerSec, shared.generationTokensPerSec, shared.totalMs))
        }
    }

    @Test
    fun capabilitiesMatchLegacy() {
        val payloads = listOf(
            "{}",
            """{"capabilities":{"chat":false,"image_query":"false","subscription_verify":"TRUE"}}""",
            """{"chat":false,"transcription_http":1,"openai_chat_completions":"no"}""",
            """{"capabilities":"none","voice_query":false}""",
        )
        payloads.forEach { payload ->
            val caps = SharedCapabilities.parse(parseJsonObject(payload))
            assertEquals(
                payload,
                legacyCapabilities(JSONObject(payload)),
                listOf(caps.chat, caps.voiceQuery, caps.imageQuery, caps.openAiChatCompletions, caps.subscriptionVerify, caps.transcriptionHttp),
            )
        }
    }

    @Test
    fun localizedErrorsMatchLegacy() {
        listOf(
            "quota_exhausted: Monthly quota exhausted for plan cheap",
            "HTTP 402 quota",
            "quota exhausted for free_trial",
            "subscription_required (HTTP 403)",
            "403 subscription",
            "authentication_required (HTTP 401)",
            "401 token expired",
            "Relay HTTP 429",
            "Relay HTTP 500",
            "x".repeat(300),
            "",
        ).forEach { message ->
            val error = IllegalStateException(message)
            assertEquals(message, legacyLocalized(error), RelayErrorLocalizer.localizedMessage(context, error))
            assertEquals(message, legacyIsQuota(error), RelayErrorLocalizer.isQuotaError(error))
        }
        val noMessage = IllegalStateException()
        assertEquals(legacyLocalized(noMessage), RelayErrorLocalizer.localizedMessage(context, noMessage))
    }

    // ---- Legacy code, copied verbatim (except for names) from before the move. ----

    private fun legacyHttpError(code: Int, body: String): String {
        val error = runCatching { JSONObject(body).optString("error").trim() }.getOrNull().orEmpty()
        val message = runCatching { JSONObject(body).optString("message").trim() }.getOrNull().orEmpty()
        return when {
            error.isNotBlank() && message.isNotBlank() -> "$error: $message"
            error.isNotBlank() -> "$error (HTTP $code)"
            message.isNotBlank() -> message
            else -> "Relay HTTP $code"
        }
    }

    private fun legacyCapabilities(payload: JSONObject): List<Boolean> {
        val caps = payload.optJSONObject("capabilities") ?: payload
        return listOf(
            caps.optBoolean("chat", true),
            caps.optBoolean("voice_query", caps.optBoolean("chat", true)),
            caps.optBoolean("image_query", true),
            caps.optBoolean("openai_chat_completions", true),
            caps.optBoolean("subscription_verify", false),
            caps.optBoolean("transcription_http", false),
        )
    }

    private fun legacyTelemetry(prompt: String, reply: String, response: JSONObject, elapsedMs: Long): List<Any> {
        val usage = response.optJSONObject("usage") ?: JSONObject()
        val perf = response.optJSONObject("performance")
            ?: response.optJSONObject("metrics")
            ?: JSONObject()
        val inputTokens = firstPositiveInt(
            usage.optInt("prompt_tokens", -1),
            usage.optInt("input_tokens", -1),
            response.optInt("prompt_tokens", -1),
            response.optInt("input_tokens", -1),
            roughTokenEstimate(prompt),
        )
        val outputTokens = firstPositiveInt(
            usage.optInt("completion_tokens", -1),
            usage.optInt("output_tokens", -1),
            response.optInt("completion_tokens", -1),
            response.optInt("output_tokens", -1),
            roughTokenEstimate(reply),
        )
        val promptMs = firstPositiveLong(
            perf.optLong("prompt_eval_ms", -1L),
            perf.optLong("prompt_ms", -1L),
            perf.optLong("prefill_ms", -1L),
            response.optLong("prompt_eval_ms", -1L),
        )
        val generationMs = firstPositiveLong(
            perf.optLong("completion_eval_ms", -1L),
            perf.optLong("generation_ms", -1L),
            perf.optLong("decode_ms", -1L),
            response.optLong("completion_eval_ms", -1L),
        )
        val promptTpsFromServer = firstPositiveDouble(
            perf.optDouble("prompt_tokens_per_sec", -1.0),
            perf.optDouble("prompt_tps", -1.0),
            response.optDouble("prompt_tokens_per_sec", -1.0),
        )
        val genTpsFromServer = firstPositiveDouble(
            perf.optDouble("generation_tokens_per_sec", -1.0),
            perf.optDouble("completion_tokens_per_sec", -1.0),
            perf.optDouble("gen_tps", -1.0),
            response.optDouble("generation_tokens_per_sec", -1.0),
        )
        val promptTps = when {
            promptTpsFromServer > 0.0 -> promptTpsFromServer
            promptMs > 0L -> inputTokens / (promptMs / 1000.0)
            else -> inputTokens / ((elapsedMs * 0.35) / 1000.0)
        }.coerceAtLeast(1.0)
        val generationTps = when {
            genTpsFromServer > 0.0 -> genTpsFromServer
            generationMs > 0L -> outputTokens / (generationMs / 1000.0)
            else -> outputTokens / ((elapsedMs * 0.65) / 1000.0)
        }.coerceAtLeast(1.0)
        return listOf(inputTokens, outputTokens, promptTps, generationTps, elapsedMs)
    }

    private fun roughTokenEstimate(text: String): Int {
        val cleaned = text.trim()
        if (cleaned.isBlank()) return 1
        val words = cleaned.split(Regex("\\s+")).count { it.isNotBlank() }
        return (words * 1.35).toInt().coerceAtLeast(1)
    }

    private fun firstPositiveInt(vararg values: Int): Int = values.firstOrNull { it > 0 } ?: 1
    private fun firstPositiveLong(vararg values: Long): Long = values.firstOrNull { it > 0L } ?: -1L
    private fun firstPositiveDouble(vararg values: Double): Double =
        values.firstOrNull { it.isFinite() && it > 0.0 } ?: -1.0

    private val quotaPlanRegex = Regex("""plan\s+([a-zA-Z0-9_\-]+)""", RegexOption.IGNORE_CASE)

    private fun legacyLocalized(throwable: Throwable): String {
        val raw = throwable.message?.trim().orEmpty()
        val lower = raw.lowercase()
        val isQuota = lower.contains("quota_exhausted") || lower.contains("quota exhausted") || lower.contains("402") && lower.contains("quota")
        val isSubscription = lower.contains("subscription_required") || lower.contains("subscription required") || lower.contains("403") && lower.contains("subscription")
        val isAuth = lower.contains("authentication_required") || lower.contains("authentication required") || lower.contains("401") && (lower.contains("auth") || lower.contains("token"))
        if (isQuota) {
            val plan = quotaPlanRegex.find(raw)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null } ?: legacyPlanFallback(lower)
            return context.getString(R.string.relay_quota_exhausted, plan ?: "Pro")
        }
        if (isSubscription) return context.getString(R.string.relay_subscription_required)
        if (isAuth) return context.getString(R.string.relay_authentication_required)
        val detail = raw.takeIf { it.isNotBlank() && !it.startsWith("Relay HTTP") } ?: raw
        return if (detail.isNotBlank() && !detail.equals("Relay HTTP 429", ignoreCase = true)) {
            context.getString(R.string.relay_error_generic, detail.take(120))
        } else {
            context.getString(R.string.relay_error_generic, raw.takeIf { it.isNotBlank() } ?: "unknown error")
        }
    }

    private fun legacyPlanFallback(lower: String): String? {
        val known = listOf("free_trial", "cheap", "standard", "max")
        for (p in known) if (lower.contains(p)) return p
        return null
    }

    private fun legacyIsQuota(throwable: Throwable): Boolean {
        val msg = throwable.message?.lowercase().orEmpty()
        return msg.contains("quota_exhausted") || msg.contains("quota exhausted") || (msg.contains("402") && msg.contains("quota"))
    }
}
