package com.fersaiyan.cyanbridge.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** How one queued utterance ended. */
enum class TtsUtteranceOutcome { DONE, ERROR, STOPPED, NOT_QUEUED, NOT_READY, TIMED_OUT }

/** Progress for every utterance of a [TtsSpeaker], for queue controllers and logs. */
interface TtsProgressObserver {
    fun onStart(utteranceId: String?) = Unit
    fun onDone(utteranceId: String?) = Unit
    fun onError(utteranceId: String?, errorCode: Int?) = Unit
    fun onStop(utteranceId: String?, interrupted: Boolean) = Unit
}

/** The [TextToSpeech] calls [TtsSpeaker] makes; replaced by a fake in tests. */
interface TtsEngine {
    fun setLanguage(locale: Locale): Int
    fun setSpeechRate(rate: Float)
    fun setAudioAttributes(attributes: AudioAttributes): Int
    fun speak(text: String, queueMode: Int, params: Bundle?, utteranceId: String): Int
    val isSpeaking: Boolean
    fun stop()
    fun shutdown()
}

/** Callbacks a [TtsEngine] reports back to its [TtsSpeaker]. */
interface TtsEngineCallbacks {
    fun onInit(ok: Boolean)
    fun onStart(utteranceId: String?)
    fun onDone(utteranceId: String?)
    fun onError(utteranceId: String?, errorCode: Int?)
    fun onStop(utteranceId: String?, interrupted: Boolean)
}

class AndroidTtsEngine(context: Context, callbacks: TtsEngineCallbacks) : TtsEngine {
    private val tts = TextToSpeech(context.applicationContext) { status ->
        callbacks.onInit(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = callbacks.onStart(utteranceId)
            override fun onDone(utteranceId: String?) = callbacks.onDone(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = callbacks.onError(utteranceId, null)
            override fun onError(utteranceId: String?, errorCode: Int) = callbacks.onError(utteranceId, errorCode)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = callbacks.onStop(utteranceId, interrupted)
        })
    }

    override fun setLanguage(locale: Locale): Int = tts.setLanguage(locale)
    override fun setSpeechRate(rate: Float) {
        tts.setSpeechRate(rate)
    }
    override fun setAudioAttributes(attributes: AudioAttributes): Int = tts.setAudioAttributes(attributes)
    override fun speak(text: String, queueMode: Int, params: Bundle?, utteranceId: String): Int =
        tts.speak(text, queueMode, params, utteranceId)
    override val isSpeaking: Boolean get() = tts.isSpeaking
    override fun stop() {
        tts.stop()
    }
    override fun shutdown() {
        tts.shutdown()
    }
}

/**
 * The one text-to-speech wrapper: engine initialization and readiness, speak with per-utterance
 * completion, speak-and-wait with timeouts and cancellation, a latest-wins action queued until
 * the engine is ready, and shutdown. Each feature owns its own speaker (and engine), as before.
 */
