package com.fersaiyan.cyanbridge.ai.live

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the exact Live WebSocket traffic and listener callbacks produced by [GeminiLiveClient]
 * so internal restructuring cannot change the wire protocol or event ordering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiLiveClientCharacterizationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val listener = RecordingListener()
    private val socket = RecordingWebSocket()
    private lateinit var client: GeminiLiveClient

    @Before
    fun setUp() {
        client = GeminiLiveClient(context, listener)
    }

    @After
    fun tearDown() {
        client.close()
    }

    // region outgoing setup

    @Test
    fun `generated setup pins model, VAD, transcription, compression and system instruction`() {
        LiveInternals.setPrompts(client, language = "pt-BR", imagePrompt = "  What   is\nthis?  ", systemPrompt = "Be brief.")

        LiveInternals.sendSetup(client, socket, config())

        val instruction = "Be brief. You are Gemini Live in CyanBridge smart glasses. " +
            "You support 97 languages. Respond in the language the user is currently speaking, defaulting to pt-BR only when unclear. " +
            "Switch immediately when the user asks to speak another language. " +
            "Keep spoken answers concise, helpful, and safe for a hands-free conversation. " +
            "Default image question when the user gives no specific question and you have a fresh glasses image: What is this?"
        assertEquals(
            listOf(
                """{"setup":{"model":"models\/test-live","generationConfig":{"responseModalities":["AUDIO"]},""" +
                    """"realtimeInputConfig":{"automaticActivityDetection":{"disabled":false,"startOfSpeechSensitivity":"START_SENSITIVITY_HIGH",""" +
                    """"endOfSpeechSensitivity":"END_SENSITIVITY_LOW","prefixPaddingMs":40,"silenceDurationMs":500},""" +
                    """"activityHandling":"START_OF_ACTIVITY_INTERRUPTS","turnCoverage":"TURN_INCLUDES_AUDIO_ACTIVITY_AND_ALL_VIDEO"},""" +
                    """"inputAudioTranscription":{},"outputAudioTranscription":{},"sessionResumption":{},""" +
                    """"contextWindowCompression":{"triggerTokens":25000,"slidingWindow":{"targetTokens":8000}},""" +
                    """"systemInstruction":{"parts":[{"text":"$instruction"}]}}}""",
            ),
            socket.sent,
        )
    }

    @Test
    fun `generated setup falls back to stored system prompt, default image question and resumption handle`() {
        LiveInternals.setPrompts(client, language = "en", imagePrompt = "   ", systemPrompt = "")
        LiveInternals.handleServerMessage(client, """{"sessionResumptionUpdate":{"newHandle":"resume-1"}}""")

        LiveInternals.sendSetup(client, socket, config())

        val setup = JSONObject(socket.sent.single()).getJSONObject("setup")
        assertEquals("""{"handle":"resume-1"}""", setup.getJSONObject("sessionResumption").toString())
        val text = setup.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        val storedPrompt = ProSubscriptionAiPrefs.getSystemPrompt(context).trim().take(4000)
        assertEquals(
            storedPrompt + " You are Gemini Live in CyanBridge smart glasses. " +
                "You support 97 languages. Respond in the language the user is currently speaking, defaulting to en only when unclear. " +
                "Switch immediately when the user asks to speak another language. " +
                "Keep spoken answers concise, helpful, and safe for a hands-free conversation. " +
                "Default image question when the user gives no specific question and you have a fresh glasses image: " +
                "Describe what you can see and answer the user's question.",
            text,
        )
    }

    @Test
    fun `server provided setup is echoed with token model and current resumption handle`() {
        LiveInternals.handleServerMessage(client, """{"sessionResumptionUpdate":{"newHandle":"h-9"}}""")
        val serverSetup = """{"model":"models/other","generationConfig":{"responseModalities":["AUDIO"]},"sessionResumption":{"handle":"old"},"extra":1}"""

        LiveInternals.sendSetup(client, socket, config(setupJson = serverSetup))

        assertEquals(
            listOf(
                """{"setup":{"model":"models\/test-live","generationConfig":{"responseModalities":["AUDIO"]},""" +
                    """"sessionResumption":{"handle":"h-9"},"extra":1}}""",
            ),
            socket.sent,
        )
    }

    @Test
    fun `server provided setup without handle appends empty resumption and model`() {
        LiveInternals.sendSetup(client, socket, config(setupJson = """{"generationConfig":{}}"""))

        assertEquals(
            listOf("""{"setup":{"generationConfig":{},"sessionResumption":{},"model":"models\/test-live"}}"""),
            socket.sent,
        )
    }

    @Test
    fun `malformed server setup falls back to generated setup`() {
        LiveInternals.setPrompts(client, language = "en", imagePrompt = "x", systemPrompt = "S")

        LiveInternals.sendSetup(client, socket, config(setupJson = "{not json"))

        val setup = JSONObject(socket.sent.single()).getJSONObject("setup")
        assertEquals(
            listOf(
                "model", "generationConfig", "realtimeInputConfig", "inputAudioTranscription",
                "outputAudioTranscription", "sessionResumption", "contextWindowCompression", "systemInstruction",
            ),
            setup.keys().asSequence().toList(),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `setup send failure is surfaced`() {
        socket.accept = false
        LiveInternals.sendSetup(client, socket, config())
    }

    // endregion

    // region outgoing realtime input / client content

    @Test
    fun `text turn is sent as a completed user clientContent turn`() {
        LiveInternals.makeLive(client, socket, config())

        client.sendTextTurn("hello \"there\"")
        client.sendTextTurn("   ")
        client.sendTextTurn("a".repeat(2_100))

        assertEquals(
            listOf(
                """{"clientContent":{"turns":[{"role":"user","parts":[{"text":"hello \"there\""}]}],"turnComplete":true}}""",
                """{"clientContent":{"turns":[{"role":"user","parts":[{"text":"${"a".repeat(2_000)}"}]}],"turnComplete":true}}""",
            ),
            socket.sent,
        )
    }

    @Test
    fun `text turn and images are dropped before setup completes`() {
        LiveInternals.makeLive(client, socket, config(), setupComplete = false)

        client.sendTextTurn("hello")
        assertFalse(client.sendVideoFrame(byteArrayOf(1, 2, 3)))

        assertTrue(socket.sent.isEmpty())
    }

    @Test
    fun `video frame is sent as realtimeInput video jpeg blob`() {
        LiveInternals.makeLive(client, socket, config())

        assertTrue(client.sendVideoFrame(byteArrayOf(1, 2, 3)))
        assertTrue(client.sendImage(byteArrayOf(-1, -2, -3, -4)))

        assertEquals(
            listOf(
                """{"realtimeInput":{"video":{"mimeType":"image\/jpeg","data":"AQID"}}}""",
                """{"realtimeInput":{"video":{"mimeType":"image\/jpeg","data":"\/\/79\/A=="}}}""",
            ),
            socket.sent,
        )
    }

    @Test
    fun `free tier visual input is capped at ten images per session`() {
        LiveInternals.makeLive(client, socket, config(freeTier = true))

        val results = (1..11).map { client.sendVideoFrame(byteArrayOf(it.toByte())) }

        assertEquals(List(10) { true } + false, results)
        assertEquals(10, socket.sent.size)
        assertTrue("state:LISTENING:Live visual-input safety limit reached" in listener.events)
    }

    @Test
    fun `glasses PCM is little endian, delayed sixteen chunks, then flushed with audioStreamEnd on pause`() {
        LiveInternals.makeLive(client, socket, config())
        LiveInternals.setCaptureEnabled(client, true)

        (1..18).forEach { client.offerGlassesPcm(chunk(it), 16_000) }
        assertEquals(listOf(audioMessage(chunkBytes(1)), audioMessage(chunkBytes(2))), socket.sent)
        assertEquals(80L, LiveInternals.inputAudioMs(client))

        client.pauseForBackground()

        assertEquals(
            (1..18).map { audioMessage(chunkBytes(it)) } + """{"realtimeInput":{"audioStreamEnd":true}}""",
            socket.sent,
        )
        assertEquals(18 * 40L, LiveInternals.inputAudioMs(client))
        // Capture is now disabled; further PCM is ignored.
        client.offerGlassesPcm(chunk(19), 16_000)
        assertEquals(19, socket.sent.size)
    }

    @Test
    fun `glasses PCM is resampled to 16 kHz before encoding`() {
        LiveInternals.makeLive(client, socket, config())
        LiveInternals.setCaptureEnabled(client, true)

        client.offerGlassesPcm(shortArrayOf(1, -2, 300, 0, 0, 0), 24_000)
        client.pauseForBackground()

        val expected = PcmResampler.resampleMono16(shortArrayOf(1, -2, 300, 0, 0, 0), 24_000, 16_000)
        assertEquals(
            listOf(audioMessage(littleEndian(expected)), """{"realtimeInput":{"audioStreamEnd":true}}"""),
            socket.sent,
        )
    }

    @Test
    fun `mentra PCM is ignored unless the mentra microphone is active`() {
        LiveInternals.makeLive(client, socket, config())
        LiveInternals.setCaptureEnabled(client, true)

        client.offerMentraPcm(chunkBytes(1))
        client.pauseForBackground()

        assertEquals(listOf("""{"realtimeInput":{"audioStreamEnd":true}}"""), socket.sent)
    }

    @Test
    fun `deferred visual context holds the silent turn tail until released`() {
        LiveInternals.makeLive(client, socket, config())
        LiveInternals.setCaptureEnabled(client, true)

        (1..3).forEach { client.offerGlassesPcm(chunk(it), 16_000) }
        client.deferAudioForVisualContext()
        client.offerGlassesPcm(chunk(4), 16_000)
        client.offerGlassesPcm(chunk(5), 16_000)
        assertEquals(emptyList<String>(), socket.sent)

        client.sendVideoFrame(byteArrayOf(9))
        client.releaseAudioAfterVisualContext()

        assertEquals(
            listOf("""{"realtimeInput":{"video":{"mimeType":"image\/jpeg","data":"CQ=="}}}""") +
                (1..4).map { audioMessage(chunkBytes(it)) },
            socket.sent,
        )
    }

    @Test
    fun `cancelled visual context discards the held tail`() {
        LiveInternals.makeLive(client, socket, config())
        LiveInternals.setCaptureEnabled(client, true)

        (1..3).forEach { client.offerGlassesPcm(chunk(it), 16_000) }
        client.deferAudioForVisualContext()
        client.cancelDeferredVisualContext()
        client.releaseAudioAfterVisualContext()
        client.pauseForBackground()

        assertEquals(listOf("""{"realtimeInput":{"audioStreamEnd":true}}"""), socket.sent)
    }

    // endregion

    // region incoming server messages

    @Test
    fun `setupComplete starts listening and reports permission failure for microphone`() {
        LiveInternals.makeLive(client, socket, config(), setupComplete = false)

        LiveInternals.handleServerMessage(client, """{"setupComplete":{}}""")
        LiveInternals.handleServerMessage(client, """{"setupComplete":{}}""")

        assertEquals(
            listOf(
                "state:ERROR:Microphone permission is required",
                "state:LISTENING:Gemini Live is listening",
                "setupComplete",
            ),
            listener.events,
        )
    }

    @Test
    fun `server content callbacks fire in protocol order`() {
        LiveInternals.makeLive(client, socket, config())

        LiveInternals.handleServerMessage(
            client,
            """{"serverContent":{"inputTranscription":{"text":"hi"},"outputTranscription":{"text":"yo"},""" +
                """"modelTurn":{"parts":[{"text":"ignored"},{"inlineData":{"mimeType":"audio/pcm","data":"AAAAAA=="}}]},""" +
                """"turnComplete":true}}""",
        )
        LiveInternals.handleServerMessage(client, """{"serverContent":{"inputTranscription":{"text":"  "},"turnComplete":false}}""")

        assertEquals(
            listOf(
                "transcription:input:hi",
                "transcription:output:yo",
                "modelOutputStarted",
                "modelTurnComplete",
                "state:LISTENING:Gemini Live is listening",
            ),
            listener.events,
        )
    }

    @Test
    fun `interruption ends model playback and notifies listener`() {
        LiveInternals.makeLive(client, socket, config())

        LiveInternals.handleServerMessage(
            client,
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"AAAA"}}]}}}""",
        )
        LiveInternals.handleServerMessage(client, """{"serverContent":{"interrupted":true}}""")
        LiveInternals.handleServerMessage(
            client,
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"AAAA"}}]}}}""",
        )

        assertEquals(
            listOf("modelOutputStarted", "interrupted", "modelOutputStarted"),
            listener.events,
        )
    }

    @Test
    fun `turnComplete is ignored once the session is inactive`() {
        LiveInternals.makeLive(client, socket, config())
        LiveInternals.setActive(client, false)

        LiveInternals.handleServerMessage(client, """{"serverContent":{"turnComplete":true}}""")

        assertEquals(emptyList<String>(), listener.events)
    }

    @Test
    fun `server error stops the session with a generic failure announcement`() {
        LiveInternals.makeLive(client, socket, config())

        LiveInternals.handleServerMessage(
            client,
            """{"error":{"message":"boom"},"serverContent":{"inputTranscription":{"text":"never"}}}""",
        )

        assertEquals(
            listOf(
                "announcement:GENERIC_FAILURE",
                "state:STOPPED:Stopped",
                "state:ERROR:Gemini Live session failed. Please try again.",
            ),
            listener.events,
        )
        assertEquals(listOf("close:1000:user_stopped"), socket.closes)
    }

    @Test
    fun `server quota error stops the session without an announcement`() {
        LiveInternals.makeLive(client, socket, config())

        LiveInternals.handleServerMessage(client, """{"error":{"message":"live_quota_exhausted"}}""")

        assertEquals(
            listOf(
                "state:STOPPED:Stopped",
                "state:ERROR:Live quota exhausted. Please wait for quota reset or upgrade.",
            ),
            listener.events,
        )
    }

    @Test
    fun `malformed and informational messages produce no callbacks`() {
        LiveInternals.makeLive(client, socket, config())

        LiveInternals.handleServerMessage(client, "not json")
        LiveInternals.handleServerMessage(client, """{"goAway":{"timeLeft":"10s"}}""")
        LiveInternals.handleServerMessage(client, """{"usageMetadata":{"totalTokenCount":10}}""")
        LiveInternals.handleServerMessage(client, """{"sessionResumptionUpdate":{"newHandle":""}}""")

        assertEquals(emptyList<String>(), listener.events)
        assertTrue(socket.sent.isEmpty())
    }

    // endregion

    private fun config(setupJson: String? = null, freeTier: Boolean = false) = LiveTokenConfig(
        token = "",
        model = "models/test-live",
        websocketUrl = "wss://example.invalid/live",
        expiresAtMs = Long.MAX_VALUE,
        reservationId = "direct",
        setupJson = setupJson,
        freeTier = freeTier,
    )

    private fun chunk(index: Int) = ShortArray(640) { index.toShort() }

    private fun chunkBytes(index: Int) = littleEndian(chunk(index))

    private fun littleEndian(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            bytes[i * 2] = (s.toInt() and 0xff).toByte()
            bytes[i * 2 + 1] = ((s.toInt() ushr 8) and 0xff).toByte()
        }
        return bytes
    }

    private fun audioMessage(bytes: ByteArray): String {
        val data = java.util.Base64.getEncoder().encodeToString(bytes).replace("/", "\\/")
        return """{"realtimeInput":{"audio":{"mimeType":"audio\/pcm;rate=16000","data":"$data"}}}"""
    }

    private class RecordingListener : GeminiLiveClient.Listener {
        val events = mutableListOf<String>()
        override fun onStateChanged(state: GeminiLiveState, detail: String) { events += "state:$state:$detail" }
        override fun onInterrupted() { events += "interrupted" }
        override fun onNetworkChanged(available: Boolean) { events += "network:$available" }
        override fun onUserSpeechActivity(active: Boolean) { events += "speech:$active" }
        override fun onTranscription(input: Boolean, text: String) {
            events += "transcription:${if (input) "input" else "output"}:$text"
        }
        override fun onSetupComplete() { events += "setupComplete" }
        override fun onModelOutputStarted() { events += "modelOutputStarted" }
        override fun onModelTurnComplete() { events += "modelTurnComplete" }
        override fun onAnnouncement(kind: GeminiLiveAnnouncement) { events += "announcement:$kind" }
    }

    private class RecordingWebSocket : WebSocket {
        val sent = mutableListOf<String>()
        val closes = mutableListOf<String>()
        var accept = true
        override fun request(): Request = Request.Builder().url("https://example.invalid/").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean {
            if (accept) sent += text
            return accept
        }
        override fun send(bytes: ByteString): Boolean = error("binary frames are not used")
        override fun close(code: Int, reason: String?): Boolean {
            closes += "close:$code:$reason"
            return true
        }
        override fun cancel() { closes += "cancel" }
    }

    /** All reflective access to client internals lives here so tests stay pinned to behavior. */
    private object LiveInternals {
        private fun field(owner: Any, name: String) =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

        private fun method(owner: Any, name: String, vararg types: Class<*>) =
            owner.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }

        fun setPrompts(client: GeminiLiveClient, language: String, imagePrompt: String, systemPrompt: String) {
            field(client, "lastLanguage").set(client, language)
            field(client, "lastImagePrompt").set(client, imagePrompt)
            field(client, "lastSystemPrompt").set(client, systemPrompt)
        }

        fun sendSetup(client: GeminiLiveClient, socket: WebSocket, config: LiveTokenConfig) {
            try {
                method(client, "sendSetup", WebSocket::class.java, LiveTokenConfig::class.java)
                    .invoke(client, socket, config)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        }

        fun handleServerMessage(client: GeminiLiveClient, raw: String) {
            method(client, "handleServerMessage", String::class.java).invoke(client, raw)
        }

        fun makeLive(client: GeminiLiveClient, socket: WebSocket, config: LiveTokenConfig, setupComplete: Boolean = true) {
            field(client, "socket").set(client, socket)
            field(client, "tokenConfig").set(client, config)
            setActive(client, true)
            (field(client, "setupComplete").get(client) as AtomicBoolean).set(setupComplete)
        }

        fun setActive(client: GeminiLiveClient, active: Boolean) {
            (field(client, "active").get(client) as AtomicBoolean).set(active)
        }

        fun setCaptureEnabled(client: GeminiLiveClient, enabled: Boolean) {
            (field(client, "captureEnabled").get(client) as AtomicBoolean).set(enabled)
        }

        fun inputAudioMs(client: GeminiLiveClient): Long = field(client, "inputAudioMs").getLong(client)
    }
}
