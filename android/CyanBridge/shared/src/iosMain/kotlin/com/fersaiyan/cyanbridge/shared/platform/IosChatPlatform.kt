package com.fersaiyan.cyanbridge.shared.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.useContents
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetooth
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionPortBluetoothHFP
import platform.AVFAudio.AVAudioSessionPortBuiltInMic
import platform.AVFAudio.currentRoute
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.availableInputs
import platform.AVFAudio.setActive
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSLocale
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.preferredLanguages
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIPasteboard
import platform.UIKit.popoverPresentationController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/** Photos picker, microphone capture, speech output and text sharing for the iOS host. */
@OptIn(ExperimentalForeignApi::class)
object IosChatPlatform {
    private const val MAX_IMAGE_SIDE = 1600.0
    private const val TAG = "IosChatPlatform"
    /** Peak level below this is treated as silence (speech near a mic peaks around -20 dB). */
    private const val SILENCE_DB = -42f
    private var pickerDelegate: ImagePickerDelegate? = null
    private var recorder: AVAudioRecorder? = null
    private var recordingUrl: NSURL? = null
    private val synthesizer = AVSpeechSynthesizer()
    private val speechDelegate = SpeechDelegate()

    fun installSharedChatHooks() {
        SharedChatHooks.pickImage = ::pickImage
        SharedChatHooks.startAudioRecording = ::startRecording
        SharedChatHooks.stopAudioRecording = ::stopRecording
        SharedChatHooks.audioMimeType = "audio/mp4"
        SharedChatHooks.copyText = { text -> UIPasteboard.generalPasteboard.string = text }
        SharedChatHooks.shareText = ::shareText
    }

    suspend fun pickImage(): ByteArray? = suspendCancellableCoroutine { continuation ->
        val presenter = IosMediaPlatform.topViewController()
        if (presenter == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        val configuration = PHPickerConfiguration()
        configuration.filter = PHPickerFilter.imagesFilter
        configuration.selectionLimit = 1
        val picker = PHPickerViewController(configuration = configuration)
        val delegate = ImagePickerDelegate { bytes ->
            pickerDelegate = null
            if (continuation.isActive) continuation.resume(bytes)
        }
        pickerDelegate = delegate // PHPickerViewController holds its delegate weakly.
        picker.delegate = delegate
        presenter.presentViewController(picker, animated = true, completion = null)
    }

    /** Records AAC audio, preferring the glasses' Bluetooth (HFP) microphone when connected. */
    suspend fun startRecording(): Boolean {
        if (!requestMicrophonePermission()) return false
        configureRecordingSession()
        val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + "cyanbridge-voice.m4a")
        val audioRecorder = AVAudioRecorder(uRL = url, settings = aacRecordingSettings(), error = null)
        if (!audioRecorder.record()) return false
        recorder = audioRecorder
        recordingUrl = url
        return true
    }

    internal suspend fun requestMicrophonePermission(): Boolean = suspendCancellableCoroutine { continuation ->
        AVAudioSession.sharedInstance().requestRecordPermission { allowed ->
            if (continuation.isActive) continuation.resume(allowed)
        }
    }

    /** Play-and-record session that prefers the glasses' HFP mic; returns a source label. */
    internal fun configureRecordingSession(): String {
        val session = AVAudioSession.sharedInstance()
        session.setCategory(
            AVAudioSessionCategoryPlayAndRecord,
            withOptions = AVAudioSessionCategoryOptionAllowBluetooth or AVAudioSessionCategoryOptionDefaultToSpeaker,
            error = null,
        )
        session.setActive(true, error = null)
        val bluetoothInput = session.availableInputs
            ?.filterIsInstance<AVAudioSessionPortDescription>()
            ?.firstOrNull { it.portType == AVAudioSessionPortBluetoothHFP }
        return if (bluetoothInput != null) {
            session.setPreferredInput(bluetoothInput, error = null)
            "Glasses microphone (Bluetooth)"
        } else {
            "iPhone microphone"
        }
    }

    /** 16 kHz mono AAC keeps speech small enough for relay transcription. */
    internal fun aacRecordingSettings(): Map<Any?, Any?> = mapOf(
        AVFormatIDKey to kAudioFormatMPEG4AAC.toInt(),
        AVSampleRateKey to 16_000.0,
        AVNumberOfChannelsKey to 1,
    )

