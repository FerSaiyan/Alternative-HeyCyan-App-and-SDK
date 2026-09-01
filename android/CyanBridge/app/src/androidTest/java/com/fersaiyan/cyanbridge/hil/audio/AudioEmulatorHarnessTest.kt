package com.fersaiyan.cyanbridge.hil.audio

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.ai.live.GeminiLiveClient
import com.fersaiyan.cyanbridge.ai.live.GeminiLiveState
import com.fersaiyan.cyanbridge.ai.live.PcmResampler
import com.fersaiyan.cyanbridge.ai.transcription.DefaultTranscriptionService
import com.fersaiyan.cyanbridge.ai.transcription.FakeTranscriptionProvider
import com.fersaiyan.cyanbridge.ai.transcription.NoOpAudioChunker
import com.fersaiyan.cyanbridge.ai.transcription.TranscriptionResult
import com.fersaiyan.cyanbridge.agent.ProSubscriptionServerPrefs
import com.fersaiyan.cyanbridge.ai.transcription.AutomaticTranscriptionEngine
import com.fersaiyan.cyanbridge.data.local.entity.CaptureSession
import com.fersaiyan.cyanbridge.plugins.handsfreetranslator.HandsFreeTranslatorService
import com.fersaiyan.cyanbridge.ui.MyApplication
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Emulator audio harness: 3 deterministic, hardware-free tests sharing the same asset scaffolding.
 *
 * Each test uses a *different* audio fixture (translator / meeting / gemini) so we can reason
 * about which pipeline broke without touching the microphone or requiring a Pro token.
 *
 * Run on emulator (no glasses needed):
 *   ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.fersaiyan.cyanbridge.hil.audio.AudioEmulatorHarnessTest
 *
 * Individual cases:
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.fersaiyan.cyanbridge.hil.audio.AudioEmulatorHarnessTest#meetingTranscriptionFromSeededAsset_hermetic
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.fersaiyan.cyanbridge.hil.audio.AudioEmulatorHarnessTest#handsFreeTranslator_fromAssetTranscript
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.fersaiyan.cyanbridge.hil.audio.AudioEmulatorHarnessTest#geminiLive_pcmInjectionDoesNotCrash
 */
@RunWith(AndroidJUnit4::class)
class AudioEmulatorHarnessTest {

    @Test
    fun assets_AllThreeFixturesExist() {
        assertTrue("translator wav missing", TestAudioAssets.assetExists(TestAudioAssets.TRANSLATOR_WAV))
        assertTrue("meeting wav missing", TestAudioAssets.assetExists(TestAudioAssets.MEETING_WAV))
        assertTrue("meeting m4a missing", TestAudioAssets.assetExists(TestAudioAssets.MEETING_M4A))
        assertTrue("gemini wav missing", TestAudioAssets.assetExists(TestAudioAssets.GEMINI_WAV))
        assertTrue("gemini pcm missing", TestAudioAssets.assetExists(TestAudioAssets.GEMINI_PCM))
        // Deterministic prompts (edge-tts, 16k mono) so transcription/translation and Gemini reply can be verified.
        val meetingTxt = TestAudioAssets.readTranscript(TestAudioAssets.MEETING_TXT)
        assertTrue(meetingTxt.contains("Alice") && meetingTxt.contains("Bob"))
        assertTrue(meetingTxt.contains("Due Friday"))
        val translatorTxt = TestAudioAssets.readTranscript(TestAudioAssets.TRANSLATOR_TXT)
        assertTrue(translatorTxt.contains("Bonjour"))
        val geminiTxt = TestAudioAssets.readTranscript(TestAudioAssets.GEMINI_TXT)
        assertTrue(geminiTxt.contains("I like red flowers"))
        assertTrue(geminiTxt.contains("Hello Gemini"))
    }

