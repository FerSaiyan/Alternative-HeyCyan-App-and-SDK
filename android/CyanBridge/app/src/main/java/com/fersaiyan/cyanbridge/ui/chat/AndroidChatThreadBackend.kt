package com.fersaiyan.cyanbridge.ui.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionRelayClient
import com.fersaiyan.cyanbridge.ai.router.AiAssistantRouter
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.localagent.context.LocalAgentContextBuilder
import com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyFactsReviewChat
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemorySearch
import com.fersaiyan.cyanbridge.localagent.memory.RagProfile
import com.fersaiyan.cyanbridge.localagent.userfacts.ChatMemoryAutoUpdater
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.localmodels.settings.LocalGenerationSettings
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.InstalledLocalModel
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.shared.chat.ChatMessage
import com.fersaiyan.cyanbridge.shared.chat.ChatRole
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatReplyRequest
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatReplyStream
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatThreadBackend
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Chat replies through the selected provider, with local agent memory as context. */
internal class AndroidChatThreadBackend(private val context: Context) : ChatThreadBackend {
    private val mainHandler = Handler(Looper.getMainLooper())
    override val dailyReview = DailyFactsReviewChat(context, this)

    override fun modelInfo() = chatModelInfo(context)

    override fun bubbleColors(): Pair<Int?, Int?> =
        ChatAppearancePrefs.getUserBubbleColor(context) to ChatAppearancePrefs.getAssistantBubbleColor(context)

