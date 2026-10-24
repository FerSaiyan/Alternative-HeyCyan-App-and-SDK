package com.fersaiyan.cyanbridge.ai.summarization

import android.content.Context
import com.fersaiyan.cyanbridge.ai.router.CliRelayClient
import com.fersaiyan.cyanbridge.shared.notes.ChatSummarizationService
import com.fersaiyan.cyanbridge.shared.notes.SummarizationService

/** Meeting notes ([ChatSummarizationService]) through the relay chat. */
class AiSummarizationService(
    context: Context,
) : SummarizationService by ChatSummarizationService({ prompt ->
    CliRelayClient.chat(
        context = context,
        chatId = "ai-summarization",
        prompt = prompt,
        messages = listOf(mapOf("role" to "user", "content" to prompt)),
    ).getOrElse { error ->
        throw IllegalStateException("Meeting summary model request failed", error)
    }
})
