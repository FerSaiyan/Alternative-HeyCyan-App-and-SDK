package com.fersaiyan.cyanbridge.devices.memomind

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.audio.AudioSessionCoordinator
import com.fersaiyan.cyanbridge.audio.AudioSessionGrant
import com.fersaiyan.cyanbridge.audio.AudioSessionHandle
import com.fersaiyan.cyanbridge.audio.AudioSessionListener
import com.fersaiyan.cyanbridge.shared.audio.AudioRoutePreference
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionRequest
import com.fersaiyan.cyanbridge.shared.audio.OpusPacketDecoder
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindAudioListener
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindCaptureOptions
import com.fersaiyan.cyanbridge.shared.devices.memomind.MemoMindSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.Closeable

/** Thrown when another app audio session holds the microphone. */
class MemoMindMicrophoneBusyException(val owner: String) : Exception("Microphone is in use by $owner")

/**
 * The glasses' Opus microphone stream as an app audio session. [open] takes a session from
 * [AudioSessionCoordinator] (route DEVICE_SDK, so the phone's Bluetooth route is untouched),
 * starts the capture and delivers the Opus packets and their 16 kHz mono PCM16LE decoding.
 * When a higher-priority session takes the microphone the capture stops, and it restarts when
 * the microphone comes back (for resumable kinds). [capture] is the same stream for a caller that
 * already holds the session (a voice question's VoiceCapture). The users share the one capture
 * through [MemoMindSession.joinMicrophone]: the last one to start receives the frames.
 */
class MemoMindMicrophone internal constructor(
    private val context: Context,
    private val session: MemoMindSession,
    private val scope: CoroutineScope,
    private val newDecoder: () -> OpusPacketDecoder = ::MemoMindOpusDecoder,
) {
    fun interface PcmListener {
        fun onPcm(pcm: ByteArray)
    }

    inner class Capture internal constructor(
        private val onPcm: PcmListener?,
        private val onOpus: MemoMindAudioListener?,
        private val options: MemoMindCaptureOptions,
    ) : Closeable {
        private val packets = Channel<List<ByteArray>>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        private val decoder = onPcm?.let { newDecoder() }
        private var worker: Job? = null

        /** The coordinator session [open] took; null when the caller holds one ([capture]). */
        internal var handle: AudioSessionHandle? = null

        @Volatile var isClosed = false
            private set

        private val frames = MemoMindAudioListener { frames ->
            onOpus?.onOpusFrames(frames)
            if (onPcm != null) packets.trySend(frames)
        }

        internal val listener = object : AudioSessionListener {
            override fun onSuspended() {
                scope.launch { runCatching { session.leaveMicrophone(this@Capture) } }
            }

            override fun onResumed() {
                if (!isClosed) scope.launch { runCatching { session.joinMicrophone(this@Capture, frames, options) } }
            }

            override fun onEnded() = close()
        }

        /** Starts (or joins) the glasses capture; suspends until it streams, throws when it cannot. */
        suspend fun start() {
            // Under the lock close() takes, so a concurrent close releases the decoder after its worker.
            synchronized(this) {
                if (isClosed) return
                if (onPcm != null && decoder != null && worker == null) {
                    decoder.start()
                    worker = scope.launch(Dispatchers.Default) {
                        for (batch in packets) batch.forEach { packet -> decoder.decode(packet).takeIf { it.isNotEmpty() }?.let(onPcm::onPcm) }
                    }
                }
            }
            // A waiting session starts in onResumed once the microphone is free.
            if (handle?.isActive == false) return
            try {
                session.joinMicrophone(this, frames, options)
            } catch (error: Throwable) {
                close()
                throw error
            }
        }

        override fun close() {
            val decoding = synchronized(this) {
                if (isClosed) return
                isClosed = true
                packets.close()
                worker
            }
            scope.launch {
                runCatching { session.leaveMicrophone(this@Capture) }
                decoding?.join()
                decoder?.release()
                handle?.close()
                Log.i(TAG, "Capture closed")
            }
        }
    }

    /**
     * Opens a capture for [request] with the glasses' [options] (noise and pickup). Throws [MemoMindMicrophoneBusyException] when arbitration
     * denies it, or the start failure of the glasses.
     */
    suspend fun open(
        request: AudioSessionRequest = AudioSessionRequest.VoiceCapture(OWNER, route = AudioRoutePreference.DEVICE_SDK),
        onPcm: PcmListener? = null,
        onOpus: MemoMindAudioListener? = null,
        options: MemoMindCaptureOptions = OPTIONS,
    ): Capture {
        val capture = Capture(onPcm, onOpus, options)
        when (val grant = AudioSessionCoordinator.open(context, request, capture.listener)) {
            is AudioSessionGrant.Denied -> throw MemoMindMicrophoneBusyException(grant.blockingOwner)
            is AudioSessionGrant.Granted -> capture.handle = grant.handle
        }
        capture.start()
        return capture
    }

    /**
     * A capture inside an audio session the caller already holds (a voice question): no second
     * coordinator session. Not started; [Capture.start] starts it and [Capture.close] may come first.
     */
    fun capture(onPcm: PcmListener, options: MemoMindCaptureOptions = OPTIONS): Capture = Capture(onPcm, null, options)

    private companion object {
        const val TAG = "MemoMindMic"
        const val OWNER = "MemoMind microphone"
        val OPTIONS = MemoMindCaptureOptions()
    }
}