    override suspend fun wallpaper(): ImageBitmap? {
        val wallpaper = ChatAppearancePrefs.getWallpaperUri(context)
        if (wallpaper.isBlank()) return null
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(wallpaper))?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()?.asImageBitmap()
    }

    override suspend fun reply(request: ChatReplyRequest, stream: ChatReplyStream?): String = withContext(Dispatchers.IO) {
        val messages = request.review?.let { dailyReview.buildMessages(request.chatId, it) }
            ?: buildChatMessages(request.chatId, request.prompt)
        AiAssistantRouter.chatReplyStreaming(
            context = context,
            chatId = request.chatId,
            userPrompt = request.prompt,
            messages = messages,
            imagePaths = request.imagePaths,
            audioPath = request.audioPath,
            callbacks = stream?.let { routerCallbacks(it, reportMaxTokens = true) },
        )
    }

    /** Forwards router callbacks to the main thread; with [reportMaxTokens] the cap status is reported apart. */
    fun routerCallbacks(stream: ChatReplyStream, reportMaxTokens: Boolean) = object : AiAssistantRouter.ChatStreamCallbacks {
        override fun onStatus(status: String) = onMain {
            if (reportMaxTokens && status == LocalModelsProvider.STATUS_MAX_TOKENS_REACHED) {
                stream.onMaxTokensReached()
            } else {
                stream.onStatus(status)
            }
        }

        override fun onToken(token: String) = onMain { stream.onToken(token) }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    override suspend fun textReply(prompt: String): String =
        withContext(Dispatchers.IO) { AiAssistantRouter.textReply(context, prompt) }

    override suspend fun cancelGeneration() = AiAssistantRouter.cancelCurrentGeneration(context)

    override suspend fun onReplyStored(prompt: String, reply: String) {
        withContext(Dispatchers.IO) {
            ChatMemoryAutoUpdater.extractAndStore(context = context, userMessage = prompt, assistantReply = reply)
        }
    }

    override fun relayUnavailableHint(text: String): String? = ProSubscriptionRelayClient.relayUnavailableHintFromText(text)

    private suspend fun buildChatMessages(chatId: String, lastUserPrompt: String): List<Map<String, String>> {
        val selectedModel = selectedLocalModel()
        val budgets = contextBudgets(
            isDailyReview = false,
            selectedModel = selectedModel,
            contextSize = selectedModel?.let { LocalModelSettingsRepository.getForModel(context, it.id).contextSize } ?: 4096,
        )
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis()))
        val relevantMemory = LocalAgentMemorySearch.buildRelevantMemoryBlock(
            context = context,
            queryText = lastUserPrompt,
            date = today,
            ragProfile = RagProfile.FULL,
        )
        val extra = if (relevantMemory.isBlank()) {
            emptyList()
        } else {
            listOf(LocalAgentContextBuilder.Section(title = "Relevant memory (search hits)", content = relevantMemory))
        }
        val ctx = localAgentContextBuilder(budgets.systemChars)
            .buildSystemMessageWithDebug(context = context, date = today, extraSections = extra)
        LocalAgentPrefs.setLastContextInjectionDebug(context = context, debugText = ctx.debug.toMultilineString())

        val history = trimHistoryForContext(ChatStore.listMessages(chatId).map(::relayMessage), budgets.historyTokens)
        if (ctx.systemMessage.isBlank()) return history
        return listOf(mapOf("role" to "System", "content" to ctx.systemMessage)) + history
    }

    fun selectedLocalModel(): InstalledLocalModel? =
        if (modelInfo().usesLocalModels) LocalModelStorageRepository.resolveSelectedModel(context) else null

    data class ContextBudgets(val systemChars: Int, val historyTokens: Int)

    fun contextBudgets(isDailyReview: Boolean, selectedModel: InstalledLocalModel?, contextSize: Int): ContextBudgets {
        val normalizedCtx = contextSize.coerceIn(LocalGenerationSettings.MIN_CONTEXT_SIZE, LocalGenerationSettings.MAX_CONTEXT_SIZE)
        if (!modelInfo().usesLocalModels) {
            return if (isDailyReview) {
                ContextBudgets(systemChars = 3000, historyTokens = 2200)
            } else {
                ContextBudgets(systemChars = 6000, historyTokens = 5000)
            }
        }
        val modelName = selectedModel?.displayName.orEmpty().lowercase()
        val smallModel = modelName.contains("1b") || (selectedModel?.sizeBytes ?: Long.MAX_VALUE) < 2_500_000_000L
        val systemFraction = when {
            isDailyReview -> 0.32
            smallModel -> 0.16
            else -> 0.30
        }
        val historyFraction = when {
            isDailyReview -> 0.30
            smallModel -> 0.34
            else -> 0.52
        }
        val systemTokens = (normalizedCtx * systemFraction).toInt().coerceAtLeast(220)
        val historyTokens = (normalizedCtx * historyFraction).toInt().coerceAtLeast(260)
        return ContextBudgets(systemChars = (systemTokens * 4).coerceIn(900, 7000), historyTokens = historyTokens)
    }

    private fun localAgentContextBuilder(maxTotalChars: Int): LocalAgentContextBuilder {
        val ratio = (maxTotalChars.toDouble() / 14_000.0).coerceIn(0.12, 1.0)
        fun scaled(base: Int, min: Int): Int = (base * ratio).toInt().coerceAtLeast(min)
        return LocalAgentContextBuilder(
            maxAgentPersonaChars = scaled(base = 3_000, min = 280),
            maxUserFactsChars = scaled(base = 3_000, min = 320),
            maxConfirmedDailyFactsChars = scaled(base = 4_000, min = 420),
            maxDailySummaryChars = scaled(base = 5_000, min = 520),
            maxTotalChars = maxTotalChars,
        )
    }

    companion object {
        fun relayMessage(message: ChatMessage) = mapOf(
            "role" to if (message.role == ChatRole.USER) "User" else "Assistant",
            "content" to message.content,
        )

        /** Keeps the newest messages that fit [maxApproxTokens] (about four characters per token). */
        fun trimHistoryForContext(history: List<Map<String, String>>, maxApproxTokens: Int): List<Map<String, String>> {
            if (history.isEmpty()) return history
            var budget = maxApproxTokens.coerceAtLeast(120)
            val kept = ArrayList<Map<String, String>>()
            for (i in history.indices.reversed()) {
                val msg = history[i]
                val approxTokens = ((msg["content"].orEmpty().length + msg["role"].orEmpty().length) / 4).coerceAtLeast(10)
                if (kept.isNotEmpty() && budget - approxTokens < 0) break
                kept.add(msg)
                budget -= approxTokens
            }
            return kept.asReversed()
        }
    }
}
