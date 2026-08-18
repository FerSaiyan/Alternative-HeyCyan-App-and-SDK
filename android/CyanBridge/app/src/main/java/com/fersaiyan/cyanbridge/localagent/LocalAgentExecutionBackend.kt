package com.fersaiyan.cyanbridge.localagent

import android.content.Context
import com.fersaiyan.cyanbridge.localagent.actions.LocalAgentActionManager
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
 *
 * Actions that already have a non-Accessibility Android implementation stay in
 * CyanBridge. Tasker is only used for screen observation and UI primitives that
 * need an Accessibility executor. This avoids duplicating calls/SMS/email/settings
 * behavior in the Tasker profile and keeps their existing debug path intact.
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
        if (hasNativeCyanBridgeExecutor(action)) {
            val nativeOk = runCatching { LocalAgentActionManager.executeNow(context, action) }
                .getOrDefault(false)
            return LocalAgentBackendExecutionResult(
                success = nativeOk,
                detail = if (nativeOk) "ok(cyanbridge_native)" else "failed(cyanbridge_native)",
            )
        }

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

    private fun hasNativeCyanBridgeExecutor(action: LocalAgentAction): Boolean = when (action) {
        is LocalAgentAction.OpenApp,
        is LocalAgentAction.MakeCall,
        is LocalAgentAction.SendSms,
        is LocalAgentAction.SendEmail,
        is LocalAgentAction.SetAlarm,
        LocalAgentAction.OpenContacts,
        LocalAgentAction.ToggleWifi,
        LocalAgentAction.ToggleBluetooth,
        LocalAgentAction.ToggleFlashlight -> true

        // ReadScreenAloud still needs a screen observer, so keep it on Tasker after the split.
        LocalAgentAction.ReadScreenAloud,
        is LocalAgentAction.Wait,
        LocalAgentAction.GlobalBack,
        LocalAgentAction.GlobalHome,
        is LocalAgentAction.ClickText,
        is LocalAgentAction.ClickCoord,
        is LocalAgentAction.TypeText,
        LocalAgentAction.PressEnter,
        is LocalAgentAction.Scroll,
        is LocalAgentAction.Finish,
        is LocalAgentAction.Swipe,
        is LocalAgentAction.LongPress,
        LocalAgentAction.OpenNotifications,
        LocalAgentAction.OpenRecents -> false
    }
}
