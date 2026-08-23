package com.fersaiyan.cyanbridge.localagent.actions

import android.content.Context
import com.fersaiyan.cyanbridge.ai.router.AgentInferencePurpose
import com.fersaiyan.cyanbridge.ai.router.AgentInferenceRouter
import com.fersaiyan.cyanbridge.localagent.LocalAgentAction

/**
 * Generates spoken confirmation and clarification copy while keeping authorization deterministic.
 *
 * The model may phrase a clarification question, but it never decides whether an action is
 * approved. LocalAgentApprovalCoordinator remains the only yes/no policy boundary.
 */
object LocalAgentApprovalClarifier {

    fun initialPrompt(action: LocalAgentAction): String = when (action) {
        is LocalAgentAction.SendEmail -> buildString {
            append("I’m ready to send an email to ${action.to}")
            if (action.subject.isNotBlank()) append(" with subject ${action.subject}")
            val excerpt = action.body.trim().replace(Regex("\\s+"), " ").take(180)
            if (excerpt.isNotBlank()) append(". The message begins: $excerpt")
            append(". Do you want me to send it? Say yes to send it, or no to cancel.")
        }
        is LocalAgentAction.SendSms ->
            "I’m ready to send a message to ${action.number}: ${action.message.take(180)}. Do you want me to send it? Say yes or no."
        is LocalAgentAction.MakeCall ->
            "I’m ready to call ${action.number}. Do you want me to place the call? Say yes or no."
        is LocalAgentAction.SetAlarm -> {
            val label = action.label?.takeIf { it.isNotBlank() }?.let { " for $it" }.orEmpty()
            "I’m ready to set an alarm for %02d:%02d%s. Do you want me to set it? Say yes or no."
                .format(action.hour, action.minute, label)
        }
        LocalAgentAction.ReadScreenAloud ->
            "I’m ready to read the current screen aloud. Do you want me to continue? Say yes or no."
        else ->
            "I’m ready to perform ${action.javaClass.simpleName}. Do you want me to continue? Say yes or no."
    }

    suspend fun clarificationPrompt(
        context: Context,
        originalGoal: String,
        action: LocalAgentAction,
        ambiguousReply: String,
    ): String {
        val fallback = fallbackClarification(action, ambiguousReply)
        return runCatching {
            AgentInferenceRouter.complete(
                context = context,
                purpose = AgentInferencePurpose.UI_PLANNING,
                sessionId = "local-agent-approval-${System.currentTimeMillis()}",
                systemPrompt = SYSTEM_PROMPT,
                userPrompt = buildString {
                    appendLine("Original task:")
                    appendLine(originalGoal.take(1_200))
                    appendLine()
                    appendLine("Blocked high-risk action:")
                    appendLine(LocalAgentActionManager.serializeAction(action).take(2_000))
                    appendLine()
                    appendLine("User reply that was not an unambiguous yes or no:")
                    append(ambiguousReply.take(400))
                },
            )
        }.getOrNull()
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.takeIf { it.length in 12..500 }
            ?: fallback
    }

    fun fallbackClarification(action: LocalAgentAction, ambiguousReply: String): String {
        val heard = ambiguousReply.trim().take(100).ifBlank { "I couldn’t make out an answer" }
        val actionDescription = when (action) {
            is LocalAgentAction.SendEmail -> "send the email to ${action.to} now"
            is LocalAgentAction.SendSms -> "send the message to ${action.number} now"
            is LocalAgentAction.MakeCall -> "place the call to ${action.number} now"
            is LocalAgentAction.SetAlarm -> "set the alarm now"
            LocalAgentAction.ReadScreenAloud -> "read the screen aloud now"
            else -> "perform this action now"
        }
        return "I heard ‘$heard’, and I don’t want to guess. Do you want me to $actionDescription, or leave it undone? Please say yes to continue or no to cancel."
    }

    private const val SYSTEM_PROMPT = """
You are CyanBridge's spoken clarification writer for a blocked high-risk device action.
The action is still pending and MUST NOT be executed based on your answer.
Write exactly one short, natural clarification question for text-to-speech.
Explain what action is waiting and acknowledge the user's ambiguous reply without treating it as consent.
Contrast the two outcomes clearly: perform the pending action versus leave/cancel it.
End by asking the user to say yes to perform it or no to cancel it.
Do not use markdown, bullets, JSON, quotation marks around the whole response, or more than two sentences.
Do not claim the action happened. Do not decide whether the user approved it.
"""
}
