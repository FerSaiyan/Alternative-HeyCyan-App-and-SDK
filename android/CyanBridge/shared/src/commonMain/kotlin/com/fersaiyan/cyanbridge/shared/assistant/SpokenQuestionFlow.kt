package com.fersaiyan.cyanbridge.shared.assistant

import com.fersaiyan.cyanbridge.shared.platform.PlatformLogger
import com.fersaiyan.cyanbridge.shared.platform.platformElapsedRealtimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

/** A question as the glasses microphone captured it. */
data class CapturedQuestion(
    /** On-device transcript, when one was made. */
    val text: String?,
    val heardSpeech: Boolean,
    /** The recording, kept for a model that hears audio (the caller deletes it). */
    val audioPath: String? = null,
    /** Why the microphone could not record; spoken as is. */
    val error: String? = null,
)

/** The microphone of a spoken question (Android: QuestionMicrophone over the glasses' Bluetooth route). */
interface SpokenQuestionMicrophone {
    /** Records a question for a model that hears audio (no transcript); speech must start within [onsetTimeoutMs]. */
    suspend fun recordQuestion(onsetTimeoutMs: Long): CapturedQuestion

    fun deleteRecording(audioPath: String)
}

/** Thrown by [SpokenQuestionBrain] when the open conversation cannot be continued; a new one starts. */
open class SpokenSessionUnavailableException(message: String?) : Exception(message)

/** Where a model turn goes in the open conversation ([SpokenConversation.SESSION_KEY]). */
data class SpokenSessionTurn(val key: String, val continueConversation: Boolean, val note: String?)

/** The model that hears the recording, decides the route and answers (Android: the selected provider). */
interface SpokenQuestionBrain {
    /** Name in logs and test events (Android: the provider type). */
    val name: String

    /** True when the model keeps the conversation open between turns (Android: local LiteRT sessions). */
    val keepsConversation: Boolean

    /** The decision turn: the recording with the route-tag instructions; [onToken] gets the streamed reply. */
    suspend fun decide(audioPath: String, session: SpokenSessionTurn?, onToken: (String) -> Unit): String

    /** Stops the decision generation once its route is known and no conversation needs the rest. */
    fun stopGeneration()

    /** A self-contained answer: the recording again, with [images] or [extraInstruction] (web results). */
    suspend fun answerRecording(audioPath: String, images: List<String>, extraInstruction: String?): String

    /** An answer in the open conversation, which already holds the recording. */
    suspend fun answerInSession(text: String, images: List<String>, note: String?): String

    /** Tokens in the open conversation after the last turn (test events), when known. */
    fun sessionTokens(): Int?
}

/** What a web search found, as far as the answer and the test events need it. */
data class WebSearchOutcome(
    /** Engine name, for the test events. */
    val engine: String,
    /** Google writes its summary in the user's language (other engines mostly in English). */
    val fromGoogle: Boolean,
    val answer: String?,
    val infoBox: String?,
    val snippets: List<String>,
    /** Why nothing could be read (name, for the test events), or null when the page loaded. */
    val failure: String?,
    /** The results page did not load at all. */
    val offline: Boolean,
    val blocked: Boolean?,
    val elapsedMs: Long?,
)

/** What the routes do outside the model (Android: glasses photo, WebView search, Local Agent, Gemini Live). */
interface SpokenQuestionActions {
    /** Asks the glasses for a photo and returns its file once delivered, or null when none comes. */
    suspend fun takePhoto(): String?

    fun deletePhoto(path: String)

    /** Name of the engine [searchWeb] uses (test events). */
    val webSearchEngine: String

    /** Null when the search could not run at all. iOS has no implementation yet. */
    suspend fun searchWeb(query: String): WebSearchOutcome?

    fun startPhoneTask(goal: String)

    fun startLive(prompt: String)
}

/** Short cues queued after what is already playing. */
enum class SpokenCue { LOOKING, CHECKING_WEB }

/** Speech and the fixed sentences of the spoken flow, in the app language. */
interface SpokenQuestionSpeech {
    fun queueCue(cue: SpokenCue)

    /** Interrupts what is playing with a fixed sentence or the microphone's error. */
    fun say(text: String)

    /** Interrupts what is playing with the short "couldn't process that question" sentence. */
    fun sayQuestionFailed()

