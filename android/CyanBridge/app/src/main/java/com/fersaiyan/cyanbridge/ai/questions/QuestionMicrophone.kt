package com.fersaiyan.cyanbridge.ai.questions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.ai.image.AudioQuestionRecorder
import com.fersaiyan.cyanbridge.ai.image.GlassesQuestionTestHooks
import com.fersaiyan.cyanbridge.ai.router.QuestionRoutingPrefs
import com.fersaiyan.cyanbridge.ai.speaker.VoiceProfile
import com.fersaiyan.cyanbridge.ai.transcription.OfflineQuestionTranscriber
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionDefaults
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.shared.assistant.CapturedQuestion
import com.fersaiyan.cyanbridge.shared.assistant.SpokenQuestionMicrophone
import com.fersaiyan.cyanbridge.shared.audio.AudioRoutePolicy
import com.fersaiyan.cyanbridge.shared.audio.AudioRoutePreference
import com.fersaiyan.cyanbridge.shared.devices.GlassesAudioProfile
import com.fersaiyan.cyanbridge.shared.settings.QuestionRoutingMode
import com.oudmon.ble.base.bluetooth.DeviceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * The glasses microphone for questions: brings up the Bluetooth headset route, plays the beep and
 * the spoken cue, records until the question ends, then (unless a model hears the recording)
 * transcribes it on the phone. One question records at a time.
 *
 * Glasses whose SDK streams microphone PCM (Mentra Live, [AudioRoutePreference.DEVICE_SDK]) are
 * recorded from that stream, so no Bluetooth call route is opened next to it.
 */
