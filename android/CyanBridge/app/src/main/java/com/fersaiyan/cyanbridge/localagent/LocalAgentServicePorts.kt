package com.fersaiyan.cyanbridge.localagent

/**
 * Narrow views of the Android capabilities used by [LocalAgentService]'s runtime classes
 * ([LocalAgentRuntime], [LocalAgentTaskLoop], [LocalAgentScreenReader]). Production
 * implementations live in AndroidLocalAgentServicePorts.kt; tests supply fakes.
 */

/** Phone state, Accessibility snapshots and UI actions. */
interface LocalAgentDevice {
    fun availability(): LocalAgentDeviceState.Availability
    fun hasNotificationPermission(): Boolean
    fun isAccessibilityConnected(): Boolean
    fun observe(): LocalAgentObservation
    fun snapshotScreenText(): String?
    fun activeWindowPackageName(): String?
    fun privacyBlockedReason(packageName: String): String?
    suspend fun perform(action: LocalAgentAction): Boolean
}

/** User settings and localized phrases. */
interface LocalAgentSettings {
    fun isAutomationEnabled(): Boolean
    fun maxSteps(): Int
    fun approvalListeningCue(): String
    fun completionSpeech(): String
}

/** Saved skills, task history and screen-capture memory. */
interface LocalAgentTaskMemory {
    fun findSavedSkill(goal: String): LocalAgentSkillStore.Skill?
    fun recordReplayFailure(goal: String)
    fun recordTaskHistory(entry: LocalAgentTaskHistory.Entry)
    fun recordSuccessfulSkill(goal: String, actions: List<LocalAgentAction>)
    fun appendScreenCapture(packageName: String, text: String)
}

/** Persisted status/last error plus the [LocalAgentIntents.ACTION_STATUS_CHANGED] broadcast. */
interface LocalAgentStatusReporter {
    fun setStatus(status: String)
    fun setLastError(error: String)
    fun clearLastError()
    fun emitStatus(
        taskId: Long? = null,
        isTerminal: Boolean = false,
        userMessage: String? = null,
    )
}

/** Foreground-service controls owned by the hosting [android.app.Service]. */
interface LocalAgentServiceHost {
    fun startForeground(content: String)

    /** Best-effort update of the ongoing notification; failures are ignored. */
    fun updateNotification(content: String)

    fun stopForegroundAndSelf()
    fun stopSelf()
}
