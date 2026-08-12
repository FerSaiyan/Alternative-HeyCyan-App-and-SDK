package com.fersaiyan.cyanbridge.ai.router

import android.content.Context
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import kotlinx.coroutines.CancellationException
import java.io.File

enum class AgentInferencePurpose {
    CLASSIFICATION,
    UI_PLANNING,
}

data class AgentInferenceResult(
    val content: String,
    val usedImage: Boolean,
    val mediaStatus: String,
)

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
     * Plans with an image only when the caller supplied one. Remote image transport is explicitly
     * guarded here as a defense in depth measure even if the caller already checked its setting.
     */
    suspend fun completeUiPlanning(
        context: Context,
        sessionId: String,
        systemPrompt: String,
        userPrompt: String,
        imagePath: String?,
        allowRemoteImageUpload: Boolean,
        providerType: AgentProviderType = AutomationPrefs.getProviderType(context),
    ): AgentInferenceResult {
        val usableImagePath = imagePath?.trim()?.takeIf { File(it).isFile }
        if (usableImagePath == null) {
            return AgentInferenceResult(
                content = completeText(
                    context,
                    AgentInferencePurpose.UI_PLANNING,
                    sessionId,
                    systemPrompt,
                    userPrompt,
                    providerType,
                ),
                usedImage = false,
                mediaStatus = "Text-only planning",
            )
        }
        if (isRemotePlanner(context, providerType) && !allowRemoteImageUpload) {
            return AgentInferenceResult(
                content = completeText(
                    context,
                    AgentInferencePurpose.UI_PLANNING,
                    sessionId,
                    systemPrompt,
                    userPrompt,
                    providerType,
                ),
                usedImage = false,
                mediaStatus = "Remote screenshot upload is off; used text-only planning.",
            )
        }

        val imageContent = try {
            when (providerType) {
                AgentProviderType.LOCAL_AGENT -> localModelsProvider.streamChat(
                    context = context,
                    messages = messages(systemPrompt, userPrompt),
                    imagePaths = listOf(usableImagePath),
                    maxTokens = UI_PLANNING_MAX_TOKENS,
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
                        maxTokens = UI_PLANNING_MAX_TOKENS,
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
            ),
            usedImage = false,
            mediaStatus = "Multimodal planning was unavailable; used text-only planning.",
        )
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

    private suspend fun completeText(
        context: Context,
        purpose: AgentInferencePurpose,
        sessionId: String,
        systemPrompt: String,
        userPrompt: String,
        providerType: AgentProviderType,
    ): String {
        val messages = messages(systemPrompt, userPrompt)

        return when (providerType) {
            AgentProviderType.LOCAL_AGENT -> localModelsProvider.streamChat(
                context = context,
                messages = messages,
                maxTokens = if (purpose == AgentInferencePurpose.CLASSIFICATION) 256 else 512,
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
                maxTokens = if (purpose == AgentInferencePurpose.CLASSIFICATION) 256 else 512,
            )

            AiProviderType.MOCK -> throw IllegalStateException("Mock provider cannot classify or plan agent tasks")
            AiProviderType.COMPANY_BACKEND -> throw IllegalStateException("Company backend is not configured for agent tasks")
        }
    }

    private const val UI_PLANNING_MAX_TOKENS = 512
}
