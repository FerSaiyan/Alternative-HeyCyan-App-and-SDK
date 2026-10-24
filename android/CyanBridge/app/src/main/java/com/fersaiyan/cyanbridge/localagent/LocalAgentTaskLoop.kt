package com.fersaiyan.cyanbridge.localagent

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The observe -> plan -> act loop of [LocalAgentService].
 *
 * - observe: best-effort screen text via [LocalAgentDevice.observe]
 * - plan: via the [Planner] (a saved low-risk skill is replayed first when one matches)
 * - act: execute the planned actions via an [ActionBatchRunner]
 *
 * When an action requires user approval, the loop pauses and waits for
 * [LocalAgentSession.approvalDeferred] to be completed (see [LocalAgentRuntime.resumeAfterApproval]).
 */
class LocalAgentTaskLoop(
    private val session: LocalAgentSession,
    private val device: LocalAgentDevice,
    private val settings: LocalAgentSettings,
    private val memory: LocalAgentTaskMemory,
    private val status: LocalAgentStatusReporter,
    private val speech: LocalAgentSpeechOutput,
    private val host: LocalAgentServiceHost,
    private val plannerProvider: () -> Planner,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** One planning call for the current task state and observation. */
    fun interface Planner {
        suspend fun next(
            taskState: LocalAgentTaskState,
            observation: LocalAgentObservation,
        ): LocalAgentBrainOutput
    }

    /** Executes one planned batch of actions. */
    fun interface ActionBatchRunner {
        suspend fun execute(actions: List<LocalAgentAction>): LocalAgentStepEngine.ExecutionSummary
    }

    /** Runs the loop for [goal] until it completes, is stopped or reaches [maxSteps]. */
    suspend fun run(
        goal: String,
        maxSteps: Int,
        engine: ActionBatchRunner,
    ) {
        Log.i(TAG, "Loop started")

        val brain = plannerProvider()
        var warnedA11yMissing = false
        // Resume from paused state if available, otherwise start fresh.
        var taskState = session.pausedTaskState ?: LocalAgentTaskState(
            goal = goal,
            maxSteps = maxSteps,
            startedAtMs = clock(),
        )
        session.pausedTaskState = null
        var pendingSavedSkill = memory.findSavedSkill(taskState.goal)
        var usedSavedSkill = false
        val executedActions = mutableListOf<LocalAgentAction>()

        while (currentCoroutineContext().isActive &&
            !session.cancelRequested.get() &&
            taskState.stepIndex <= taskState.maxSteps
        ) {
            var settleAction: LocalAgentAction? = null
            try {
                device.availability()
                    .takeIf { it != LocalAgentDeviceState.Availability.READY }
                    ?.let { availability ->
                        stopTaskForUnavailableDevice(
                            taskState = taskState,
                            usedSavedSkill = usedSavedSkill,
                            executedActions = executedActions,
                            availability = availability,
                        )
                        return
                    }

                if (!device.isAccessibilityConnected()) {
                    val err = "accessibility_not_connected"
                    session.lastError.set(err)
                    status.setStatus("Waiting for accessibility")
                    status.setLastError(err)
                    status.emitStatus()

                    if (!warnedA11yMissing) {
                        warnedA11yMissing = true
                        Log.w(TAG, "Accessibility service not connected; enable it in Android Accessibility settings")
                    }
                    delay(1_000)
                    continue
                }

                status.setStatus("Running step ${taskState.stepIndex}/${taskState.maxSteps}")
                status.emitStatus()

                val obs = device.observe()
                val replayingSavedSkill = pendingSavedSkill != null
                val out = pendingSavedSkill?.let { skill ->
                    pendingSavedSkill = null
                    usedSavedSkill = true
                    LocalAgentBrainOutput(
                        actions = skill.actions,
                        note = "Replaying a saved low-risk navigation skill.",
                    )
                } ?: withTimeoutOrNull(BRAIN_CALL_TIMEOUT_MS) {
                    brain.next(taskState, obs)
                } ?: LocalAgentBrainOutput(
                    actions = listOf(LocalAgentAction.Finish("Brain call timed out.")),
                    note = "Brain call timed out after ${BRAIN_CALL_TIMEOUT_MS / 1000}s.",
                    isComplete = true,
                )

                device.availability()
                    .takeIf { it != LocalAgentDeviceState.Availability.READY }
                    ?.let { availability ->
                        stopTaskForUnavailableDevice(
                            taskState = taskState,
                            usedSavedSkill = usedSavedSkill,
                            executedActions = executedActions,
                            availability = availability,
                        )
                        return
                    }

                val actions = out.actions
                if (actions.isNotEmpty()) {
                    Log.i(TAG, "Planned ${actions.size} actions. note=${out.note}")
                }

                val plannedAction = actions.singleOrNull()
                settleAction = plannedAction
                if (plannedAction != null && taskState.hasReachedRepeatLimit(plannedAction)) {
                    val reason = LocalAgentRuntimePolicy.repeatLimitMessage(plannedAction)
                    Log.w(TAG, reason)
                    taskState = taskState.nextStep(
                        previousActionResult = reason,
                        failed = true,
                    )
                    delay(LocalAgentRuntimePolicy.settleDelayMs(plannedAction))
                    continue
                }

                val summary = engine.execute(actions)
                if (summary.haltedForDeviceState) {
                    stopTaskForUnavailableDevice(
                        taskState = taskState,
                        usedSavedSkill = usedSavedSkill,
                        executedActions = executedActions,
                        availability = summary.deviceAvailability
                            ?: device.availability(),
                    )
                    return
                }
                val actionFailed = summary.actionResults.any { it.endsWith("failed") }
                if (!summary.haltedForApproval && !actionFailed) {
                    executedActions += actions.filterNot { it is LocalAgentAction.Finish }
                }
                if (replayingSavedSkill && actionFailed) {
                    memory.recordReplayFailure(taskState.goal)
                }

                // Recovery: when an action fails, try a diagnostic recovery before
                // letting the brain plan the next step.
                if (actionFailed && !summary.haltedForApproval) {
                    val recovery = LocalAgentRecoveryEngine.diagnose(
                        lastFailedAction = plannedAction,
                        screenText = obs.screenText,
                        consecutiveFailures = taskState.consecutiveFailures + 1,
                    )
                    if (recovery != null) {
                        Log.i(TAG, "Recovery: ${recovery.description}")
                        status.setStatus("Recovering: ${recovery.description.take(48)}")
                        status.emitStatus()
                        val recoveryOk = device.perform(recovery.action)
                        Log.i(TAG, "Recovery action ok=$recoveryOk")
                        delay(LocalAgentRuntimePolicy.settleDelayMs(recovery.action))
                    }
                }
                val previousResult = buildString {
                    out.note?.takeIf { it.isNotBlank() }?.let {
                        append(it)
                    }
                    if (summary.actionResults.isNotEmpty()) {
                        if (isNotBlank()) append(" | ")
                        append(summary.actionResults.joinToString("; "))
                    }
                }.ifBlank { out.note ?: "" }

                if (summary.haltedForApproval) {
                    val approved = awaitApprovalWithVoice(plannedAction)
                    if (!approved) {
                        recordTaskOutcome(
                            taskState = taskState,
                            status = "Stopped",
                            usedSavedSkill = usedSavedSkill,
                            executedActions = executedActions,
                        )
                        completeLoop(
                            taskId = taskState.startedAtMs,
                            status = "Stopped",
                            notification = "Action rejected",
                            userMessage = "Action was not approved.",
                            error = "action_rejected",
                        )
                        return
                    }

                    plannedAction?.takeUnless { it is LocalAgentAction.Finish }?.let(executedActions::add)

                    taskState = taskState.nextStep(
                        previousActionResult = "Action approved and executed by user",
                        failed = false,
                        action = plannedAction,
                    )
                    delay(LocalAgentRuntimePolicy.settleDelayMs(plannedAction))
                    continue
                }

                if (out.isComplete || summary.finished) {
                    recordTaskOutcome(
                        taskState = taskState,
                        status = "Completed",
                        usedSavedSkill = usedSavedSkill,
                        executedActions = executedActions,
                    )
                    completeLoop(
                        taskId = taskState.startedAtMs,
                        status = "Completed",
                        notification = summary.actionResults.lastOrNull()
                            ?: out.note
                            ?: "Task completed",
                        userMessage = completionSpeech(actions),
                    )
                    return
                }

                taskState = taskState.nextStep(
                    previousActionResult = previousResult,
                    failed = actionFailed,
                    action = plannedAction,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                session.lastError.set(msg)
                status.setStatus("Error")
                status.setLastError(msg)
                status.emitStatus()
                Log.e(TAG, "Loop error: $msg", e)
                // Back off to avoid crash loops.
                taskState = taskState.nextStep(previousActionResult = msg, failed = true)
                delay(1_000)
            }

            // Let the destination app render before observing its next state.
            delay(LocalAgentRuntimePolicy.settleDelayMs(settleAction))
        }

        if (currentCoroutineContext().isActive) {
            recordTaskOutcome(
                taskState = taskState,
                status = "Stopped",
                usedSavedSkill = usedSavedSkill,
                executedActions = executedActions,
            )
            completeLoop(
                taskId = taskState.startedAtMs,
                status = "Stopped",
                notification = "Reached max steps (${taskState.maxSteps})",
                userMessage = "I couldn't finish that task.",
                error = "max_steps_reached",
            )
        }
    }

    private fun recordTaskOutcome(
        taskState: LocalAgentTaskState,
        status: String,
        usedSavedSkill: Boolean,
        executedActions: List<LocalAgentAction>,
    ) {
        memory.recordTaskHistory(
            LocalAgentTaskHistory.Entry(
                goal = taskState.goal,
                status = status,
                stepCount = taskState.stepIndex,
                usedSavedSkill = usedSavedSkill,
            ),
        )
        if (status == "Completed") {
            memory.recordSuccessfulSkill(
                goal = taskState.goal,
                actions = executedActions,
            )
        }
    }

    private suspend fun stopTaskForUnavailableDevice(
        taskState: LocalAgentTaskState,
        usedSavedSkill: Boolean,
        executedActions: List<LocalAgentAction>,
        availability: LocalAgentDeviceState.Availability,
    ) {
        val blocked = availability.takeIf { it != LocalAgentDeviceState.Availability.READY }
            ?: LocalAgentDeviceState.Availability.UNAVAILABLE
        session.pausedTaskState = null
        session.rejectPendingApproval()
        recordTaskOutcome(
            taskState = taskState,
            status = "Stopped",
            usedSavedSkill = usedSavedSkill,
            executedActions = executedActions,
        )
        completeLoop(
            taskId = taskState.startedAtMs,
            status = "Stopped: ${blocked.statusText}",
            notification = "Stopped: ${blocked.statusText}",
            userMessage = "",
            error = blocked.errorCode,
            speakResult = false,
        )
    }

    private suspend fun awaitApprovalWithVoice(action: LocalAgentAction?): Boolean {
        AudioSessionCoordinator.waitUntilIdle()
        device.availability()
            .takeIf { it != LocalAgentDeviceState.Availability.READY }
            ?.let { return false }

        val summary = describeActionForApproval(action)
        val approvalCue = settings.approvalListeningCue()

        session.pausedTaskState = taskStateFromCurrentGoal()
        val deferred = CompletableDeferred<Boolean>()
        session.approvalDeferred = deferred

        host.updateNotification(WAITING_FOR_APPROVAL_NOTIFICATION)

        speech.speakAndWaitBestEffort("$summary $approvalCue", maxLength = 240)

        var approved: Boolean? = null
        var unavailableDevice: LocalAgentDeviceState.Availability? = null
        while (currentCoroutineContext().isActive && approved == null) {
            val availability = device.availability()
            if (availability != LocalAgentDeviceState.Availability.READY) {
                unavailableDevice = availability
                break
            }
            approved = withTimeoutOrNull(500L) { deferred.await() }
        }
        session.approvalDeferred = null

        unavailableDevice?.let {
            deferred.complete(false)
            return false
        }

        return approved == true
    }

    private fun taskStateFromCurrentGoal(): LocalAgentTaskState? {
        val existing = session.pausedTaskState
        if (existing != null) return existing
        return session.loopJob?.let {
            LocalAgentTaskState(
                goal = "Current agent task",
                maxSteps = settings.maxSteps(),
                startedAtMs = clock(),
            )
        }
    }

    private suspend fun completeLoop(
        taskId: Long,
        status: String,
        notification: String,
        userMessage: String,
        error: String? = null,
        speakResult: Boolean = true,
    ) {
        session.running.set(false)
        session.loopJob = null
        session.lastError.set(error)
        this.status.setStatus(status)
        if (error.isNullOrBlank()) {
            this.status.clearLastError()
        } else {
            this.status.setLastError(error)
        }
        this.status.emitStatus(
            taskId = taskId,
            isTerminal = true,
            userMessage = userMessage,
        )
        if (speakResult) speech.speakAndWaitBestEffort(userMessage)
        host.updateNotification(notification)
        host.stopForegroundAndSelf()
    }

    private fun completionSpeech(actions: List<LocalAgentAction>): String {
        if (session.screenReadInProgress.get() || actions.any { it == LocalAgentAction.ReadScreenAloud }) return ""
        val finishMessage = actions.filterIsInstance<LocalAgentAction.Finish>()
            .lastOrNull()
            ?.message
            .orEmpty()
            .lowercase()
        val failed = finishMessage.contains("fail") ||
            finishMessage.contains("couldn't") ||
            finishMessage.contains("cannot") ||
            finishMessage.contains("blocked") ||
            finishMessage.contains("stopped")
        return if (failed) "I couldn't finish that task." else settings.completionSpeech()
    }

    internal companion object {
        private const val TAG = LOCAL_AGENT_SERVICE_LOG_TAG
        const val BRAIN_CALL_TIMEOUT_MS = 60_000L

        /** Notification text that also routes the notification tap to the pending-actions screen. */
        const val WAITING_FOR_APPROVAL_NOTIFICATION = "Waiting for your approval"

        fun describeActionForApproval(action: LocalAgentAction?): String {
            return when (action) {
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
                null -> "Perform the next action."
            }
        }
    }
}
