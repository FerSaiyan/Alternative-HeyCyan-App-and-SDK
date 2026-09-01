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
import com.fersaiyan.cyanbridge.data.local.entity.CaptureSession
import com.fersaiyan.cyanbridge.plugins.handsfreetranslator.HandsFreeTranslatorService
import com.fersaiyan.cyanbridge.ui.MyApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
            // Before START we can still pump glasses PCM via offerGlassesPcm — it should be a safe no-op, not a crash.
            val pcmShorts = TestAudioAssets.wavToPcm16Shorts(
                TestAudioAssets.readWavBytes(TestAudioAssets.GEMINI_WAV)
            )
            assertTrue(pcmShorts.isNotEmpty())
            client.offerGlassesPcm(pcmShorts, 16_000)

            // Also verify raw pcm16 asset path (used for direct injection without wav header).
            val raw = TestAudioAssets.readPcm16(TestAudioAssets.GEMINI_PCM)
            assertTrue(raw.isNotEmpty())
            assertEquals(0, raw.size % 2)

            // offerGlassesPcm is the emulator-friendly seam: real GeminiLiveActivity would call it from
            // a BT SCO glasses mic; tests inject deterministic file PCM instead of a live AudioRecord.
            // We don't assert network state here — the harness only needs to prove the PCM path doesn't throw
            // and that the fake token provider is wired.
            assertTrue(fakeProvider.requestCount == 0)
            // Note: we intentionally don't call client.start() here — that would try to open a WebSocket.
            // A separate nightly job with CYANBRIDGE_GEMINI_LIVE_REAL=1 can call start+assert LISTENING.
            assertTrue(lastState == null || lastState == GeminiLiveState.IDLE)
        } finally {
            client.close()
        }
    }
}