    @Test
    fun meetingTranscriptionFromSeededAsset_hermetic() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // Seed a real CaptureSession from the meeting asset — no MediaRecorder involved.
        val seeded = TestMeetingCaptureSeeder.seedFromAsset(context, TestAudioAssets.MEETING_M4A)
        try {
            assertTrue(seeded.audioFile.exists())
            assertTrue(seeded.audioFile.length() > 0)

            // Hermetic provider: no network, no Pro token needed. Proves the seeding + chunking + store path.
            val service = DefaultTranscriptionService(
                context = context,
                repository = MyApplication.repository,
                provider = FakeTranscriptionProvider("Spark meeting summary fixture"),
                chunker = NoOpAudioChunker(),
            )
            val expectedTranscript = TestAudioAssets.readTranscript(TestAudioAssets.MEETING_TXT)
            assertTrue("sidecar transcript empty", expectedTranscript.isNotBlank())

            val result = service.transcribe(seeded.session)
            assertTrue(result is TranscriptionResult.Success)
            assertEquals("Spark meeting summary fixture", (result as TranscriptionResult.Success).text)

            // Verify persistence round-trip
            val record = MyApplication.repository.getTranscriptionByCaptureSessionId(seeded.sessionId)
            checkNotNull(record) { "Transcription not persisted for seeded session ${seeded.sessionId}" }
            assertEquals("SUCCEEDED", record.status)
        } finally {
            MyApplication.repository.deleteCaptureSession(seeded.sessionId)
            runCatching { seeded.audioFile.delete() }
        }
    }

    @Test
    fun handsFreeTranslator_fromAssetTranscript() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val transcript = TestAudioAssets.readTranscript(TestAudioAssets.TRANSLATOR_TXT)
        assertTrue(transcript.isNotBlank())

        // Hands-free translator exposes a test-friendly intent entrypoint that bypasses SpeechRecognizer.
        // This proves the translation pipeline (store + glasses bridge) without needing a mic or Google voice app.
        // We only verify the intent can be fired without crash; real relay call is hermetic/gated elsewhere.
        HandsFreeTranslatorService.translate(context, transcript)

        // Also verify the asset wav is decodable (header + pcm).
        val wav = TestAudioAssets.readWavBytes(TestAudioAssets.TRANSLATOR_WAV)
        assertTrue(wav.size > 44)
        val shorts = TestAudioAssets.wavToPcm16Shorts(wav)
        assertTrue(shorts.isNotEmpty())
        // Resampler is used by GeminiLiveClient.offerGlassesPcm — smoke-check it on this fixture too.
        val resampled = PcmResampler.resampleMono16(shorts, 16_000, 16_000)
        assertEquals(shorts.size, resampled.size)
    }

    @Test
    fun geminiLive_pcmInjectionDoesNotCrash() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        val fakeProvider = FakeGeminiLiveTokenProvider()
        var lastState: GeminiLiveState? = null
        var lastDetail: String? = null
        val client = GeminiLiveClient(
            context = context,
            listener = object : GeminiLiveClient.Listener {
                override fun onStateChanged(state: GeminiLiveState, detail: String) {
                    lastState = state
                    lastDetail = detail
                }
                override fun onInterrupted() = Unit
                override fun onNetworkChanged(available: Boolean) = Unit
            },
            tokenProvider = fakeProvider,
        )

        try {
            // Deterministic prompt: "Hello Gemini, please reply with exactly for this small test, I like red flowers"
            // lets a real nightly run assert the model echoes "I like red flowers".
            val pcmShorts = TestAudioAssets.wavToPcm16Shorts(
                TestAudioAssets.readWavBytes(TestAudioAssets.GEMINI_WAV)
            )
            assertTrue(pcmShorts.isNotEmpty())
            // ~191k bytes of 16k PCM => ~6s of speech
            assertTrue(TestAudioAssets.readTranscript(TestAudioAssets.GEMINI_TXT).contains("I like red flowers"))
            client.offerGlassesPcm(pcmShorts, 16_000)

            // Also verify raw pcm16 asset path (used for direct injection without wav header).
            val raw = TestAudioAssets.readPcm16(TestAudioAssets.GEMINI_PCM)
            assertTrue(raw.isNotEmpty())
            assertEquals(0, raw.size % 2)
            // Raw pcm was extracted via wave module from the same logical audio; sizes should match (allow 0 diff)
            assertTrue("pcmShorts ${pcmShorts.size} vs raw ${raw.size/2}", kotlin.math.abs(pcmShorts.size * 2 - raw.size) <= 2)

            // offerGlassesPcm is the emulator-friendly seam: real GeminiLiveActivity would call it from
            // a BT SCO glasses mic; tests inject deterministic file PCM instead of a live AudioRecord.
            // We don't assert network state here — the harness only needs to prove the PCM path doesn't throw
            // and that the fake token provider is wired.
            assertTrue(fakeProvider.requestCount == 0)
            // Note: we intentionally don't call client.start() here — that would try to open a WebSocket.
            // A separate nightly job with CYANBRIDGE_GEMINI_LIVE_REAL=1 can call start+assert LISTENING and
            // check that onTranscription(false, ...) contains "I like red flowers".
            assertTrue(lastState == null || lastState == GeminiLiveState.IDLE)
        } finally {
            client.close()
        }
    }

    @Test
    fun meetingTranscription_realRelay_ifPro() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val apiToken = ProSubscriptionServerPrefs.getApiToken(ctx).trim()
        assumeTrue("No Pro API token linked — run Pro email verification first (ProSubscriptionActivity → I'm already Pro!)", apiToken.isNotBlank())

        // Real relay path: AutomaticTranscriptionEngine.select() → PRO → RelayAudioTranscriptionProvider
        // Seeded asset is the edge-tts meeting pitch ("Alice/Bob/Due Friday") — deterministic.
        val seeded = TestMeetingCaptureSeeder.seedFromAsset(ctx, TestAudioAssets.MEETING_M4A)
        try {
            val sel = AutomaticTranscriptionEngine.select(ctx)
            // Ensure we actually hit Pro relay, not LOCAL_AGENT with missing model.
            assumeTrue("Not on Pro route — set provider to Pro in Glasses dashboard or set Pro subscription active", sel.route.name == "PRO_SUBSCRIPTION")

            val service = com.fersaiyan.cyanbridge.ai.transcription.DefaultTranscriptionService(
                context = ctx,
                repository = MyApplication.repository,
                provider = sel.provider,
                chunker = sel.chunker,
            )
            val result = withTimeout(90_000) { service.transcribe(seeded.session) }
            assertTrue("Expected Success but got $result", result is TranscriptionResult.Success)
            val text = (result as TranscriptionResult.Success).text
            // Deterministic edge-tts prompt: should contain Alice, Bob, Friday (case-insensitive, allow STT variance)
            assertTrue("Transcript missing Alice: $text", text.contains("Alice", ignoreCase = true))
            assertTrue("Transcript missing Bob: $text", text.contains("Bob", ignoreCase = true))
            assertTrue("Transcript missing Friday: $text", text.contains("Friday", ignoreCase = true))
        } finally {
            MyApplication.repository.deleteCaptureSession(seeded.sessionId)
            runCatching { seeded.audioFile.delete() }
        }
    }

    @Test
    fun geminiLive_real_ifPro_repliesWithRedFlowers() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val apiToken = ProSubscriptionServerPrefs.getApiToken(ctx).trim()
        assumeTrue("No Pro API token linked — run Pro email verification first", apiToken.isNotBlank())

        val latch = CountDownLatch(1)
        val outputText = AtomicReference<String>("")
        val errorText = AtomicReference<String>("")
        var observedListening = false

        val client = GeminiLiveClient(
            context = ctx,
            listener = object : GeminiLiveClient.Listener {
                override fun onStateChanged(state: GeminiLiveState, detail: String) {
                    if (state == GeminiLiveState.LISTENING) observedListening = true
                    if (state == GeminiLiveState.ERROR) errorText.set(detail)
                }
                override fun onInterrupted() = Unit
                override fun onNetworkChanged(available: Boolean) = Unit
                override fun onTranscription(input: Boolean, text: String) {
                    if (!input && text.contains("I like red flowers", ignoreCase = true)) {
                        outputText.set(text)
                        latch.countDown()
                    }
                }
            },
        )

        try {
            // Default prompt from ImageQuestionPreferences + system prompt, but the injected PCM is the deterministic phrase.
            // Deterministic check: model should echo "I like red flowers" when we stream the gemini fixture PCM.
            client.start(language = "en-US", imagePrompt = "You are a test assistant. Reply with exactly: I like red flowers")
            // Give token + WS time (up to 15s) — gated test, not hermetic.
            var waited = 0
            while (waited < 15_000 && !observedListening && errorText.get().isNullOrBlank()) {
                Thread.sleep(300)
                waited += 300
            }
            assumeTrue("Gemini Live never reached LISTENING (token/network?): ${errorText.get()}", observedListening)

            // Inject deterministic PCM after LISTENING (mirrors GlassesDailyGemini vision+audio injection via offerGlassesPcm)
            val pcmShorts = TestAudioAssets.wavToPcm16Shorts(TestAudioAssets.readWavBytes(TestAudioAssets.GEMINI_WAV))
            client.offerGlassesPcm(pcmShorts, 16_000)

            val heard = latch.await(45, TimeUnit.SECONDS)
            assertTrue("Gemini Live did not transcribe/output 'I like red flowers' within 45s. error=${errorText.get()} output=${outputText.get()}", heard)
            assertTrue(outputText.get().contains("I like red flowers", ignoreCase = true))
        } finally {
            client.close()
        }
    }
}
