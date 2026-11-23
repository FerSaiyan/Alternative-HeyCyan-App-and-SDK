package com.fersaiyan.cyanbridge.ai.questions

import android.speech.tts.TextToSpeech
import android.util.Log
import com.fersaiyan.cyanbridge.ai.image.GlassesQuestionTestHooks
import com.fersaiyan.cyanbridge.ai.live.GeminiLiveOpeningImage
import com.fersaiyan.cyanbridge.ai.router.QuestionRoutingPrefs
import com.fersaiyan.cyanbridge.ai.search.WebSearchAssist
import com.fersaiyan.cyanbridge.ai.search.WebSearchEngine
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionDefaults
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.localagent.LocalAgentController
import com.fersaiyan.cyanbridge.localagent.memory.RagProfile
import com.fersaiyan.cyanbridge.localmodels.engine.SessionTurn
import com.fersaiyan.cyanbridge.localmodels.engine.SessionUnavailableException
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.localmodels.session.LocalChatSessionManager
import com.fersaiyan.cyanbridge.shared.ai.RelayTurn
import com.fersaiyan.cyanbridge.shared.assistant.LivePhoto
import com.fersaiyan.cyanbridge.shared.assistant.SpokenAnswerStream
import com.fersaiyan.cyanbridge.shared.assistant.SpokenConversation
import com.fersaiyan.cyanbridge.shared.assistant.SpokenCue
import com.fersaiyan.cyanbridge.shared.assistant.SpokenQuestionActions
import com.fersaiyan.cyanbridge.shared.assistant.SpokenQuestionBrain
import com.fersaiyan.cyanbridge.shared.assistant.SpokenQuestionSpeech
import com.fersaiyan.cyanbridge.shared.assistant.SpokenSessionTurn
import com.fersaiyan.cyanbridge.shared.assistant.SpokenSessionUnavailableException
import com.fersaiyan.cyanbridge.shared.assistant.WebSearchOutcome
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android's ports of the shared spoken-question flow: the chosen provider (local LiteRT sessions or
 * the Pro relay), the glasses photo, the WebView web search, the Local Agent, Gemini Live and the
 * phone's text-to-speech. Local answers start speaking while they are written. [photoQuestion]: the flow runs a
 * photo question (the photo button), whose phone tasks need the Local Agent to be set up.
 */