class TtsSpeaker(
    context: Context?,
    private val tag: String,
    private val onInit: (TtsSpeaker, Boolean) -> Unit = { _, _ -> },
    engineFactory: (TtsEngineCallbacks) -> TtsEngine = { callbacks -> AndroidTtsEngine(requireNotNull(context), callbacks) },
) {
    private val ready = CompletableDeferred<Boolean>()
    private val completions = ConcurrentHashMap<String, (TtsUtteranceOutcome) -> Unit>()
    private val lock = Any()
    private var pendingWhenReady: ((TtsSpeaker) -> Unit)? = null

    @Volatile
    private var shutDown = false

    @Volatile
    private var initResult: Boolean? = null

    @Volatile
    var progressObserver: TtsProgressObserver? = null

    private val engine: TtsEngine = engineFactory(object : TtsEngineCallbacks {
        override fun onInit(ok: Boolean) = handleInit(ok)
        override fun onStart(utteranceId: String?) {
            progressObserver?.onStart(utteranceId)
        }
        override fun onDone(utteranceId: String?) {
            progressObserver?.onDone(utteranceId)
            finish(utteranceId, TtsUtteranceOutcome.DONE)
        }
        override fun onError(utteranceId: String?, errorCode: Int?) {
            progressObserver?.onError(utteranceId, errorCode)
            finish(utteranceId, TtsUtteranceOutcome.ERROR)
        }
        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            progressObserver?.onStop(utteranceId, interrupted)
            finish(utteranceId, TtsUtteranceOutcome.STOPPED)
        }
    })

    /** True once the engine initialized successfully. */
    val isReady: Boolean get() = initResult == true

    /** True when initialization finished, successfully or not. */
    val isInitialized: Boolean get() = initResult != null

    val isSpeaking: Boolean get() = !shutDown && runCatching { engine.isSpeaking }.getOrDefault(false)

    suspend fun awaitReady(timeoutMs: Long): Boolean {
        if (isInitialized || timeoutMs <= 0) return isReady
        return withTimeoutOrNull(timeoutMs) { ready.await() } ?: false
    }

    /**
     * Runs [action] now when ready; otherwise keeps it (replacing an earlier pending action) until
     * initialization succeeds. Dropped when initialization fails or the speaker shuts down.
     */
    fun whenReady(action: (TtsSpeaker) -> Unit) {
        val runNow = synchronized(lock) {
            if (isReady) {
                true
            } else {
                if (!isInitialized) pendingWhenReady = action
                false
            }
        }
        if (runNow) action(this)
    }

    fun setLanguage(locale: Locale): Int = runCatching { engine.setLanguage(locale) }.getOrDefault(TextToSpeech.ERROR)

    fun setSpeechRate(rate: Float) {
        runCatching { engine.setSpeechRate(rate) }
    }

    fun setAudioAttributes(attributes: AudioAttributes): Int =
        runCatching { engine.setAudioAttributes(attributes) }.getOrDefault(TextToSpeech.ERROR)

    /** Routes speech as voice communication (to a Bluetooth headset in communication mode). */
    fun useVoiceCommunicationAudio(): Int = setAudioAttributes(VOICE_COMMUNICATION_SPEECH)

    /**
     * Queues [text]. [onFinished] runs once when the utterance is done, fails, is stopped, or was
     * not accepted. Returns whether the engine accepted the utterance.
     */
    fun speak(
        text: String,
        utteranceId: String,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH,
        params: Bundle? = null,
        onFinished: ((TtsUtteranceOutcome) -> Unit)? = null,
    ): Boolean {
        if (shutDown) {
            onFinished?.invoke(TtsUtteranceOutcome.NOT_READY)
            return false
        }
        if (onFinished != null) completions[utteranceId] = onFinished
        val result = runCatching { engine.speak(text, queueMode, params, utteranceId) }
            .onFailure { Log.w(tag, "TTS speak failed: ${it.message}") }
            .getOrDefault(TextToSpeech.ERROR)
        if (result != TextToSpeech.SUCCESS) {
            // No progress callback follows a refused utterance.
            completions.remove(utteranceId)?.invoke(TtsUtteranceOutcome.NOT_QUEUED)
            return false
        }
        return true
    }

    /**
     * Speaks [text] and suspends until it finishes, [timeoutMs] passes, or the caller is
     * cancelled (which stops the engine). Waits up to [readyTimeoutMs] for initialization first.
     */
    suspend fun speakAndAwait(
        text: String,
        utteranceId: String,
        readyTimeoutMs: Long,
        timeoutMs: Long? = null,
        params: Bundle? = null,
    ): TtsUtteranceOutcome {
        if (!awaitReady(readyTimeoutMs)) return TtsUtteranceOutcome.NOT_READY
        val outcome = CompletableDeferred<TtsUtteranceOutcome>()
        var finished = false
        try {
            if (!speak(text, utteranceId, params = params) { outcome.complete(it) }) {
                finished = true
                return TtsUtteranceOutcome.NOT_QUEUED
            }
            val result = if (timeoutMs == null) outcome.await() else withTimeoutOrNull(timeoutMs) { outcome.await() }
            finished = true
            return result ?: TtsUtteranceOutcome.TIMED_OUT
        } finally {
            completions.remove(utteranceId)
            // Cancellation stops the utterance; a timeout leaves it playing, as before.
            if (!finished) stop()
        }
    }

    fun forget(utteranceId: String) {
        completions.remove(utteranceId)
    }

    fun stop() {
        if (!shutDown) runCatching { engine.stop() }
    }

    fun shutdown() {
        synchronized(lock) {
            if (shutDown) return
            shutDown = true
            pendingWhenReady = null
        }
        runCatching { engine.stop() }
        runCatching { engine.shutdown() }
        val waiting = completions.values.toList()
        completions.clear()
        waiting.forEach { it(TtsUtteranceOutcome.STOPPED) }
    }

    private fun handleInit(ok: Boolean) {
        val pending = synchronized(lock) {
            if (initResult == null) initResult = ok
            if (!ready.isCompleted) ready.complete(ok)
            pendingWhenReady.also { pendingWhenReady = null }
        }
        runCatching { onInit(this, ok) }.onFailure { Log.w(tag, "TTS init hook failed: ${it.message}") }
        if (ok && !shutDown) pending?.invoke(this)
    }

    private fun finish(utteranceId: String?, outcome: TtsUtteranceOutcome) {
        if (utteranceId != null) completions.remove(utteranceId)?.invoke(outcome)
    }

    companion object {
        val VOICE_COMMUNICATION_SPEECH: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
