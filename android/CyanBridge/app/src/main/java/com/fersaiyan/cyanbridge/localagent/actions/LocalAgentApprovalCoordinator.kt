package com.fersaiyan.cyanbridge.localagent.actions

import android.content.Context
import android.content.Intent
import android.os.Build
import com.fersaiyan.cyanbridge.data.local.entity.PendingAction
import com.fersaiyan.cyanbridge.localagent.LocalAgentActionParser
import com.fersaiyan.cyanbridge.localagent.LocalAgentBackendExecutionResult
import com.fersaiyan.cyanbridge.localagent.LocalAgentDeviceState
import com.fersaiyan.cyanbridge.localagent.LocalAgentIntents
import com.fersaiyan.cyanbridge.localagent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.localagent.TaskerExecutionBackend
import com.fersaiyan.cyanbridge.localagent.TaskerLocalAgentService
import com.fersaiyan.cyanbridge.ui.MyApplication

/**
 * Production approval boundary for high-risk Local Agent actions.
 *
 * A human reply such as "yes" or "no" is resolved here, not in Tasker. Approved actions are
 * executed only after CyanBridge updates the pending-action record, then the planner is resumed.
 * This keeps policy/consent in CyanBridge while Tasker remains the Android execution boundary.
 */
object LocalAgentApprovalCoordinator {
    enum class ReplyKind { APPROVE, REJECT, UNKNOWN }

    data class ReplyResult(
        val kind: ReplyKind,
        val action: PendingAction? = null,
        val executed: Boolean = false,
        val detail: String,
    )

    fun classifyReply(reply: String): ReplyKind {
        val normalized = reply.trim().lowercase()
            .replace(Regex("[.!?,;:]+$"), "")
            .trim()
        return when (normalized) {
            "yes", "y", "yeah", "yep", "approve", "approved", "confirm", "confirmed", "send", "go ahead" -> ReplyKind.APPROVE
            "no", "n", "nope", "reject", "rejected", "cancel", "stop", "do not send", "don't send" -> ReplyKind.REJECT
            else -> ReplyKind.UNKNOWN
        }
    }

    suspend fun handleReply(context: Context, reply: String): ReplyResult {
        return when (val kind = classifyReply(reply)) {
            ReplyKind.UNKNOWN -> ReplyResult(kind = kind, detail = "approval_reply_not_understood")
            ReplyKind.REJECT -> rejectOldestPending(context)
            ReplyKind.APPROVE -> approveOldestPending(context)
        }
    }

    suspend fun approveOldestPending(context: Context): ReplyResult {
        val dao = MyApplication.database.pendingActionDao()
        val pending = dao.getActionsByStatus("pending").firstOrNull()
            ?: return ReplyResult(ReplyKind.APPROVE, detail = "no_pending_action")

        pending.status = "approved"
        pending.result = null
        dao.update(pending)

        val actions = LocalAgentActionParser.parseList(pending.actionJson)
        if (actions.isEmpty()) {
            pending.status = "executed"
            pending.result = "parse_failed"
            dao.update(pending)
            resumePlanner(context, rejected = true)
            return ReplyResult(ReplyKind.APPROVE, pending, executed = false, detail = "parse_failed")
        }

        val results = mutableListOf<String>()
        var allSucceeded = true
        for (action in actions) {
            val availability = LocalAgentDeviceState.availability(context)
            if (availability != LocalAgentDeviceState.Availability.READY) {
                LocalAgentPrefs.setStatus(context, "Action blocked: ${availability.statusText}")
                LocalAgentPrefs.setLastError(context, availability.errorCode)
                results += "${action.javaClass.simpleName}: blocked_device_state"
                allSucceeded = false
                break
            }

            val execution = runCatching {
                TaskerExecutionBackend.execute(context, action)
            }.getOrElse {
                LocalAgentBackendExecutionResult(
                    success = false,
                    detail = "tasker_exception:${it.javaClass.simpleName}:${it.message.orEmpty()}",
                )
            }
            results += "${action.javaClass.simpleName}: ${execution.detail}"
            if (!execution.success) {
                allSucceeded = false
                break
            }
        }

        pending.status = "executed"
        pending.result = results.joinToString("; ")
        dao.update(pending)
        resumePlanner(context, rejected = !allSucceeded)
        return ReplyResult(
            kind = ReplyKind.APPROVE,
            action = pending,
            executed = allSucceeded,
            detail = pending.result.orEmpty(),
        )
    }

    suspend fun rejectOldestPending(context: Context): ReplyResult {
        val dao = MyApplication.database.pendingActionDao()
        val pending = dao.getActionsByStatus("pending").firstOrNull()
            ?: return ReplyResult(ReplyKind.REJECT, detail = "no_pending_action")
        pending.status = "rejected"
        pending.result = "rejected_by_user"
        dao.update(pending)
        resumePlanner(context, rejected = true)
        return ReplyResult(
            kind = ReplyKind.REJECT,
            action = pending,
            executed = false,
            detail = "rejected_by_user",
        )
    }

    private fun resumePlanner(context: Context, rejected: Boolean) {
        val intent = Intent(context, TaskerLocalAgentService::class.java).apply {
            action = LocalAgentIntents.ACTION_RESUME_AFTER_APPROVAL
            putExtra(LocalAgentIntents.EXTRA_REJECTED, rejected)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }
}
