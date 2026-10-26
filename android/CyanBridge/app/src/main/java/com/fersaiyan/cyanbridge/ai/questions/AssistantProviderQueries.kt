package com.fersaiyan.cyanbridge.ai.questions

import android.content.Context
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.ai.decision.LocalDecisionEngine
import com.fersaiyan.cyanbridge.ai.decision.SingleTokenDecisionEngine
import com.fersaiyan.cyanbridge.ai.image.GlassesQuestionTestHooks
import com.fersaiyan.cyanbridge.ai.router.AgentInferenceRouter
import com.fersaiyan.cyanbridge.ai.router.AssistantRequestRouter
import com.fersaiyan.cyanbridge.ai.router.CliRelayClient
import com.fersaiyan.cyanbridge.ai.router.RelayErrorLocalizer
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionDefaults
import com.fersaiyan.cyanbridge.localagent.context.LocalAgentContextBuilder
import com.fersaiyan.cyanbridge.localagent.context.SpokenMemoryContext
import com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyFactsStorage
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemorySearch
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.localagent.memory.RagProfile
import com.fersaiyan.cyanbridge.localagent.userfacts.CandidateUserFactsStorage
import com.fersaiyan.cyanbridge.localmodels.engine.DecideOnceRequest
import com.fersaiyan.cyanbridge.localmodels.engine.SessionTurn
import com.fersaiyan.cyanbridge.localmodels.engine.SessionUnavailableException
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import com.fersaiyan.cyanbridge.localmodels.session.DecideOnceUnsupportedException
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.memoryvault.MemoryPolicyService
import com.fersaiyan.cyanbridge.shared.assistant.AssistantMemorySelection
import com.fersaiyan.cyanbridge.shared.assistant.AssistantSystemPrompt
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Glasses questions sent to the chosen provider (Pro relay, local models or the Tasker relay), with
 * the memory-aware system turn ([AssistantSystemPrompt]) and the Classic transcript router.
 */