    suspend fun stopRecording(): ByteArray? {
        val audioRecorder = recorder ?: return null
        audioRecorder.stop()
        recorder = null
        delay(150L) // let AVAudioRecorder finalize the file
        val url = recordingUrl ?: return null
        return NSData.dataWithContentsOfURL(url)?.toKotlinBytes()
    }

    /** Records for a fixed window; used by the glasses voice-question flow. */
    suspend fun recordFor(millis: Long): ByteArray? = recordVoiceQuestion(millis, onRetryWithPhoneMic = {})?.audio

    class VoiceRecording(val audio: ByteArray, val source: String, val peakDb: Float)

    /**
     * Records a voice question from the glasses' Bluetooth (HFP) mic, waiting for the route to
     * come up first. If that mic only captured silence, [onRetryWithPhoneMic] runs and the
     * question is recorded again with the iPhone microphone.
     */
    suspend fun recordVoiceQuestion(millis: Long, onRetryWithPhoneMic: () -> Unit): VoiceRecording? {
        if (!requestMicrophonePermission()) return null
        val wantsGlassesMic = configureRecordingSession() != "iPhone microphone"
        if (wantsGlassesMic) awaitBluetoothInput()
        // Spoken cue through the glasses (Android: ImageQuestionDefaults.questionCueForLanguage).
        speak(questionCue())
        val first = recordMetered(millis) ?: return null
        PlatformLogger.i(TAG, "Voice question: ${first.source}, peak ${first.peakDb.toInt()} dB, ${first.audio.size} bytes")
        if (first.peakDb > SILENCE_DB || !wantsGlassesMic) return first
        onRetryWithPhoneMic()
        val session = AVAudioSession.sharedInstance()
        session.availableInputs?.filterIsInstance<AVAudioSessionPortDescription>()
            ?.firstOrNull { it.portType == AVAudioSessionPortBuiltInMic }
            ?.let { session.setPreferredInput(it, error = null) }
        speak(questionCue())
        val second = recordMetered(millis) ?: return first
        PlatformLogger.i(TAG, "Voice question retry: ${second.source}, peak ${second.peakDb.toInt()} dB")
        return second
    }

    /** Same short prompts as Android's questionCueForLanguage, in the app language. */
    fun questionCue(): String = when (preferredLanguageCode()) {
        "pt" -> "Pergunte."
        "es" -> "Pregunta."
        "de" -> "Frag."
        "fr" -> "Demandez."
        "it" -> "Chiedi."
        "zh" -> "请提问。"
        "ko" -> "질문하세요."
        "ru" -> "Спросите."
        else -> "Ask."
    }

    /** Spoken while an answer is being prepared. */
    fun waitCue(): String = when (preferredLanguageCode()) {
        "pt" -> "Um momento."
        "es" -> "Un momento."
        "de" -> "Einen Moment."
        "fr" -> "Un instant."
        "it" -> "Un momento."
        "zh" -> "请稍等。"
        "ko" -> "잠시만요."
        "ru" -> "Минутку."
        else -> "One moment."
    }

    private fun preferredLanguageCode(): String =
        ((NSLocale.preferredLanguages.firstOrNull() as? String) ?: "en").substringBefore('-').lowercase()

    /** HFP takes a moment to switch the route after setPreferredInput. */
    internal suspend fun awaitBluetoothInput() {
        repeat(25) {
            if (currentInputName(onlyBluetooth = true) != null) return
            delay(100L)
        }
        PlatformLogger.w(TAG, "Glasses microphone route did not become active")
    }

    internal fun currentInputName(onlyBluetooth: Boolean = false): String? =
        AVAudioSession.sharedInstance().currentRoute.inputs
            .filterIsInstance<AVAudioSessionPortDescription>()
            .firstOrNull { !onlyBluetooth || it.portType == AVAudioSessionPortBluetoothHFP }
            ?.portName

