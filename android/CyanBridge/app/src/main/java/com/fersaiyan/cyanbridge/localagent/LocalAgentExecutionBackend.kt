package com.fersaiyan.cyanbridge.localagent

import android.content.Context
import com.fersaiyan.cyanbridge.localagent.tasker.TaskerAgentBridge
import com.fersaiyan.cyanbridge.localagent.tasker.TaskerAgentContract

/**
 * Separates planning/policy from device observation/execution.
 *
 * CyanBridge owns task state, policy, approvals, planning and action selection.
 * A backend only observes the device and carries out an already-approved action.
 */
interface LocalAgentExecutionBackend {
    val id: String

    suspend fun isReady(context: Context): Boolean
    suspend fun observe(context: Context): LocalAgentObservation?
    suspend fun execute(context: Context, action: LocalAgentAction): LocalAgentBackendExecutionResult
}

data class LocalAgentBackendExecutionResult(
    val success: Boolean,
    val detail: String,
)

/** Existing in-process Accessibility backend kept temporarily for migration/debug comparison. */
object AccessibilityExecutionBackend : LocalAgentExecutionBackend {
    override val id: String = "accessibility_legacy"

    override suspend fun isReady(context: Context): Boolean = LocalAgentAccessibilityBridge.isConnected()

    override suspend fun observe(context: Context): LocalAgentObservation? = LocalAgentObserver.observe()

    override suspend fun execute(
        context: Context,
        action: LocalAgentAction,
    ): LocalAgentBackendExecutionResult {
        val ok = LocalAgentAccessibilityBridge.perform(action)
        return LocalAgentBackendExecutionResult(
            success = ok,
            detail = if (ok) "ok(accessibility)" else "failed(accessibility)",
        )
    }
}

/**
 * Tasker-backed observation/execution.
 *
 * Architectural invariant for the migrated Local Agent:
 *
 * - CyanBridge decides whether an action should happen.
 * - Tasker is the single executor for every model-selected device action.
 * - Tasker does not independently classify, approve, reject or silently cancel actions.
 * - Tasker returns the concrete execution result so CyanBridge remains the source of
 *   truth for policy decisions, task state and debug history.
 *
 * Internal runtime controls such as Wait and Finish are consumed by the agent loop and
 * normally never reach this backend.
 */
object TaskerExecutionBackend : LocalAgentExecutionBackend {
    override val id: String = "tasker"

    override suspend fun isReady(context: Context): Boolean {
        val probe = TaskerAgentBridge.requestObservation(context)
        return probe.success && !probe.payload.isNullOrBlank()
    }

    override suspend fun observe(context: Context): LocalAgentObservation? {
        val response = TaskerAgentBridge.requestObservation(context)
        if (!response.success || response.payload.isNullOrBlank()) return null
        return runCatching { TaskerAgentContract.observationFromJson(response.payload) }.getOrNull()
    }

    override suspend fun execute(
        context: Context,
        action: LocalAgentAction,
    ): LocalAgentBackendExecutionResult {
        val response = TaskerAgentBridge.executeAction(
            context = context,
            actionPayload = TaskerAgentContract.actionToJson(action),
        )
        return LocalAgentBackendExecutionResult(
            success = response.success,
            detail = response.payload
                ?.takeIf { it.isNotBlank() }
                ?: response.error
                    ?.takeIf { it.isNotBlank() }
                ?: if (response.success) "ok(tasker)" else "failed(tasker)",
        )
    }
}