internal class AssistantProviderQueries(
    /** The main screen when it exists (relay messages follow its locale), else the application. */
    private val context: () -> Context,
    private val languageTag: () -> String,
) {
    private val ctx: Context get() = context()
    private val decisionEngines = ConcurrentHashMap<AgentProviderType, LocalDecisionEngine>()

    /** Classic-only transcript router; SMART questions go to the decision model (decide-once). */
    val requestRouter = AssistantRequestRouter { providerType ->
        decisionEngines.computeIfAbsent(providerType) {
            SingleTokenDecisionEngine(
                generate = { systemPrompt, userPrompt ->
                    AgentInferenceRouter.completeDecisionToken(
                        context = ctx,
                        sessionId = "assistant-decision-${System.currentTimeMillis()}",
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        maxTokens = 8,
                        providerType = providerType,
                    )
                },
            )
        }
    }

    suspend fun query(
        userPrompt: String,
        providerType: AgentProviderType,
        ragProfile: RagProfile,
        imagePaths: List<String> = emptyList(),
        audioPath: String? = null,
        onToken: ((String) -> Unit)? = null,
        decideOnce: DecideOnceRequest? = null,
        /** Spoken-question decision turn: the reply starts with a route tag (shared SpokenDecision.kt). */
        decisionRoutes: Boolean = false,
        /** Extra system text for this turn (e.g. no web access after a [search] decision). */
        extraInstruction: String? = null,
        /** Local LiteRT turn kept in an open conversation (SessionTurn); ignored by other providers. */
        session: SessionTurn? = null,
        /** False when the caller reports the outcome itself (spoken decision turns). */
        reportReply: Boolean = true,
    ): String {
        val date = todayDateString()
        val languageTag = languageTag()
        val spoken = !audioPath.isNullOrBlank()
        // A spoken question is the recording itself (callers pass no user text); the model is told
        // what the recording is and answers in the language the user spoke.
        val systemPrompt = AssistantSystemPrompt.build(
            memoryContext = memoryAwareSystemPrompt(queryText = userPrompt, date = date, ragProfile = ragProfile),
            spokenQuestion = spoken,
            responseLanguageInstruction = { ImageQuestionDefaults.responseLanguageInstruction(languageTag) },
            spokenQuestionInstruction = {
                ImageQuestionDefaults.spokenQuestionInstruction(languageTag, allowQuestionEcho = decideOnce != null)
            },
            decisionRoutes = ImageQuestionDefaults.SPOKEN_DECISION_ROUTES.takeIf { decisionRoutes },
            extraInstruction = extraInstruction,
            spokenMemory = { SpokenMemoryContext.build(ctx, date) },
            currentContext = { ImageQuestionDefaults.currentContextInstruction() },
        )

        val messages = listOf(
            mapOf("role" to "System", "content" to systemPrompt),
            mapOf("role" to "User", "content" to userPrompt),
        )

        return when (providerType) {
            AgentProviderType.PRO_SUBSCRIPTION -> {
                val chosenQuestionsModel = ProSubscriptionAiPrefs.getQuestionsModel(ctx).trim()
                // A spoken question goes to an audio-capable relay model as the recording itself
                // (photo first when there is one); systemPrompt carries the spoken-question instructions.
                if (!audioPath.isNullOrBlank()) {
                    val photo = imagePaths.firstOrNull()
                    return (if (photo != null) {
                        CliRelayClient.imageQuery(
                            context = ctx, imagePath = photo, modelOverride = chosenQuestionsModel,
                            audioPath = audioPath, spokenQuestion = true, systemContext = systemPrompt,
                        )
                    } else {
                        CliRelayClient.audioQuery(
                            context = ctx, audioPath = audioPath, modelOverride = chosenQuestionsModel,
                            spokenQuestion = true, systemContext = systemPrompt,
                        )
                    }).getOrElse { RelayErrorLocalizer.localizedMessage(ctx, it) }.trim().also { reply ->
                        if (!decisionRoutes && reportReply) GlassesQuestionTestHooks.emit("reply", "provider=$providerType images=${imagePaths.size} audio=true text=$reply")
                    }
                }
                CliRelayClient.chat(
                    context = ctx,
                    chatId = "glasses_${System.currentTimeMillis()}",
                    prompt = userPrompt,
                    messages = messages,
                    modelOverride = chosenQuestionsModel,
                ).getOrElse {
                    RelayErrorLocalizer.localizedMessage(ctx, it)
                }
            }

            AgentProviderType.LOCAL_AGENT ->
                runCatching {
                    val modelIssue = validateSelectedLocalMediaModel()
                    if (modelIssue != null) {
                        return@runCatching modelIssue
                    }
                    LocalModelsProvider().streamChat(
                        context = ctx,
                        messages = messages,
                        imagePaths = imagePaths,
                        audioPath = audioPath,
                        onToken = onToken,
                        decideOnce = decideOnce,
                        session = session,
                    )
                }.getOrElse {
                    if (it is DecideOnceUnsupportedException || it is SessionUnavailableException) throw it
                    "Local Models error: ${it.message ?: "unknown error"}"
                }

            AgentProviderType.TASKER -> {
                CliRelayClient.chat(
                    context = ctx,
                    chatId = "glasses_${System.currentTimeMillis()}",
                    prompt = userPrompt,
                    messages = messages,
                ).getOrElse { RelayErrorLocalizer.localizedMessage(ctx, it) }
            }
        }.trim().also { reply ->
            // The decision turn reports its outcome itself (the shared SpokenQuestionFlow).
            if (!decisionRoutes && reportReply) {
                GlassesQuestionTestHooks.emit(
                    "reply",
                    "provider=$providerType images=${imagePaths.size} audio=${!audioPath.isNullOrBlank()} text=$reply",
                )
            }
        }
    }

    /**
     * LocalModelsProvider can select an installed LiteRT/compatible GGUF media model for this
     * request, without changing the user's text-model selection or calling a relay.
     */
    private fun validateSelectedLocalMediaModel(): String? {
        if (RemoteOpenAiPrefs.isActive(ctx)) return null
        LocalModelStorageRepository.resolveSelectedModel(ctx)
            ?: return "No local model selected. Install or select a local model in Settings."
        return null
    }

    private fun todayDateString(tsMs: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(tsMs))

    private fun memoryAwareSystemPrompt(queryText: String, date: String, ragProfile: RagProfile): String {
        if (ragProfile == RagProfile.NONE) return ""
        val light = ragProfile == RagProfile.LIGHT
        val extraSections = mutableListOf<LocalAgentContextBuilder.Section>()

        val retrieval = LocalAgentMemorySearch.buildRelevantMemoryBlock(
            context = ctx,
            queryText = queryText,
            date = date,
            lookbackDaysFacts = if (light) 3 else 5,
            topFacts = if (light) 2 else 4,
            topSummaryLines = if (light) 1 else 3,
            maxChars = if (light) 500 else 900,
            ragProfile = ragProfile,
        )
        if (retrieval.isNotBlank()) {
            extraSections += LocalAgentContextBuilder.Section(
                title = "Relevant memory (search hits)",
                content = retrieval,
            )
        }

        val draftFacts = runCatching { DailyFactsStorage.load(ctx, date).draft }.getOrDefault(emptyList())
        val draftRef = LocalAgentMemoryStore.memoryRefForFile(
            ctx,
            LocalAgentMemoryStore.dailyFactsFileForDate(ctx, date),
        )
        val relevantDraft = if (MemoryPolicyService.isMemoryRefSearchEligible(ctx, draftRef)) {
            AssistantMemorySelection.selectRelevant(draftFacts, queryText, maxItems = if (light) 1 else 4)
        } else {
            emptyList()
        }
        if (relevantDraft.isNotEmpty()) {
            extraSections += LocalAgentContextBuilder.Section(
                title = "Today's draft daily facts (unconfirmed)",
                content = relevantDraft.joinToString("\n") { "- $it" },
            )
        }

        val candidateFacts = runCatching { CandidateUserFactsStorage.load(ctx, date) }.getOrDefault(emptyList())
        val candidateRef = LocalAgentMemoryStore.memoryRefForFile(
            ctx,
            LocalAgentMemoryStore.userFactsCandidatesFileForDate(ctx, date),
        )
        val relevantCandidates = if (MemoryPolicyService.isMemoryRefSearchEligible(ctx, candidateRef)) {
            AssistantMemorySelection.selectRelevant(candidateFacts, queryText, maxItems = if (light) 1 else 3)
        } else {
            emptyList()
        }
        if (relevantCandidates.isNotEmpty()) {
            extraSections += LocalAgentContextBuilder.Section(
                title = "Candidate user facts (pending review)",
                content = relevantCandidates.joinToString("\n") { "- $it" },
            )
        }

        val builder = LocalAgentContextBuilder(
            maxAgentPersonaChars = if (light) 300 else MAX_AGENT_PERSONA_CHARS,
            maxUserFactsChars = if (light) 400 else MAX_USER_FACTS_CHARS,
            maxConfirmedDailyFactsChars = if (light) 350 else MAX_CONFIRMED_FACTS_CHARS,
            maxDailySummaryChars = if (light) 300 else MAX_DAILY_SUMMARY_CHARS,
            maxTotalChars = if (light) 1_800 else MAX_TOTAL_CONTEXT_CHARS,
        )

        return builder.buildSystemMessage(
            context = ctx,
            date = date,
            extraSections = extraSections,
        )
    }

    private companion object {
        const val MAX_AGENT_PERSONA_CHARS = 1200
        const val MAX_USER_FACTS_CHARS = 1400
        const val MAX_CONFIRMED_FACTS_CHARS = 1800
        const val MAX_DAILY_SUMMARY_CHARS = 2200
        const val MAX_TOTAL_CONTEXT_CHARS = 6500
    }
}
