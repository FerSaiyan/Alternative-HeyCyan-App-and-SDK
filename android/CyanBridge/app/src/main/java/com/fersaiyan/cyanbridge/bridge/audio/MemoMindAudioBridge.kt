package com.fersaiyan.cyanbridge.bridge.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.fersaiyan.cyanbridge.audio.AudioSessionCoordinator
import com.fersaiyan.cyanbridge.audio.AudioSessionGrant
import com.fersaiyan.cyanbridge.audio.AudioSessionHandle
import com.fersaiyan.cyanbridge.bridge.devices.memomind.MemoMindConstants
import com.fersaiyan.cyanbridge.bridge.devices.memomind.MemoMindGattClient
import com.fersaiyan.cyanbridge.shared.audio.AudioRoutePreference
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Audio bridge for MemoMind smart glasses.
 *
 * Manages:
 * - BLE recording characteristic discovery (0x2020, 0x2024, 0x2025, 0x2026)
 * - Subscribing to recording data notifications on 0x2020
 * - PCM playback via AudioTrack (for received audio or TTS)
 * - Phone mic recording via AudioRecord with BLE SCO routing
 *
 * ## Recording data flow:
 * ```
 * Glasses mic → BLE 0x2020 notify → handleRecordingNotification()
 * → WqRecordParser → OpusDecoderWrapper → audioData SharedFlow → app
 * ```
 *
 * ## Playback data flow:
 * ```
 * App → playPcmData() → AudioTrack → A2DP → Glasses speaker
 * ```
 *
 * ## Phone mic recording flow:
 * ```
 * Phone mic → AudioRecord → startPhoneMicRecording() Flow → app → (BLE SCO / A2DP)
 * ```
 *
 * Tag: MemoMindAudioBridge
 */
