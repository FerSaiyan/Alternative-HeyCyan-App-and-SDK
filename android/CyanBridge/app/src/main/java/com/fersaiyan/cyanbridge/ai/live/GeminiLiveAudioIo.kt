package com.fersaiyan.cyanbridge.ai.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.ai.live.GeminiLiveProtocol.INPUT_SAMPLE_RATE_HZ
import com.fersaiyan.cyanbridge.ai.live.GeminiLiveProtocol.OUTPUT_SAMPLE_RATE_HZ
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.mentra.MentraLiveManager
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Gemini Live audio I/O: microphone capture (phone AudioRecord or Mentra SDK PCM), model speech
 * playback, communication routing and audio focus. Session decisions stay in [GeminiLiveClient];
 * this component reports back only through the constructor callbacks.
 */
internal class GeminiLiveAudioIo(
    context: Context,
    private val scope: CoroutineScope,
    private val onFocusLost: () -> Unit,
    private val onFocusLostTransiently: () -> Unit,
    private val onFocusGained: () -> Unit,
    private val onRouteChanged: () -> Unit,
    private val onCaptureError: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** True while microphone input should be forwarded to Gemini. */
    val captureEnabled = AtomicBoolean(false)

    /** True while the Mentra SDK microphone is the PCM source. */
    @Volatile var mentraMicActive = false
        private set

    private var recorder: AudioRecord? = null
    private var recorderJob: Job? = null
    private var playback: AudioTrack? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var previousAudioMode: Int? = null
    private var previousCommunicationDevice: AudioDeviceInfo? = null
    private var liveAudioRouteConfigured = false

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focus ->
        when (focus) {
            AudioManager.AUDIOFOCUS_LOSS -> onFocusLost()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onFocusLostTransiently()
            AudioManager.AUDIOFOCUS_GAIN -> onFocusGained()
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = onRouteChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = onRouteChanged()
    }

    fun registerDeviceCallback() {
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    fun unregisterDeviceCallback() {
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
    }

    fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun isMentraSelected(): Boolean =
        DeviceProfileStore.selectedClass(appContext) == DeviceClass.MENTRA_LIVE

    /**
     * Uses Mentra SDK PCM as the ONLY input. SCO and a second AudioRecord can duplicate audio
     * and force output away from Mentra's Bluetooth media profile.
     */
    fun startMentraInput(onPcm: (ByteArray) -> Unit) {
        val manager = MentraLiveManager.getInstance(appContext)
        if (!manager.connected || !manager.ready) {
            captureEnabled.set(false)
            onCaptureError("Mentra Live is not ready")
            return
        }
        startPlayback()
        mentraMicActive = true
        manager.setPcmListener(onPcm)
        runCatching { manager.setMicrophoneEnabled(true) }.onFailure { error ->
            manager.setPcmListener(null)
            mentraMicActive = false
            captureEnabled.set(false)
            onCaptureError(error.message ?: "Mentra microphone failed")
        }
    }

    /** Routes to communication audio and reads 40 ms packets while [keepRunning] and capture are on. */
    fun startPhoneInput(keepRunning: () -> Boolean, onPcm: (ByteArray) -> Unit) {
        configureAudioRoute()
        startPlayback()
        val minBuffer = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            captureEnabled.set(false)
            onCaptureError("Microphone is unavailable")
            return
        }
        val newRecorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            INPUT_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, INPUT_CHUNK_BYTES * 4),
        )
        if (newRecorder.state != AudioRecord.STATE_INITIALIZED) {
            captureEnabled.set(false)
            newRecorder.release()
            onCaptureError("Microphone could not be initialized")
            return
        }
        recorder = newRecorder
        recorderJob = scope.launch {
            val buffer = ByteArray(INPUT_CHUNK_BYTES)
            newRecorder.startRecording()
            while (keepRunning() && captureEnabled.get()) {
                val count = newRecorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count > 0) {
                    val packet = if (count == buffer.size) buffer else buffer.copyOf(count)
                    onPcm(packet)
                }
            }
        }
    }

    fun stopMentraInput() {
        if (mentraMicActive) {
            mentraMicActive = false
            runCatching {
                MentraLiveManager.getInstance(appContext).apply {
                    setPcmListener(null)
                    setMicrophoneEnabled(false)
                    setOwnAudioPlaying(false)
                }
            }
        }
    }

    fun stopPhoneInput() {
        recorderJob?.cancel()
        recorderJob = null
        recorder?.let {
            runCatching { it.stop() }
            it.release()
        }
        recorder = null
    }

    private fun startPlayback() {
        if (playback != null) return
        val minBuffer = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(OUTPUT_SAMPLE_RATE_HZ / 5)
        playback = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(OUTPUT_SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    /** Blocking write of 24 kHz PCM16; [onWritten] receives each successful write's byte count. */
    fun play(bytes: ByteArray, onWritten: (Int) -> Unit) {
        if (playback == null) startPlayback()
        setMentraOwnAudioPlaying(true)
        val track = playback ?: return
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            runCatching { track.play() }
        }
        var offset = 0
        while (offset < bytes.size) {
            val written = track.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) {
                Log.w(TAG, "Live audio playback write failed result=$written remaining=${bytes.size - offset}")
                break
            }
            offset += written
            onWritten(written)
        }
    }

    /** Drops queued model speech after a server interruption; the track stays allocated. */
    fun interruptPlayback() {
        playback?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
        }
    }

    /** Tells Mentra glasses whether app audio is playing, when Mentra is the selected device. */
    fun setMentraOwnAudioPlaying(playing: Boolean) {
        if (isMentraSelected()) {
            runCatching { MentraLiveManager.getInstance(appContext).setOwnAudioPlaying(playing) }
        }
    }

    fun stopPlayback() {
        setMentraOwnAudioPlaying(false)
        playback?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            it.release()
        }
        playback = null
    }

    private fun configureAudioRoute() {
        if (liveAudioRouteConfigured) return
        previousAudioMode = audioManager.mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            previousCommunicationDevice = audioManager.communicationDevice
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val bluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        } else {
            null
        }
        val selected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && bluetooth != null) {
            audioManager.setCommunicationDevice(bluetooth)
        } else {
            false
        }
        @Suppress("DEPRECATION")
        runCatching { audioManager.startBluetoothSco() }
        liveAudioRouteConfigured = true
        Log.i(TAG, "Live audio route selectedBluetooth=$selected device=${bluetooth?.productName}")
    }

    fun restoreAudioRoute() {
        if (!liveAudioRouteConfigured) return
        @Suppress("DEPRECATION")
        runCatching { audioManager.stopBluetoothSco() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val previous = previousCommunicationDevice
            if (previous != null) runCatching { audioManager.setCommunicationDevice(previous) }
            else runCatching { audioManager.clearCommunicationDevice() }
        }
        previousAudioMode?.let { audioManager.mode = it }
        previousAudioMode = null
        previousCommunicationDevice = null
        liveAudioRouteConfigured = false
    }

    fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .build(),
                )
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            )
        }
    }

    fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
        audioFocusRequest = null
    }

    private companion object {
        const val TAG = "GeminiLiveClient"
        const val INPUT_CHUNK_BYTES = 1_280 // 40 ms PCM16 mono at 16 kHz; halves WS message rate vs 20 ms.
    }
}
