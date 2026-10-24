package com.fersaiyan.cyanbridge.shared.assistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GlassesAssistantTest {
    private class Recorder {
        val events = mutableListOf<String>()
        val saved = mutableListOf<Triple<String, String, Boolean>>()
        var heard: suspend () -> String? = { "what is this" }
        var reply: suspend (String) -> String = { "an answer" }
    }

    private fun TestScope.assistant(recorder: Recorder): GlassesAssistant {
        val voice = object : AssistantVoice {
            override suspend fun listen(initialTimeoutMs: Long): String? = recorder.heard()
            override fun stopSpeaking() { recorder.events += "stop speaking" }
            override fun say(text: String) { recorder.events += "say $text" }
            override fun waitCue() = "wait"
            override fun questionCue() = "ask"
            override fun usePlaybackRoute() {}
            override fun releaseAudio() { recorder.events += "release audio" }
            override fun streamingSpeech(beforeFirstChunk: suspend () -> Unit) = object : AssistantSpeechStream {
                var opened = false
                override fun append(text: String) {
                    if (!opened) {
                        opened = true
                        backgroundScope.launchNow { beforeFirstChunk() }
                    }
                    recorder.events += "speak $text"
                }
                override suspend fun finish() { recorder.events += "speech done" }
                override fun cancel() { recorder.events += "speech cancelled" }
            }
        }
        val brain = object : AssistantBrain {
            override suspend fun preload() {}
            override suspend fun answerImage(image: ByteArray, question: String, onToken: (String) -> Unit) = recorder.reply(question)
            override suspend fun answerText(question: String, onToken: (String) -> Unit) = recorder.reply(question)
            override fun defaultImageQuestion() = "describe"
        }
        val output = object : AssistantOutput {
            override fun status(text: String) {}
            override suspend fun saveAnswer(question: String, answer: String, photo: ByteArray?) {
                recorder.saved += Triple(question, answer, photo != null)
            }
        }
        val hooks = object : AssistantGlassesHooks {
            override suspend fun beforeAnswer() { recorder.events += "glasses thinking" }
            override suspend fun beforeFirstChunk() { recorder.events += "glasses speaking" }
            override suspend fun afterAnswer() { recorder.events += "glasses stop" }
            override suspend fun onNoQuestion() { recorder.events += "glasses released" }
        }
        return GlassesAssistant(this, voice, brain, output, { hooks })
    }

    @Test
    fun voiceQuestionIsAnsweredSavedAndTheGlassesStopAfterwards() = runTest {
        val recorder = Recorder()
        assistant(recorder).askByVoice()
        advanceUntilIdle()

        assertEquals(listOf(Triple("what is this", "an answer", false)), recorder.saved)
        val order = recorder.events
        assertTrue(order.indexOf("glasses thinking") < order.indexOf("speak an answer"))
        assertTrue(order.indexOf("speech done") < order.indexOf("glasses stop"))
        assertTrue(order.indexOf("glasses stop") < order.indexOf("release audio"))
    }

    @Test
    fun silenceReleasesTheGlassesWithoutAnswering() = runTest {
        val recorder = Recorder().apply { heard = { null } }
        assistant(recorder).askByVoice()
        advanceUntilIdle()

        assertTrue(recorder.saved.isEmpty())
        assertTrue("glasses released" in recorder.events)
        assertTrue("glasses thinking" !in recorder.events)
    }

    @Test
    fun silentPhotoQuestionUsesTheDefaultQuestionAndAttachesThePhoto() = runTest {
        val recorder = Recorder().apply { heard = { null } }
        assistant(recorder).askAboutPhoto(byteArrayOf(1, 2, 3))
        advanceUntilIdle()

        assertEquals(listOf(Triple("📷 describe", "an answer", true)), recorder.saved)
    }

    @Test
    fun errorRepliesAreNotSavedButTheGlassesStillStop() = runTest {
        val recorder = Recorder().apply { reply = { "Error: relay down" } }
        assistant(recorder).askByVoice()
        advanceUntilIdle()

        assertTrue(recorder.saved.isEmpty())
        assertTrue("speech cancelled" in recorder.events)
        assertTrue("glasses stop" in recorder.events)
    }

    @Test
    fun aNewPhotoReplacesTheRunningQuestion() = runTest {
        val recorder = Recorder()
        val firstAnswer = CompletableDeferred<String>()
        recorder.reply = { question -> if (question == "what is this") firstAnswer.await() else "photo answer" }
        val assistant = assistant(recorder)
        assistant.askByVoice()
        advanceUntilIdle()

        recorder.heard = { "and this?" }
        assistant.askAboutPhoto(byteArrayOf(9))
        advanceUntilIdle()

        assertEquals(listOf(Triple("📷 and this?", "photo answer", true)), recorder.saved)
        // The replaced turn still left the glasses' AI state before the new one began.
        assertEquals(2, recorder.events.count { it == "glasses stop" })
        assertTrue(!assistant.isBusy)
    }
}

private fun CoroutineScope.launchNow(block: suspend () -> Unit) {
    launch(start = CoroutineStart.UNDISPATCHED) { block() }
}
