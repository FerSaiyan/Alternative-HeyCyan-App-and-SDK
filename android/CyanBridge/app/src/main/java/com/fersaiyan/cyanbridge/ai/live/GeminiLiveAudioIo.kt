package com.fersaiyan.cyanbridge.ai.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.ai.live.GeminiLiveProtocol.INPUT_SAMPLE_RATE_HZ
import com.fersaiyan.cyanbridge.ai.live.GeminiLiveProtocol.OUTPUT_SAMPLE_RATE_HZ
import com.fersaiyan.cyanbridge.audio.AudioFocusChange
import com.fersaiyan.cyanbridge.audio.AudioSessionCoordinator
import com.fersaiyan.cyanbridge.audio.AudioSessionGrant
import com.fersaiyan.cyanbridge.audio.AudioSessionHandle
import com.fersaiyan.cyanbridge.audio.AudioSessionListener
import com.fersaiyan.cyanbridge.audio.PcmRecorder
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.mentra.MentraLiveManager
import com.fersaiyan.cyanbridge.shared.audio.AudioRoutePolicy
import com.fersaiyan.cyanbridge.shared.audio.AudioRoutePreference
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionRequest
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Gemini Live audio I/O: microphone capture (phone AudioRecord or Mentra SDK PCM), model speech
 * playback, and the Live [AudioSessionRequest.Conversation] session that owns communication
 * routing, audio focus and the microphone slot. Session decisions stay in [GeminiLiveClient];
 * this component reports back only through the constructor callbacks.
 *
 * A spoken prompt (approval, voice question) suspends the conversation like a transient focus
 * loss: [onFocusLostTransiently] pauses capture and [onFocusGained] resumes it afterwards.
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

    private var recorder: PcmRecorder? = null
    private var recorderJob: Job? = null
    private var playback: AudioTrack? = null

    @Volatile
    private var session: AudioSessionHandle? = null
    private var liveAudioRouteConfigured = false

    private val sessionListener = object : AudioSessionListener {
        override fun onSuspended() {
            Log.i(TAG, "Live microphone suspended for another audio session")
            onFocusLostTransiently()
        }

        override fun onResumed() {
            Log.i(TAG, "Live microphone available again")
            onFocusGained()
        }

        override fun onEnded() = onFocusLost()
    }

    private fun onFocusChange(change: AudioFocusChange) = when (change) {
        AudioFocusChange.LOST -> onFocusLost()
        AudioFocusChange.LOST_TRANSIENTLY -> onFocusLostTransiently()
        AudioFocusChange.GAINED -> onFocusGained()
    }

    /** False while another audio session holds the microphone (capture must wait). */
    val microphoneAvailable: Boolean get() = session?.isActive == true

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

    private fun deviceAudio() = GlassesFamilyProfiles.forClass(DeviceProfileStore.selectedClass(appContext)).audio

    /** True when the selected glasses stream microphone PCM through their SDK (Mentra Live). */
    fun usesVendorPcmInput(): Boolean =
        AudioRoutePolicy.forDevice(AudioRoutePreference.COMMUNICATION_BLUETOOTH_PREFERRED, deviceAudio()) ==
            AudioRoutePreference.DEVICE_SDK

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
        val owner = session
        val newRecorder = try {
            checkNotNull(owner) { "Live audio session is not open" }.openRecorder(
                source = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRateHz = INPUT_SAMPLE_RATE_HZ,
                bufferBytes = { minBuffer -> maxOf(minBuffer, INPUT_CHUNK_BYTES * 4) },
            )
        } catch (error: PcmRecorder.OpenException) {
            captureEnabled.set(false)
            onCaptureError(
                if (error.failure == PcmRecorder.Failure.NO_BUFFER) "Microphone is unavailable"
                else "Microphone could not be initialized",
            )
            return
        } catch (error: IllegalStateException) {
            captureEnabled.set(false)
            onCaptureError("Microphone could not be initialized")
            return
        }
        recorder = newRecorder
        recorderJob = scope.launch {
            val buffer = ByteArray(INPUT_CHUNK_BYTES)
            newRecorder.start()
            while (keepRunning() && captureEnabled.get()) {
                val count = newRecorder.read(buffer, 0, buffer.size, blocking = true)
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
        recorder?.let { active -> session?.closeRecorder(active) ?: active.close() }
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

    /** Tells Mentra glasses whether app audio is playing, when the selected glasses need that signal. */
    fun setMentraOwnAudioPlaying(playing: Boolean) {
        if (AudioRoutePolicy.needsPlaybackSignal(deviceAudio())) {
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
        val route = session?.connectRouteNow() ?: return
        liveAudioRouteConfigured = true
        Log.i(TAG, "Live audio route selectedBluetooth=${route.bluetoothActive} device=${route.inputDevice?.productName}")
    }

    /**
     * Opens the Live conversation session on first use and (re)asserts its audio focus. Mentra
     * input leaves phone routing alone. A session that must wait for the microphone reports
     * [microphoneAvailable] false until the blocking session ends.
     */
    fun requestAudioFocus() {
        val current = session ?: synchronized(this) {
            session ?: openSession()?.also { session = it }
        } ?: return
        current.requestFocus(::onFocusChange)
    }

    private fun openSession(): AudioSessionHandle? {
        val route = AudioRoutePolicy.forDevice(AudioRoutePreference.COMMUNICATION_BLUETOOTH_PREFERRED, deviceAudio())
        return when (
            val grant = AudioSessionCoordinator.open(appContext, AudioSessionRequest.Conversation(TAG, route), sessionListener)
        ) {
            is AudioSessionGrant.Granted -> grant.handle
            is AudioSessionGrant.Denied -> {
                Log.w(TAG, "Live audio session denied: ${grant.reason}")
                null
            }
        }
    }

    /** Ends the Live session: releases focus, the shared route and the microphone slot. */
    fun endSession() {
        val ended = synchronized(this) { session.also { session = null } }
        liveAudioRouteConfigured = false
        ended?.close()
    }

    private companion object {
        const val TAG = "GeminiLiveClient"
        const val INPUT_CHUNK_BYTES = 1_280 // 40 ms PCM16 mono at 16 kHz; halves WS message rate vs 20 ms.
    }
}
