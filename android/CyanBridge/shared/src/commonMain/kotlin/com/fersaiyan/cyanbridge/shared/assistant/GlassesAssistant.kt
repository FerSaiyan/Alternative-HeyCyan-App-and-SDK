package com.fersaiyan.cyanbridge.shared.assistant

import com.fersaiyan.cyanbridge.shared.platform.PlatformLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Listening and speaking on the phone (iOS: SFSpeechRecognizer and AVSpeechSynthesizer). */
interface AssistantVoice {
    /** Listens for a spoken question; null when nothing was said within [initialTimeoutMs]. */
    suspend fun listen(initialTimeoutMs: Long): String?

    fun stopSpeaking()

    /** Queues a short phrase, such as [waitCue]. */
    fun say(text: String)

    /** "One moment" in the user's language, said while an answer is being generated. */
    fun waitCue(): String

    /** "Ask." in the user's language, said before listening. */
    fun questionCue(): String

    /** Leaves the Bluetooth call route after listening so the answer plays as normal audio. */
    fun usePlaybackRoute()

    fun releaseAudio()

    /** Speaks text as it streams in, sentence by sentence. */
    fun streamingSpeech(beforeFirstChunk: suspend () -> Unit): AssistantSpeechStream
}

interface AssistantSpeechStream {
    fun append(text: String)

    /** Speaks what is left and returns when the synthesizer finished. */
    suspend fun finish()

    fun cancel()
}

/** Where answers come from: an on-device model, a custom provider or the relay. */
interface AssistantBrain {
    /** Loads an on-device model; started while the user is still talking. */
    suspend fun preload()

    /** Answers about [image]; replies starting with "Error:" count as failures. */
    suspend fun answerImage(image: ByteArray, question: String, onToken: (String) -> Unit): String

    suspend fun answerText(question: String, onToken: (String) -> Unit): String

    /** The question used when the user says nothing about a photo (Settings ▸ default image question). */
    fun defaultImageQuestion(): String
}

/**
 * What the glasses need around an answer. HeyCyan glasses must get their AI-speak state changes
 * outside speech (thinking before generating, speaking before the first chunk, stop after the
 * last one) or they cut sentences off or stay in AI mode. Other glasses need nothing.
 */
interface AssistantGlassesHooks {
    suspend fun beforeAnswer() {}

    suspend fun beforeFirstChunk() {}

    /** Runs after every answer, also when it failed or was replaced by a new question. */
    suspend fun afterAnswer() {}

    /** The user stayed silent after a voice-question cue. */
    suspend fun onNoQuestion() {}

    object None : AssistantGlassesHooks
}

/** Where the assistant reports progress and keeps the conversation. */
interface AssistantOutput {
    fun status(text: String)

    /** Keeps a spoken answer in the "Glasses" chat; [photo] is attached to the first answer about it. */
    suspend fun saveAnswer(question: String, answer: String, photo: ByteArray?)
}

/** Timings of a glasses question, matching Android's image and voice question flows. */
data class GlassesAssistantSettings(
    val voiceQuestionListeningMs: Long = 6_000L,
    /** Android: IMAGE_QUESTION_INITIAL_LISTENING_TIMEOUT_MS. */
    val imageQuestionListeningMs: Long = 3_300L,
    /** Android: the 500 ms shutter settle delay before listening during a photo. */
    val shutterSettleMs: Long = 500L,
    /** Follow-up questions about the same photo ("Pergunte." again); 0 because they were too eager. */
    val maxImageFollowUps: Int = 0,
    /**
     * Pause after a spoken reply before the glasses' AI-speak STOP and the next cue or listening
     * window: the synthesizer reports the end before the glasses played the last words. Android
     * waits 500 ms too (offerFollowUp).
     */
    val speechTailMs: Long = 500L,
)

/**
 * The glasses assistant: questions about photos and spoken questions, answered aloud while the
 * reply streams. One question runs at a time; a new one (button, wake word or photo) cancels the
 * running one and waits for its cleanup, so answers, cues and microphone switches never overlap.
 */
