package com.fersaiyan.cyanbridge.localagent

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.localagent.actions.LocalAgentActionManager
import kotlinx.coroutines.delay

class LocalAgentStepEngine(
    private val context: Context,
    private val executor: LocalAgentActionExecutor,
) {
    data class ExecutionSummary(
        val actionResults: List<String>,
        val haltedForApproval: Boolean,
        val finished: Boolean,
        val haltedForDeviceState: Boolean = false,
        val deviceAvailability: LocalAgentDeviceState.Availability? = null,
    )

    /**
     * Executes a list of actions sequentially.
     *
     * This class is kept for legacy service compatibility during migration, but all
     * model-selected device effects now flow through LocalAgentActionManager ->
     * TaskerExecutionBackend. There is no native-intent-then-Accessibility fallback.
     */
    suspend fun execute(actions: List<LocalAgentAction>): ExecutionSummary {
        val results = mutableListOf<String>()
        for ((index, action) in actions.withIndex()) {
            executor.ensureNotCancelled()

            LocalAgentDeviceState.availability(context)
                .takeIf { it != LocalAgentDeviceState.Availability.READY }
                ?.let { availability ->
                    Log.i(TAG, "Stopping action execution: ${availability.errorCode}")
                    results += "${action.javaClass.simpleName}: blocked_device_state"
                    return ExecutionSummary(
                        actionResults = results,
                        haltedForApproval = false,
                        finished = false,
                        haltedForDeviceState = true,
                        deviceAvailability = availability,
                    )
                }

            if (action is LocalAgentAction.Finish) {
                results += action.message?.takeIf { it.isNotBlank() } ?: "Task marked complete"
                return ExecutionSummary(results, haltedForApproval = false, finished = true)
            }

            if (action is LocalAgentAction.Wait) {
                delay(action.ms)
                continue
            }

            val risk = LocalAgentActionManager.classifyRisk(action)
            val requireConfirm = LocalAgentPrefs.isRequireActionConfirmationEnabled(context)
            val needsApproval = requireConfirm && risk == LocalAgentActionManager.Risk.HIGH

            if (needsApproval) {
                Log.i(TAG, "action=${action.javaClass.simpleName} requires approval, enqueuing")
                LocalAgentActionManager.processPlannedAction(context, action)
                results += "${action.javaClass.simpleName}: queued_for_approval"
                return ExecutionSummary(results, haltedForApproval = true, finished = false)
            }

            val ok = LocalAgentActionManager.processPlannedAction(context, action)
            Log.i(TAG, "action=${action.javaClass.simpleName} executed via Tasker ok=$ok")
            results += "${action.javaClass.simpleName}: ${if (ok) "ok(tasker)" else "failed(tasker)"}"

            if (!ok) {
                return ExecutionSummary(results, haltedForApproval = false, finished = false)
            }

            // Saved navigation skills may contain several actions. Give the target app
            // the same rendering time that the normal observe-act loop would provide.
            if (index < actions.lastIndex) {
                delay(LocalAgentRuntimePolicy.settleDelayMs(action))
            }
        }

        return ExecutionSummary(results, haltedForApproval = false, finished = false)
    }

    interface LocalAgentActionExecutor {
        /** Kept only so the legacy service implementation does not need a wider rewrite yet. */
        suspend fun execute(action: LocalAgentAction): Boolean
        fun ensureNotCancelled()
    }

    private companion object {
        private const val TAG = "LocalAgentSteps"
    }
}
