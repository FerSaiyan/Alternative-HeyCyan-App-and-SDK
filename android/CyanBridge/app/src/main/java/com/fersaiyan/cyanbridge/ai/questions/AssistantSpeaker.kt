package com.fersaiyan.cyanbridge.ai.questions

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import com.fersaiyan.cyanbridge.ai.image.GlassesQuestionTestHooks
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.audio.AudioSessionCoordinator
import com.fersaiyan.cyanbridge.audio.AudioSessionHandle
import com.fersaiyan.cyanbridge.audio.TtsProgressObserver
import com.fersaiyan.cyanbridge.audio.TtsSpeaker
import com.fersaiyan.cyanbridge.audio.TtsUtteranceOutcome
import com.fersaiyan.cyanbridge.audio.VendorPlaybackSignal
import com.fersaiyan.cyanbridge.localmodels.tts.StreamingSpeechSessionManager
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionRequest
import java.util.Locale

/**
 * The speech of the glasses questions: cues, fixed sentences and answers, on one [TtsSpeaker]. The
 * same engine plays the local model's streamed answers ([streaming]). It is created with the main
 * screen and shut down with it; a question started without the screen creates it first.
 *
 * Every utterance sets the process speech flag ([AudioSessionCoordinator.markSpeechBusy]); an
 * answer ([answerPlayback]) also holds a playback session and tells glasses that need it
 * ([VendorPlaybackSignal], Mentra Live) that the app is playing audio.
 */
class AssistantSpeaker(context: Context) {
    private val appContext = context.applicationContext

    @Volatile private var tts: TtsSpeaker? = null

    val ready: Boolean get() = tts?.isReady == true

    val streaming: StreamingSpeechSessionManager by lazy { StreamingSpeechSessionManager.getInstance(appContext) }

    /** Called with every answer as it starts playing (glasses with a display show it). */
    @Volatile var answerShown: (text: String) -> Unit = {}

    private var playbackSession: AudioSessionHandle? = null

    val isStarted: Boolean get() = tts != null

    /** Creates the engine on [engineContext] (the main screen passes itself, as it always did). */
    fun start(engineContext: Context) {
        if (tts != null) return
        tts = TtsSpeaker(engineContext, TAG, onInit = ::onEngineInit).also { it.progressObserver = progress }
    }

    /** For a question started without the screen: creates the engine if needed and waits for it. */
    suspend fun awaitStarted(timeoutMs: Long = START_TIMEOUT_MS): Boolean {
        start(appContext)
        return tts?.awaitReady(timeoutMs) == true
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }

    private fun onEngineInit(engine: TtsSpeaker, ok: Boolean) {
        Log.i("ImageQuestionAudio", "TTS initialization ready=$ok")
        if (ok) engine.setLanguage(Locale.getDefault())
        streaming.attachTtsEngine(engine, ok)
    }

    private val progress = object : TtsProgressObserver {
        override fun onStart(utteranceId: String?) {
            streaming.speechQueueController.onUtteranceStart(utteranceId)
            if (utteranceId.isCue()) Log.i("ImageQuestionAudio", "TTS cue started id=$utteranceId")
        }

        override fun onDone(utteranceId: String?) {
            streaming.speechQueueController.onUtteranceDone(utteranceId)
            if (utteranceId.isCue()) Log.i("ImageQuestionAudio", "TTS cue completed id=$utteranceId")
        }

        override fun onError(utteranceId: String?, errorCode: Int?) {
            streaming.speechQueueController.onUtteranceError(utteranceId, errorCode)
            Log.w("ImageQuestionAudio", "TTS failed id=$utteranceId errorCode=$errorCode")
        }
    }

    private fun String?.isCue(): Boolean =
        this?.startsWith("image_question_cue_") == true || this?.startsWith("voice_listening_") == true

    fun speak(text: String) {
        speak(text, languageTag = null, utteranceId = null, onDone = null, streamType = null)
    }

    /**
     * Brackets an answer: true opens the answer's playback session and signals the glasses' SDK;
     * false closes the session and clears the signal.
     */
    fun answerPlayback(playing: Boolean) {
        val ended = synchronized(this) {
            if (playing) {
                if (playbackSession == null) {
                    playbackSession = AudioSessionCoordinator.tryOpen(appContext, AudioSessionRequest.Playback(TAG))
                }
                null
            } else {
                playbackSession.also { playbackSession = null }
            }
        }
        ended?.close()
        VendorPlaybackSignal.forSelectedGlasses(appContext)?.setAppAudioPlaying(playing)
    }

    /** An answer, in the app language; [answerPlayback] brackets it and [answerShown] gets its text. */
    fun speakVision(text: String, onDone: (() -> Unit)? = null) {
        answerShown(text)
        answerPlayback(true)
        enqueue(
            text = text,
            languageTag = ImageQuestionPreferences.get(appContext).appLanguageTag,
            utteranceId = null,
            streamType = null,
            queueMode = TextToSpeech.QUEUE_FLUSH,
        ) { stopped ->
            answerPlayback(false)
            if (!stopped) onDone?.invoke()
        }
    }

    /**
     * Queues [text]. [onDone] runs once when it finished or failed, or right away when the engine
     * did not take it (also before the engine is ready). An utterance the engine stopped (flushed
     * by a newer one, or shut down) does not run it.
     */
    fun speak(
        text: String,
        languageTag: String? = null,
        utteranceId: String?,
        streamType: Int? = null,
        onDone: (() -> Unit)? = null,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH,
    ) {
        enqueue(text, languageTag, utteranceId, streamType, queueMode) { stopped -> if (!stopped) onDone?.invoke() }
    }

    private fun enqueue(
        text: String,
        languageTag: String?,
        utteranceId: String?,
        streamType: Int?,
        queueMode: Int,
        onFinished: (stopped: Boolean) -> Unit,
    ) {
        val engine = tts
        languageTag?.takeIf { it.isNotBlank() }?.let { tag ->
            val result = engine?.setLanguage(Locale.forLanguageTag(tag))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Text-to-speech voice unavailable for $tag")
            }
            Log.i("ImageQuestionAudio", "TTS language tag=$tag result=$result")
        }
        val id = utteranceId ?: "utt_${System.currentTimeMillis()}"
        GlassesQuestionTestHooks.emit("speak", text)
        val params = streamType?.let { stream ->
            Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_STREAM, stream.toString()) }
        }
        val finished: (TtsUtteranceOutcome) -> Unit = { outcome ->
            try {
                onFinished(outcome == TtsUtteranceOutcome.STOPPED)
            } finally {
                AudioSessionCoordinator.markSpeechIdle()
            }
        }
        AudioSessionCoordinator.markSpeechBusy()
        // No progress callback follows a refused utterance: it completes here, so SCO or foreground
        // work never waits on it.
        val accepted = engine?.speak(text, id, queueMode, params, finished)
            ?: false.also { finished(TtsUtteranceOutcome.NOT_READY) }
        Log.i(
            "ImageQuestionAudio",
            "TTS enqueue id=$id ready=$ready stream=$streamType textLength=${text.length} accepted=$accepted",
        )
    }

    /** Drops the completion of [utteranceId] (a cue that already timed out or was cancelled). */
    fun forgetUtterance(utteranceId: String) {
        tts?.forget(utteranceId)
    }

    fun configureForVoiceCommunication(reason: String) {
        val result = tts?.useVoiceCommunicationAudio()
        Log.i("ImageQuestionAudio", "Configured TTS voice-communication audio reason=$reason result=$result")
    }

    private companion object {
        const val TAG = "AssistantSpeaker"
        const val START_TIMEOUT_MS = 5_000L
    }
}
