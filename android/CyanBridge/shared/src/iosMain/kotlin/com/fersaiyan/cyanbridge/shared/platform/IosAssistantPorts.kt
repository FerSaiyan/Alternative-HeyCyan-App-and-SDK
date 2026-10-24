package com.fersaiyan.cyanbridge.shared.platform

import com.fersaiyan.cyanbridge.shared.ai.ChatAiService
import com.fersaiyan.cyanbridge.shared.ai.ChatMessage
import com.fersaiyan.cyanbridge.shared.ai.ImageAiService
import com.fersaiyan.cyanbridge.shared.assistant.AssistantBrain
import com.fersaiyan.cyanbridge.shared.assistant.AssistantGlassesHooks
import com.fersaiyan.cyanbridge.shared.assistant.AssistantSpeechStream
import com.fersaiyan.cyanbridge.shared.assistant.AssistantVoice
import com.fersaiyan.cyanbridge.shared.ble.VendorAiSpeakMode
import com.fersaiyan.cyanbridge.shared.ble.VendorGlassesBridge
import com.fersaiyan.cyanbridge.shared.ble.VendorGlassesMode
import com.fersaiyan.cyanbridge.shared.ble.awaitAiSpeakMode
import com.fersaiyan.cyanbridge.shared.ble.awaitModeAccepted
import com.fersaiyan.cyanbridge.shared.ui.sharedDefaultImageQuestion

/** Speech recognition and synthesis for the glasses assistant on iOS. */
internal object IosAssistantVoice : AssistantVoice {
    override suspend fun listen(initialTimeoutMs: Long): String? = IosVoiceQuestion.ask(initialTimeoutMs)

    override fun stopSpeaking() = IosChatPlatform.stopSpeaking()

    override fun say(text: String) = IosChatPlatform.enqueueSpeech(text)

    override fun waitCue(): String = IosChatPlatform.waitCue()

    override fun questionCue(): String = IosChatPlatform.questionCue()

    override fun usePlaybackRoute() = IosChatPlatform.useSpeechPlaybackRoute()

    override fun releaseAudio() = IosChatPlatform.releaseAudioSession()

    override fun streamingSpeech(beforeFirstChunk: suspend () -> Unit): AssistantSpeechStream =
        IosStreamingSpeech(beforeFirstChunk)
}

/** The selected on-device model, unless a custom remote provider or the relay should answer. */
internal class IosAssistantBrain(
    private val chatAiService: ChatAiService,
    private val imageAiService: ImageAiService,
) : AssistantBrain {
    private val useLocalModel: Boolean get() = IosLocalModels.isActive && !IosRemoteModelSettings.isActive

    override suspend fun preload() = IosLocalModels.preload()

    override suspend fun answerImage(image: ByteArray, question: String, onToken: (String) -> Unit): String =
        if (useLocalModel) {
            IosLocalModels.describeImage(image, question, onToken)
        } else {
            imageAiService.analyzeImage(image, question)
        }

    override suspend fun answerText(question: String, onToken: (String) -> Unit): String {
        val messages = listOf(ChatMessage("user", question))
        return if (useLocalModel) {
            IosLocalModels.chat(messages, onToken = onToken)
        } else {
            chatAiService.chat(messages).message.content
        }
    }

    override fun defaultImageQuestion(): String = sharedDefaultImageQuestion()
}

/**
 * HeyCyan glasses through QCSDK. Their AI state must end with STOP or they stay in AI mode (the
 * camera button then stops reporting photos until a restart), and it may only change outside
 * speech: thinking before generation, speaking before the first chunk, stop after the last one.
 */
internal class QcsdkAssistantHooks(private val bridge: VendorGlassesBridge) : AssistantGlassesHooks {
    override suspend fun beforeAnswer() {
        bridge.awaitAiSpeakMode(VendorAiSpeakMode.THINKING_START)
    }

    override suspend fun beforeFirstChunk() {
        bridge.awaitAiSpeakMode(VendorAiSpeakMode.THINKING_STOP)
        bridge.awaitAiSpeakMode(VendorAiSpeakMode.START)
    }

    override suspend fun afterAnswer() {
        bridge.awaitAiSpeakMode(VendorAiSpeakMode.THINKING_STOP)
        bridge.awaitAiSpeakMode(VendorAiSpeakMode.STOP)
        releaseAiMode()
    }

    override suspend fun onNoQuestion() = releaseAiMode()

    /** Leaves the glasses' AI voice mode, or the camera button stops reporting photos. */
    suspend fun releaseAiMode() {
        val released = bridge.awaitModeAccepted(VendorGlassesMode.SPEECH_RECOGNITION_STOP)
        PlatformLogger.i("IosGlassesAssistant", "Glasses AI mode released=$released")
    }
}
