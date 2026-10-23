package com.fersaiyan.cyanbridge.localagent

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** The few text-to-speech engine calls [LocalAgentSpeechOutput] needs. */
interface LocalAgentTtsEngine {
    fun setLanguage(locale: Locale)

    /** Queues [text] with QUEUE_FLUSH and returns whether the engine accepted it. */
    fun speak(text: String, utteranceId: String): Boolean

    /** Replaces the progress listener; [onFinished] runs when an utterance is done or fails. */
    fun setOnUtteranceFinished(onFinished: (utteranceId: String?) -> Unit)

    fun stop()

    fun shutdown()
}

/** [LocalAgentTtsEngine] backed by the platform [TextToSpeech]. */
class AndroidLocalAgentTtsEngine(
    context: Context,
    onInit: (ok: Boolean) -> Unit,
) : LocalAgentTtsEngine {

    private val tts = TextToSpeech(context) { status ->
        onInit(status == TextToSpeech.SUCCESS)
    }

    override fun setLanguage(locale: Locale) {
        tts.language = locale
    }

    override fun speak(text: String, utteranceId: String): Boolean {
        return tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS
    }

    override fun setOnUtteranceFinished(onFinished: (utteranceId: String?) -> Unit) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(doneId: String?) {
                onFinished(doneId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(errorId: String?) {
                onFinished(errorId)
            }

            override fun onError(errorId: String?, errorCode: Int) {
                onFinished(errorId)
            }
        })
    }

    override fun stop() {
        tts.stop()
    }

    override fun shutdown() {
        tts.shutdown()
    }
}

/**
 * Spoken output for [LocalAgentService]: task results, approval prompts and screen reading.
 *
 * Speech is skipped whenever the phone is not ready, and a queued utterance is stopped as soon as
 * the phone locks or the screen turns off.
 */
class LocalAgentSpeechOutput(
    private val isDeviceReady: () -> Boolean,
    private val currentLocale: () -> Locale,
    private val engineFactory: (onInit: (ok: Boolean) -> Unit) -> LocalAgentTtsEngine,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var engine: LocalAgentTtsEngine? = null
    private var engineReady: CompletableDeferred<Boolean>? = null

    fun initialize() {
        val ready = CompletableDeferred<Boolean>()
        engineReady = ready

        engine = engineFactory { ok ->
            if (ok) {
                runCatching { engine?.setLanguage(currentLocale()) }
            }
            if (!ready.isCompleted) ready.complete(ok)
        }
    }

    suspend fun speakBestEffort(text: String) {
        if (!isDeviceReady()) return
        val ready = engineReady
        val ok = if (ready != null) {
            withTimeoutOrNull(3_000) { ready.await() } ?: false
        } else false

        if (!ok) {
            Log.w(TAG, "TTS not ready; skipping speak")
            return
        }

        runCatching {
            engine?.speak(text, "local_agent_demo")
        }.onFailure {
            Log.w(TAG, "TTS speak failed: ${it.message}")
        }
    }

    suspend fun speakAndWaitBestEffort(text: String, maxLength: Int = 160) {
        val clean = text.trim().take(maxLength)
        if (clean.isBlank()) return
        if (!isDeviceReady()) return

        val ready = engineReady
        val ok = if (ready != null) {
            withTimeoutOrNull(3_000) { ready.await() } ?: false
        } else {
            false
        }
        if (!ok) return
        AudioSessionCoordinator.markBusy()

        val utteranceId = "local_agent_result_${clock()}"
        val completed = CompletableDeferred<Unit>()
        engine?.setOnUtteranceFinished { finishedId ->
            if (finishedId == utteranceId && !completed.isCompleted) completed.complete(Unit)
        }

        val queued = runCatching {
            engine?.speak(clean, utteranceId) == true
        }.getOrDefault(false)
        if (!queued) {
            AudioSessionCoordinator.markIdle()
            return
        }

        try {
            repeat(32) {
                if (!isDeviceReady()) {
                    runCatching { engine?.stop() }
                    return
                }
                if (withTimeoutOrNull(250L) { completed.await() } != null) return
            }
        } finally {
            AudioSessionCoordinator.markIdle()
        }
    }

    fun stop() {
        runCatching { engine?.stop() }
    }

    fun shutdown() {
        runCatching {
            engine?.stop()
            engine?.shutdown()
        }
        engine = null
        engineReady = null
    }

    private companion object {
        private const val TAG = LOCAL_AGENT_SERVICE_LOG_TAG
    }
}