internal class AndroidSpokenQuestionPorts(
    private val env: QuestionEnvironment,
    private val provider: AgentProviderType,
    private val speaker: AssistantSpeaker,
    private val providers: AssistantProviderQueries,
    private val live: GeminiLiveQuestions,
    private val languageTag: () -> String,
    private val requestPhoto: () -> CompletableDeferred<String?>,
    private val startLocalAgent: (goal: String) -> Unit,
    private val photoQuestion: Boolean = false,
) : SpokenQuestionBrain, SpokenQuestionActions, SpokenQuestionSpeech {
    override val name: String = provider.toString()

    // Engine sessions keep the conversation (and its KV cache) between turns. The relay keeps none: for Pro the app keeps
    // it as text and the photo (RelayConversation) and resends both with each follow-up.
    override val keepsConversation: Boolean =
        provider == AgentProviderType.LOCAL_AGENT || provider == AgentProviderType.PRO_SUBSCRIPTION

    private val relay: Boolean get() = provider == AgentProviderType.PRO_SUBSCRIPTION

    private fun SpokenSessionTurn.engineTurn() = SessionTurn(key, continueConversation, note)

    override suspend fun decide(
        audioPath: String,
        images: List<String>,
        session: SpokenSessionTurn?,
        routeOnly: Boolean,
        onToken: (String) -> Unit,
    ): String =
        if (relay) {
            decideOnRelay(audioPath, images, session)
        } else {
            sessionCall {
                providers.query(
                    userPrompt = "",
                    providerType = provider,
                    ragProfile = RagProfile.NONE,
                    imagePaths = images,
                    audioPath = audioPath,
                    decisionRoutes = true,
                    session = session?.engineTurn(),
                    onToken = onToken,
                    photoNote = ImageQuestionDefaults.PHOTO_QUESTION_NOTE.takeIf { images.isNotEmpty() },
                    // LiteRT ends the reply there itself (no cancel), so the conversation stays usable.
                    maxTokens = ROUTE_ONLY_MAX_TOKENS.takeIf { routeOnly },
                )
            }
        }

    /**
     * A Pro decision turn: the route instructions in system_context (no clock, so the text stays stable), the time and
     * the photo note in turn_note, and for a follow-up the earlier turns as history plus the conversation's photo again
     * (with a note that it is the earlier photo, so [camera] still takes a new one).
     */
    private suspend fun decideOnRelay(audioPath: String, images: List<String>, session: SpokenSessionTurn?): String {
        val followUp = session?.continueConversation == true
        if (!followUp) RelayConversation.start(images.firstOrNull())
        val resent = RelayConversation.photo?.takeIf { followUp && images.isEmpty() && File(it).isFile }
        val history = RelayConversation.history().let { turns ->
            // A note the flow carries into the next turn ("You told them: …") is what was said last.
            session?.note?.takeIf { followUp && it.isNotBlank() }?.let { turns + RelayTurn(fromUser = false, text = it) } ?: turns
        }
        val reply = withContext(Dispatchers.IO) {
            providers.query(
                userPrompt = "",
                providerType = provider,
                ragProfile = RagProfile.NONE,
                imagePaths = images.ifEmpty { listOfNotNull(resent) },
                audioPath = audioPath,
                decisionRoutes = true,
                extraInstruction = RelayConversation.HEARD_INSTRUCTION,
                photoNote = when {
                    images.isNotEmpty() -> ImageQuestionDefaults.PHOTO_QUESTION_NOTE
                    resent != null -> ImageQuestionDefaults.PHOTO_RESENT_NOTE
                    else -> null
                },
                relayHistory = history,
            )
        }
        val (answer, heard) = RelayConversation.split(reply)
        RelayConversation.record(heard, answer)
        return answer
    }

    override fun stopGeneration() {
        env.scope.launch(Dispatchers.IO) { LocalModelsProvider().cancelGeneration() }
    }

    override suspend fun answerRecording(audioPath: String, images: List<String>, extraInstruction: String?): String =
        withContext(Dispatchers.IO) {
            providers.query(
                userPrompt = "",
                providerType = provider,
                ragProfile = RagProfile.NONE,
                imagePaths = images,
                audioPath = audioPath,
                extraInstruction = extraInstruction,
                reportReply = false,
                relayHistory = if (relay) RelayConversation.history(excludingCurrent = true) else emptyList(),
            ).also { answer ->
                if (relay) {
                    RelayConversation.replaceLastAnswer(answer)
                    // A [camera] reply's photo is what follow-ups are about.
                    images.firstOrNull()?.let { if (it != RelayConversation.photo) RelayConversation.adoptPhoto(it) }
                }
            }
        }

    override suspend fun answerInSession(text: String, images: List<String>, note: String?): String =
        // The relay keeps no conversation: the flow answers with the recording again (answerRecording, with history).
        if (relay) {
            throw SpokenSessionUnavailableException("The relay keeps no conversation")
        } else withContext(Dispatchers.IO) {
            sessionCall {
                providers.query(
                    userPrompt = text,
                    providerType = provider,
                    ragProfile = RagProfile.NONE,
                    imagePaths = images,
                    session = SessionTurn(SpokenConversation.SESSION_KEY, true, note),
                    reportReply = false,
                )
            }
        }

    /** The engine's "conversation gone" becomes the shared flow's signal to start a new one. */
    private inline fun sessionCall(block: () -> String): String = try {
        block()
    } catch (gone: SessionUnavailableException) {
        throw SpokenSessionUnavailableException(gone.message)
    }

    override fun sessionTokens(): Int? = LocalChatSessionManager.lastSessionTokens

    override suspend fun takePhoto(): String? = requestPhoto().await()

    override fun deletePhoto(path: String) {
        // Pro follow-ups resend the photo a [camera] reply took; RelayConversation deletes it when the conversation ends.
        if (relay && RelayConversation.keeps(path)) return
        File(path).delete()
    }

    override val webSearchEngine: String get() = QuestionRoutingPrefs.webSearchEngine(env.context).toString()

    override suspend fun searchWeb(query: String): WebSearchOutcome? {
        val engine = QuestionRoutingPrefs.webSearchEngine(env.context)
        return runCatching { WebSearchAssist.search(env.context, query, engine, languageTag()) }
            .onFailure { Log.w("AIHijack", "$engine web search failed", it) }
            .getOrNull()
            ?.let { if (GlassesQuestionTestHooks.webResultsOnly) it.copy(answer = null, infoBox = null) else it }
            ?.let { web ->
                WebSearchOutcome(
                    engine = web.engine.toString(),
                    fromGoogle = web.engine == WebSearchEngine.GOOGLE,
                    answer = web.answer,
                    infoBox = web.infoBox,
                    snippets = web.snippets,
                    failure = web.failure?.toString(),
                    offline = web.failure == WebSearchAssist.Failure.NETWORK,
                    blocked = web.blocked,
                    elapsedMs = web.elapsedMs,
                )
            }
    }

    override fun startPhoneTask(goal: String) = startLocalAgent(goal)

    override fun phoneTaskAvailable(): Boolean =
        !photoQuestion || LocalAgentController.unavailableReason(env.context) == null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun startLive(prompt: String, photo: LivePhoto?) {
        val ready = photo?.photo?.takeIf { it.isCompleted }?.let { runCatching { it.getCompleted() }.getOrNull() }
        when {
            photo == null -> live.launch(prompt = prompt)
            ready != null -> live.launch(prompt = photo.prompt, imagePath = ready)
            photo.photo.isCompleted -> live.launch(prompt = prompt) // the photo failed
            else -> live.launch(
                prompt = prompt,
                openingImage = GeminiLiveOpeningImage.Pending(
                    photo = photo.photo,
                    deadlineMs = android.os.SystemClock.elapsedRealtime() + photo.waitMs,
                    promptWithPhoto = photo.prompt,
                    promptWithoutPhoto = prompt,
                ),
            )
        }
    }

    override fun queueCue(cue: SpokenCue) {
        val language = languageTag()
        when (cue) {
            SpokenCue.LOOKING -> speaker.speak(
                text = LOOKING_CUE,
                languageTag = language,
                utteranceId = "spoken_decision_look_${System.nanoTime()}",
                queueMode = TextToSpeech.QUEUE_ADD,
            )
            SpokenCue.CHECKING_WEB -> speaker.speak(
                text = ImageQuestionDefaults.checkingWebCueForLanguage(language),
                languageTag = language,
                utteranceId = "spoken_decision_web_${System.nanoTime()}",
                queueMode = TextToSpeech.QUEUE_ADD,
            )
        }
    }

    override fun say(text: String) = speaker.speak(text)

    override fun sayQuestionFailed() {
        speaker.speak(
            text = ImageQuestionDefaults.questionFailedCueForLanguage(languageTag()),
            languageTag = languageTag(),
            utteranceId = "spoken_decision_failed_${System.nanoTime()}",
        )
    }

    override suspend fun speakAnswer(text: String) {
        val spoken = CompletableDeferred<Unit>()
        speaker.speakVision(text) { spoken.complete(Unit) }
        spoken.await()
    }

    /**
     * A local model's answer (spoken and photo questions) speaks phrase by phrase while it is written
     * (StreamingSpeechSessionManager); the Pro relay returns its reply in one piece.
     */
    override fun answerStream(): SpokenAnswerStream? {
        if (provider != AgentProviderType.LOCAL_AGENT) return null
        val streaming = speaker.streaming
        val spoken = CompletableDeferred<Unit>()
        speaker.answerPlayback(true)
        val session = streaming.startNewSession(
            languageTag = ImageQuestionPreferences.get(env.context).appLanguageTag,
            onSpeechCompleted = {
                speaker.answerPlayback(false)
                spoken.complete(Unit)
            },
        )
        return object : SpokenAnswerStream {
            override fun append(text: String) = streaming.onModelTokenDelta(text, session)

            override suspend fun finish(answer: String) {
                env.runOnUiThread {
                    speaker.answerShown(answer)
                    streaming.onModelGenerationCompleted(session)
                }
                // A barge-in (a new question, the stop gesture) cancels the session without completing it: the
                // question ends there instead of listening for a follow-up.
                while (!spoken.isCompleted) {
                    if (!streaming.isActive(session)) {
                        speaker.answerPlayback(false)
                        throw kotlinx.coroutines.CancellationException("Answer speech was stopped")
                    }
                    kotlinx.coroutines.delay(200L)
                }
            }

            override fun cancel() {
                // Only this answer: a newer question's speech may already be the active session.
                if (streaming.isActive(session)) streaming.cancelActiveStreamingResponse()
                speaker.answerPlayback(false)
            }
        }
    }

    override fun englishSpeaker(): Boolean = languageTag().startsWith("en", ignoreCase = true)

    override fun webResultsInstruction(answer: String?, snippets: List<String>, infoBox: String?): String =
        ImageQuestionDefaults.webResultsInstruction(answer, snippets, infoBox)

    override fun webSearchEmpty(): String = ImageQuestionDefaults.webSearchEmptyCueForLanguage(languageTag())

    override fun webSearchFailed(offline: Boolean): String =
        ImageQuestionDefaults.webSearchFailedCueForLanguage(languageTag(), offline = offline)

    private companion object {
        const val LOOKING_CUE = "Okay. I'll check what you see."

        /** A route and its one-line payload ("[search] next public holiday in Brazil"). */
        const val ROUTE_ONLY_MAX_TOKENS = 32
    }
}