    /** Speaks an answer and returns once it was spoken. */
    suspend fun speakAnswer(text: String)

    /** The user's language is English (the engines' summaries are mostly English). */
    fun englishSpeaker(): Boolean

    fun webResultsInstruction(answer: String?, snippets: List<String>, infoBox: String?): String

    fun webSearchEmpty(): String

    fun webSearchFailed(offline: Boolean): String
}

/** Flow events for instrumentation ("spoken_decision", "web_search", "spoken_turn", "reply"). */
fun interface AssistantEvents {
    fun emit(event: String, detail: String)

    companion object {
        val NONE = AssistantEvents { _, _ -> }
    }
}

data class SpokenQuestionSettings(
    /** Speech must start this soon after the cue. */
    val questionOnsetMs: Long = 5_000L,
    /** How long the glasses listen for a follow-up after a spoken answer (speech must start within it). */
    val followUpWindowMs: Long = 5_000L,
    /** Without a photo by then the question is answered without it. */
    val photoTimeoutMs: Long = 45_000L,
    /** Upper bound for speaking one answer. */
    val answerSpeechTimeoutMs: Long = 180_000L,
)

/** After an answer, a follow-up is heard only where the conversation is kept, and only after these routes. */
object SpokenFollowUpPolicy {
    val ROUTES = setOf(SpokenRoute.ANSWER, SpokenRoute.CAMERA, SpokenRoute.SEARCH)

    fun listensForFollowUp(keepsConversation: Boolean, route: SpokenRoute): Boolean =
        keepsConversation && route in ROUTES
}

/**
 * A spoken question with the decision model. The recording is heard once; the reply starts with a
 * route tag ([SpokenDecisionParser]) and the flow acts as soon as it is written: answer → spoken;
 * camera → photo, then answered; search → web lookup; phone → the phone agent; live → a live
 * conversation; ignore → silence. Where the model keeps the conversation open ([SpokenConversation]),
 * the photo and the web results are added as turns, so the recording is not encoded again, and a
 * follow-up heard right after the answer (or a new question within a few minutes) continues it.
 */
