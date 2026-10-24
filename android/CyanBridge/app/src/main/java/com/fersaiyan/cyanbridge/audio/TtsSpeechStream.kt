package com.fersaiyan.cyanbridge.audio

import android.speech.tts.TextToSpeech
import com.fersaiyan.cyanbridge.shared.assistant.AssistantSpeechStream
import com.fersaiyan.cyanbridge.shared.speech.StreamingSpeechChunker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Speaks a streamed reply on a [TtsSpeaker] as it arrives: the shared [StreamingSpeechChunker]
 * picks the phrases and they are queued back to back (QUEUE_ADD) so there is no gap between them.
 * [beforeFirstChunk] runs once before the first phrase is queued.
 */
class TtsSpeechStream(
    private val tts: TtsSpeaker,
    private val scope: CoroutineScope,
    private val beforeFirstChunk: suspend () -> Unit = {},
    private val utteranceIdPrefix: String = "assistant_stream_${System.nanoTime()}",
) : AssistantSpeechStream {
    private val lock = Any()
    private val chunks = Channel<String>(Channel.UNLIMITED)
    private val chunker = StreamingSpeechChunker { chunk -> chunks.trySend(chunk) }
    private var idleFlush: Job? = null

    private val speaking: Job = scope.launch {
        var sequence = 0
        var last: CompletableDeferred<TtsUtteranceOutcome>? = null
        for (chunk in chunks) {
            if (sequence == 0) {
                tts.awaitReady(READY_TIMEOUT_MS)
                beforeFirstChunk()
            }
            val finished = CompletableDeferred<TtsUtteranceOutcome>()
            tts.speak(chunk, "${utteranceIdPrefix}_${sequence++}", TextToSpeech.QUEUE_ADD) { finished.complete(it) }
            last = finished
        }
        last?.await()
    }

    override fun append(text: String) {
        synchronized(lock) {
            chunker.append(text)
            idleFlush?.cancel()
            val pause = if (chunker.isFirstChunkPending) {
                StreamingSpeechChunker.FIRST_IDLE_FLUSH_MS
            } else {
                StreamingSpeechChunker.NORMAL_IDLE_FLUSH_MS
            }
            idleFlush = scope.launch {
                delay(pause)
                synchronized(lock) { chunker.flushIdle() }
            }
        }
    }

    override suspend fun finish() {
        synchronized(lock) {
            idleFlush?.cancel()
            chunker.finish()
        }
        chunks.close()
        speaking.join()
    }

    override fun cancel() {
        synchronized(lock) { idleFlush?.cancel() }
        chunks.close()
        speaking.cancel()
        tts.stop()
    }

    private companion object {
        const val READY_TIMEOUT_MS = 5_000L
    }
}
