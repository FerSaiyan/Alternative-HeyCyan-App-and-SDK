package com.fersaiyan.cyanbridge.localagent.dailyfacts

import android.content.Context
import com.fersaiyan.cyanbridge.ai.router.AiAssistantRouter
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.localagent.daily.NightlyEnrichmentPrefs
import com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyFactsReviewProtocol.ReviewBatchItem
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.localagent.userfacts.CandidateUserFactsStorage
import com.fersaiyan.cyanbridge.localagent.userfacts.UserFactsStorage
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelRequestPriority
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.memoryvault.MemoryModeManager
import com.fersaiyan.cyanbridge.shared.chat.ChatRole
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatReplyStream
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatThreadViewModel
import com.fersaiyan.cyanbridge.shared.ui.chat.DailyReviewBackend
import com.fersaiyan.cyanbridge.shared.ui.chat.DailyReviewConfig
import com.fersaiyan.cyanbridge.ui.chat.AndroidChatThreadBackend
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The daily facts review run inside a chat: the model asks about a small batch of draft facts
 * and user-fact candidates, and the user's answers confirm or reject them by ID.
 */
internal class DailyFactsReviewChat(
    private val context: Context,
    private val chat: AndroidChatThreadBackend,
) : DailyReviewBackend {
    private var seededFromOcr = false

    private data class ReviewBatch(val daily: List<ReviewBatchItem>, val user: List<ReviewBatchItem>)

    override fun retentionDays() = MemoryModeManager.getScreenOcrRetentionDays(context)

    override fun savedConfig(chatId: String) =
        DailyFactsReviewThreadStore.load(context, chatId)?.let { DailyReviewConfig(it.date, it.lookbackDays) }

    override fun save(chatId: String, date: String, lookbackDays: Int) =
        DailyFactsReviewThreadStore.save(context = context, chatId = chatId, date = date, lookbackDays = lookbackDays)

    private val usesLocalModels get() = chat.modelInfo().usesLocalModels

    private fun DailyReviewConfig.day(): String =
        date ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis()))

    private fun systemMessage(state: DailyFactsStorage.State, candidateUserFacts: List<String>): String {
        LocalAgentMemoryStore.ensureSeedFiles(context)
        val batch = reviewBatch(state, candidateUserFacts)
        return DailyFactsReviewProtocol.buildSystemMessage(
            state = state,
            userFactsMd = LocalAgentMemoryStore.readText(LocalAgentMemoryStore.userFactsFile(context)),
            candidateUserFacts = candidateUserFacts,
            dailyFactsBatch = batch.daily,
            userFactsBatch = batch.user,
            outputMode = DailyFactsReviewProtocol.OutputMode.JSON,
        )
    }

    override suspend fun kickoff(chatId: String, review: DailyReviewConfig, stream: ChatReplyStream?): String =
        withContext(Dispatchers.IO) {
            val date = review.day()
            hydrateDraftFromOcrIfNeeded(date, review.lookbackDays)
            val state = DailyFactsStorage.load(context, date)
            val candidates = pruneKnownUserFactCandidates(date, CandidateUserFactsStorage.load(context, date))
            val messages = listOf(
                mapOf("role" to "System", "content" to systemMessage(state, candidates)),
                mapOf(
                    "role" to "User",
                    "content" to "Start the daily facts review from the current batch. Ask me about up to 3 items.",
                ),
            )
            AiAssistantRouter.chatReplyStreaming(
                context = context,
                chatId = chatId,
                userPrompt = "Start daily facts review",
                messages = messages,
                imagePaths = emptyList(),
                audioPath = null,
                callbacks = stream?.let { chat.routerCallbacks(it, reportMaxTokens = false) },
            )
        }

    /** The review's system prompt plus the chat so far, trimmed to the model's context. */
    suspend fun buildMessages(chatId: String, review: DailyReviewConfig): List<Map<String, String>> {
        val date = review.day()
        hydrateDraftFromOcrIfNeeded(date, review.lookbackDays)
        val state = DailyFactsStorage.load(context, date)
        val system = systemMessage(state, CandidateUserFactsStorage.load(context, date))
        val historyRaw = ChatStore.listMessages(chatId)
            .filterNot { it.role == ChatRole.ASSISTANT && it.content.startsWith(ChatThreadViewModel.DAILY_REVIEW_DEBUG_PREFIX) }
            .map(AndroidChatThreadBackend::relayMessage)
        val selectedModel = chat.selectedLocalModel()
        val history = AndroidChatThreadBackend.trimHistoryForContext(
            history = historyRaw,
            maxApproxTokens = chat.contextBudgets(
                isDailyReview = true,
                selectedModel = selectedModel,
                contextSize = if (usesLocalModels) {
                    selectedModel?.let { LocalModelSettingsRepository.getForModel(context, it.id).contextSize } ?: 2048
                } else {
                    4096
                },
            ).historyTokens,
        )
        return listOf(mapOf("role" to "System", "content" to system)) + history
    }

    private fun hydrateDraftFromOcrIfNeeded(date: String, lookbackDays: Int) {
        if (seededFromOcr) return
        seededFromOcr = true
        // READY batches already contain processed bullets. Do not contaminate them with raw OCR.
        if (NightlyEnrichmentPrefs.isReady(context, date)) return
        val retentionDays = MemoryModeManager.getScreenOcrRetentionDays(context)
        runCatching {
            OcrDailyFactsSeeder.seedDraftFactsFromScreenCaptures(
                context = context,
                targetDate = date,
                lookbackDays = lookbackDays.coerceIn(1, retentionDays.coerceAtLeast(1)),
            )
        }
    }

    override suspend fun queueStatus(date: String): String = withContext(Dispatchers.IO) {
        val state = runCatching { DailyFactsStorage.load(context, date) }.getOrNull()
        val candidates = pruneKnownUserFactCandidates(
            date,
            runCatching { CandidateUserFactsStorage.load(context, date) }.getOrElse { emptyList() },
        )
        val draftCount = state?.draft?.size ?: 0
        val confirmedCount = state?.confirmed?.size ?: 0
        val candidateCount = candidates.size
        if (draftCount == 0 && candidateCount == 0) NightlyEnrichmentPrefs.markReviewed(context, date)
        "Queue integrity: ok · pending $draftCount · confirmed $confirmedCount · user candidates $candidateCount"
    }

    override suspend fun handleReply(chatId: String, review: DailyReviewConfig, userPrompt: String, raw: String) {
        val date = review.day()
        val preState = DailyFactsStorage.load(context, date)
        val preCandidates = pruneKnownUserFactCandidates(date, CandidateUserFactsStorage.load(context, date))
        val currentBatch = reviewBatch(preState, preCandidates)

        if (!shouldApplyStructuredUpdate(userPrompt)) {
            val followUp = queueContinuationPrompt(preState, preCandidates)
            addAssistantMessage(
                chatId,
                if (followUp.isNotBlank()) {
                    "Got it — here is the current next batch.\n\n$followUp"
                } else {
                    "You're all caught up. There are no pending daily facts to review right now."
                },
            )
            return
        }

        val parsed = runCatching { DailyFactsReviewProtocol.parseUpdate(raw) }.getOrNull()
            ?: attemptRepairUpdate(date, raw)
        if (parsed != null) {
            applyUpdate(chatId, date, parsed, parsed.assistantMessage, currentBatch)
            return
        }

        val inferred = if (usesLocalModels) {
            inferUpdateFromUserReplyHeuristic(userPrompt, currentBatch) ?: attemptInferUpdateFromUserReply(date, userPrompt)
        } else {
            null
        }
        if (inferred != null) {
            applyUpdate(chatId, date, inferred, extractUserFacingAssistantText(raw), currentBatch)
            return
        }

        val userFacing = extractUserFacingAssistantText(raw)
        if (userFacing.isNotBlank()) {
            addAssistantMessage(chatId, userFacing)
            return
        }

        runCatching {
            val brief = buildString {
                append("I couldn't parse the structured update, so your queue is unchanged. ")
                append("Please confirm/reject by ID (example: D1 yes, D2 no, U1 yes). ")
                if (currentBatch.daily.isNotEmpty()) {
                    append("Current daily batch: ")
                    currentBatch.daily.forEachIndexed { idx, item ->
                        if (idx > 0) append(" | ")
                        append("${item.id}) ${item.text.take(90)}")
                    }
                }
                if (currentBatch.user.isNotEmpty()) {
                    append(". User-fact candidates: ")
                    currentBatch.user.forEachIndexed { idx, item ->
                        if (idx > 0) append(" | ")
                        append("${item.id}) ${item.text.take(90)}")
                    }
                }
            }
            addAssistantMessage(chatId, brief)
        }.onFailure {
            addAssistantMessage(
                chatId,
                "I couldn't parse that review update safely, so I kept your queue unchanged. Please answer once more.",
            )
        }
    }

    private fun addAssistantMessage(chatId: String, text: String) {
        ChatStore.addMessage(chatId, ChatRole.ASSISTANT, text)
    }

    private fun applyUpdate(
        chatId: String,
        date: String,
        update: DailyFactsReviewProtocol.AiUpdate,
        assistantTextOverride: String?,
        batch: ReviewBatch,
    ) {
        val confirmedDaily = resolveFactsAgainstBatch(update.confirmedFacts, batch.daily)
        val rejectedDaily = resolveFactsAgainstBatch(update.rejectedFacts, batch.daily)
        val confirmedUser = resolveFactsAgainstBatch(update.confirmedUserFacts, batch.user)
        val rejectedUser = resolveFactsAgainstBatch(update.rejectedUserFacts, batch.user)
        val newDailyFacts = update.newFacts.map { it.trim() }.filter { it.isNotBlank() }.take(8)
        val newUserFactCandidates = update.newUserFactsCandidates.map { it.trim() }.filter { it.isNotBlank() }.take(8)

        if (newDailyFacts.isNotEmpty()) {
            val current = DailyFactsStorage.load(context, date)
            DailyFactsStorage.writeDraft(context, date, current.draft + newDailyFacts)
        }
        val resolvedDaily = confirmedDaily + rejectedDaily
        if (resolvedDaily.isNotEmpty()) DailyFactsStorage.removeFromDraft(context, date, resolvedDaily)
        if (confirmedDaily.isNotEmpty()) DailyFactsStorage.appendConfirmed(context, date, confirmedDaily)
        if (newUserFactCandidates.isNotEmpty()) CandidateUserFactsStorage.append(context, date, newUserFactCandidates)
        val resolvedUser = confirmedUser + rejectedUser
        if (resolvedUser.isNotEmpty()) CandidateUserFactsStorage.remove(context, date, resolvedUser)
        if (confirmedUser.isNotEmpty()) UserFactsStorage.appendUniqueFacts(context, confirmedUser)

        val followUp = queueContinuationPrompt(
            DailyFactsStorage.load(context, date),
            pruneKnownUserFactCandidates(date, CandidateUserFactsStorage.load(context, date)),
        )
        val baseText = assistantTextOverride?.trim()?.takeIf { it.isNotBlank() }
            ?: update.assistantMessage.trim().ifBlank { "Got it. I updated the queue and prepared the next facts to review." }
        addAssistantMessage(chatId, if (followUp.isNotBlank()) "$baseText\n\n$followUp" else baseText)
    }

    private fun reviewBatch(state: DailyFactsStorage.State, candidateUserFacts: List<String>): ReviewBatch {
        val confirmedNorm = state.confirmed.map(::normalizeFact).filter { it.isNotBlank() }.toSet()
        val dailyTake = state.draft.filterNot { confirmedNorm.contains(normalizeFact(it)) }.take(MAX_BATCH)
        val userCandidates = pruneKnownUserFactCandidates(state.date, candidateUserFacts)
        // User-fact candidates come up once the day's draft facts are done.
        val userTake = if (dailyTake.isEmpty()) userCandidates.take(MAX_BATCH) else emptyList()
        return ReviewBatch(
            daily = dailyTake.mapIndexed { idx, text -> ReviewBatchItem(id = "D${idx + 1}", text = text) },
            user = userTake.mapIndexed { idx, text -> ReviewBatchItem(id = "U${idx + 1}", text = text) },
        )
    }

    /** Drops candidates already among the user's facts, and removes them from storage. */
    private fun pruneKnownUserFactCandidates(date: String, candidateUserFacts: List<String>): List<String> {
        if (candidateUserFacts.isEmpty()) return emptyList()
        val known = UserFactsStorage.normalizedFacts(context)
        if (known.isEmpty()) return candidateUserFacts
        val (removed, kept) = candidateUserFacts.partition { fact ->
            val norm = normalizeFact(fact)
            norm.isNotBlank() && known.contains(norm)
        }
        if (removed.isNotEmpty()) CandidateUserFactsStorage.remove(context, date, removed)
        return kept
    }

    private fun queueContinuationPrompt(state: DailyFactsStorage.State, candidateUserFacts: List<String>): String {
        val batch = reviewBatch(state, candidateUserFacts)
        if (batch.daily.isEmpty() && batch.user.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("Next batch to review:")
        batch.daily.forEach { item -> sb.appendLine("${item.id}) ${item.text.take(180)}") }
        if (batch.user.isNotEmpty()) {
            sb.appendLine("User-fact candidates:")
            batch.user.forEach { item -> sb.appendLine("${item.id}) ${item.text.take(180)}") }
        }
        sb.append("Please confirm or reject by ID (e.g., 'D1 yes, D2 no, U1 yes').")
        return sb.toString().trim()
    }

    private suspend fun attemptRepairUpdate(date: String, raw: String): DailyFactsReviewProtocol.AiUpdate? = runCatching {
        val batch = reviewBatch(
            DailyFactsStorage.load(context, date),
            pruneKnownUserFactCandidates(date, CandidateUserFactsStorage.load(context, date)),
        )
        val prompt = buildString {
            appendLine("Convert the following daily-review assistant output into strict JSON only.")
            appendLine("Return one JSON object with keys:")
            appendLine(JSON_KEYS)
            appendLine("Use only IDs for confirmed/rejected arrays (D# for daily, U# for user).")
            appendLine("Use text content only for new_facts and new_user_facts_candidates.")
            appendLine()
            appendLine("DATE: $date")
            appendLine("DAILY_BATCH:")
            batch.daily.forEach { item -> appendLine("${item.id}. ${item.text}") }
            appendLine("USER_BATCH:")
            batch.user.forEach { item -> appendLine("${item.id}. ${item.text}") }
            appendLine()
            appendLine("OUTPUT_TO_CONVERT:")
            appendLine(raw.take(4000))
        }
        DailyFactsReviewProtocol.parseUpdate(chat.textReply(prompt))
    }.getOrNull()

    private suspend fun attemptInferUpdateFromUserReply(date: String, userReply: String): DailyFactsReviewProtocol.AiUpdate? {
        if (userReply.isBlank()) return null
        return runCatching {
            val batch = reviewBatch(
                DailyFactsStorage.load(context, date),
                pruneKnownUserFactCandidates(date, CandidateUserFactsStorage.load(context, date)),
            )
            val prompt = buildString {
                appendLine("Infer structured queue updates from this USER reply.")
                appendLine("Return strict JSON only with keys:")
                appendLine(JSON_KEYS)
                appendLine("Use only IDs for confirmed/rejected arrays (D# for daily, U# for user).")
                appendLine("Use text content only for new_facts and new_user_facts_candidates.")
                appendLine()
                appendLine("CURRENT_DAILY_BATCH:")
                if (batch.daily.isEmpty()) appendLine("(none)") else batch.daily.forEach { item -> appendLine("${item.id}. ${item.text}") }
                appendLine("CURRENT_USER_BATCH:")
                if (batch.user.isEmpty()) appendLine("(none)") else batch.user.forEach { item -> appendLine("${item.id}. ${item.text}") }
                appendLine()
                appendLine("USER_REPLY:")
                appendLine(userReply.take(2000))
            }
            // Only reached with the Local provider selected.
            val inferenceRaw = LocalModelsProvider().streamChat(
                context = context,
                messages = listOf(mapOf("role" to "User", "content" to prompt)),
                requestPriority = LocalModelRequestPriority.HIGH,
            )
            DailyFactsReviewProtocol.parseUpdate(inferenceRaw)
        }.getOrNull()
    }

    private fun resolveFactsAgainstBatch(returnedFacts: List<String>, batchFacts: List<ReviewBatchItem>): List<String> {
        if (returnedFacts.isEmpty() || batchFacts.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        val canonicalByNorm = LinkedHashMap<String, String>()
        val byIndex = LinkedHashMap<Int, String>()
        val byId = LinkedHashMap<String, String>()
        batchFacts.forEach { fact ->
            val norm = normalizeFact(fact.text)
            if (norm.isNotBlank() && !canonicalByNorm.containsKey(norm)) canonicalByNorm[norm] = fact.text
            byId[fact.id.uppercase()] = fact.text
        }
        batchFacts.forEachIndexed { idx, fact -> byIndex[idx + 1] = fact.text }

        for (raw in returnedFacts) {
            val candidate = raw.trim()
            if (candidate.isBlank()) continue
            ID_PATTERN.findAll(candidate)
                .map { it.groupValues[1].replace(" ", "").uppercase() }
                .forEach { id -> byId[id]?.let { out += it } }
            if (out.isNotEmpty() && candidate.length <= 14) continue

            val explicitIndexes = NUMBER_PATTERN.findAll(candidate)
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .toList()
            if (explicitIndexes.isNotEmpty()) {
                explicitIndexes.forEach { idx -> byIndex[idx]?.let { out += it } }
                if (out.isNotEmpty()) continue
            }

            val norm = normalizeFact(candidate)
            if (norm.isBlank()) continue
            val exact = canonicalByNorm[norm]
            if (exact != null) {
                out += exact
                continue
            }
            val fuzzy = canonicalByNorm.entries.firstOrNull { (batchNorm, _) ->
                val a = batchNorm.split(' ').filter { it.length > 2 }.toSet()
                val b = norm.split(' ').filter { it.length > 2 }.toSet()
                val overlap = a.intersect(b).size
                (batchNorm.contains(norm) || norm.contains(batchNorm)) ||
                    (overlap >= 3 && overlap >= (a.size.coerceAtMost(b.size) / 2))
            }?.value
            if (fuzzy != null) out += fuzzy
        }
        return out.toList()
    }

    private fun inferUpdateFromUserReplyHeuristic(userReply: String, batch: ReviewBatch): DailyFactsReviewProtocol.AiUpdate? {
        if (userReply.isBlank()) return null
        if (batch.daily.isEmpty() && batch.user.isEmpty()) return null

        val lower = userReply.lowercase(Locale.US)
        val confirmWords = listOf("yes", "yep", "yeah", "correct", "right", "true", "confirm", "approved", "ok")
        val rejectWords = listOf("no", "nope", "wrong", "false", "reject", "not", "nah", "skip")
        val confirmDaily = LinkedHashSet<String>()
        val rejectDaily = LinkedHashSet<String>()
        val confirmUser = LinkedHashSet<String>()
        val rejectUser = LinkedHashSet<String>()
        val dailyIds = batch.daily.map { it.id.uppercase() }.toSet()
        val userIds = batch.user.map { it.id.uppercase() }.toSet()

        fun applyForIds(ids: List<String>, confirm: Boolean) {
            ids.forEach { id ->
                val normalized = id.uppercase()
                when {
                    normalized.startsWith("D") -> {
                        if (!dailyIds.contains(normalized)) return@forEach
                        if (confirm) confirmDaily += normalized else rejectDaily += normalized
                    }
                    normalized.startsWith("U") -> {
                        if (!userIds.contains(normalized)) return@forEach
                        if (confirm) confirmUser += normalized else rejectUser += normalized
                    }
                }
            }
        }

        userReply.split(Regex("[\\n,;]+|\\band\\b", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { segment ->
                val segLower = segment.lowercase(Locale.US)
                val hasConfirm = confirmWords.any { segLower.contains(it) }
                val hasReject = rejectWords.any { segLower.contains(it) }
                if (!hasConfirm && !hasReject) return@forEach

                val explicitIds = ID_PATTERN.findAll(segment).map { it.groupValues[1].replace(" ", "").uppercase() }.toList()
                val bareDaily = if (explicitIds.isEmpty()) {
                    NUMBER_PATTERN.findAll(segment)
                        .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                        .map { "D$it" }
                        .toList()
                } else {
                    emptyList()
                }
                val ids = (explicitIds + bareDaily).distinct()
                if (ids.isNotEmpty()) {
                    applyForIds(ids, confirm = hasConfirm && !hasReject)
                    if (hasReject && !hasConfirm) applyForIds(ids, confirm = false)
                }
            }

        if (confirmDaily.isEmpty() && rejectDaily.isEmpty() && confirmUser.isEmpty() && rejectUser.isEmpty()) {
            if (batch.daily.size == 1 || batch.user.size == 1) {
                val hasConfirm = confirmWords.any { lower.contains(it) }
                val hasReject = rejectWords.any { lower.contains(it) }
                if (hasConfirm xor hasReject) {
                    if (batch.daily.size == 1) {
                        val onlyDailyId = batch.daily.first().id
                        if (hasConfirm) confirmDaily += onlyDailyId else rejectDaily += onlyDailyId
                    }
                    if (batch.user.size == 1) {
                        val onlyUserId = batch.user.first().id
                        if (hasConfirm) confirmUser += onlyUserId else rejectUser += onlyUserId
                    }
                }
            }
        }

        if (confirmDaily.isEmpty() && rejectDaily.isEmpty() && confirmUser.isEmpty() && rejectUser.isEmpty()) return null
        return DailyFactsReviewProtocol.AiUpdate(
            assistantMessage = "Got it — updated. Continuing with the next batch.",
            confirmedFacts = confirmDaily.toList(),
            rejectedFacts = rejectDaily.toList(),
            newFacts = emptyList(),
            confirmedUserFacts = confirmUser.toList(),
            rejectedUserFacts = rejectUser.toList(),
            newUserFactsCandidates = emptyList(),
        )
    }

    private companion object {
        const val MAX_BATCH = 3
        const val JSON_KEYS =
            "assistant_message, confirmed_facts, rejected_facts, new_facts, confirmed_user_facts, rejected_user_facts, new_user_facts_candidates"
        val ID_PATTERN = Regex("\\b([DU]\\s*\\d{1,3})\\b", RegexOption.IGNORE_CASE)
        val NUMBER_PATTERN = Regex("\\b(\\d{1,3})\\b")

        /** A navigation request ("next batch") must not be read as answers to the current batch. */
        fun shouldApplyStructuredUpdate(userPrompt: String): Boolean {
            val text = userPrompt.trim().lowercase(Locale.US)
            if (text.isBlank()) return false
            val hasExplicitId = Regex("\\b[du]\\s*\\d{1,3}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
            val hasConfirmWord = listOf(
                "yes", "yep", "yeah", "confirm", "confirmed", "correct", "right", "approve", "approved", "true",
            ).any { text.contains(it) }
            val hasRejectWord = listOf(
                "no", "nope", "reject", "rejected", "wrong", "false", "deny", "denied", "not correct",
            ).any { text.contains(it) }
            val hasDecisionWord = hasConfirmWord || hasRejectWord
            val hasCollectiveDecision = hasDecisionWord && listOf(
                "all", "both", "these", "those", "them", "all three", "all 3",
            ).any { text.contains(it) }
            val isNavigationOnly = listOf(
                "next batch", "show next", "next", "continue", "move on", "skip", "more", "start daily facts review", "start review",
            ).any { text.contains(it) } && !hasDecisionWord
            if (isNavigationOnly) return false
            return (hasExplicitId && hasDecisionWord) || hasCollectiveDecision
        }

        fun extractUserFacingAssistantText(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return ""
            val looksLikeRawJson = (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                trimmed.startsWith("```json") ||
                trimmed.contains("\"confirmed_facts\"") ||
                trimmed.contains("\"rejected_facts\"")
            return if (looksLikeRawJson) "" else trimmed.take(1500)
        }

        fun normalizeFact(value: String): String = value
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(260)
    }
}