class GlassesAssistant(
    private val scope: CoroutineScope,
    private val voice: AssistantVoice,
    private val brain: AssistantBrain,
    private val output: AssistantOutput,
    /** Hooks of the glasses connected right now. */
    private val glassesHooks: () -> AssistantGlassesHooks,
    private val settings: GlassesAssistantSettings = GlassesAssistantSettings(),
    /**
     * The decision-model flow for spoken questions ([SpokenQuestionFlow]: route tags, camera, web
     * search, follow-ups), when this platform has its ports and the current model hears the
     * recording; null keeps [askByVoice] on the transcript flow below.
     */
    private val spokenQuestions: () -> SpokenQuestionFlow? = { null },
) {
    private var questionJob: Job? = null

    /** Question captured while the photo is still being taken ([startPhotoQuestion]). */
    private var pendingImageQuestion: Deferred<String?>? = null

    val isBusy: Boolean get() = questionJob?.isActive == true

    /**
     * Asks the glasses for a photo and listens for the question while it transfers
     * (Android: startParallelAudioQuestionIfEligible). The photo itself arrives through
     * [askAboutPhoto]. [requestPhoto] returns false when the glasses rejected the request.
     */
    fun startPhotoQuestion(requestPhoto: suspend () -> Boolean) = startQuestion {
        dropPendingImageQuestion()
        output.status("Waiting for the glasses photo… ask about it now")
        val question = scope.async {
            delay(settings.shutterSettleMs)
            voice.listen(settings.imageQuestionListeningMs)
        }
        pendingImageQuestion = question
        if (!requestPhoto()) {
            question.cancel()
            if (pendingImageQuestion === question) pendingImageQuestion = null
            output.status("Image question was rejected by the glasses (they may be busy)")
        }
    }

    /** A photo from the glasses: answer it, replacing the current turn. */
    fun askAboutPhoto(image: ByteArray) {
        voice.stopSpeaking()
        startQuestion { answerImageQuestion(image) }
    }

    fun askByVoice() = startQuestion {
        val flow = spokenQuestions()
        if (flow != null) {
            dropPendingImageQuestion()
            flow.run()
        } else {
            answerVoiceQuestion()
        }
    }

    private fun startQuestion(block: suspend () -> Unit) {
        val previous = questionJob
        questionJob = scope.launch {
            previous?.cancelAndJoin()
            block()
        }
    }

    private fun dropPendingImageQuestion() {
        pendingImageQuestion?.cancel()
        pendingImageQuestion = null
    }

    /**
     * Android's image-question flow: ask "Pergunte." while the photo arrives (the model loads in
     * parallel), answer the spoken question or the default description, then offer follow-ups
     * about the same photo until the user stays silent.
     */
    private suspend fun answerImageQuestion(image: ByteArray) {
        scope.launch { brain.preload() }
        output.status("Ask about the photo now, or wait for the description…")
        // A question already asked while the photo transferred is final, even if it was silence.
        val pending = pendingImageQuestion?.also { pendingImageQuestion = null }
        val spoken = if (pending != null) {
            try {
                pending.await()
            } finally {
                pending.cancel() // no-op once answered; stops listening when this question is replaced
            }
        } else {
            voice.listen(settings.imageQuestionListeningMs)
        }
        var question = spoken ?: brain.defaultImageQuestion()
        repeat(settings.maxImageFollowUps + 1) { round ->
            output.status("Analyzing the photo: $question")
            // Leave the HFP call route right after listening; answers use normal playback.
            voice.usePlaybackRoute()
            // Photos take several seconds to describe; say so instead of going silent.
            voice.say(voice.waitCue())
            val currentQuestion = question
            val reply = speakAnswer { onToken -> brain.answerImage(image, currentQuestion, onToken) }
            deliverAnswer("📷 $question", reply, photo = image.takeIf { round == 0 })
            if (reply.isFailure || round >= settings.maxImageFollowUps) return
            question = voice.listen(settings.imageQuestionListeningMs) ?: return
        }
    }

    private suspend fun answerVoiceQuestion() {
        dropPendingImageQuestion()
        scope.launch { brain.preload() }
        output.status("Listening… ask your question")
        val question = voice.listen(settings.voiceQuestionListeningMs)
        if (question.isNullOrBlank()) {
            voice.releaseAudio()
            glassesHooks().onNoQuestion()
            output.status("No question heard. Tap Test voice and speak after \"${voice.questionCue()}\"")
            return
        }
        output.status("You asked: $question")
        voice.usePlaybackRoute()
        voice.say(voice.waitCue())
        val reply = speakAnswer { onToken -> brain.answerText(question, onToken) }
        deliverAnswer(question, reply)
    }

    /**
     * Runs [generate] and speaks the reply while it streams (on-device models stream tokens;
     * remote replies are spoken chunk by chunk when they arrive).
     */
    private suspend fun speakAnswer(generate: suspend (onToken: (String) -> Unit) -> String): Result<String> {
        val hooks = glassesHooks()
        hooks.beforeAnswer()
        val speech = voice.streamingSpeech(beforeFirstChunk = { hooks.beforeFirstChunk() })
        var streamed = false
        val reply = try {
            runCatching {
                generate { token ->
                    streamed = true
                    speech.append(token)
                }
            }.mapCatching { text ->
                if (text.startsWith("Error:")) error(text.removePrefix("Error:").trim())
                text
            }.also { result ->
                // runCatching also catches cancellation: a replaced question stops here, unreported.
                currentCoroutineContext().ensureActive()
                result.onSuccess { text -> if (!streamed) speech.append(text) }.onFailure { speech.cancel() }
                if (result.isSuccess) {
                    speech.finish()
                    delay(settings.speechTailMs)
                }
            }
        } catch (cancelled: CancellationException) {
            speech.cancel()
            throw cancelled
        } finally {
            // Also on cancellation (a new photo replaced this turn).
            withContext(NonCancellable) {
                hooks.afterAnswer()
                voice.releaseAudio()
            }
        }
        PlatformLogger.i(TAG, reply.fold({ "Answer: $it" }, { "Answer failed: ${it.message}" }))
        return reply
    }

    /** Shows the reply and keeps it in the "Glasses" chat (it was already spoken). */
    private suspend fun deliverAnswer(question: String, reply: Result<String>, photo: ByteArray? = null) {
        val answer = reply.getOrElse { error ->
            output.status("AI request failed: ${error.message ?: "unknown error"}")
            return
        }
        output.status(answer)
        output.saveAnswer(question, answer, photo)
    }

    private companion object {
        const val TAG = "GlassesAssistant"
    }
}