class SpokenQuestionFlow(
    /** Runs the decision generation; it outlives a cancelled question like Android's lifecycle scope. */
    private val generationScope: CoroutineScope,
    private val generationContext: CoroutineContext,
    private val microphone: SpokenQuestionMicrophone,
    private val brain: SpokenQuestionBrain,
    private val actions: SpokenQuestionActions,
    private val speech: SpokenQuestionSpeech,
    private val events: AssistantEvents = AssistantEvents.NONE,
    private val settings: SpokenQuestionSettings = SpokenQuestionSettings(),
    /** Decision timing and the conversation window ([SpokenConversation.CONTINUE_WITHIN_MS]). */
    private val clock: () -> Long = ::platformElapsedRealtimeMillis,
) {
    /** Records the question and runs its turns; failures are spoken, never thrown (except cancellation). */
    suspend fun run() {
        var audioPath: String? = null
        try {
            val captured = microphone.recordQuestion(settings.questionOnsetMs)
            audioPath = captured.audioPath
            when {
                captured.error != null -> {
                    speech.say(captured.error)
                    return
                }
                !captured.heardSpeech || audioPath == null -> {
                    speech.say(NOT_HEARD)
                    return
                }
            }
            val sessions = brain.keepsConversation
            var continueSession = sessions && SpokenConversation.canContinue(clock())
            if (!continueSession) SpokenConversation.reset()
            var turn = 1
            while (true) {
                val route = runTurn(audioPath!!, continueSession.takeIf { sessions }, turn)
                microphone.deleteRecording(audioPath)
                audioPath = null
                if (route == SpokenRoute.IGNORE) SpokenConversation.reset() else SpokenConversation.turnEnded(clock())
                if (!SpokenFollowUpPolicy.listensForFollowUp(sessions, route)) break
                val followUp = microphone.recordQuestion(settings.followUpWindowMs)
                audioPath = followUp.audioPath
                if (followUp.error != null || !followUp.heardSpeech || audioPath == null) break
                continueSession = true
                turn++
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            PlatformLogger.e(TAG, "Spoken decision question failed", error)
            SpokenConversation.reset()
            events.emit("reply", "provider=${brain.name} images=0 audio=true text=$MODEL_ERROR_PREFIX: ${error.message}")
            speech.sayQuestionFailed()
        } finally {
            audioPath?.let(microphone::deleteRecording)
        }
    }

    /**
     * One turn: decision, the route's action and the spoken reply. [continueSession] is null when the
     * model keeps no conversation, else whether to continue the open one.
     */
    private suspend fun runTurn(audioPath: String, continueSession: Boolean?, turn: Int): SpokenRoute {
        var photoPath: String? = null
        try {
            val started = clock()
            var continued = continueSession == true
            // The flow acts as soon as the route tag (and payload line) is written. Without a conversation
            // the rest of the reply is stopped; in one it is left to finish (a cancelled conversation cannot
            // be continued) while the photo or web search runs, and is awaited before the next turn.
            fun startDecision(cont: Boolean?): Pair<Deferred<String>, CompletableDeferred<SpokenDecision>> {
                val streamed = StringBuilder()
                val early = CompletableDeferred<SpokenDecision>()
                val job = generationScope.async(generationContext) {
                    brain.decide(
                        audioPath = audioPath,
                        session = cont?.let {
                            SpokenSessionTurn(SpokenConversation.SESSION_KEY, it, note = if (it) SpokenConversation.takeNote() else null)
                        },
                        onToken = { delta ->
                            streamed.append(delta)
                            if (!early.isCompleted && SpokenDecisionParser.isComplete(streamed.toString())) {
                                early.complete(SpokenDecisionParser.parse(streamed.toString()))
                                if (cont == null) brain.stopGeneration()
                            }
                        },
                    )
                }
                return job to early
            }
            suspend fun awaitDecision(job: Deferred<String>, early: CompletableDeferred<SpokenDecision>): SpokenDecision =
                select {
                    early.onAwait { it }
                    job.onAwait { reply ->
                        if (early.isCompleted) {
                            early.await()
                        } else {
                            // A failed model call must not be read out as an answer.
                            if (reply.startsWith(MODEL_ERROR_PREFIX)) throw IllegalStateException(reply)
                            SpokenDecisionParser.parse(reply)
                        }
                    }
                }
            var (replyJob, early) = startDecision(continueSession)
            val decision = try {
                awaitDecision(replyJob, early)
            } catch (gone: SpokenSessionUnavailableException) {
                // Another request used the engine, or the conversation is full: start a new one.
                PlatformLogger.i(TAG, "Spoken conversation not continued: ${gone.message}")
                continued = false
                SpokenConversation.pendingNote = null
                startDecision(false).also { replyJob = it.first; early = it.second }.let { awaitDecision(it.first, it.second) }
            }
            // The full reply (answers stream to the end; other routes finish in the background).
            suspend fun fullReply(): String = runCatching { replyJob.await() }.getOrDefault("")
            val decisionMs = clock() - started
            PlatformLogger.i(
                TAG,
                "Spoken decision route=${decision.route} tagged=${decision.tagged} provider=${brain.name} " +
                    "turn=$turn continued=$continued ms=$decisionMs",
            )
            events.emit(
                "spoken_decision",
                "route=${decision.route} tagged=${decision.tagged} ms=$decisionMs payload=${decision.payload.take(120)}",
            )

            fun checkedAnswer(answer: String): String {
                // Same as the decision turn: a model failure is reported, never read out.
                if (answer.startsWith(MODEL_ERROR_PREFIX)) throw IllegalStateException(answer)
                return answer
            }
            // Self-contained answer: the recording again, with the photo or web text.
            suspend fun answerRecording(images: List<String>, extra: String? = null): String =
                checkedAnswer(brain.answerRecording(audioPath, images, extra))
            // Answer in the open conversation, which already holds the recording; null when it is gone or
            // the model asks for the camera again.
            suspend fun answerInSession(text: String, images: List<String> = emptyList(), note: String? = null): String? {
                if (continueSession == null) return null
                fullReply()
                val answer = try {
                    checkedAnswer(brain.answerInSession(text, images, note))
                } catch (gone: SpokenSessionUnavailableException) {
                    PlatformLogger.i(TAG, "Spoken answer outside the conversation: ${gone.message}")
                    return null
                }
                // Every reply starts with a route tag, so the answer may come as "[camera] Those are yellow
                // socks."; only a bare tag (asking for the camera again) is unusable.
                return SpokenDecisionParser.clean(answer).takeIf { it.isNotBlank() }
                    ?: null.also { PlatformLogger.i(TAG, "Answer in the conversation not usable: ${answer.take(80)}") }
            }

            val spokenReply: String? = when (decision.route) {
                SpokenRoute.ANSWER -> decision.payload.ifBlank { SpokenDecisionParser.clean(fullReply()) }
                SpokenRoute.CAMERA -> {
                    speech.queueCue(SpokenCue.LOOKING)
                    photoPath = withTimeoutOrNull(settings.photoTimeoutMs) { actions.takePhoto() }
                    if (photoPath == null) PlatformLogger.w(TAG, "Spoken decision: no photo from the glasses; answering without it")
                    photoPath?.let { answerInSession("", listOf(it), note = PHOTO_NOTE) }
                        ?: answerRecording(listOfNotNull(photoPath)).also { SpokenConversation.pendingNote = "You told them: $it" }
                }
                SpokenRoute.SEARCH -> {
                    speech.queueCue(SpokenCue.CHECKING_WEB)
                    val engine = actions.webSearchEngine
                    val web = decision.payload.takeIf { it.isNotBlank() }?.let { query -> actions.searchWeb(query) }
                    events.emit(
                        "web_search",
                        "engine=$engine assist=${web?.answer != null} infoBox=${web?.infoBox != null} " +
                            "snippets=${web?.snippets?.size ?: 0} failure=${web?.failure} blocked=${web?.blocked} ms=${web?.elapsedMs}",
                    )
                    val englishSpeaker = speech.englishSpeaker()
                    suspend fun answerFrom(webText: String): String =
                        answerInSession(webText) ?: answerRecording(emptyList(), webText).also {
                            SpokenConversation.pendingNote = "You told them: $it"
                        }
                    when (val plan = WebAnswerPolicy.plan(web, englishSpeaker)) {
                        is WebAnswerPlan.FromModel -> answerFrom(speech.webResultsInstruction(plan.answer, plan.snippets, plan.infoBox))
                        is WebAnswerPlan.ReadOut -> plan.answer.also { SpokenConversation.pendingNote = "You answered from a web search: $it" }
                        is WebAnswerPlan.Fixed ->
                            (if (plan.nothingFound) speech.webSearchEmpty() else speech.webSearchFailed(plan.offline))
                                .also { SpokenConversation.pendingNote = "You told them: $it" }
                    }
                }
                SpokenRoute.PHONE -> {
                    actions.startPhoneTask(decision.payload)
                    null
                }
                SpokenRoute.LIVE -> {
                    actions.startLive(LIVE_PROMPT)
                    null
                }
                SpokenRoute.IGNORE -> null
            }
            // The next turn uses the conversation only after this reply has finished.
            fullReply()
            val sessionTokens = if (continueSession != null) brain.sessionTokens() else null
            events.emit("spoken_turn", "n=$turn continued=$continued sessionTokens=$sessionTokens route=${decision.route}")
            events.emit(
                "reply",
                "provider=${brain.name} images=${if (photoPath != null) 1 else 0} audio=true " +
                    "text=${spokenReply?.let(SpokenDecisionParser::clean) ?: "[${decision.route.tag}]"}",
            )
            if (!spokenReply.isNullOrBlank()) {
                withTimeoutOrNull(settings.answerSpeechTimeoutMs) { speech.speakAnswer(SpokenDecisionParser.clean(spokenReply)) }
            }
            return decision.route
        } finally {
            photoPath?.let(actions::deletePhoto)
        }
    }

    companion object {
        private const val TAG = "AIHijack"

        /** Replies starting with this are model failures (Android's local provider). */
        const val MODEL_ERROR_PREFIX = "Local Models error"
        const val NOT_HEARD = "I couldn't hear a voice question. Please try again."

        /** Text before the photo turn in the open conversation (the question is the earlier recording). */
        const val PHOTO_NOTE = "Here is the photo from the glasses camera. Answer their question now, starting with [answer]."
        const val LIVE_PROMPT = "The user asked to start a live SpokenConversation. Greet them briefly and listen."
    }
}