internal class QuestionMicrophone(
    context: Context,
    private val speaker: AssistantSpeaker,
    private val scope: () -> CoroutineScope,
    private val languageTag: () -> String,
    private val deviceAudio: () -> GlassesAudioProfile?,
    /** The question was recorded: announce a cold local-model load if one follows. */
    private val onQuestionRecorded: () -> Unit,
    /** A model hears the recording, so no on-device transcript is needed. */
    private val audioGoesToModel: () -> Boolean,
) : SpokenQuestionMicrophone {
    private val appContext = context.applicationContext

    /** The Bluetooth call route a question or voice query selected; cleared by its owner only. */
    class RouteOwner(val queryToken: Any, val audioManager: AudioManager)

    val activeRoute = AtomicReference<RouteOwner?>(null)
    private val mutex = Mutex()

    /** The selected glasses deliver the microphone through their SDK: no Bluetooth call route. */
    fun usesDeviceSdkRoute(): Boolean =
        AudioRoutePolicy.forDevice(AudioRoutePreference.COMMUNICATION_BLUETOOTH_PREFERRED, deviceAudio()) ==
            AudioRoutePreference.DEVICE_SDK

    override suspend fun recordQuestion(onsetTimeoutMs: Long): CapturedQuestion =
        capture(onsetTimeoutMs, voiceOnly = true, transcribe = false)

    override fun deleteRecording(audioPath: String) {
        File(audioPath).delete()
    }

    suspend fun capture(
        timeoutMs: Long,
        voiceOnly: Boolean = false,
        /** null: on-device STT only when no model can hear the recording (see processRecordedQuestion). */
        transcribe: Boolean? = null,
    ): CapturedQuestion = mutex.withLock {
        if (GlassesQuestionTestHooks.questionAudioActive) {
            Log.i("ImageQuestionAudio", "Using test question audio instead of the microphone")
            val fixture = GlassesQuestionTestHooks.takeQuestionAudio(appContext.cacheDir)
                ?: return@withLock CapturedQuestion(null, false)
            return@withLock processRecordedQuestion(fixture, voiceOnly, transcribe)
        }
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val deviceSdk = usesDeviceSdkRoute()
        val owner = RouteOwner(Any(), audioManager)
        if (!deviceSdk) activeRoute.set(owner)
        var audioFile: File? = null
        try {
            withContext(Dispatchers.Main) {
                if (deviceSdk) {
                    Log.i("ImageQuestionAudio", "Question route: glasses SDK microphone, no Bluetooth call route")
                } else {
                    val ready = startBluetoothMicRouteAndAwait(audioManager)
                    Log.i("ImageQuestionAudio", "Question route ready=$ready ${audioRouteSummary(audioManager)}")
                    if (Build.VERSION.SDK_INT >= 31 && audioManager.availableCommunicationDevices.any {
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                        }) check(ready) { "Could not connect the glasses microphone" }
                }
                playTone(android.media.ToneGenerator.TONE_PROP_BEEP)
                speakCue(voiceOnly)
            }
            val communicationName = if (Build.VERSION.SDK_INT >= 31) audioManager.communicationDevice?.productName?.toString() else null
            val input = if (deviceSdk) {
                null
            } else {
                audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                    (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)) &&
                        (communicationName == null || it.productName.toString() == communicationName)
                }
            }
            audioFile = AudioQuestionRecorder(appContext).record(timeoutMs, input)
            withContext(Dispatchers.Main) { playTone(android.media.ToneGenerator.TONE_PROP_BEEP2) }
        } catch (cancelled: CancellationException) {
            audioFile?.delete()
            throw cancelled
        } catch (error: Exception) {
            audioFile?.delete()
            Log.w("ImageQuestionAudio", "Question capture failed", error)
            return@withLock CapturedQuestion(null, false, error = error.message ?: "Microphone capture failed")
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                if (activeRoute.compareAndSet(owner, null)) {
                    if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice()
                    audioManager.isBluetoothScoOn = false
                    audioManager.stopBluetoothSco()
                    audioManager.mode = AudioManager.MODE_NORMAL
                }
            }
        }
        val recorded = audioFile ?: return@withLock CapturedQuestion(null, false)
        processRecordedQuestion(recorded, voiceOnly, transcribe)
    }

    /** Everything after capture: load cue, speaker observation, on-device transcript or the raw audio. */
    private suspend fun processRecordedQuestion(recorded: File, voiceOnly: Boolean, transcribe: Boolean? = null): CapturedQuestion {
        onQuestionRecorded()
        observeQuestionSpeaker(recorded, flow = if (voiceOnly) "voice" else "image")
        // Audio is the default: the model hears the recording directly (faster, works in every
        // language). On-device STT is the fallback for text-only models and non-local routes.
        val useStt = transcribe ?: withContext(Dispatchers.IO) { !audioGoesToModel() }
        if (!useStt) return CapturedQuestion(null, true, recorded.absolutePath)
        return try {
            val text = OfflineQuestionTranscriber(appContext).transcribe(recorded, languageTag())
            if (text != null) {
                recorded.delete()
                Log.i("ImageQuestionAudio", "On-device transcript ready chars=${text.length}")
                CapturedQuestion(text, true)
            } else {
                Log.i("ImageQuestionAudio", "On-device STT failed/unavailable; handing original audio to model")
                CapturedQuestion(null, true, recorded.absolutePath)
            }
        } catch (cancelled: CancellationException) {
            recorded.delete()
            throw cancelled
        }
    }

    /**
     * Speaker-verification foundation: with an enrolled voice profile and a non-classic routing
     * mode, score the question against the wearer's template and log the numbers. Never gates.
     */
    private suspend fun observeQuestionSpeaker(wav: File, flow: String) {
        if (QuestionRoutingPrefs.mode(appContext) == QuestionRoutingMode.CLASSIC) return
        val pcm = withContext(Dispatchers.IO) {
            if (!VoiceProfile.isEnrolled(appContext)) return@withContext null
            runCatching { readWavPcm16(wav) }.getOrNull()
        } ?: return
        scope().launch(Dispatchers.Default) { VoiceProfile.observe(appContext, pcm, flow) }
    }

    /** Reads AudioQuestionRecorder's canonical 44-byte-header PCM16 WAV. */
    private fun readWavPcm16(wav: File): ShortArray {
        val bytes = wav.readBytes()
        require(bytes.size >= 44) { "Truncated WAV" }
        val buffer = java.nio.ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(buffer.remaining()).also { buffer.get(it) }
    }

    private suspend fun playTone(toneType: Int) {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val tone = android.media.ToneGenerator(AudioManager.STREAM_VOICE_CALL, 90)
        try {
            val played = tone.startTone(toneType, 240)
            Log.i(
                "ImageQuestionAudio",
                "Image-question tone type=$toneType played=$played route=${audioRouteSummary(audioManager)}",
            )
            delay(300L)
        } finally {
            tone.release()
        }
    }

    private suspend fun speakCue(voiceOnly: Boolean = false) {
        suspendCancellableCoroutine { cont ->
            val completed = AtomicBoolean(false)
            val utteranceId = "image_question_cue_${System.nanoTime()}"
            fun complete(reason: String) {
                if (completed.compareAndSet(false, true)) {
                    speaker.forgetUtterance(utteranceId)
                    Log.i("ImageQuestionAudio", "Image-question cue complete reason=$reason id=$utteranceId")
                    // Keep only enough separation to avoid clipping the route transition. The
                    // reclaimed tail time is added to the initial listening window.
                    scope().launch {
                        delay(CUE_BLUETOOTH_TAIL_MS)
                        if (cont.isActive) {
                            cont.resume(Unit)
                        }
                    }
                }
            }

            val questionSettings = ImageQuestionPreferences.get(appContext)
            val cue = if (voiceOnly) ImageQuestionDefaults.listeningCueForLanguage(questionSettings.appLanguageTag)
                else ImageQuestionDefaults.questionCueForLanguage(questionSettings.appLanguageTag)
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            speaker.configureForVoiceCommunication("image-question cue")
            Log.i(
                "ImageQuestionAudio",
                "Queueing image-question cue='$cue' language=${questionSettings.appLanguageTag} " +
                    "attributes=voice_communication/speech " +
                    "ttsReady=${speaker.ready} route=${audioRouteSummary(audioManager)}",
            )
            speaker.speak(
                text = cue,
                languageTag = questionSettings.appLanguageTag,
                utteranceId = utteranceId,
                onDone = { complete("tts callback") },
                streamType = AudioManager.STREAM_VOICE_CALL,
            )
            // Avoid blocking the image question if the system TTS service never returns a callback.
            scope().launch {
                delay(2_000L)
                complete("2s fallback")
            }
            cont.invokeOnCancellation {
                speaker.forgetUtterance(utteranceId)
            }
        }
    }

    private fun startBluetoothMicRoute(audioManager: AudioManager) {
        runCatching {
            Log.i("ImageQuestionAudio", "Selecting Bluetooth microphone route: ${audioRouteSummary(audioManager)}")
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices.filter {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                val glassesName = runCatching { DeviceManager.getInstance().deviceName }.getOrNull()
                val device = devices.firstOrNull { it.productName.toString().equals(glassesName, ignoreCase = true) }
                    ?: devices.singleOrNull()
                check(devices.size <= 1 || device != null) { "Multiple Bluetooth microphones are connected; could not identify the glasses" }
                if (device != null) {
                    val selected = audioManager.setCommunicationDevice(device)
                    Log.i(
                        "ImageQuestionAudio",
                        "Communication device candidate type=${device.type} name=${device.productName} " +
                            "selected=$selected route=${audioRouteSummary(audioManager)}",
                    )
                    if (selected) return
                }
            }
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
            Log.i("ImageQuestionAudio", "Requested legacy Bluetooth SCO route: ${audioRouteSummary(audioManager)}")
        }.onFailure {
            Log.w("AIHijack", "Bluetooth microphone route unavailable; using phone microphone", it)
        }
    }

    private suspend fun startBluetoothMicRouteAndAwait(
        audioManager: AudioManager,
        timeoutMs: Long = BLUETOOTH_ROUTE_TIMEOUT_MS,
    ): Boolean {
        val connected = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                Log.i("ImageQuestionAudio", "Bluetooth SCO state=$state route=${audioRouteSummary(audioManager)}")
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> connected.complete(true)
                    AudioManager.SCO_AUDIO_STATE_ERROR -> connected.complete(false)
                }
            }
        }
        var receiverRegistered = false

        return try {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
            startBluetoothMicRoute(audioManager)

            val ready: Boolean = if (isBluetoothCommunicationDeviceSelected(audioManager)) {
                true
            } else {
                withTimeoutOrNull<Boolean>(timeoutMs) {
                    while (!isBluetoothCommunicationDeviceSelected(audioManager) && !connected.isCompleted) {
                        delay(100L)
                    }
                    isBluetoothCommunicationDeviceSelected(audioManager) || connected.await()
                } ?: false
            }
            if (ready) delay(CUE_ROUTE_SETTLE_MS)
            ready
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("ImageQuestionAudio", "Could not confirm Bluetooth communication route", error)
            false
        } finally {
            if (receiverRegistered) runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    private fun isBluetoothCommunicationDeviceSelected(audioManager: AudioManager): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return when (audioManager.communicationDevice?.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET -> true
            else -> false
        }
    }

    private fun audioRouteSummary(audioManager: AudioManager): String {
        val communicationDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.let { "${it.type}:${it.productName}" } ?: "none"
        } else {
            "unsupported"
        }
        val devices = audioManager.getDevices(
            AudioManager.GET_DEVICES_INPUTS or
                AudioManager.GET_DEVICES_OUTPUTS,
        )
            .joinToString { "${it.type}:${it.productName}" }
        return "mode=${audioManager.mode}, sco=${audioManager.isBluetoothScoOn}, " +
            "communication=$communicationDevice, devices=[$devices]"
    }

    /** Ends a voice query's call route at teardown (the activity used to do this in onDestroy). */
    fun clearRouteForTeardown() {
        activeRoute.getAndSet(null)?.audioManager?.let { audioManager ->
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                }
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        }
    }

    private companion object {
        const val CUE_ROUTE_SETTLE_MS = 500L
        const val BLUETOOTH_ROUTE_TIMEOUT_MS = 3_000L
        const val CUE_BLUETOOTH_TAIL_MS = 50L
    }
}
