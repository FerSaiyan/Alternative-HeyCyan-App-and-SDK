package com.fersaiyan.cyanbridge.ai.questions

import android.speech.tts.TextToSpeech
import android.util.Log
import com.fersaiyan.cyanbridge.ai.image.GlassesQuestionTestHooks
import com.fersaiyan.cyanbridge.ai.router.QuestionRoutingPrefs
import com.fersaiyan.cyanbridge.ai.search.WebSearchAssist
import com.fersaiyan.cyanbridge.ai.search.WebSearchEngine
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionDefaults
import com.fersaiyan.cyanbridge.localagent.memory.RagProfile
import com.fersaiyan.cyanbridge.localmodels.engine.SessionTurn
import com.fersaiyan.cyanbridge.localmodels.engine.SessionUnavailableException
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.localmodels.session.LocalChatSessionManager
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
 * phone's text-to-speech.
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
) : SpokenQuestionBrain, SpokenQuestionActions, SpokenQuestionSpeech {
    override val name: String = provider.toString()

    // Engine sessions keep the conversation (and its KV cache) between turns; the relay keeps none.
    override val keepsConversation: Boolean = provider == AgentProviderType.LOCAL_AGENT

    private fun SpokenSessionTurn.engineTurn() = SessionTurn(key, continueConversation, note)

    override suspend fun decide(audioPath: String, session: SpokenSessionTurn?, onToken: (String) -> Unit): String =
        sessionCall {
            providers.query(
                userPrompt = "",
                providerType = provider,
                ragProfile = RagProfile.NONE,
                audioPath = audioPath,
                decisionRoutes = true,
                session = session?.engineTurn(),
                onToken = onToken,
            )
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
            )
        }

    override suspend fun answerInSession(text: String, images: List<String>, note: String?): String =
        withContext(Dispatchers.IO) {
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

    override fun startLive(prompt: String) = live.launch(prompt = prompt)

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

    override fun englishSpeaker(): Boolean = languageTag().startsWith("en", ignoreCase = true)

    override fun webResultsInstruction(answer: String?, snippets: List<String>, infoBox: String?): String =
        ImageQuestionDefaults.webResultsInstruction(answer, snippets, infoBox)

    override fun webSearchEmpty(): String = ImageQuestionDefaults.webSearchEmptyCueForLanguage(languageTag())

    override fun webSearchFailed(offline: Boolean): String =
        ImageQuestionDefaults.webSearchFailedCueForLanguage(languageTag(), offline = offline)

    private companion object {
        const val LOOKING_CUE = "Okay. I'll check what you see."
    }
}
