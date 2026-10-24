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
class TtsSpeechStreamTest {
    private class QueueEngine(val callbacks: TtsEngineCallbacks) : TtsEngine {
        val queued = mutableListOf<Triple<String, Int, String>>()
        var stops = 0
        override fun setLanguage(locale: Locale) = TextToSpeech.LANG_AVAILABLE
        override fun setSpeechRate(rate: Float) = Unit
        override fun setAudioAttributes(attributes: AudioAttributes) = TextToSpeech.SUCCESS
        override fun speak(text: String, queueMode: Int, params: Bundle?, utteranceId: String): Int {
            queued += Triple(text, queueMode, utteranceId)
            return TextToSpeech.SUCCESS
        }
        override val isSpeaking = false
        override fun stop() { stops++ }
        override fun shutdown() = Unit
    }

    @Test
    fun phrasesAreQueuedBackToBackAfterTheFirstChunkHookAndFinishWaitsForTheLast() = runTest {
        lateinit var engine: QueueEngine
        val tts = TtsSpeaker(null, "TtsSpeechStreamTest") { QueueEngine(it).also { created -> engine = created } }
        engine.callbacks.onInit(true)
        val events = mutableListOf<String>()
        val stream = TtsSpeechStream(tts, backgroundScope, beforeFirstChunk = { events += "before" }, utteranceIdPrefix = "s")

        stream.append("Hello there, friend. ")
        stream.append("This is a longer second sentence that keeps going on. ")
        runCurrent()
        val finishing = async { stream.finish() }
        runCurrent()
        assertEquals(listOf("before"), events)
        assertTrue(engine.queued.size >= 2)
        assertTrue(engine.queued.all { it.second == TextToSpeech.QUEUE_ADD })
        assertEquals(listOf("s_0", "s_1"), engine.queued.take(2).map { it.third })
        assertFalse(finishing.isCompleted)

        engine.queued.forEach { engine.callbacks.onDone(it.third) }
        finishing.await()
    }

    @Test
    fun idlePauseFlushesAShortFirstClause() = runTest {
        lateinit var engine: QueueEngine
        val tts = TtsSpeaker(null, "TtsSpeechStreamTest") { QueueEngine(it).also { created -> engine = created } }
        engine.callbacks.onInit(true)
        val stream = TtsSpeechStream(tts, backgroundScope)
        stream.append("Let me look at that")
        runCurrent()
        assertTrue(engine.queued.isEmpty())
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf("Let me look at that"), engine.queued.map { it.first })
        stream.cancel()
        assertEquals(1, engine.stops)
    }
}
