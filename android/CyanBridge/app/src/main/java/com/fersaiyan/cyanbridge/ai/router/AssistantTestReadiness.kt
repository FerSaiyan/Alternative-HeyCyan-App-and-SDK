package com.fersaiyan.cyanbridge.ai.router

import android.content.Context
import com.fersaiyan.cyanbridge.agent.ProSubscriptionVerifier
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelRuntime
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import java.util.Locale

enum class AssistantTestKind {
    VOICE,
    IMAGE,
}

enum class AssistantSetupDestination {
    LOCAL_MODELS,
    PRO_SUBSCRIPTION,
}

data class AssistantSetupIssue(
    val title: String,
    val message: String,
    val actionLabel: String,
    val destination: AssistantSetupDestination,
)

object AssistantTestReadiness {
    /** Fast metadata-only preflight. Model loading and warmup remain first-use operations. */
    fun blockingIssue(
        context: Context,
        route: GlassesAssistantRoute,
        kind: AssistantTestKind,
    ): AssistantSetupIssue? = when (route) {
        GlassesAssistantRoute.LOCAL -> localIssue(context, kind)
        GlassesAssistantRoute.PRO -> proIssue(context)
        GlassesAssistantRoute.PHONE_ASSISTANT,
        GlassesAssistantRoute.TASKER_EXTERNAL_UI -> null
    }

    private fun localIssue(context: Context, kind: AssistantTestKind): AssistantSetupIssue? {
        if (RemoteOpenAiPrefs.isActive(context)) return null

        val selected = LocalModelStorageRepository.resolveSelectedModel(context)
            ?: return AssistantSetupIssue(
                title = "Set up Local Models",
                message = "Download or import a local model and select it before testing AI questions. The model is loaded only when you start a question, not during this check.",
                actionLabel = "Open Local Models",
                destination = AssistantSetupDestination.LOCAL_MODELS,
            )
        if (kind == AssistantTestKind.VOICE) return null

        val settings = LocalModelSettingsRepository.getForModel(context, selected.id)
        if (settings.modelRuntime != LocalModelRuntime.LITERT) {
            return AssistantSetupIssue(
                title = "Configure local image AI",
                message = "AI image questions require Local Runtime = LiteRT. Open Local Models and change the selected model's runtime.",
                actionLabel = "Open Local Models",
                destination = AssistantSetupDestination.LOCAL_MODELS,
            )
        }
        val modelHint = "${selected.displayName} ${selected.catalogId.orEmpty()} ${selected.fileName}"
            .lowercase(Locale.US)
        if (!modelHint.contains("gemma")) {
            return AssistantSetupIssue(
                title = "Select an image-capable model",
                message = "Choose a Gemma LiteRT model for local AI image questions. Other local models can still answer voice questions.",
                actionLabel = "Open Local Models",
                destination = AssistantSetupDestination.LOCAL_MODELS,
            )
        }
        return null
    }

    private fun proIssue(context: Context): AssistantSetupIssue? {
        if (ProSubscriptionVerifier.localStatus(context).active) return null
        return AssistantSetupIssue(
            title = "Pro subscription required",
            message = "The selected Pro provider has no active subscription. Choose Local Models instead or activate a Pro plan.",
            actionLabel = "View Pro plans",
            destination = AssistantSetupDestination.PRO_SUBSCRIPTION,
        )
    }
}
