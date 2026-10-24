package com.fersaiyan.cyanbridge.audio

import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class TtsSpeakerTest {
    private class FakeEngine(val callbacks: TtsEngineCallbacks) : TtsEngine {
        val spoken = mutableListOf<Pair<String, String>>()
        var accept = true
        var stops = 0
        var shutdowns = 0
        var language: Locale? = null
        override fun setLanguage(locale: Locale): Int { language = locale; return TextToSpeech.LANG_AVAILABLE }
        override fun setSpeechRate(rate: Float) = Unit
        override fun setAudioAttributes(attributes: AudioAttributes): Int = TextToSpeech.SUCCESS
        override fun speak(text: String, queueMode: Int, params: Bundle?, utteranceId: String): Int {
            spoken += text to utteranceId
            return if (accept) TextToSpeech.SUCCESS else TextToSpeech.ERROR
        }
        override val isSpeaking: Boolean get() = false
        override fun stop() { stops++ }
        override fun shutdown() { shutdowns++ }
    }

    private lateinit var engine: FakeEngine
    private fun speaker(onInit: (TtsSpeaker, Boolean) -> Unit = { _, _ -> }) =
        TtsSpeaker(null, "TtsSpeakerTest", onInit) { callbacks -> FakeEngine(callbacks).also { engine = it } }

    @Test
    fun pendingActionKeepsOnlyTheNewestUntilReady() {
        val tts = speaker { sp, ok -> if (ok) sp.setLanguage(Locale.US) }
        tts.whenReady { it.speak("first", "a") }
        tts.whenReady { it.speak("second", "b") }
        assertTrue(engine.spoken.isEmpty())

        engine.callbacks.onInit(true)
        assertEquals(Locale.US, engine.language)
        assertEquals(listOf("second" to "b"), engine.spoken)
        tts.whenReady { it.speak("now", "c") }
        assertEquals("now", engine.spoken.last().first)
    }

    @Test
    fun failedInitDropsThePendingAction() {
        val tts = speaker()
        tts.whenReady { it.speak("never", "a") }
        engine.callbacks.onInit(false)
        assertFalse(tts.isReady)
        assertTrue(tts.isInitialized)
        tts.whenReady { it.speak("still never", "b") }
        assertTrue(engine.spoken.isEmpty())
    }

    @Test
    fun speakAndAwaitFinishesOnDoneErrorOrStop() = runTest {
        val tts = speaker()
        engine.callbacks.onInit(true)
        listOf<(String) -> Unit>(
            { engine.callbacks.onDone(it) },
            { engine.callbacks.onError(it, 5) },
            { engine.callbacks.onStop(it, true) },
        ).zip(listOf(TtsUtteranceOutcome.DONE, TtsUtteranceOutcome.ERROR, TtsUtteranceOutcome.STOPPED))
            .forEachIndexed { index, (finish, expected) ->
                val id = "u$index"
                val result = async { tts.speakAndAwait("hello", id, readyTimeoutMs = 0) }
                runCurrent()
                engine.callbacks.onDone("other")
                assertFalse(result.isCompleted)
                finish(id)
                assertEquals(expected, result.await())
            }
    }

    @Test
    fun refusedUtteranceAndUnreadyEngineReturnImmediately() = runTest {
        val tts = speaker()
        assertEquals(TtsUtteranceOutcome.NOT_READY, tts.speakAndAwait("x", "a", readyTimeoutMs = 3_000))
        engine.callbacks.onInit(true)
        engine.accept = false
        var outcome: TtsUtteranceOutcome? = null
        assertFalse(tts.speak("x", "b") { outcome = it })
        assertEquals(TtsUtteranceOutcome.NOT_QUEUED, outcome)
        assertEquals(TtsUtteranceOutcome.NOT_QUEUED, tts.speakAndAwait("x", "c", readyTimeoutMs = 0))
    }

    @Test
    fun timeoutLeavesSpeechPlayingButCancellationStopsIt() = runTest {
        val tts = speaker()
        engine.callbacks.onInit(true)
        val timed = async { tts.speakAndAwait("long", "a", readyTimeoutMs = 0, timeoutMs = 15_000) }
        advanceTimeBy(15_001)
        assertEquals(TtsUtteranceOutcome.TIMED_OUT, timed.await())
        assertEquals(0, engine.stops)

        val cancelled = async { tts.speakAndAwait("long", "b", readyTimeoutMs = 0) }
        runCurrent()
        cancelled.cancel()
        runCurrent()
        assertEquals(1, engine.stops)
    }

    @Test
    fun shutdownReleasesWaitersAndIsIdempotent() {
        val tts = speaker()
        engine.callbacks.onInit(true)
        var outcome: TtsUtteranceOutcome? = null
        tts.speak("x", "a") { outcome = it }
        tts.shutdown()
        tts.shutdown()
        assertEquals(TtsUtteranceOutcome.STOPPED, outcome)
        assertEquals(1, engine.shutdowns)
        assertFalse(tts.speak("after", "b"))
    }
}
