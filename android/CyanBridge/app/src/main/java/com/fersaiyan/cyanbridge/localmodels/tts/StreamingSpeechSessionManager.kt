package com.fersaiyan.cyanbridge.localmodels.tts

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.audio.TtsSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicLong

/**
 * Top-level session controller that coordinates local model streaming,
 * multilingual phrase chunking, speech sanitization, and TTS queue playback.
 */
class StreamingSpeechSessionManager(
    private val context: Context,
    val config: SpeechChunkingConfig = SpeechChunkingConfig(),
) {
    companion object {
        private const val TAG = "StreamingSpeechSessionManager"
        
        @Volatile
        private var instance: StreamingSpeechSessionManager? = null

        fun getInstance(context: Context): StreamingSpeechSessionManager {
            return instance ?: synchronized(this) {
                instance ?: StreamingSpeechSessionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionIdCounter = AtomicLong(1L)

    @Volatile
    var activeSessionId: Long = 0L
        private set

    @Volatile
    var activeGenerationJob: Job? = null

    private var onActiveSpeechCompleted: (() -> Unit)? = null

    val speechQueueController = SpeechQueueController(context, config) { sessionId ->
        if (sessionId == activeSessionId) {
            onActiveSpeechCompleted?.invoke()
            onActiveSpeechCompleted = null
        }
    }

    val chunker = MultilingualSpeechChunker(
        config = config,
        scope = sessionScope,
        onChunkReady = { sessionId, phraseChunk ->
            onPhraseChunkReady(sessionId, phraseChunk)
        },
    )

    @Volatile
    private var currentLanguageTag: String? = null

    fun attachTtsEngine(tts: TtsSpeaker?, ready: Boolean) {
        speechQueueController.attachTtsEngine(tts, ready)
    }

    @Synchronized
    fun startNewSession(
        languageTag: String? = null,
        generationJob: Job? = null,
        onSpeechCompleted: (() -> Unit)? = null,
    ): Long {
        cancelActiveStreamingResponse()

        val newSessionId = sessionIdCounter.getAndIncrement()
        this.activeSessionId = newSessionId
        this.activeGenerationJob = generationJob
        this.currentLanguageTag = languageTag
        this.onActiveSpeechCompleted = onSpeechCompleted

        chunker.startSession(newSessionId)
        speechQueueController.startSession(newSessionId)

        Log.i(TAG, "Started new streaming speech session id=$newSessionId lang=$languageTag")
        return newSessionId
    }

    @Synchronized
    fun attachGenerationJob(sessionId: Long, generationJob: Job) {
        if (sessionId != activeSessionId) {
            generationJob.cancel()
            return
        }
        activeGenerationJob = generationJob
    }

    @Synchronized
    fun cancelActiveStreamingResponse() {
        val cancelledSessionId = activeSessionId
        if (cancelledSessionId != 0L) {
            Log.i(TAG, "Cancelling active streaming speech session id=$cancelledSessionId")
        }
        
        val job = activeGenerationJob
        activeGenerationJob = null
        job?.cancel()

        chunker.reset()
        speechQueueController.cancelSession()
        activeSessionId = 0L
        currentLanguageTag = null
        onActiveSpeechCompleted = null
    }

    fun onModelTokenDelta(deltaText: String, sessionId: Long) {
        if (sessionId != activeSessionId) {
            speechQueueController.metrics.staleCallbacksDiscarded++
            return
        }

        if (speechQueueController.metrics.firstModelFragmentAtMs == 0L) {
            speechQueueController.metrics.firstModelFragmentAtMs = System.currentTimeMillis()
        }
        speechQueueController.metrics.totalModelFragments++

        chunker.append(deltaText, sessionId)
    }

    fun onModelGenerationCompleted(sessionId: Long) {
        if (sessionId != activeSessionId) return
        Log.i(TAG, "Model generation completed for session id=$sessionId")
        speechQueueController.metrics.generationCompletedAtMs = System.currentTimeMillis()
        chunker.finish(sessionId)
        Log.d(
            TAG,
            "Chunking session=$sessionId accepted=${chunker.metrics.acceptedCandidateBoundaries} " +
                "rejected=${chunker.metrics.rejectedCandidateBoundaries} " +
                "timeoutFlushes=${chunker.metrics.timeoutFlushes}",
        )
        speechQueueController.onGenerationCompleted(sessionId)
    }

    private fun onPhraseChunkReady(sessionId: Long, phraseChunkText: String) {
        if (sessionId == 0L || sessionId != activeSessionId) {
            speechQueueController.metrics.staleCallbacksDiscarded++
            return
        }
        speechQueueController.enqueueChunk(
            sessionId = sessionId,
            rawChunkText = phraseChunkText,
            languageTag = currentLanguageTag,
        )
    }
}
