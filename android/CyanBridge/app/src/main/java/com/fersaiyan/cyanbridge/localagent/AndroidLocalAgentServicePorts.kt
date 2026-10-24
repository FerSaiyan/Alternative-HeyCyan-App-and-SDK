package com.fersaiyan.cyanbridge.localagent

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.ui.hasNotificationPermission

/**
 * [LocalAgentDevice] over the system services and the Accessibility bridge.
 *
 * The notification permission is checked against [serviceContext]; everything else uses its
 * application context.
 */
class AndroidLocalAgentDevice(
    private val serviceContext: Context,
) : LocalAgentDevice {
    private val appContext: Context get() = serviceContext.applicationContext

    override fun availability(): LocalAgentDeviceState.Availability =
        LocalAgentDeviceState.availability(appContext)

    override fun hasNotificationPermission(): Boolean = hasNotificationPermission(serviceContext)

    override fun isAccessibilityConnected(): Boolean = LocalAgentAccessibilityBridge.isConnected()

    override fun observe(): LocalAgentObservation = LocalAgentObserver.observe()

    override fun snapshotScreenText(): String? = LocalAgentAccessibilityBridge.snapshotScreenText()

    override fun activeWindowPackageName(): String? = LocalAgentAccessibilityBridge.activeWindowPackageName()

    override fun privacyBlockedReason(packageName: String): String? =
        LocalAgentSafetyPolicy.blockedReason(appContext, packageName)

    override suspend fun perform(action: LocalAgentAction): Boolean =
        LocalAgentAccessibilityBridge.performWithOptionalShizukuFallback(appContext, action)
}

class AndroidLocalAgentSettings(
    private val context: Context,
) : LocalAgentSettings {
    override fun isAutomationEnabled(): Boolean = AutomationPrefs.isLocalAgentAutomationEnabled(context)

    override fun maxSteps(): Int = AutomationPrefs.getMaxSteps(context)

    override fun approvalListeningCue(): String = LocalAgentLanguage.approvalListeningCue(context)

    override fun completionSpeech(): String = LocalAgentLanguage.completionSpeech(context)
}

class AndroidLocalAgentTaskMemory(
    private val context: Context,
) : LocalAgentTaskMemory {
    override fun findSavedSkill(goal: String): LocalAgentSkillStore.Skill? =
        LocalAgentSkillStore.findExact(context, goal)

    override fun recordReplayFailure(goal: String) {
        LocalAgentSkillStore.recordReplayFailure(context, goal)
    }

    override fun recordTaskHistory(entry: LocalAgentTaskHistory.Entry) {
        LocalAgentTaskHistory.record(context, entry)
    }

    override fun recordSuccessfulSkill(goal: String, actions: List<LocalAgentAction>) {
        LocalAgentSkillStore.recordSuccessful(
            context = context,
            goal = goal,
            actions = actions,
        )
    }

    override fun appendScreenCapture(packageName: String, text: String) {
        LocalAgentMemoryStore.appendScreenCapture(
            context = context,
            packageName = packageName,
            text = text,
        )
    }
}

/** Stores status in [LocalAgentPrefs] and broadcasts it with [LocalBroadcastManager]. */
class LocalAgentStatusBroadcaster(
    private val context: Context,
) : LocalAgentStatusReporter {
    override fun setStatus(status: String) {
        LocalAgentPrefs.setStatus(context, status)
    }

    override fun setLastError(error: String) {
        LocalAgentPrefs.setLastError(context, error)
    }

    override fun clearLastError() {
        LocalAgentPrefs.clearLastError(context)
    }

    override fun emitStatus(
        taskId: Long?,
        isTerminal: Boolean,
        userMessage: String?,
    ) {
        val status = LocalAgentPrefs.getStatus(context)
        val err = LocalAgentPrefs.getLastError(context)

        val intent = Intent(LocalAgentIntents.ACTION_STATUS_CHANGED)
            .putExtra(LocalAgentIntents.EXTRA_STATUS, status)
            .putExtra(LocalAgentIntents.EXTRA_LAST_ERROR, err)
            .putExtra(LocalAgentIntents.EXTRA_IS_TERMINAL, isTerminal)
        taskId?.let { intent.putExtra(LocalAgentIntents.EXTRA_TASK_ID, it) }
        userMessage?.trim()?.takeIf { it.isNotBlank() }?.let {
            intent.putExtra(LocalAgentIntents.EXTRA_USER_MESSAGE, it)
        }

        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }
}