class MemoMindAudioBridge(
    private val context: Context,
    private val gattClient: MemoMindGattClient,
) {
    companion object {
        private const val TAG = "MemoMindAudioBridge"

        /** Opus sample rate used by MemoMind glasses. */
        private const val SAMPLE_RATE = 24000

        /** Mono audio. */
        private const val CHANNELS = 1

        /** 16-bit PCM. */
        private const val BIT_DEPTH = 16
    }

    // ------------------------------------------------------------------
    // Observable state
    // ------------------------------------------------------------------

    private val _isRecording = MutableStateFlow(false)
    /** Whether phone mic recording is active. */
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    /** Whether audio playback is active. */
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // ------------------------------------------------------------------
    // Audio objects
    // ------------------------------------------------------------------

    /** AudioTrack for PCM playback to glasses speakers (A2DP). */
    private var audioTrack: AudioTrack? = null

    /** Session owning the phone mic capture (and its recorder). */
    private var micSession: AudioSessionHandle? = null

    // ------------------------------------------------------------------
    // BLE recording characteristics
    // ------------------------------------------------------------------

    /** Record data characteristic (0x2020) — glasses → phone, Opus encoded. */
    private var recordDataChar: BluetoothGattCharacteristic? = null

    /** Record notify characteristic (0x2024) — glasses → phone, state notifications. */
    private var recordNotifyChar: BluetoothGattCharacteristic? = null

    /** Record write characteristic (0x2025) — phone → glasses, control commands. */
    private var recordWriteChar: BluetoothGattCharacteristic? = null

    /** Record extra / auxiliary characteristic (0x2026) — bidirectional. */
    private var recordExtraChar: BluetoothGattCharacteristic? = null

    // ------------------------------------------------------------------
    // Audio data flow
    // ------------------------------------------------------------------

    private val _audioData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    /**
     * Stream of decoded PCM audio data from the glasses mic.
     *
     * Emits raw 16-bit PCM at 24000 Hz mono after Opus decoding.
     */
    val audioData: SharedFlow<ByteArray> = _audioData.asSharedFlow()

    // ------------------------------------------------------------------
    // BLE characteristic discovery
    // ------------------------------------------------------------------

    /**
     * Locate recording characteristics among the discovered GATT services.
     *
     * Call this after [MemoMindGattClient.connect] succeeds and services are
     * available via [MemoMindGattClient.discoveredServices].
     */
    fun resolveRecordingCharacteristics(services: List<BluetoothGattService>) {
        for (service in services) {
            for (char in service.characteristics) {
                when (char.uuid) {
                    MemoMindConstants.RECORD_DATA_UUID -> {
                        recordDataChar = char
                        Log.i(TAG, "Found record data char (0x2020) in service ${service.uuid}")
                    }
                    MemoMindConstants.RECORD_NOTIFY_UUID -> {
                        recordNotifyChar = char
                        Log.i(TAG, "Found record notify char (0x2024) in service ${service.uuid}")
                    }
                    MemoMindConstants.RECORD_WRITE_UUID -> {
                        recordWriteChar = char
                        Log.i(TAG, "Found record write char (0x2025) in service ${service.uuid}")
                    }
                    MemoMindConstants.RECORD_EXTRA_UUID -> {
                        recordExtraChar = char
                        Log.i(TAG, "Found record extra char (0x2026) in service ${service.uuid}")
                    }
                }
            }
        }
        if (recordDataChar == null) {
            Log.w(TAG, "Record data char (0x2020) NOT found — glasses mic audio unavailable")
        }
        if (recordNotifyChar == null) {
            Log.w(TAG, "Record notify char (0x2024) NOT found")
        }
        if (recordWriteChar == null) {
            Log.w(TAG, "Record write char (0x2025) NOT found")
        }
    }

    // ------------------------------------------------------------------
    // Recording control
    // ------------------------------------------------------------------

    /**
     * Start recording from the glasses mic.
     *
     * Sends a start-recorder command via BLE characteristic 0x2025
     * (or via the command characteristic 0x2001 with recorder serviceId).
     *
     * **Requires BLE sniffing** to determine the exact command bytes:
     * - serviceId byte for recorder (CMD_TYPE_RECORDER = 6)
     * - Payload encoding for start/stop commands
     */
    suspend fun startGlassesRecording(): Result<Unit> {
        val writeChar = recordWriteChar ?: return Result.failure(
            UnsupportedOperationException("Record write characteristic (0x2025) not found")
        )
        // TODO: BLE sniffing needed — determine exact start-recorder command bytes
        Log.i(TAG, "startGlassesRecording — command encoding needs BLE sniffing")
        return Result.failure(
            UnsupportedOperationException(
                "Recording command encoding needs BLE sniffing. " +
                    "See AGENTS.md for protocol analysis notes."
            )
        )
    }

    /**
     * Stop recording from the glasses mic.
     */
    suspend fun stopGlassesRecording(): Result<Unit> {
        val writeChar = recordWriteChar ?: return Result.failure(
            UnsupportedOperationException("Record write characteristic (0x2025) not found")
        )
        Log.i(TAG, "stopGlassesRecording — command encoding needs BLE sniffing")
        return Result.failure(
            UnsupportedOperationException("Recording command encoding needs BLE sniffing")
        )
    }

    // ------------------------------------------------------------------
    // Audio playback (PCM via AudioTrack)
    // ------------------------------------------------------------------

    /**
     * Initialise the AudioTrack for PCM playback.
     *
     * Configures 24000 Hz, mono, 16-bit PCM — matching the format that
     * the glasses Opus decoder produces.
     *
     * Call before [playPcmData] to set up the audio pipeline.
     */
    fun initPlayback() {
        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        Log.i(TAG, "AudioTrack initialised (bufferSize=$bufferSize)")
    }

    /**
     * Play raw PCM audio data through the AudioTrack.
     *
     * The data must be 16-bit PCM at 24000 Hz mono. The AudioTrack starts
     * playing automatically on the first write if not already playing.
     *
     * @param data Raw PCM byte array to play
     */
    fun playPcmData(data: ByteArray) {
        val track = audioTrack
        if (track == null) {
            Log.w(TAG, "playPcmData — AudioTrack not initialised, call initPlayback() first")
            return
        }
        val written = track.write(data, 0, data.size)
        if (written != data.size) {
            Log.w(TAG, "playPcmData — short write: $written / ${data.size}")
        }
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            track.play()
            _isPlaying.value = true
        }
    }

    /**
     * Pause audio playback.
     */
    fun stopPlayback() {
        audioTrack?.pause()
        _isPlaying.value = false
    }

    /**
     * Stop and release the AudioTrack.
     */
    fun releasePlayback() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
        _isPlaying.value = false
    }

    // ------------------------------------------------------------------
    // Phone mic recording
    // ------------------------------------------------------------------

    /**
     * Start recording from the phone microphone.
     *
     * Returns a [Flow] that emits raw 16-bit PCM buffers at 24000 Hz mono.
     * The recording runs until the flow is cancelled or [stopPhoneMicRecording] is called.
     *
     * Use this to capture phone-side audio for routing to the glasses speakers
     * via A2DP or for local processing (e.g. transcription).
     */
    @SuppressLint("MissingPermission")
    fun startPhoneMicRecording(): Flow<ByteArray> = callbackFlow {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            val error = SecurityException("RECORD_AUDIO permission is required")
            Log.w(TAG, error.message.orEmpty())
            close(error)
            return@callbackFlow
        }

        // A continuous phone-mic stream: pauses (drops audio) while a spoken prompt or Gemini
        // Live owns the microphone, and only one such stream runs at a time.
        val session = when (
            val grant = AudioSessionCoordinator.open(
                context,
                AudioSessionRequest.ContinuousRecognition(TAG, route = AudioRoutePreference.SYSTEM_DEFAULT),
            )
        ) {
            is AudioSessionGrant.Granted -> grant.handle
            is AudioSessionGrant.Denied -> {
                val error = IllegalStateException("Microphone is in use: ${grant.reason}")
                Log.w(TAG, error.message.orEmpty())
                close(error)
                return@callbackFlow
            }
        }
        micSession = session

        val recorder = try {
            session.openRecorder(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, { minBuffer -> minBuffer * 2 })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord", e)
            session.close()
            micSession = null
            close(e)
            return@callbackFlow
        }
        val bufferSize = recorder.minBufferBytes

        try {
            recorder.startOrThrow()
            _isRecording.value = true
            Log.i(TAG, "Phone mic recording started (bufferSize=$bufferSize)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord", e)
            session.close()
            micSession = null
            close(e)
            return@callbackFlow
        }

        val buffer = ByteArray(bufferSize)

        // Launch on IO dispatcher inside the callbackFlow scope
        launch(Dispatchers.IO) {
            while (isActive && _isRecording.value) {
                try {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        if (session.isActive) trySend(buffer.copyOf(read))
                    } else if (read < 0) {
                        Log.w(TAG, "AudioRecord read error: $read")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "AudioRecord read exception", e)
                    break
                }
            }
        }

        awaitClose {
            Log.i(TAG, "Phone mic recording stopped")
            _isRecording.value = false
            session.close()
            if (micSession === session) micSession = null
        }
    }

    /**
     * Stop phone mic recording.
     *
     * This signals the recording loop to exit; the [AudioRecord] resources
     * are released in the [kotlinx.coroutines.flow.callbackFlow] [awaitClose] block
     * of [startPhoneMicRecording].
     */
    fun stopPhoneMicRecording() {
        _isRecording.value = false
    }

    // ------------------------------------------------------------------
    // BLE notification handling
    // ------------------------------------------------------------------

    /**
     * Handle incoming recording data from the glasses (0x2020 notifications).
     *
     * This method processes raw BLE notification bytes. The data follows the
     * WQ Record Protocol V2 frame format:
     *
     * ```
     * [magic: 1B] [frameCnt: ?B] [opusPayload: variable] [crc32: 4B optional]
     * ```
     *
     * For now the raw data is emitted on [_audioData] for analysis/logging.
     * Once the WQ frame format is fully confirmed via BLE sniffing, the
     * pipeline will become:
     *
     * ```
     * raw → WqRecordParser → WqFrame → OpusDecoderWrapper → PCM → _audioData
     * ```
     *
     * @param data Raw notification bytes from characteristic 0x2020
     */
    fun handleRecordingNotification(data: ByteArray) {
        // Log raw bytes for protocol analysis
        Log.d(
            TAG,
            "Recording notification: ${data.size} bytes — " +
                data.take(20).joinToString(" ") { "%02x".format(it) },
        )

        // TODO: Parse WQ Record Protocol V2 frame:
        //   [magic: 1B] [frameCnt: ?B] [opusPayload: variable] [crc32: 4B optional]
        // The magic byte and frameCnt encoding need BLE sniffing verification.
        //
        // Once confirmed, integrate WqRecordParser + OpusDecoderWrapper:
        //   val frame = WqRecordParser().parse(data)
        //   if (frame != null) {
        //       val pcm = OpusDecoderWrapper().decode(frame.opusPayload)
        //       if (pcm != null) _audioData.tryEmit(pcm)
        //   }

        // For now, emit raw data for analysis
        _audioData.tryEmit(data)
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Release all resources held by this audio bridge.
     *
     * Call from [MemoMindDeviceAdapter.destroy] to avoid leaking
     * AudioTrack, AudioRecord, and coroutine resources.
     */
    fun release() {
        Log.i(TAG, "release() — releasing audio resources")
        releasePlayback()
        stopPhoneMicRecording()
        micSession?.close()
        micSession = null
    }
}
