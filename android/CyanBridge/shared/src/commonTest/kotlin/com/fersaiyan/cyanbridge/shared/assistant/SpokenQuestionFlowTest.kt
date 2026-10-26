package com.fersaiyan.cyanbridge.shared.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpokenQuestionFlowTest {
    private class Fakes(keepsConversation: Boolean) {
        val log = mutableListOf<String>()
        val heard = ArrayDeque<CapturedQuestion>()
        val decisions = ArrayDeque<String>()
        var sessionGoneOnce = false
        var web: WebSearchOutcome? = null
        var photo: String? = "photo.jpg"

        val microphone = object : SpokenQuestionMicrophone {
            override suspend fun recordQuestion(onsetTimeoutMs: Long) =
                heard.removeFirstOrNull() ?: CapturedQuestion(null, heardSpeech = false)
            override fun deleteRecording(audioPath: String) { log += "delete $audioPath" }
        }
        val brain = object : SpokenQuestionBrain {
            override val name = "LOCAL_AGENT"
            override val keepsConversation = keepsConversation
            override suspend fun decide(audioPath: String, session: SpokenSessionTurn?, onToken: (String) -> Unit): String {
                log += "decide $audioPath continue=${session?.continueConversation} note=${session?.note}"
                if (sessionGoneOnce && session?.continueConversation == true) {
                    sessionGoneOnce = false
                    throw SpokenSessionUnavailableException("engine busy")
                }
                val reply = decisions.removeFirst()
                reply.chunked(3).forEach(onToken)
                return reply
            }
            override fun stopGeneration() { log += "stop generation" }
            override suspend fun answerRecording(audioPath: String, images: List<String>, extraInstruction: String?) =
                "From the recording $images".also { log += "answer recording $images $extraInstruction" }
            override suspend fun answerInSession(text: String, images: List<String>, note: String?) =
                "[answer] In session.".also { log += "answer in session '$text' $images $note" }
            override fun sessionTokens(): Int? = 42
        }
        val actions = object : SpokenQuestionActions {
            override suspend fun takePhoto(): String? = photo
            override fun deletePhoto(path: String) { log += "delete photo $path" }
            override val webSearchEngine = "GOOGLE"
            override suspend fun searchWeb(query: String): WebSearchOutcome? = web.also { log += "search $query" }
            override fun startPhoneTask(goal: String) { log += "phone $goal" }
            override fun startLive(prompt: String) { log += "live" }
        }
        val speech = object : SpokenQuestionSpeech {
            override fun queueCue(cue: SpokenCue) { log += "cue $cue" }
            override fun say(text: String) { log += "say $text" }
            override fun sayQuestionFailed() { log += "say failed" }
            override suspend fun speakAnswer(text: String) { log += "speak $text" }
            override fun englishSpeaker() = false
            override fun webResultsInstruction(answer: String?, snippets: List<String>, infoBox: String?) = "web:$answer/$snippets/$infoBox"
            override fun webSearchEmpty() = "nothing found"
            override fun webSearchFailed(offline: Boolean) = "search failed offline=$offline"
        }
        val events = mutableListOf<Pair<String, String>>()

        fun TestScope.flow() = SpokenQuestionFlow(
            // Like Android's lifecycle scope: a failed generation does not cancel the question's parent.
            generationScope = CoroutineScope(coroutineContext + SupervisorJob()),
            generationContext = EmptyCoroutineContext,
            microphone = microphone,
            brain = brain,
            actions = actions,
            speech = speech,
            events = { event, detail -> events += event to detail },
            clock = { testScheduler.currentTime },
        )

        fun question(name: String) = CapturedQuestion(null, heardSpeech = true, audioPath = name)
    }

    @BeforeTest @AfterTest
    fun resetConversation() = SpokenConversation.reset()

    @Test
    fun anAnswerIsSpokenAndAFollowUpContinuesTheConversation() = runTest {
        val fakes = Fakes(keepsConversation = true).apply {
            heard += question("q1.wav")
            heard += question("q2.wav")
            decisions += "[answer] Paris."
            decisions += "[answer] About two million."
        }
        with(fakes) { flow().run() }

        val log = fakes.log
        assertEquals(
            listOf("decide q1.wav continue=false note=null", "speak Paris.", "delete q1.wav",
                "decide q2.wav continue=true note=null", "speak About two million.", "delete q2.wav"),
            log,
        )
        assertTrue(fakes.events.any { it == "spoken_turn" to "n=2 continued=true sessionTokens=42 route=ANSWER" })
        assertTrue(SpokenConversation.canContinue())
    }

    @Test
    fun withoutAConversationTheCameraAnswerUsesTheRecordingAndNoFollowUpIsHeard() = runTest {
        val fakes = Fakes(keepsConversation = false).apply {
            heard += question("q1.wav")
            heard += question("never.wav")
            decisions += "[camera]"
        }
        with(fakes) { flow().run() }

        assertEquals(
            listOf("decide q1.wav continue=null note=null", "stop generation", "cue LOOKING",
                "answer recording [photo.jpg] null", "speak From the recording [photo.jpg]", "delete photo photo.jpg", "delete q1.wav"),
            fakes.log,
        )
        assertEquals("You told them: From the recording [photo.jpg]", SpokenConversation.pendingNote)
        assertTrue(fakes.events.any { it == "reply" to "provider=LOCAL_AGENT images=1 audio=true text=From the recording [photo.jpg]" })
    }

    @Test
    fun aSearchAnswersInTheConversationOrSaysTheFixedSentence() = runTest {
        val infoBox = WebSearchOutcome("GOOGLE", true, answer = "Sunny", infoBox = "22 degrees", snippets = listOf("a", "b", "c"),
            failure = null, offline = false, blocked = false, elapsedMs = 900)
        val fakes = Fakes(keepsConversation = true).apply {
            heard += question("q1.wav")
            decisions += "[search] weather today\n"
            web = infoBox
        }
        with(fakes) { flow().run() }
        assertTrue("answer in session 'web:null/[a, b]/22 degrees' [] null" in fakes.log)
        assertTrue("speak In session." in fakes.log)

        SpokenConversation.reset()
        val offline = Fakes(keepsConversation = true).apply {
            heard += question("q2.wav")
            decisions += "[search] weather today\n"
            web = WebSearchOutcome("GOOGLE", true, null, null, emptyList(), failure = "NETWORK", offline = true, blocked = false, elapsedMs = 10)
        }
        with(offline) { flow().run() }
        assertTrue("speak search failed offline=true" in offline.log)
        assertEquals("You told them: search failed offline=true", SpokenConversation.pendingNote)
    }

    @Test
    fun failuresAndSilenceAreSpokenNotRead() = runTest {
        val failed = Fakes(keepsConversation = true).apply {
            heard += question("q1.wav")
            decisions += "Local Models error: out of memory"
        }
        with(failed) { flow().run() }
        assertTrue("say failed" in failed.log)
        assertTrue(failed.log.none { it.startsWith("speak") })
        assertTrue("delete q1.wav" in failed.log)
        assertNull(SpokenConversation.pendingNote)

        val silent = Fakes(keepsConversation = true)
        with(silent) { flow().run() }
        assertEquals(listOf("say ${SpokenQuestionFlow.NOT_HEARD}"), silent.log)

        val micError = Fakes(keepsConversation = true).apply { heard += CapturedQuestion(null, false, error = "The microphone is busy") }
        with(micError) { flow().run() }
        assertEquals(listOf("say The microphone is busy"), micError.log)
    }

    @Test
    fun aConversationThatCannotContinueStartsANewOne() = runTest {
        SpokenConversation.turnEnded()
        SpokenConversation.pendingNote = "You told them: earlier"
        val fakes = Fakes(keepsConversation = true).apply {
            heard += question("q1.wav")
            decisions += "[ignore]"
            sessionGoneOnce = true
        }
        with(fakes) { flow().run() }

        assertEquals(
            listOf("decide q1.wav continue=true note=You told them: earlier", "decide q1.wav continue=false note=null", "delete q1.wav"),
            fakes.log,
        )
        // An ignored question ends the conversation.
        assertTrue(!SpokenConversation.canContinue())
    }
}
