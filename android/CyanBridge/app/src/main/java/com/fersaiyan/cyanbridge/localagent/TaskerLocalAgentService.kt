package com.fersaiyan.cyanbridge.localagent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.localagent.actions.LocalAgentActionManager
import com.fersaiyan.cyanbridge.localagent.actions.LocalAgentApprovalClarifier
import com.fersaiyan.cyanbridge.localagent.actions.LocalAgentApprovalCoordinator
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tasker-backed Local Agent runtime.
 *
 * CyanBridge owns the goal, planner, repeat/failure handling and approval policy.
 * Tasker owns screen observation and action execution only. A Tasker execution
 * failure is returned verbatim into task state so debugging has a single source
 * of truth in CyanBridge.
 */
class TaskerLocalAgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private val cancelRequested = AtomicBoolean(false)
    private var approvalDeferred: CompletableDeferred<Boolean>? = null
    private lateinit var approvalVoiceSession: LocalAgentApprovalVoiceSession

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        LocalAgentMemoryStore.ensureSeedFiles(applicationContext)
        approvalVoiceSession = LocalAgentApprovalVoiceSession(applicationContext) { status ->
            setStatus(status, null)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            LocalAgentIntents.ACTION_START -> startLoop(intent.getStringExtra(LocalAgentIntents.EXTRA_GOAL))
            LocalAgentIntents.ACTION_STOP -> stopLoop("user")
            LocalAgentIntents.ACTION_GET_STATUS -> emitStatus()
            LocalAgentIntents.ACTION_READ_SCREEN_ALOUD -> readScreenAloudOnce()
            LocalAgentIntents.ACTION_APPROVAL_REPLY -> {
                val reply = intent.getStringExtra(LocalAgentIntents.EXTRA_APPROVAL_REPLY).orEmpty()
                if (approvalDeferred != null) {
                    approvalVoiceSession.submitExternalReply(reply)
                } else if (reply.isNotBlank()) {
                    scope.launch {
                        val result = LocalAgentApprovalCoordinator.handleReply(applicationContext, reply)
                        if (result.kind == LocalAgentApprovalCoordinator.ReplyKind.UNKNOWN) {
                            setStatus("No active voice confirmation understood that reply", null)
                        }
                    }
                }
            }
            LocalAgentIntents.ACTION_RESUME_AFTER_APPROVAL -> {
                val rejected = intent.getBooleanExtra(LocalAgentIntents.EXTRA_REJECTED, false)
                approvalDeferred?.takeIf { !it.isCompleted }?.complete(!rejected)
            }
            LocalAgentIntents.ACTION_DEMO -> startLoop("Open the notification shade, then return home.")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (loopJob?.isActive == true && !cancelRequested.get()) {
            setStatus("Stopped", "service_destroy")
        }
        cancelRequested.set(true)
        loopJob?.cancel()
        loopJob = null
        approvalDeferred?.takeIf { !it.isCompleted }?.complete(false)
        approvalDeferred = null
        if (::approvalVoiceSession.isInitialized) approvalVoiceSession.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun readScreenAloudOnce() {
        if (loopJob?.isActive == true) return
        startForeground(NOTIFICATION_ID, notification("Reading screen through Tasker"))
        setStatus("Reading screen through Tasker", null)
        loopJob = scope.launch {
            val result = withTimeoutOrNull(EXECUTION_TIMEOUT_MS) {
                TaskerExecutionBackend.execute(applicationContext, LocalAgentAction.ReadScreenAloud)
            } ?: LocalAgentBackendExecutionResult(false, "tasker_execution_timeout")
            if (result.success) {
                finishService("Screen read aloud", null)
            } else {
                finishService("Screen read failed", result.detail)
            }
        }
    }

    private fun startLoop(goalRaw: String?) {
        if (loopJob?.isActive == true) return
        if (!AutomationPrefs.isLocalAgentAutomationEnabled(applicationContext)) {
            finishService("Disabled", "local_agent_automation_disabled")
            return
        }

        val goal = goalRaw?.trim().orEmpty()
        if (goal.isBlank()) {
            finishService("Missing goal", "missing_goal")
            return
        }

        cancelRequested.set(false)
        startForeground(NOTIFICATION_ID, notification("Tasker agent: ${goal.take(48)}"))
        setStatus("Connecting to Tasker", null)

        loopJob = scope.launch {
            val backend: LocalAgentExecutionBackend = TaskerExecutionBackend
            val brain: LocalAgentBrain = RemoteUiControlLocalAgentBrain()
            var taskState = LocalAgentTaskState(
                goal = goal,
                maxSteps = AutomationPrefs.getMaxSteps(applicationContext),
                startedAtMs = System.currentTimeMillis(),
            )
            var pendingSavedSkill = LocalAgentSkillStore.findExact(applicationContext, goal)

            while (isActive && !cancelRequested.get() && taskState.stepIndex <= taskState.maxSteps) {
                val availability = LocalAgentDeviceState.availability(applicationContext)
                if (availability != LocalAgentDeviceState.Availability.READY) {
                    finishService("Stopped: ${availability.statusText}", availability.errorCode)
                    return@launch
                }

                setStatus("Tasker step ${taskState.stepIndex}/${taskState.maxSteps}", null)

                val observation = withTimeoutOrNull(OBSERVATION_TIMEOUT_MS) {
                    backend.observe(applicationContext)
                }
                if (observation == null) {
                    taskState = taskState.nextStep(
                        previousActionResult = "tasker_observation_failed_or_timed_out",
                        failed = true,
                    )
                    setStatus("Waiting for Tasker observation", "tasker_observation_failed_or_timed_out")
                    delay(RETRY_DELAY_MS)
                    continue
                }

                val replayingSavedSkill = pendingSavedSkill != null
                val output = pendingSavedSkill?.let { skill ->
                    pendingSavedSkill = null
                    LocalAgentBrainOutput(
                        actions = skill.actions,
                        note = "Replaying saved navigation skill through Tasker.",
                    )
                } ?: withTimeoutOrNull(BRAIN_TIMEOUT_MS) {
                    brain.next(applicationContext, taskState, observation)
                } ?: LocalAgentBrainOutput(
                    actions = listOf(LocalAgentAction.Finish("Brain call timed out.")),
                    note = "brain_timeout",
                    isComplete = true,
                )

                if (output.actions.isEmpty()) {
                    taskState = taskState.nextStep(output.note ?: "no_action", failed = true)
                    continue
                }

                var stepFailed = false
                var requiresFreshObservation = false
                val resultParts = mutableListOf<String>()

                for (action in output.actions) {
                    if (cancelRequested.get()) break
                    if (action is LocalAgentAction.Finish) {
                        finishService(action.message ?: output.note ?: "Done", null)
                        return@launch
                    }
                    if (action is LocalAgentAction.Wait) {
                        delay(action.ms)
                        resultParts += "Wait: ok"
                        continue
                    }
                    if (taskState.hasReachedRepeatLimit(action)) {
                        val reason = LocalAgentRuntimePolicy.repeatLimitMessage(action)
                        resultParts += reason
                        stepFailed = true
                        break
                    }

                    val risk = LocalAgentActionManager.classifyRisk(action)
                    val requireConfirm = LocalAgentPrefs.isRequireActionConfirmationEnabled(applicationContext)
                    val needsApproval = requireConfirm && risk == LocalAgentActionManager.Risk.HIGH

                    if (needsApproval) {
                        LocalAgentActionManager.processPlannedAction(
                            applicationContext,
                            action,
                            source = "tasker_agent",
                        )
                        when (awaitApprovalConversation(goal, action)) {
                            ApprovalOutcome.APPROVED -> {
                                resultParts += "${action.javaClass.simpleName}: approved_and_executed_through_tasker"
                                requiresFreshObservation = true
                            }
                            ApprovalOutcome.REJECTED -> {
                                resultParts += "${action.javaClass.simpleName}: rejected_by_user"
                                stepFailed = true
                                break
                            }
                            ApprovalOutcome.EXECUTION_FAILED -> {
                                resultParts += "${action.javaClass.simpleName}: approved_but_tasker_execution_failed"
                                stepFailed = true
                                break
                            }
                            ApprovalOutcome.TIMED_OUT -> {
                                finishService(
                                    "Voice confirmation timed out; the action is still pending",
                                    "approval_timeout_pending",
                                )
                                return@launch
                            }
                        }
                    } else {
                        val execution = withTimeoutOrNull(EXECUTION_TIMEOUT_MS) {
                            backend.execute(applicationContext, action)
                        } ?: LocalAgentBackendExecutionResult(false, "tasker_execution_timeout")
                        resultParts += "${action.javaClass.simpleName}: ${execution.detail}"
                        if (!execution.success) {
                            stepFailed = true
                            val recovery = LocalAgentRecoveryEngine.diagnose(
                                lastFailedAction = action,
                                screenText = observation.screenText,
                                consecutiveFailures = taskState.consecutiveFailures + 1,
                            )
                            if (recovery != null) {
                                val recoveryResult = backend.execute(applicationContext, recovery.action)
                                resultParts += "recovery=${recovery.description}:${recoveryResult.detail}"
                            }
                            break
                        }
                    }

                    taskState = taskState.copy(
                        previousActionSignature = LocalAgentRuntimePolicy.actionSignature(action),
                        identicalActionCount = if (
                            taskState.previousActionSignature == LocalAgentRuntimePolicy.actionSignature(action)
                        ) taskState.identicalActionCount + 1 else 1,
                    )
                }

                if (replayingSavedSkill && stepFailed) {
                    LocalAgentSkillStore.recordReplayFailure(applicationContext, taskState.goal)
                }

                val previousResult = buildString {
                    output.note?.takeIf { it.isNotBlank() }?.let { append(it) }
                    if (resultParts.isNotEmpty()) {
                        if (isNotBlank()) append(" | ")
                        append(resultParts.joinToString("; "))
                    }
                }
                Log.i(TAG, previousResult)
                taskState = taskState.nextStep(
                    previousActionResult = previousResult,
                    failed = stepFailed,
                )

                if (output.isComplete && !stepFailed && !requiresFreshObservation) {
                    finishService(output.note ?: "Done", null)
                    return@launch
                }
                delay(LocalAgentRuntimePolicy.settleDelayMs(output.actions.last()))
            }

            if (!cancelRequested.get()) {
                finishService("Stopped after maximum steps", "max_steps_reached")
            }
        }
    }

    private enum class ApprovalOutcome { APPROVED, REJECTED, EXECUTION_FAILED, TIMED_OUT }

    private suspend fun awaitApprovalConversation(
        goal: String,
        action: LocalAgentAction,
    ): ApprovalOutcome {
        val deferred = CompletableDeferred<Boolean>()
        approvalDeferred = deferred
        LocalAgentPrefs.clearLastApprovalVoiceReply(applicationContext)
        val deadline = System.currentTimeMillis() + APPROVAL_TIMEOUT_MS
        var prompt = LocalAgentApprovalClarifier.initialPrompt(action)
        var silenceCount = 0

        return try {
            while (System.currentTimeMillis() < deadline && !cancelRequested.get()) {
                if (deferred.isCompleted) {
                    return if (deferred.await()) ApprovalOutcome.APPROVED else ApprovalOutcome.REJECTED
                }

                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
                val reply = approvalVoiceSession.askAndListen(
                    prompt = prompt,
                    timeoutMs = minOf(LocalAgentApprovalVoiceSession.DEFAULT_LISTEN_TIMEOUT_MS, remaining),
                )

                if (deferred.isCompleted) {
                    return if (deferred.await()) ApprovalOutcome.APPROVED else ApprovalOutcome.REJECTED
                }

                if (reply.isNullOrBlank()) {
                    silenceCount++
                    prompt = if (silenceCount == 1) {
                        "I didn’t catch an answer. The action is still waiting. Please say yes to continue or no to cancel."
                    } else {
                        "I’m still waiting for your confirmation. Say yes to continue or no to cancel."
                    }
                    continue
                }

                silenceCount = 0
                when (LocalAgentApprovalCoordinator.classifyReply(reply)) {
                    LocalAgentApprovalCoordinator.ReplyKind.APPROVE -> {
                        setStatus("Voice approval received", null)
                        val result = LocalAgentApprovalCoordinator.handleReply(applicationContext, reply)
                        return if (result.executed) ApprovalOutcome.APPROVED else ApprovalOutcome.EXECUTION_FAILED
                    }
                    LocalAgentApprovalCoordinator.ReplyKind.REJECT -> {
                        setStatus("Voice rejection received", null)
                        LocalAgentApprovalCoordinator.handleReply(applicationContext, reply)
                        return ApprovalOutcome.REJECTED
                    }
                    LocalAgentApprovalCoordinator.ReplyKind.UNKNOWN -> {
                        setStatus("Asking for clarification", null)
                        prompt = withTimeoutOrNull(CLARIFICATION_TIMEOUT_MS) {
                            LocalAgentApprovalClarifier.clarificationPrompt(
                                context = applicationContext,
                                originalGoal = goal,
                                action = action,
                                ambiguousReply = reply,
                            )
                        } ?: LocalAgentApprovalClarifier.fallbackClarification(action, reply)
                    }
                }
            }
            ApprovalOutcome.TIMED_OUT
        } finally {
            if (approvalDeferred === deferred) approvalDeferred = null
        }
    }

    private fun stopLoop(reason: String) {
        cancelRequested.set(true)
        loopJob?.cancel()
        loopJob = null
        approvalDeferred?.takeIf { !it.isCompleted }?.complete(false)
        approvalDeferred = null
        setStatus("Stopped", reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finishService(status: String, error: String?) {
        setStatus(status, error)
        cancelRequested.set(true)
        loopJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun setStatus(status: String, error: String?) {
        LocalAgentPrefs.setStatus(applicationContext, status)
        if (error == null) LocalAgentPrefs.clearLastError(applicationContext)
        else LocalAgentPrefs.setLastError(applicationContext, error)
        emitStatus()
    }

    private fun emitStatus() {
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
            Intent(LocalAgentIntents.ACTION_STATUS_CHANGED).apply {
                putExtra(LocalAgentIntents.EXTRA_STATUS, LocalAgentPrefs.getStatus(applicationContext))
                putExtra(LocalAgentIntents.EXTRA_LAST_ERROR, LocalAgentPrefs.getLastError(applicationContext))
            }
        )
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Local Agent", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(content: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("CyanBridge Local Agent")
        .setContentText(content)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()

    companion object {
        private const val TAG = "TaskerLocalAgent"
        private const val CHANNEL_ID = "local_agent_tasker"
        private const val NOTIFICATION_ID = 55244
        private const val OBSERVATION_TIMEOUT_MS = 10_000L
        private const val EXECUTION_TIMEOUT_MS = 15_000L
        private const val BRAIN_TIMEOUT_MS = 60_000L
        private const val APPROVAL_TIMEOUT_MS = 10 * 60_000L
        private const val CLARIFICATION_TIMEOUT_MS = 60_000L
        private const val RETRY_DELAY_MS = 1_000L
    }
}
