package com.fersaiyan.cyanbridge.ai.live

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiLiveProtocolTest {
    // region outgoing frames

    @Test
    fun `realtime blobs use base64 without wrapping`() {
        assertEquals(
            """{"realtimeInput":{"audio":{"mimeType":"audio\/pcm;rate=16000","data":"AAH\/"}}}""",
            GeminiLiveProtocol.buildRealtimeBlob(
                GeminiLiveProtocol.REALTIME_AUDIO_FIELD,
                GeminiLiveProtocol.INPUT_AUDIO_MIME_TYPE,
                byteArrayOf(0, 1, -1),
            ),
        )
        assertEquals(
            """{"realtimeInput":{"video":{"mimeType":"image\/jpeg","data":"${"A".repeat(100)}"}}}""",
            GeminiLiveProtocol.buildRealtimeBlob(
                GeminiLiveProtocol.REALTIME_VIDEO_FIELD,
                GeminiLiveProtocol.IMAGE_MIME_TYPE,
                ByteArray(75),
            ),
        )
    }

    @Test
    fun `audio stream end and text turn frames`() {
        assertEquals("""{"realtimeInput":{"audioStreamEnd":true}}""", GeminiLiveProtocol.buildAudioStreamEnd())
        assertEquals(
            """{"clientContent":{"turns":[{"role":"user","parts":[{"text":"hi"}]}],"turnComplete":true}}""",
            GeminiLiveProtocol.buildClientTextTurn("hi"),
        )
    }

    @Test
    fun `stored system prompt is only read when the session prompt is blank`() {
        var reads = 0
        val stored = { reads++; "Stored." }
        GeminiLiveProtocol.buildSetup(config(), null, "en", "", "Session.") { stored() }
        GeminiLiveProtocol.buildSetup(config(setupJson = "{}"), null, "en", "", "") { stored() }
        assertEquals(0, reads)

        val setup = GeminiLiveProtocol.buildSetup(config(), " ", "en", "", "  ") { stored() }

        assertEquals(1, reads)
        assertEquals(false, setup.serverProvided)
        assertTrue(setup.json.contains(""""sessionResumption":{}"""))
        assertTrue(setup.json.contains(""""text":"Stored. You are Gemini Live in CyanBridge smart glasses."""))
    }

    @Test
    fun `server provided setup is flagged`() {
        val setup = GeminiLiveProtocol.buildSetup(config(setupJson = "{}"), "h", "en", "", "") { "" }
        assertEquals(true, setup.serverProvided)
        assertEquals("""{"setup":{"sessionResumption":{"handle":"h"},"model":"models\/test-live"}}""", setup.json)
    }

    // endregion

    // region connect request

    @Test
    fun `private session sends ephemeral token and app language`() {
        val request = GeminiLiveProtocol.buildConnectRequest(config(token = "tok"), "vid") { "pt-BR" }

        assertEquals("https://live.example/ws?key=1", request.url.toString())
        assertEquals("Token tok", request.header("Authorization"))
        assertNull(request.header("x-goog-api-key"))
        assertEquals("pt-BR", request.header("Accept-Language"))
    }

    @Test
    fun `constrained URL carrying access_token gets no second credential`() {
        val request = GeminiLiveProtocol.buildConnectRequest(
            config(token = "tok", url = "wss://live.example/ws?access_token=tok"),
            "vid",
        ) { "" }

        assertNull(request.header("Authorization"))
        assertNull(request.header("Accept-Language"))
    }

    @Test
    fun `explicit authorization header wins and api key is forwarded`() {
        val request = GeminiLiveProtocol.buildConnectRequest(
            config(token = "tok", authorizationHeader = "Bearer acct", apiKey = "k"),
            "vid",
        ) { error("language unavailable") }

        assertEquals("Bearer acct", request.header("Authorization"))
        assertEquals("k", request.header("x-goog-api-key"))
        assertNull(request.header("Accept-Language"))
    }

    @Test
    fun `metered sessions carry the vision session id`() {
        listOf(config(freeTier = true), config(economy = true)).forEach { metered ->
            val request = GeminiLiveProtocol.buildConnectRequest(metered, "vid-1") { "en" }
            assertEquals("https://live.example/ws?key=1&vision_session_id=vid-1", request.url.toString())
        }
    }

    // endregion

    // region incoming frames

    @Test
    fun `server message events are decoded in handling order`() {
        val events = GeminiLiveProtocol.parseServerMessage(
            """{"serverContent":{"turnComplete":true,"modelTurn":{"parts":[{"inlineData":{"data":"AQI="}},{"text":"x"},"bad",""" +
                """{"inlineData":{"data":"   "}},{"inlineData":{"data":"A"}},{"inlineData":{"data":"Aw=="}}]},""" +
                """"interrupted":true,"outputTranscription":{"text":"out"},"inputTranscription":{"text":"in"}},""" +
                """"usageMetadata":{"totalTokenCount":7},"goAway":{"timeLeft":"5s"},""" +
                """"sessionResumptionUpdate":{"newHandle":"h1","resumable":true},"setupComplete":{}}""",
        )!!

        assertEquals(
            listOf(
                "SetupComplete", "Resumption:h1", "GoAway:5s", "Usage:{\"totalTokenCount\":7}",
                "Transcription:true:in", "Transcription:false:out", "Interrupted",
                "Audio:[1, 2]", "Audio:[3]", "TurnComplete",
            ),
            events.map(::describe),
        )
    }

    @Test
    fun `error object short-circuits everything else`() {
        assertEquals(
            listOf(GeminiLiveServerEvent.Error(quotaExhausted = false)),
            GeminiLiveProtocol.parseServerMessage("""{"setupComplete":{},"error":{"code":1}}"""),
        )
        assertEquals(
            listOf(GeminiLiveServerEvent.Error(quotaExhausted = true)),
            GeminiLiveProtocol.parseServerMessage("""{"error":{"message":"live_quota_exhausted"}}"""),
        )
        // A non-object error is not an error frame.
        assertEquals(listOf("SetupComplete"), GeminiLiveProtocol.parseServerMessage("""{"error":"x","setupComplete":true}""")!!.map(::describe))
    }

    @Test
    fun `malformed or non-object frames are rejected`() {
        assertNull(GeminiLiveProtocol.parseServerMessage("not json"))
        assertNull(GeminiLiveProtocol.parseServerMessage("[1]"))
        assertNull(GeminiLiveProtocol.parseServerMessage(""))
    }

    @Test
    fun `empty optional fields produce no events`() {
        val events = GeminiLiveProtocol.parseServerMessage(
            """{"sessionResumptionUpdate":{"newHandle":" "},"goAway":{},""" +
                """"serverContent":{"inputTranscription":{"text":""},"interrupted":false,"turnComplete":false}}""",
        )!!

        assertEquals(listOf("GoAway:"), events.map(::describe))
    }

    @Test
    fun `model audio bytes are decoded from standard base64`() {
        val audio = GeminiLiveProtocol.parseServerMessage(
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AAH/"}}]}}}""",
        )!!.single() as GeminiLiveServerEvent.ModelAudio

        assertArrayEquals(byteArrayOf(0, 1, -1), audio.pcm)
    }

    // endregion

    private fun describe(event: GeminiLiveServerEvent): String = when (event) {
        is GeminiLiveServerEvent.Error -> "Error:${event.quotaExhausted}"
        GeminiLiveServerEvent.SetupComplete -> "SetupComplete"
        is GeminiLiveServerEvent.SessionResumptionUpdate -> "Resumption:${event.newHandle}"
        is GeminiLiveServerEvent.GoAway -> "GoAway:${event.timeLeft}"
        is GeminiLiveServerEvent.UsageMetadata -> "Usage:${event.usage}"
        is GeminiLiveServerEvent.Transcription -> "Transcription:${event.input}:${event.text}"
        GeminiLiveServerEvent.Interrupted -> "Interrupted"
        is GeminiLiveServerEvent.ModelAudio -> "Audio:${event.pcm.toList()}"
        GeminiLiveServerEvent.TurnComplete -> "TurnComplete"
    }

    private fun config(
        setupJson: String? = null,
        token: String = "",
        url: String = "wss://live.example/ws?key=1",
        authorizationHeader: String? = null,
        apiKey: String? = null,
        freeTier: Boolean = false,
        economy: Boolean = false,
    ) = LiveTokenConfig(
        token = token,
        model = "models/test-live",
        websocketUrl = url,
        expiresAtMs = Long.MAX_VALUE,
        reservationId = "direct",
        authorizationHeader = authorizationHeader,
        apiKey = apiKey,
        setupJson = setupJson,
        economy = economy,
        freeTier = freeTier,
    )
}