    private suspend fun recordMetered(millis: Long): VoiceRecording? {
        val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + "cyanbridge-voice.m4a")
        val audioRecorder = AVAudioRecorder(uRL = url, settings = aacRecordingSettings(), error = null)
        audioRecorder.meteringEnabled = true
        if (!audioRecorder.record()) return null
        val source = currentInputName() ?: "unknown input"
        var peak = -160f
        var elapsed = 0L
        while (elapsed < millis) {
            delay(100L)
            elapsed += 100L
            audioRecorder.updateMeters()
            peak = maxOf(peak, audioRecorder.peakPowerForChannel(0u))
        }
        audioRecorder.stop()
        delay(150L) // let AVAudioRecorder finalize the file
        val audio = NSData.dataWithContentsOfURL(url)?.toKotlinBytes() ?: return null
        return VoiceRecording(audio, source, peak)
    }

    /** Speaks through the current route (the glasses when connected) and waits until done. */
    suspend fun speak(text: String) {
        enqueueSpeech(text)
        awaitSpeechDone()
    }

    /** Queues one utterance; AVSpeechSynthesizer plays queued utterances back to back. */
    fun enqueueSpeech(text: String) {
        if (text.isBlank()) return
        if (speechDelegate.pending == 0) {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                withOptions = AVAudioSessionCategoryOptionAllowBluetooth or AVAudioSessionCategoryOptionDefaultToSpeaker,
                error = null,
            )
            session.setActive(true, error = null)
        }
        val utterance = AVSpeechUtterance.speechUtteranceWithString(text)
        (NSLocale.preferredLanguages.firstOrNull() as? String)?.let { language ->
            utterance.voice = AVSpeechSynthesisVoice.voiceWithLanguage(language)
                ?: AVSpeechSynthesisVoice.voiceWithLanguage(language.substringBefore('-'))
        }
        synthesizer.delegate = speechDelegate
        speechDelegate.pending++
        synthesizer.speakUtterance(utterance)
    }

    /** Suspends until every queued utterance finished (or was cancelled). */
    suspend fun awaitSpeechDone() {
        if (speechDelegate.pending == 0) return
        suspendCancellableCoroutine { continuation ->
            speechDelegate.idleWaiters += { if (continuation.isActive) continuation.resume(Unit) }
            continuation.invokeOnCancellation { stopSpeaking() }
        }
    }

    fun stopSpeaking() {
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
    }

    fun shareText(text: String) {
        val presenter = IosMediaPlatform.topViewController() ?: return
        val share = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
        share.popoverPresentationController?.sourceView = presenter.view
        presenter.presentViewController(share, animated = true, completion = null)
    }

    /** Downscales to [MAX_IMAGE_SIDE] and re-encodes as JPEG for the relay. */
    fun jpegForUpload(data: NSData): ByteArray? {
        val image = UIImage.imageWithData(data) ?: return null
        val (width, height) = image.size.useContents { width to height }
        val scale = minOf(1.0, MAX_IMAGE_SIDE / maxOf(width, height, 1.0))
        val resized = if (scale < 1.0) {
            image.imageByPreparingThumbnailOfSize(CGSizeMake(width * scale, height * scale)) ?: image
        } else {
            image
        }
        return UIImageJPEGRepresentation(resized, 0.85)?.toKotlinBytes()
    }
}

private class SpeechDelegate : NSObject(), AVSpeechSynthesizerDelegateProtocol {
    var pending = 0
    val idleWaiters = mutableListOf<() -> Unit>()

    private fun utteranceEnded() {
        pending = maxOf(0, pending - 1)
        if (pending == 0) {
            val waiters = idleWaiters.toList()
            idleWaiters.clear()
            waiters.forEach { it() }
        }
    }

    @ObjCSignatureOverride
    override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) {
        utteranceEnded()
    }

    @ObjCSignatureOverride
    override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didCancelSpeechUtterance: AVSpeechUtterance) {
        utteranceEnded()
    }
}

private class ImagePickerDelegate(
    private val onPicked: (ByteArray?) -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {
    @OptIn(ExperimentalForeignApi::class)
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val result = didFinishPicking.firstOrNull() as? PHPickerResult
        if (result == null) {
            onPicked(null)
            return
        }
        result.itemProvider.loadDataRepresentationForTypeIdentifier("public.image") { data, _ ->
            val bytes = data?.let(IosChatPlatform::jpegForUpload)
            dispatch_async(dispatch_get_main_queue()) { onPicked(bytes) }
        }
    }
}
