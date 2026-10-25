package com.fersaiyan.cyanbridge.devices.memomind

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.audio.AudioSessionCoordinator
import com.fersaiyan.cyanbridge.audio.AudioSessionGrant
import com.fersaiyan.cyanbridge.audio.AudioSessionHandle
import com.fersaiyan.cyanbridge.audio.AudioSessionListener
import com.fersaiyan.cyanbridge.shared.audio.AudioRoutePreference
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionRequest
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
 * the microphone comes back (for resumable kinds).
 */
class MemoMindMicrophone internal constructor(
    private val context: Context,
    private val session: MemoMindSession,
    private val scope: CoroutineScope,
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
        private val decoder = MemoMindOpusDecoder()
        private var worker: Job? = null
        internal var handle: AudioSessionHandle? = null

        @Volatile var isClosed = false
            private set

        internal val listener = object : AudioSessionListener {
            override fun onSuspended() {
                scope.launch { runCatching { session.stopMicrophone() } }
            }

            override fun onResumed() {
                if (!isClosed) scope.launch { runCatching { session.startMicrophone(options) } }
            }

            override fun onEnded() = close()
        }

        internal suspend fun start() {
            if (onPcm != null) {
                decoder.start()
                worker = scope.launch(Dispatchers.Default) {
                    for (frames in packets) frames.forEach { packet -> decoder.decode(packet).takeIf { it.isNotEmpty() }?.let(onPcm::onPcm) }
                }
            }
            session.audioListener = MemoMindAudioListener { frames ->
                onOpus?.onOpusFrames(frames)
                if (onPcm != null) packets.trySend(frames)
            }
            // A waiting session starts in onResumed once the microphone is free.
            if (handle?.isActive == true) session.startMicrophone(options)
        }

        override fun close() {
            if (isClosed) return
            isClosed = true
            session.audioListener = null
            packets.close()
            scope.launch {
                runCatching { session.stopMicrophone() }
                worker?.join()
                decoder.release()
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
        try {
            capture.start()
        } catch (error: Throwable) {
            capture.close()
            throw error
        }
        return capture
    }

    private companion object {
        const val TAG = "MemoMindMic"
        const val OWNER = "MemoMind microphone"
        val OPTIONS = MemoMindCaptureOptions()
    }
}
