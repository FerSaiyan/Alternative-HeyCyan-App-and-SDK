package com.fersaiyan.cyanbridge.ai.transcription

import android.content.Context
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.ai.router.MediaInferenceRoutingPolicy
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType

data class AutomaticTranscriptionSelection(
    val route: AgentProviderType,
    val provider: TranscriptionProvider,
    val chunker: AudioChunker,
    val chunkDurationSec: Long = 45L,
)

object AutomaticTranscriptionEngine {
    fun select(context: Context): AutomaticTranscriptionSelection {
        val route = MediaInferenceRoutingPolicy.resolve(context)
        val provider = when (route) {
            AgentProviderType.LOCAL_AGENT -> LocalMultimodalTranscriptionProvider(context)
            AgentProviderType.PRO_SUBSCRIPTION -> RelayAudioTranscriptionProvider(
                context = context,
                modelOverride = ProSubscriptionAiPrefs.getQuestionsModel(context),
                name = "pro_audio",
            )
            AgentProviderType.TASKER -> RelayAudioTranscriptionProvider(context)
        }
        return AutomaticTranscriptionSelection(
            route = route,
            provider = RetryingTranscriptionProvider(provider, RetryPolicy(maxAttempts = 1)),
            chunker = Mp4AudioChunker(context),
        )
    }
}
