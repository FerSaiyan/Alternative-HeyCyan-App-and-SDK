package com.fersaiyan.cyanbridge.shared.ai

import com.fersaiyan.cyanbridge.shared.platform.HttpRequest
import com.fersaiyan.cyanbridge.shared.platform.HttpResponse
import com.fersaiyan.cyanbridge.shared.platform.HttpTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Pins the relay requests and replies the Android app has always sent and accepted. */
class RelayClientTest {
    private class FakeTransport(private val reply: (HttpRequest) -> HttpResponse) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return reply(request)
        }
    }

    private fun ok(body: String) = HttpResponse(200, emptyMap(), body)

    private val settings = RelaySettingsSnapshot(
        baseUrl = "https://relay.test/",
        backend = CliRelayBackend.CODEX,
        systemPrompt = "Be brief.",
        headers = mapOf("Accept-Language" to "pt-BR", "Authorization" to "Bearer tok"),
    )

    private fun client(transport: HttpTransport, caps: RelayCapabilitiesCache? = null, clock: () -> Long = { 0L }) =
        RelayClient(settings, transport, caps, clock = clock, sleep = {})

    @Test
    fun chatSendsSystemMessageBackendAndTrimmedModel() = runTest {
        val transport = FakeTransport { ok("""{"reply":"Olá"}""") }
        val reply = client(transport).chat(
            chatId = "c1",
            prompt = "Hi",
            messages = listOf(mapOf("role" to "user", "content" to "Hi"), mapOf("content" to "no role")),
            modelOverride = " m1 ",
        )
        assertEquals("Olá", reply.getOrThrow())
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://relay.test/chat", request.url)
        assertEquals(
            """{"backend":"codex","chatId":"c1","prompt":"Hi","messages":[{"role":"system","content":"Be brief."},""" +
                """{"role":"user","content":"Hi"},{"content":"no role"}],"system_prompt":"Be brief.","model":"m1"}""",
            request.body,
        )
        assertEquals(
            mapOf(
                "Content-Type" to "application/json; charset=utf-8",
                "Accept-Language" to "pt-BR",
                "Authorization" to "Bearer tok",
            ),
            request.headers,
        )
        assertEquals(7000, request.connectTimeoutMs)
        assertEquals(120000, request.readTimeoutMs)
    }

    @Test
    fun blankModelAndPromptAreLeftOut() = runTest {
        val transport = FakeTransport { ok("""{"reply":"x"}""") }
        client(transport).voiceQuery("Que horas são?", modelOverride = "  ").getOrThrow()
        client(transport).imageQuery(RelayMedia("a.jpg") { byteArrayOf(1, 2, 3) }, prompt = "  ").getOrThrow()
        client(transport).audioQuery(RelayMedia("a.wav") { byteArrayOf(4, 5) }, prompt = " say ", modelOverride = "m2")
            .getOrThrow()
        val bodies = transport.requests.map { it.url to it.body }
        assertEquals(
            listOf(
                "https://relay.test/voice-query" to
                    """{"backend":"codex","prompt":"Que horas são?","system_prompt":"Be brief."}""",
                "https://relay.test/image-query" to
                    """{"backend":"codex","filename":"a.jpg","imageBase64":"AQID","system_prompt":"Be brief."}""",
                "https://relay.test/audio-query" to
                    """{"filename":"a.wav","audioBase64":"BAU=","system_prompt":"Be brief.","prompt":"say","model":"m2"}""",
            ),
            bodies,
        )
    }

    @Test
    fun backendOverrideWinsAndHealthReportsStatus() = runTest {
        val transport = FakeTransport { request ->
            if (request.url.endsWith("/health")) ok("""{"status":"ok"}""") else ok("""{"reply":"r"}""")
        }
        assertEquals("ok (unknown)", client(transport).healthCheck().getOrThrow())
        client(transport).voiceQuery("p", backendOverride = CliRelayBackend.GEMINI).getOrThrow()
        assertEquals("""{"backend":"codex"}""", transport.requests[0].body)
        assertTrue(transport.requests[1].body!!.startsWith("""{"backend":"gemini","""))
    }

    @Test
    fun httpErrorsCarryTheRelayErrorAndMessage() {
        assertEquals(
            "quota_exhausted: Monthly quota exhausted for plan cheap",
            RelayClient.relayHttpErrorMessage(402, """{"error":"quota_exhausted","message":"Monthly quota exhausted for plan cheap"}"""),
        )
        assertEquals("boom (HTTP 500)", RelayClient.relayHttpErrorMessage(500, """{"error":" boom "}"""))
        assertEquals("only message", RelayClient.relayHttpErrorMessage(500, """{"message":"only message"}"""))
        assertEquals("Relay HTTP 503", RelayClient.relayHttpErrorMessage(503, "<html>down</html>"))
        assertEquals("""{"code":7} (HTTP 400)""", RelayClient.relayHttpErrorMessage(400, """{"error":{"code":7}}"""))
        assertEquals("null (HTTP 400)", RelayClient.relayHttpErrorMessage(400, """{"error":null}"""))
    }

    @Test
    fun imageErrorsAreNotRetriedButChatErrorsAre() = runTest {
        val transport = FakeTransport { HttpResponse(500, emptyMap(), """{"error":"boom"}""") }
        val image = client(transport).imageQuery(RelayMedia("a.jpg") { byteArrayOf(1) })
        assertEquals("boom (HTTP 500)", image.exceptionOrNull()?.message)
        assertEquals(1, transport.requests.size)

        val chat = client(transport).chat("c", "p", emptyList())
        assertEquals("boom (HTTP 500)", chat.exceptionOrNull()?.message)
        assertEquals(4, transport.requests.size)
    }

    @Test
    fun emptyRepliesFail() = runTest {
        val transport = FakeTransport { ok("""{"response":"elsewhere"}""") }
        assertEquals("Relay returned empty chat reply", client(transport).chat("c", "p", emptyList()).exceptionOrNull()?.message)
        assertEquals("Relay returned empty voice reply", client(transport).voiceQuery("p").exceptionOrNull()?.message)
        assertEquals(
            "Relay returned empty image reply",
            client(transport).imageQuery(RelayMedia("a") { byteArrayOf() }).exceptionOrNull()?.message,
        )
    }

    @Test
    fun relayUrlMustBeHttp() = runTest {
        val transport = FakeTransport { ok("{}") }
        val bad = RelayClient(settings.copy(baseUrl = "relay.test"), transport, sleep = {})
        val error = bad.imageQuery(RelayMedia("a") { byteArrayOf() }).exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals("Relay URL must start with http:// or https://", error.message)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun mediaIsReadAfterTheCapabilityCheck() = runTest {
        val transport = FakeTransport { ok("""{"capabilities":{"image_query":false}}""") }
        val caps = RelayCapabilitiesCache(transport) { 0L }
        var read = false
        val result = client(transport, caps).imageQuery(RelayMedia("a") { read = true; byteArrayOf() })
        assertEquals("Server capability unavailable: image_query", result.exceptionOrNull()?.message)
        assertEquals(false, read)
        assertEquals("https://relay.test/capabilities", transport.requests.single().url)
    }

    @Test
    fun capabilitiesAreParsedWithDefaultsAndCachedForAMinute() = runTest {
        var now = 1_000L
        val transport = FakeTransport { ok("""{"voice_query":"false","subscription_verify":true}""") }
        val cache = RelayCapabilitiesCache(transport) { now }
        val caps = cache.get(" https://relay.test/ ").getOrThrow()
        assertEquals(true, caps.chat)
        assertEquals(false, caps.voiceQuery)
        assertEquals(true, caps.imageQuery)
        assertEquals(true, caps.openAiChatCompletions)
        assertEquals(true, caps.subscriptionVerify)
        assertEquals(false, caps.transcriptionHttp)
        now += 60_000L
        cache.get("https://relay.test")
        assertEquals(1, transport.requests.size)
        now += 1L
        cache.get("https://relay.test")
        assertEquals(2, transport.requests.size)
        assertEquals(5000, transport.requests[0].connectTimeoutMs)
        assertEquals(10000, transport.requests[0].readTimeoutMs)
        assertEquals("Relay base URL is blank", cache.get(" ").exceptionOrNull()?.message)
        assertEquals("Relay base URL must start with http:// or https://", cache.get("ftp://x").exceptionOrNull()?.message)
    }

    @Test
    fun voiceTelemetryPrefersServerFigures() = runTest {
        val transport = FakeTransport {
            ok(
                """{"reply":"one two three","usage":{"prompt_tokens":"12","output_tokens":30},""" +
                    """"metrics":{"prompt_tps":40.5,"decode_ms":1500}}""",
            )
        }
        var tick = 0L
        val details = client(transport, clock = { tick.also { tick += 2000L } }).voiceQueryDetailed("p").getOrThrow()
        assertEquals(VoiceQueryTelemetry(12, 30, 40.5, 20.0, 2000L), details.telemetry)
    }

    @Test
    fun voiceTelemetryFallsBackToEstimates() {
        val telemetry = RelayClient.parseVoiceTelemetry(
            prompt = "one two three four",
            reply = "",
            response = kotlinx.serialization.json.JsonObject(emptyMap()),
            elapsedMs = 1000L,
        )
        // 4 words * 1.35 = 5 tokens over 35% of the time; a blank reply counts as 1 token.
        assertEquals(
            VoiceQueryTelemetry(5, 1, 5 / ((1000L * 0.35) / 1000.0), 1 / ((1000L * 0.65) / 1000.0), 1000L),
            telemetry,
        )
    }

    @Test
    fun severalMediaRepliesAreMergedByChat() = runTest {
        val transport = FakeTransport { request ->
            when {
                request.url.endsWith("/image-query") -> ok("""{"reply":"img${request.body!!.substringAfter("Image ").take(1)}"}""")
                request.url.endsWith("/audio-query") -> ok("""{"reply":"aud"}""")
                else -> ok("""{"reply":"merged"}""")
            }
        }
        val reply = client(transport).chatWithMedia(
            chatId = "c",
            userPrompt = "What?",
            messages = listOf(mapOf("role" to "user", "content" to "What?")),
            images = listOf(RelayMedia("1.jpg") { byteArrayOf(1) }, RelayMedia("2.jpg") { byteArrayOf(2) }),
            audio = RelayMedia("a.wav") { byteArrayOf(3) },
            textModel = "text",
            mediaModel = "media",
        )
        assertEquals("merged", reply.getOrThrow())
        assertTrue(transport.requests[0].body!!.contains(""""prompt":"Image 1 of 2. What?","model":"media""""))
        assertTrue(transport.requests[2].body!!.contains(""""prompt":"What?","model":"media""""))
        val synthesis = "What?\n\nAttached media analyses:\n1. img1\n2. img2\n3. aud\nGive one coherent answer using all attached media."
        val chatBody = transport.requests[3].body!!
        assertTrue(chatBody.contains(""""prompt":${quoted(synthesis)}"""))
        assertTrue(chatBody.endsWith(""""content":${quoted(synthesis)}}],"system_prompt":"Be brief.","model":"media"}"""))
    }

    @Test
    fun oneMediaReplyIsReturnedAndNoMediaIsAPlainChat() = runTest {
        val transport = FakeTransport { ok("""{"reply":"r"}""") }
        client(transport).chatWithMedia("c", "Q", emptyList(), listOf(RelayMedia("1.jpg") { byteArrayOf(1) }), null, "t", "m")
            .getOrThrow()
        assertEquals(1, transport.requests.size)
        client(transport).chatWithMedia("c", "Q", emptyList(), emptyList(), null, "t", "m").getOrThrow()
        assertTrue(transport.requests[1].body!!.endsWith(""""model":"t"}"""))
    }

    @Test
    fun failedMediaCallThrows() = runTest {
        val transport = FakeTransport { HttpResponse(500, emptyMap(), "") }
        assertFailsWith<IllegalStateException> {
            client(transport).chatWithMedia("c", "Q", emptyList(), listOf(RelayMedia("1") { byteArrayOf() }), null, null, null)
        }
    }

    private fun quoted(text: String) = kotlinx.serialization.json.JsonPrimitive(text).toString()
}
