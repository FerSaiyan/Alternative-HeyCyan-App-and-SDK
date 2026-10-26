package com.fersaiyan.cyanbridge.ai.questions

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.fersaiyan.cyanbridge.ai.image.GlassesQuestionTestHooks
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.localagent.AudioSessionCoordinator
import com.fersaiyan.cyanbridge.localmodels.tts.StreamingSpeechSessionManager
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The text-to-speech engine of the glasses questions: cues, fixed sentences and answers. The
 * engine also plays the local model's streamed answers ([streaming]). It is created with the
 * main screen and shut down with it.
 */
class AssistantSpeaker(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext

    @Volatile private var tts: TextToSpeech? = null

    @Volatile var ready = false
        private set

    private val doneCallbacks = ConcurrentHashMap<String, () -> Unit>()

    val streaming: StreamingSpeechSessionManager by lazy { StreamingSpeechSessionManager.getInstance(appContext) }

    /** Called around every answer (Mentra Live must be told when the app plays audio). */
    @Volatile var answerPlayback: (playing: Boolean) -> Unit = {}

    val isStarted: Boolean get() = tts != null

    /** Creates the engine on [engineContext] (the main screen passes itself, as it always did). */
    fun start(engineContext: Context) {
        if (tts != null) return
        tts = TextToSpeech(engineContext, this).apply { setOnUtteranceProgressListener(progressListener) }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        Log.i("ImageQuestionAudio", "TTS initialization status=$status ready=$ready")
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
        }
        streaming.attachTtsEngine(tts, ready)
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            streaming.speechQueueController.onUtteranceStart(utteranceId)
            if (utteranceId.isCue()) Log.i("ImageQuestionAudio", "TTS cue started id=$utteranceId")
        }

        override fun onDone(utteranceId: String?) {
            streaming.speechQueueController.onUtteranceDone(utteranceId)
            if (utteranceId.isCue()) Log.i("ImageQuestionAudio", "TTS cue completed id=$utteranceId")
            utteranceId?.let { doneCallbacks.remove(it)?.invoke() }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            streaming.speechQueueController.onUtteranceError(utteranceId)
            Log.w("ImageQuestionAudio", "TTS failed id=$utteranceId")
            utteranceId?.let { doneCallbacks.remove(it)?.invoke() }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            streaming.speechQueueController.onUtteranceError(utteranceId, errorCode)
            Log.w("ImageQuestionAudio", "TTS failed id=$utteranceId errorCode=$errorCode")
            utteranceId?.let { doneCallbacks.remove(it)?.invoke() }
        }
    }

    private fun String?.isCue(): Boolean =
        this?.startsWith("image_question_cue_") == true || this?.startsWith("voice_listening_") == true

    fun speak(text: String) {
        speak(text, languageTag = null, utteranceId = null, onDone = null, streamType = null)
    }

    /** An answer, in the app language; [answerPlayback] brackets it. */
    fun speakVision(text: String, onDone: (() -> Unit)? = null) {
        answerPlayback(true)
        speak(
            text = text,
            languageTag = ImageQuestionPreferences.get(appContext).appLanguageTag,
            utteranceId = null,
            onDone = {
                answerPlayback(false)
                onDone?.invoke()
            },
            streamType = null,
        )
    }

    fun speak(
        text: String,
        languageTag: String? = null,
        utteranceId: String?,
        streamType: Int? = null,
        onDone: (() -> Unit)? = null,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH,
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
        val wrappedOnDone: () -> Unit = {
            try {
                onDone?.invoke()
            } finally {
                AudioSessionCoordinator.markIdle()
            }
        }
        doneCallbacks[id] = wrappedOnDone

        val bundle = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
            streamType?.let { putString(TextToSpeech.Engine.KEY_PARAM_STREAM, it.toString()) }
        }

        AudioSessionCoordinator.markBusy()
        val result = engine?.speak(text, queueMode, bundle, id)
        Log.i(
            "ImageQuestionAudio",
            "TTS enqueue id=$id ready=$ready stream=$streamType textLength=${text.length} result=$result",
        )
        if (result != TextToSpeech.SUCCESS) {
            // No progress callback is delivered when enqueueing fails (including when the
            // engine is not initialized). Complete the caller now instead of leaking its
            // callback or leaving SCO/foreground work waiting forever.
            doneCallbacks.remove(id)?.invoke()
        }
    }

    /** Drops the completion of [utteranceId] (a cue that already timed out or was cancelled). */
    fun forgetUtterance(utteranceId: String) {
        doneCallbacks.remove(utteranceId)
    }

    fun configureForVoiceCommunication(reason: String) {
        val result = tts?.setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        Log.i("ImageQuestionAudio", "Configured TTS voice-communication audio reason=$reason result=$result")
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
