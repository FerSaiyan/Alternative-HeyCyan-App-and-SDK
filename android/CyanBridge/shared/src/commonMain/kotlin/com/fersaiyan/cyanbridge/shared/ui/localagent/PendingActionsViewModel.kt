package com.fersaiyan.cyanbridge.shared.ui.localagent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.data.local.entity.PendingAction
import com.fersaiyan.cyanbridge.localagent.LocalAgentAction
import com.fersaiyan.cyanbridge.localagent.LocalAgentActionParser
import com.fersaiyan.cyanbridge.shared.ui.UiMessage
import kotlinx.coroutines.launch

/** What the platform did with an approval reply: the oldest pending action, and whether it ran. */
data class PendingActionReply(val actionId: Long?, val executed: Boolean)

interface PendingActionsPlatform {
    suspend fun pendingActions(): List<PendingAction>

    /** Approves or rejects the oldest pending action. */
    suspend fun reply(approve: Boolean): PendingActionReply
    fun formatTimestamp(ms: Long): String

    /** Indents a JSON object; other text comes back unchanged. */
    fun prettyJson(json: String): String
    fun showMessage(message: UiMessage)
}

/** The Local Agent's approval queue: the oldest pending action, approved or rejected one at a time. */
class PendingActionsViewModel(private val platform: PendingActionsPlatform) : ViewModel() {
    var pendingCount by mutableStateOf(0)
        private set
    var renderedAction by mutableStateOf(NO_PENDING_ACTIONS)
        private set
    var hasPendingAction by mutableStateOf(false)
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val pending = platform.pendingActions()
            pendingCount = pending.size
            val current = pending.firstOrNull()
            hasPendingAction = current != null
            renderedAction = current?.let(::render) ?: NO_PENDING_ACTIONS
        }
    }

    fun approve() {
        viewModelScope.launch {
            val reply = platform.reply(approve = true)
            val message = when {
                reply.actionId == null -> "No pending action"
                reply.executed -> "Executed action #${reply.actionId}"
                else -> "Tasker failed action #${reply.actionId}"
            }
            platform.showMessage(UiMessage(message))
            refresh()
        }
    }

    fun reject() {
        viewModelScope.launch {
            val reply = platform.reply(approve = false)
            platform.showMessage(UiMessage(if (reply.actionId != null) "Rejected action #${reply.actionId}" else "No pending action"))
            refresh()
        }
    }

    private fun render(action: PendingAction): String {
        val tsText = if (action.ts > 0L) platform.formatTimestamp(action.ts) else "(no-ts)"
        val humanSummary = LocalAgentActionParser.parseList(action.actionJson).joinToString("\n", transform = ::describe)
        val prettyJson = runCatching {
            val trimmed = action.actionJson.trim()
            if (trimmed.startsWith("{")) platform.prettyJson(trimmed) else trimmed
        }.getOrDefault(action.actionJson)

        return buildString {
            appendLine("id=${action.id}")
            appendLine("ts=$tsText")
            appendLine("source=${action.source}")
            appendLine("status=${action.status}")
            if (!action.result.isNullOrBlank()) appendLine("result=${action.result}")
            appendLine("---")
            if (humanSummary.isNotBlank()) {
                appendLine(humanSummary)
                appendLine("---")
            }
            appendLine(prettyJson)
        }.trimEnd()
    }

    private fun describe(action: LocalAgentAction): String = when (action) {
        is LocalAgentAction.OpenApp -> "Open ${action.appName}."
        is LocalAgentAction.ClickText -> "Click ${action.text}."
        is LocalAgentAction.ClickCoord -> "Tap the highlighted control."
        is LocalAgentAction.TypeText -> "Type ${action.text}."
        is LocalAgentAction.PressEnter -> "Press enter."
        is LocalAgentAction.Scroll -> "Scroll the screen."
        is LocalAgentAction.Swipe -> "Swipe the screen."
        is LocalAgentAction.LongPress -> "Long press the selected control."
        is LocalAgentAction.GlobalBack -> "Press back."
        is LocalAgentAction.GlobalHome -> "Go home."
        is LocalAgentAction.OpenNotifications -> "Open notifications."
        is LocalAgentAction.OpenRecents -> "Open recent apps."
        is LocalAgentAction.OpenContacts -> "Open contacts."
        is LocalAgentAction.MakeCall -> "Call ${action.number}."
        is LocalAgentAction.SendSms -> "Send a message to ${action.number}."
        is LocalAgentAction.SendEmail -> "Send an email to ${action.to}."
        is LocalAgentAction.SetAlarm -> "Set an alarm."
        is LocalAgentAction.ReadScreenAloud -> "Read the screen aloud."
        is LocalAgentAction.ToggleWifi -> "Open Wi-Fi settings."
        is LocalAgentAction.ToggleBluetooth -> "Open Bluetooth settings."
        is LocalAgentAction.ToggleFlashlight -> "Open flashlight settings."
        is LocalAgentAction.Wait -> "Wait briefly."
        is LocalAgentAction.Finish -> "Finish the task."
    }

    private companion object {
        const val NO_PENDING_ACTIONS = "(no pending actions)"
    }
}
