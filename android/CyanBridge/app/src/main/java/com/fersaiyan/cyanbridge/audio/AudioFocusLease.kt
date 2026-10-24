package com.fersaiyan.cyanbridge.audio

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.fersaiyan.cyanbridge.shared.audio.AudioFocusMode

/** Focus changes a session owner reacts to. */
enum class AudioFocusChange { LOST, LOST_TRANSIENTLY, GAINED }

/**
 * One audio-focus request for speech (USAGE_VOICE_COMMUNICATION, CONTENT_TYPE_SPEECH;
 * STREAM_VOICE_CALL before Android 8). Owned by an [AudioSessionHandle].
 */
internal class AudioFocusLease(
    private val audioManager: AudioManager,
    private val mode: AudioFocusMode,
    onChange: ((AudioFocusChange) -> Unit)?,
) {
    private var request: AudioFocusRequest? = null
    private var requested = false
    private var held = false

    private val listener: AudioManager.OnAudioFocusChangeListener? = onChange?.let { callback ->
        AudioManager.OnAudioFocusChangeListener { focus ->
            when (focus) {
                AudioManager.AUDIOFOCUS_LOSS -> callback(AudioFocusChange.LOST)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> callback(AudioFocusChange.LOST_TRANSIENTLY)
                AudioManager.AUDIOFOCUS_GAIN -> callback(AudioFocusChange.GAINED)
            }
        }
    }

    val isHeld: Boolean @Synchronized get() = held

    /**
     * Requests focus and returns whether it was granted. Repeated calls re-request with the same
     * client, which moves it back to the top of the focus stack. A no-op for [AudioFocusMode.NONE].
     */
    @Synchronized
    fun request(): Boolean {
        val gain = when (mode) {
            AudioFocusMode.NONE -> return false
            AudioFocusMode.TRANSIENT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioFocusMode.TRANSIENT_EXCLUSIVE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        }
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val built = request ?: AudioFocusRequest.Builder(gain)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .also { builder -> listener?.let(builder::setOnAudioFocusChangeListener) }
                .build()
            request = built
            audioManager.requestAudioFocus(built)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(listener, AudioManager.STREAM_VOICE_CALL, gain)
        }
        requested = true
        held = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return held
    }

    @Synchronized
    fun abandon() {
        if (!requested) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request?.let(audioManager::abandonAudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(listener)
        }
        request = null
        requested = false
        held = false
    }
}
