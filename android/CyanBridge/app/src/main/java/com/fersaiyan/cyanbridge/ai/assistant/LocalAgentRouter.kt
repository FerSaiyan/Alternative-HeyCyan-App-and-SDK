package com.fersaiyan.cyanbridge.ai.assistant

import android.content.Context
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.shared.chat.ChatMessage

/**
 * Skeleton router for the on-device assistant.
 *
 * In the future this will call into an on-device runtime (e.g., llama.cpp / MLKit / vendor SDK).
 */
class LocalAgentRouter {

    suspend fun generateReply(
        context: Context,
        messages: List<ChatMessage>,
    ): String {
        // Placeholder implementation for Task 52.
        val enabled = LocalAgentPrefs.isEnabled(context)
        val modelId = LocalAgentPrefs.getModelId(context)

        return if (!enabled) {
            "Local Agent is disabled. Enable it in settings to use the on-device assistant."
        } else {
            "Local Agent (on-device) is not implemented yet. modelId=${modelId ?: "(default)"}." +
                " (messages=${messages.size})"
        }
    }
}
