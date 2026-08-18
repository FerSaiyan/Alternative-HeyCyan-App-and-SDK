package com.fersaiyan.cyanbridge.localagent

import android.content.Context
import com.fersaiyan.cyanbridge.localagent.tasker.TaskerAgentBridge
import com.fersaiyan.cyanbridge.localagent.tasker.TaskerAgentContract

/**
 * Separates planning from device observation/execution.
 *
 * CyanBridge remains responsible for task state, policy, approvals, planning and
 * action selection. Backends only provide observations and carry out actions.
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
 * Tasker-backed observation/execution. No independent action policy lives here:
 * any rejected/cancelled action should originate in CyanBridge and be logged there.
 */
object TaskerExecutionBackend : LocalAgentExecutionBackend {
    override val id: String = "tasker"

    override suspend fun isReady(context: Context): Boolean {
        val probe = TaskerAgentBridge.requestObservation(context, timeoutMs = 2_500L)
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
