package com.fersaiyan.cyanbridge.ai.router

import android.content.Context
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.localagent.tasker.TaskerScreenshotStore
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import kotlinx.coroutines.CancellationException

enum class AgentInferencePurpose {
    CLASSIFICATION,
    UI_PLANNING,
}

data class AgentInferenceResult(
    val content: String,
    val usedImage: Boolean,
    val mediaStatus: String,
)

internal enum class UiPlanningImageDecision { TEXT_ONLY, REMOTE_NOT_ALLOWED, ATTACH }

/** Resolves the two existing provider preference layers into one agent inference path. */
object AgentInferenceRouter {
    private val localModelsProvider = LocalModelsProvider()

    suspend fun complete(
        context: Context,
        purpose: AgentInferencePurpose,
        sessionId: String,
        systemPrompt: String,
        userPrompt: String,
        providerType: AgentProviderType = AutomationPrefs.getProviderType(context),
    ): String {
        return completeText(
            context = context,
            purpose = purpose,
            sessionId = sessionId,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            providerType = providerType,
        )
    }

    /**
     * Plans with an image only when the caller supplied a screenshot that passes
     * [TaskerScreenshotStore.usablePath]. Remote image transport is guarded here as defense in
     * depth: a remote planner gets the image only with [allowRemoteImageUpload].
     */
    suspend fun completeUiPlanning(
        context: Context,
        sessionId: String,
        systemPrompt: String,
        userPrompt: String,
        imagePath: String?,
        allowRemoteImageUpload: Boolean,
        providerType: AgentProviderType = AutomationPrefs.getProviderType(context),
        maxTokens: Int = UI_PLANNING_MAX_TOKENS,
    ): AgentInferenceResult {
        val usableImagePath = TaskerScreenshotStore.usablePath(context, imagePath)
        val decision = uiPlanningImageDecision(
            hasUsableImage = usableImagePath != null,
            remotePlanner = usableImagePath != null && isRemotePlanner(context, providerType),
            allowRemoteImageUpload = allowRemoteImageUpload,
        )
        if (usableImagePath == null || decision == UiPlanningImageDecision.TEXT_ONLY) {
            return AgentInferenceResult(
                content = completeText(
                    context,
                    AgentInferencePurpose.UI_PLANNING,
                    sessionId,
                    systemPrompt,
                    userPrompt,
                    providerType,
                    maxTokens,
                ),
                usedImage = false,
                mediaStatus = "Text-only planning",
            )
        }
        if (decision == UiPlanningImageDecision.REMOTE_NOT_ALLOWED) {
            return AgentInferenceResult(
                content = completeText(
                    context,
                    AgentInferencePurpose.UI_PLANNING,
                    sessionId,
                    systemPrompt,
                    userPrompt,
                    providerType,
                    maxTokens,
                ),
                usedImage = false,
                mediaStatus = "Screenshot not sent to the remote planner; used text-only planning.",
            )
        }

        val imageContent = try {
            when (providerType) {
                AgentProviderType.LOCAL_AGENT -> localModelsProvider.streamChat(
                    context = context,
                    messages = messages(systemPrompt, userPrompt),
                    imagePaths = listOf(usableImagePath),
                    maxTokens = maxTokens,
                )

                AgentProviderType.PRO_SUBSCRIPTION -> CliRelayClient.imageQuery(
                    context = context,
                    imagePath = usableImagePath,
                    prompt = multimodalPrompt(systemPrompt, userPrompt),
                    modelOverride = ProSubscriptionAiPrefs.getTasksModel(context),
                ).getOrThrow()

                AgentProviderType.TASKER -> when (AiProviderPrefs.getProvider(context)) {
                    AiProviderType.CLI_RELAY -> CliRelayClient.imageQuery(
                        context = context,
                        imagePath = usableImagePath,
                        prompt = multimodalPrompt(systemPrompt, userPrompt),
                    ).getOrThrow()

                    AiProviderType.LOCAL_MODELS -> localModelsProvider.streamChat(
                        context = context,
                        messages = messages(systemPrompt, userPrompt),
                        imagePaths = listOf(usableImagePath),
                        maxTokens = maxTokens,
                    )

                    AiProviderType.MOCK,
                    AiProviderType.COMPANY_BACKEND -> throw UnsupportedOperationException("planner_has_no_image_capability")
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

        if (imageContent != null) {
            return AgentInferenceResult(
                content = imageContent,
                usedImage = true,
                mediaStatus = "Screenshot attached to the planner for this step.",
            )
        }

        // A provider may advertise a vision endpoint but reject the selected model. Fall back to
        // the same structured text planner rather than claiming vision succeeded.
        return AgentInferenceResult(
            content = completeText(
                context,
                AgentInferencePurpose.UI_PLANNING,
                sessionId,
                systemPrompt,
                userPrompt,
                providerType,
                maxTokens,
            ),
            usedImage = false,
            mediaStatus = "Multimodal planning was unavailable; used text-only planning.",
        )
    }

    /**
     * A screenshot goes to the planner when there is a valid one and the planner is on-device,
     * or the caller has the user's consent to send it to a remote planner.
     */
    internal fun uiPlanningImageDecision(
        hasUsableImage: Boolean,
        remotePlanner: Boolean,
        allowRemoteImageUpload: Boolean,
    ): UiPlanningImageDecision = when {
        !hasUsableImage -> UiPlanningImageDecision.TEXT_ONLY
        remotePlanner && !allowRemoteImageUpload -> UiPlanningImageDecision.REMOTE_NOT_ALLOWED
        else -> UiPlanningImageDecision.ATTACH
    }

    fun isRemotePlanner(
        context: Context,
        providerType: AgentProviderType = AutomationPrefs.getProviderType(context),
    ): Boolean {
        return when (providerType) {
            AgentProviderType.PRO_SUBSCRIPTION -> true
            AgentProviderType.LOCAL_AGENT -> isRemoteLocalModelsPlanner(context)
            AgentProviderType.TASKER -> when (AiProviderPrefs.getProvider(context)) {
                AiProviderType.CLI_RELAY -> true
                AiProviderType.LOCAL_MODELS -> isRemoteLocalModelsPlanner(context)
                AiProviderType.MOCK,
                AiProviderType.COMPANY_BACKEND -> false
            }
        }
    }

    /**
     * Jev-like constrained decode: maxTokens=[maxTokens] with a single-letter
     * instruction. Replaces the 256-token JSON classification with ~1 output
     * token. Thinking models may still emit a short preamble (<think>/reasoning
     * + letter); [com.fersaiyan.cyanbridge.ai.decision.DecisionOutputParser]
     * tolerates that. Falls back through the same provider chain as [complete].
     */
    suspend fun completeDecisionToken(
        context: Context,
        sessionId: String,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 8,
        providerType: AgentProviderType = AutomationPrefs.getProviderType(context),
    ): String {
        val messages = messages(systemPrompt, userPrompt)
        return when (providerType) {
            AgentProviderType.LOCAL_AGENT -> localModelsProvider.streamChat(
                context = context,
                messages = messages,
                maxTokens = maxTokens.coerceIn(1, 32),
            )

            AgentProviderType.PRO_SUBSCRIPTION -> CliRelayClient.chat(
                context = context,
                chatId = sessionId,
                prompt = userPrompt,
                messages = messages,
                modelOverride = ProSubscriptionAiPrefs.getTasksModel(context),
            ).getOrThrow()

            AgentProviderType.TASKER -> when (AiProviderPrefs.getProvider(context)) {
                AiProviderType.CLI_RELAY -> CliRelayClient.chat(
                    context = context,
                    chatId = sessionId,
                    prompt = userPrompt,
                    messages = messages,
                ).getOrThrow()

                AiProviderType.LOCAL_MODELS -> localModelsProvider.streamChat(
                    context = context,
                    messages = messages,
                    maxTokens = maxTokens.coerceIn(1, 32),
                )

                AiProviderType.MOCK -> throw IllegalStateException("Mock provider cannot score decisions")
                AiProviderType.COMPANY_BACKEND -> throw IllegalStateException("Company backend is not configured for decisions")
            }
        }
    }

    private suspend fun completeText(
        context: Context,
        purpose: AgentInferencePurpose,
        sessionId: String,
        systemPrompt: String,
        userPrompt: String,
        providerType: AgentProviderType,
        maxTokens: Int? = null,
    ): String {
        val messages = messages(systemPrompt, userPrompt)

        return when (providerType) {
            AgentProviderType.LOCAL_AGENT -> localModelsProvider.streamChat(
                context = context,
                messages = messages,
                maxTokens = maxTokens ?: if (purpose == AgentInferencePurpose.CLASSIFICATION) 256 else 512,
            )

            AgentProviderType.PRO_SUBSCRIPTION -> CliRelayClient.chat(
                context = context,
                chatId = sessionId,
                prompt = userPrompt,
                messages = messages,
                modelOverride = ProSubscriptionAiPrefs.getTasksModel(context),
            ).getOrThrow()

            AgentProviderType.TASKER -> completeUsingAiProviderPrefs(
                context = context,
                purpose = purpose,
                sessionId = sessionId,
                userPrompt = userPrompt,
                messages = messages,
                maxTokens = maxTokens,
            )
        }
    }

    private fun messages(systemPrompt: String, userPrompt: String): List<Map<String, String>> = listOf(
        mapOf("role" to "system", "content" to systemPrompt),
        mapOf("role" to "user", "content" to userPrompt),
    )

    private fun multimodalPrompt(systemPrompt: String, userPrompt: String): String = buildString {
        appendLine("System instructions:")
        appendLine(systemPrompt)
        appendLine()
        appendLine("Planning request:")
        append(userPrompt)
    }

    private fun isRemoteLocalModelsPlanner(context: Context): Boolean =
        RemoteOpenAiPrefs.isActive(context)

    private suspend fun completeUsingAiProviderPrefs(
        context: Context,
        purpose: AgentInferencePurpose,
        sessionId: String,
        userPrompt: String,
        messages: List<Map<String, String>>,
        maxTokens: Int? = null,
    ): String {
        return when (AiProviderPrefs.getProvider(context)) {
            AiProviderType.CLI_RELAY -> CliRelayClient.chat(
                context = context,
                chatId = sessionId,
                prompt = userPrompt,
                messages = messages,
            ).getOrThrow()

            AiProviderType.LOCAL_MODELS -> localModelsProvider.streamChat(
                context = context,
                messages = messages,
                maxTokens = maxTokens ?: if (purpose == AgentInferencePurpose.CLASSIFICATION) 256 else 512,
            )

            AiProviderType.MOCK -> throw IllegalStateException("Mock provider cannot classify or plan agent tasks")
            AiProviderType.COMPANY_BACKEND -> throw IllegalStateException("Company backend is not configured for agent tasks")
        }
    }

    private const val UI_PLANNING_MAX_TOKENS = 512
}
