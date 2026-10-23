package com.fersaiyan.cyanbridge.localagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fersaiyan.cyanbridge.localagent.AudioSessionCoordinator
import com.fersaiyan.cyanbridge.localagent.LocalAgentLanguage
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.ui.hasNotificationPermission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicReference

internal const val LOCAL_AGENT_SERVICE_LOG_TAG = "LocalAgentService"

/**
 * Foreground service that runs a simple observe -> plan -> act loop.
 *
 * - observe: best-effort screen text via AccessibilityService
 * - plan: via [LocalAgentBrain] (stubbed by default)
 * - act: execute JSON actions via Accessibility
 *
 * When an action requires user approval, the loop pauses and waits for
 * [LocalAgentIntents.ACTION_RESUME_AFTER_APPROVAL] to continue.
 */
class LocalAgentService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private val cancelRequested = AtomicBoolean(false)

    private val speech by lazy {
        LocalAgentSpeechOutput(
            isDeviceReady = { LocalAgentDeviceState.isReady(applicationContext) },
            currentLocale = { LocalAgentLanguage.currentLocale(applicationContext) },
            engineFactory = { onInit -> AndroidLocalAgentTtsEngine(applicationContext, onInit) },
        )
    }
    private val screenReadInProgress = AtomicBoolean(false)
    private var runtimeInitialized = false
    private var deviceStateReceiverRegistered = false

    /** Signalled when the user approves a pending action; the loop awaits this. */
    private var approvalDeferred: CompletableDeferred<Boolean>? = null

    /** Stored task state so the loop can resume after approval. */
    private var pausedTaskState: LocalAgentTaskState? = null

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val deviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                stopForUnavailableDevice()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // New contract (UI -> Service)
            LocalAgentIntents.ACTION_START -> {
                ensureRuntimeInitialized()
                startLoop(intent.getStringExtra(LocalAgentIntents.EXTRA_GOAL))
            }
            LocalAgentIntents.ACTION_STOP -> stopLoop(reason = "user")
            LocalAgentIntents.ACTION_DEMO -> {
                ensureRuntimeInitialized()
                runDemo()
            }
            LocalAgentIntents.ACTION_READ_SCREEN_ALOUD -> {
                ensureRuntimeInitialized()
                readCurrentScreenAloud()
            }
            LocalAgentIntents.ACTION_GET_STATUS -> {
                emitStatus()
                if (!runningState.get() && !screenReadInProgress.get()) stopSelfResult(startId)
            }
            LocalAgentIntents.ACTION_RESUME_AFTER_APPROVAL -> resumeAfterApproval(
                rejected = intent.getBooleanExtra(LocalAgentIntents.EXTRA_REJECTED, false)
            )

            // Back-compat (older internal actions)
            ACTION_START_LEGACY -> stopLoop(reason = "legacy_goalless_start")
            ACTION_STOP_LEGACY -> stopLoop(reason = "user")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopLoop(reason = "service_destroy")
        if (deviceStateReceiverRegistered) {
            runCatching { unregisterReceiver(deviceStateReceiver) }
            deviceStateReceiverRegistered = false
        }
        approvalDeferred?.complete(false)
        approvalDeferred = null
        serviceScope.cancel()
        speech.shutdown()
        super.onDestroy()
    }

    private fun ensureRuntimeInitialized() {
        if (runtimeInitialized) return
        runtimeInitialized = true
        speech.initialize()
        LocalAgentMemoryStore.ensureSeedFiles(applicationContext)
        ContextCompat.registerReceiver(
            this,
            deviceStateReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        deviceStateReceiverRegistered = true
    }

    private fun startLoop(goalOverride: String?) {
        if (!AutomationPrefs.isLocalAgentAutomationEnabled(applicationContext)) {
            LocalAgentPrefs.setStatus(applicationContext, "Disabled")
            LocalAgentPrefs.setLastError(applicationContext, "local_agent_automation_disabled")
            emitStatus()
            stopSelf()
            return
        }

        if (!hasNotificationPermission(this)) {
            LocalAgentPrefs.setStatus(applicationContext, "Waiting for notification permission")
            LocalAgentPrefs.setLastError(applicationContext, "missing_post_notifications")
            emitStatus()
            stopSelf()
            return
        }

        LocalAgentDeviceState.availability(applicationContext)
            .takeIf { it != LocalAgentDeviceState.Availability.READY }
            ?.let { availability ->
                LocalAgentPrefs.setStatus(applicationContext, "Unavailable: ${availability.statusText}")
                LocalAgentPrefs.setLastError(applicationContext, availability.errorCode)
                emitStatus()
                stopSelf()
                return
            }

        if (runningState.getAndSet(true)) {
            Log.i(TAG, "startLoop: already running")
            emitStatus()
            return
        }

        cancelRequested.set(false)
        val goal = goalOverride?.trim().orEmpty()
        if (goal.isBlank()) {
            Log.i(TAG, "startLoop: missing goal")
            LocalAgentPrefs.setStatus(applicationContext, "Missing goal")
            LocalAgentPrefs.setLastError(applicationContext, "missing_goal")
            emitStatus()
            if (runningState.getAndSet(false)) stopSelf()
            return
        }
        val maxSteps = AutomationPrefs.getMaxSteps(applicationContext)

        lastError.set(null)
        LocalAgentPrefs.setStatus(applicationContext, "Running")
        LocalAgentPrefs.clearLastError(applicationContext)
        emitStatus()

        startForeground(NOTIFICATION_ID, buildNotification(content = "Running: ${goal.take(48)}"))

        val engine = LocalAgentStepEngine(
            context = applicationContext,
            executor = object : LocalAgentStepEngine.LocalAgentActionExecutor {
                override suspend fun execute(action: LocalAgentAction): Boolean {
                    return if (action == LocalAgentAction.ReadScreenAloud) {
                        readCurrentScreenAloud()
                    } else {
                        LocalAgentAccessibilityBridge.performWithOptionalShizukuFallback(
                            applicationContext,
                            action,
                        )
                    }
                }

                override fun ensureNotCancelled() {
                    // cooperative cancellation handled by coroutine.
                }
            }
        )

        loopJob = serviceScope.launch {
            Log.i(TAG, "Loop started")

            val brain = brainRef.get()
            var warnedA11yMissing = false
            // Resume from paused state if available, otherwise start fresh.
            var taskState = pausedTaskState ?: LocalAgentTaskState(
                goal = goal,
                maxSteps = maxSteps,
                startedAtMs = System.currentTimeMillis(),
            )
            pausedTaskState = null
            var pendingSavedSkill = LocalAgentSkillStore.findExact(applicationContext, taskState.goal)
            var usedSavedSkill = false
            val executedActions = mutableListOf<LocalAgentAction>()

            while (isActive && !cancelRequested.get() && taskState.stepIndex <= taskState.maxSteps) {
                var settleAction: LocalAgentAction? = null
                try {
                    LocalAgentDeviceState.availability(applicationContext)
                        .takeIf { it != LocalAgentDeviceState.Availability.READY }
                        ?.let { availability ->
                            stopTaskForUnavailableDevice(
                                taskState = taskState,
                                usedSavedSkill = usedSavedSkill,
                                executedActions = executedActions,
                                availability = availability,
                            )
                            return@launch
                        }

                    if (!LocalAgentAccessibilityBridge.isConnected()) {
                        val err = "accessibility_not_connected"
                        lastError.set(err)
                        LocalAgentPrefs.setStatus(applicationContext, "Waiting for accessibility")
                        LocalAgentPrefs.setLastError(applicationContext, err)
                        emitStatus()

                        if (!warnedA11yMissing) {
                            warnedA11yMissing = true
                            Log.w(TAG, "Accessibility service not connected; enable it in Android Accessibility settings")
                        }
                        delay(1_000)
                        continue
                    }

                    LocalAgentPrefs.setStatus(
                        applicationContext,
                        "Running step ${taskState.stepIndex}/${taskState.maxSteps}"
                    )
                    emitStatus()

                    val obs = LocalAgentObserver.observe()
                    val replayingSavedSkill = pendingSavedSkill != null
                    val out = pendingSavedSkill?.let { skill ->
                        pendingSavedSkill = null
                        usedSavedSkill = true
                        LocalAgentBrainOutput(
                            actions = skill.actions,
                            note = "Replaying a saved low-risk navigation skill.",
                        )
                    } ?: withTimeoutOrNull(BRAIN_CALL_TIMEOUT_MS) {
                        brain.next(applicationContext, taskState, obs)
                    } ?: LocalAgentBrainOutput(
                        actions = listOf(LocalAgentAction.Finish("Brain call timed out.")),
                        note = "Brain call timed out after ${BRAIN_CALL_TIMEOUT_MS / 1000}s.",
                        isComplete = true,
                    )

                    LocalAgentDeviceState.availability(applicationContext)
                        .takeIf { it != LocalAgentDeviceState.Availability.READY }
                        ?.let { availability ->
                            stopTaskForUnavailableDevice(
                                taskState = taskState,
                                usedSavedSkill = usedSavedSkill,
                                executedActions = executedActions,
                                availability = availability,
                            )
                            return@launch
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
                                ?: LocalAgentDeviceState.availability(applicationContext),
                        )
                        return@launch
                    }
                    val actionFailed = summary.actionResults.any { it.endsWith("failed") }
                    if (!summary.haltedForApproval && !actionFailed) {
                        executedActions += actions.filterNot { it is LocalAgentAction.Finish }
                    }
                    if (replayingSavedSkill && actionFailed) {
                        LocalAgentSkillStore.recordReplayFailure(applicationContext, taskState.goal)
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
                            LocalAgentPrefs.setStatus(
                                applicationContext,
                                "Recovering: ${recovery.description.take(48)}"
                            )
                            emitStatus()
                            val recoveryOk = LocalAgentAccessibilityBridge.performWithOptionalShizukuFallback(
                                applicationContext, recovery.action
                            )
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
                            return@launch
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
                        return@launch
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
                    lastError.set(msg)
                    LocalAgentPrefs.setStatus(applicationContext, "Error")
                    LocalAgentPrefs.setLastError(applicationContext, msg)
                    emitStatus()
                    Log.e(TAG, "Loop error: $msg", e)
                    // Back off to avoid crash loops.
                    taskState = taskState.nextStep(previousActionResult = msg, failed = true)
                    delay(1_000)
                }

                // Let the destination app render before observing its next state.
                delay(LocalAgentRuntimePolicy.settleDelayMs(settleAction))
            }

            if (isActive) {
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
    }

    /**
     * Called when the user approves a pending action from [PendingActionsActivity].
     * Signals the paused loop to resume.
     */
    private fun resumeAfterApproval(rejected: Boolean = false) {
        if (!LocalAgentDeviceState.isReady(applicationContext)) {
            approvalDeferred?.complete(false)
            stopForUnavailableDevice()
            return
        }
        val deferred = approvalDeferred
        if (deferred != null && !deferred.isCompleted) {
            Log.i(TAG, "resumeAfterApproval: signalling deferred rejected=$rejected")
            deferred.complete(!rejected)
        } else {
            Log.w(TAG, "resumeAfterApproval: no deferred waiting")
            emitStatus()
        }
    }

    private fun stopLoop(
        reason: String,
        status: String = "Stopped",
        error: String? = null,
    ) {
        cancelRequested.set(true)
        if (!runningState.getAndSet(false)) {
            if (error != null) {
                lastError.set(error)
                LocalAgentPrefs.setStatus(applicationContext, status)
                LocalAgentPrefs.setLastError(applicationContext, error)
                emitStatus()
            }
            stopSelf()
            return
        }
        Log.i(TAG, "Stopping loop: reason=$reason")

        loopJob?.cancel()
        loopJob = null

        LocalAgentPrefs.setStatus(applicationContext, status)
        if (error != null) {
            lastError.set(error)
            LocalAgentPrefs.setLastError(applicationContext, error)
        }
        // Keep the previous error for ordinary user stops.
        emitStatus()

        runCatching {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(content = status))
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun recordTaskOutcome(
        taskState: LocalAgentTaskState,
        status: String,
        usedSavedSkill: Boolean,
        executedActions: List<LocalAgentAction>,
    ) {
        LocalAgentTaskHistory.record(
            applicationContext,
            LocalAgentTaskHistory.Entry(
                goal = taskState.goal,
                status = status,
                stepCount = taskState.stepIndex,
                usedSavedSkill = usedSavedSkill,
            ),
        )
        if (status == "Completed") {
            LocalAgentSkillStore.recordSuccessful(
                context = applicationContext,
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
        pausedTaskState = null
        approvalDeferred?.complete(false)
        approvalDeferred = null
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
        LocalAgentDeviceState.availability(applicationContext)
            .takeIf { it != LocalAgentDeviceState.Availability.READY }
            ?.let { return false }

        val summary = describeActionForApproval(action)
        val approvalCue = LocalAgentLanguage.approvalListeningCue(applicationContext)

        pausedTaskState = taskStateFromCurrentGoal()
        val deferred = CompletableDeferred<Boolean>()
        approvalDeferred = deferred

        runCatching {
            notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification(content = "Waiting for your approval"),
            )
        }

        speech.speakAndWaitBestEffort("$summary $approvalCue", maxLength = 240)

        var approved: Boolean? = null
        var unavailableDevice: LocalAgentDeviceState.Availability? = null
        while (currentCoroutineContext().isActive && approved == null) {
            val availability = LocalAgentDeviceState.availability(applicationContext)
            if (availability != LocalAgentDeviceState.Availability.READY) {
                unavailableDevice = availability
                break
            }
            approved = withTimeoutOrNull(500L) { deferred.await() }
        }
        approvalDeferred = null

        unavailableDevice?.let {
            deferred.complete(false)
            return false
        }

        return approved == true
    }

    private fun taskStateFromCurrentGoal(): LocalAgentTaskState? {
        val existing = pausedTaskState
        if (existing != null) return existing
        return loopJob?.let {
            LocalAgentTaskState(
                goal = "Current agent task",
                maxSteps = AutomationPrefs.getMaxSteps(applicationContext),
                startedAtMs = System.currentTimeMillis(),
            )
        }
    }

    private fun describeActionForApproval(action: LocalAgentAction?): String {
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

    private fun stopForUnavailableDevice() {
        val availability = LocalAgentDeviceState.availability(applicationContext)
        if (availability == LocalAgentDeviceState.Availability.READY) return
        if (!runningState.get() && !screenReadInProgress.get()) return

        pausedTaskState = null
        approvalDeferred?.complete(false)
        approvalDeferred = null
        screenReadInProgress.set(false)
        speech.stop()
        stopLoop(
            reason = availability.errorCode,
            status = "Stopped: ${availability.statusText}",
            error = availability.errorCode,
        )
    }

    private suspend fun completeLoop(
        taskId: Long,
        status: String,
        notification: String,
        userMessage: String,
        error: String? = null,
        speakResult: Boolean = true,
    ) {
        runningState.set(false)
        loopJob = null
        lastError.set(error)
        LocalAgentPrefs.setStatus(applicationContext, status)
        if (error.isNullOrBlank()) {
            LocalAgentPrefs.clearLastError(applicationContext)
        } else {
            LocalAgentPrefs.setLastError(applicationContext, error)
        }
        emitStatus(
            taskId = taskId,
            isTerminal = true,
            userMessage = userMessage,
        )
        if (speakResult) speech.speakAndWaitBestEffort(userMessage)
        runCatching {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(content = notification))
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun completionSpeech(actions: List<LocalAgentAction>): String {
        if (screenReadInProgress.get() || actions.any { it == LocalAgentAction.ReadScreenAloud }) return ""
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
        return if (failed) "I couldn't finish that task." else LocalAgentLanguage.completionSpeech(applicationContext)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Local agent",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when the local agent loop is running"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun emitStatus(
        taskId: Long? = null,
        isTerminal: Boolean = false,
        userMessage: String? = null,
    ) {
        val status = LocalAgentPrefs.getStatus(applicationContext)
        val err = LocalAgentPrefs.getLastError(applicationContext)

        val intent = Intent(LocalAgentIntents.ACTION_STATUS_CHANGED)
            .putExtra(LocalAgentIntents.EXTRA_STATUS, status)
            .putExtra(LocalAgentIntents.EXTRA_LAST_ERROR, err)
            .putExtra(LocalAgentIntents.EXTRA_IS_TERMINAL, isTerminal)
        taskId?.let { intent.putExtra(LocalAgentIntents.EXTRA_TASK_ID, it) }
        userMessage?.trim()?.takeIf { it.isNotBlank() }?.let {
            intent.putExtra(LocalAgentIntents.EXTRA_USER_MESSAGE, it)
        }

        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

    private fun runDemo() {
        // Demo behavior:
        // - wait 5 seconds to let the user change screens
        // - snapshot screen text
        // - store it in Local Agent memory
        // - read it back via TTS (Bluetooth headset/glasses will receive audio if routed)

        LocalAgentDeviceState.availability(applicationContext)
            .takeIf { it != LocalAgentDeviceState.Availability.READY }
            ?.let { availability ->
                LocalAgentPrefs.setStatus(applicationContext, "Demo: unavailable")
                LocalAgentPrefs.setLastError(applicationContext, availability.errorCode)
                emitStatus()
                stopSelf()
                return
            }

        if (!LocalAgentAccessibilityBridge.isConnected()) {
            val err = "accessibility_not_connected"
            LocalAgentPrefs.setStatus(applicationContext, "Demo: failed")
            LocalAgentPrefs.setLastError(applicationContext, err)
            emitStatus()
            stopSelf()
            return
        }

        LocalAgentPrefs.setStatus(applicationContext, "Demo: reading in 5s…")
        LocalAgentPrefs.clearLastError(applicationContext)
        emitStatus()

        serviceScope.launch {
            delay(5_000)

            LocalAgentDeviceState.availability(applicationContext)
                .takeIf { it != LocalAgentDeviceState.Availability.READY }
                ?.let { availability ->
                    LocalAgentPrefs.setStatus(applicationContext, "Demo: stopped")
                    LocalAgentPrefs.setLastError(applicationContext, availability.errorCode)
                    emitStatus()
                    stopSelf()
                    return@launch
                }

            val text = LocalAgentAccessibilityBridge.snapshotScreenText() ?: ""
            if (text.isBlank()) {
                LocalAgentPrefs.setStatus(applicationContext, "Demo: no text found")
                LocalAgentPrefs.setLastError(applicationContext, "empty_screen_text")
                emitStatus()
                speech.speakAndWaitBestEffort("I couldn't read any text on the screen.")
                stopSelf()
                return@launch
            }

            // Store snapshot (package name isn't directly available here; mark as unknown for demo).
            LocalAgentMemoryStore.appendScreenCapture(
                context = applicationContext,
                packageName = "(demo)",
                text = text,
            )

            val toSpeak = text
                .lineSequence()
                .filter { it.isNotBlank() }
                .take(14)
                .joinToString(". ")
                .take(550)

            speech.speakAndWaitBestEffort("Reading your screen. $toSpeak", maxLength = 600)

            LocalAgentPrefs.setStatus(applicationContext, "Demo: spoke (${toSpeak.length} chars)")
            LocalAgentPrefs.clearLastError(applicationContext)
            emitStatus()
            stopSelf()
        }
    }

    /**
     * Reads only currently visible accessibility text. This is deliberately an explicit,
     * approval-gated action rather than a background notification or message reader.
     */
    private fun readCurrentScreenAloud(): Boolean {
        val standaloneRequest = !runningState.get()
        LocalAgentDeviceState.availability(applicationContext)
            .takeIf { it != LocalAgentDeviceState.Availability.READY }
            ?.let { availability ->
                LocalAgentPrefs.setStatus(applicationContext, "Screen reading unavailable: ${availability.statusText}")
                LocalAgentPrefs.setLastError(applicationContext, availability.errorCode)
                emitStatus()
                if (!runningState.get()) stopSelf()
                return false
            }
        if (!hasNotificationPermission(this)) {
            LocalAgentPrefs.setLastError(applicationContext, "missing_post_notifications")
            emitStatus()
            if (standaloneRequest) stopSelf()
            return false
        }
        if (!LocalAgentAccessibilityBridge.isConnected()) {
            LocalAgentPrefs.setLastError(applicationContext, "accessibility_not_connected")
            emitStatus()
            if (standaloneRequest) stopSelf()
            return false
        }
        val activePackage = LocalAgentAccessibilityBridge.activeWindowPackageName()
        if (activePackage.isNullOrBlank()) {
            LocalAgentPrefs.setStatus(applicationContext, "Unable to verify current app")
            LocalAgentPrefs.setLastError(applicationContext, "screen_package_unknown")
            emitStatus()
            if (standaloneRequest) stopSelf()
            return false
        }
        LocalAgentSafetyPolicy.blockedReason(applicationContext, activePackage)?.let {
            LocalAgentPrefs.setStatus(applicationContext, "Screen reading blocked by privacy settings")
            LocalAgentPrefs.setLastError(applicationContext, "privacy_blacklisted_app")
            emitStatus()
            if (standaloneRequest) stopSelf()
            return false
        }

        if (standaloneRequest) {
            LocalAgentPrefs.setStatus(applicationContext, "Reading current screen")
            LocalAgentPrefs.clearLastError(applicationContext)
            startForeground(NOTIFICATION_ID, buildNotification(content = "Reading current screen"))
            emitStatus()
        }

        val visibleText = LocalAgentAccessibilityBridge.snapshotScreenText()
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.take(20)
            ?.joinToString(". ")
            ?.take(MAX_SCREEN_READ_ALOUD_CHARS)
            .orEmpty()
        if (visibleText.isBlank()) {
            LocalAgentPrefs.setLastError(applicationContext, "empty_screen_text")
            emitStatus()
            screenReadInProgress.set(true)
            serviceScope.launch {
                try {
                    speech.speakBestEffort("I couldn't read any text on the screen.")
                } finally {
                    screenReadInProgress.set(false)
                    if (standaloneRequest) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
            return false
        }

        screenReadInProgress.set(true)
        serviceScope.launch {
            try {
                LocalAgentDeviceState.availability(applicationContext)
                    .takeIf { it != LocalAgentDeviceState.Availability.READY }
                    ?.let { availability ->
                        LocalAgentPrefs.setStatus(
                            applicationContext,
                            "Screen reading stopped: ${availability.statusText}",
                        )
                        LocalAgentPrefs.setLastError(applicationContext, availability.errorCode)
                        emitStatus()
                        return@launch
                    }
                speech.speakAndWaitBestEffort("Reading the visible screen. $visibleText")
            } finally {
                screenReadInProgress.set(false)
                if (standaloneRequest) {
                    LocalAgentPrefs.setStatus(applicationContext, "Read current screen")
                    LocalAgentPrefs.clearLastError(applicationContext)
                    emitStatus()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return true
    }

    private fun buildNotification(content: String): Notification {
        val target = if (content == "Waiting for your approval") {
            com.fersaiyan.cyanbridge.ui.localagent.PendingActionsActivity::class.java
        } else {
            MainActivity::class.java
        }
        val openIntent = Intent(this, target)
        val openPi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, LocalAgentService::class.java).apply { action = LocalAgentIntents.ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Local agent")
            .setContentText(content)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(
                NotificationCompat.Action.Builder(
                    0,
                    "Stop",
                    stopPi
                ).build()
            )
            .build()
    }

    companion object {
        private const val TAG = LOCAL_AGENT_SERVICE_LOG_TAG

        // Legacy action names (kept to avoid breaking any old entrypoints).
        private const val ACTION_START_LEGACY = "com.fersaiyan.cyanbridge.action.LOCAL_AGENT_START"
        private const val ACTION_STOP_LEGACY = "com.fersaiyan.cyanbridge.action.LOCAL_AGENT_STOP"

        private const val NOTIFICATION_CHANNEL_ID = "local_agent"
        private const val NOTIFICATION_ID = 937
        private const val MAX_SCREEN_READ_ALOUD_CHARS = 750
        private const val BRAIN_CALL_TIMEOUT_MS = 60_000L

        private val runningState = AtomicBoolean(false)
        private val lastError = AtomicReference<String?>(null)

        private val brainRef: AtomicReference<LocalAgentBrain> = AtomicReference(RemoteUiControlLocalAgentBrain())

        fun setBrain(brain: LocalAgentBrain) {
            brainRef.set(brain)
        }

        fun getLastError(): String? = lastError.get()

        fun isRunning(): Boolean = runningState.get()

        fun start(context: Context) {
            context.startService(
                Intent(context, LocalAgentService::class.java).apply { action = LocalAgentIntents.ACTION_GET_STATUS }
            )
        }

        fun start(context: Context, goal: String?) {
            val trimmedGoal = goal?.trim().orEmpty()
            if (trimmedGoal.isBlank()) {
                context.startService(
                    Intent(context, LocalAgentService::class.java).apply { action = LocalAgentIntents.ACTION_GET_STATUS }
                )
                return
            }
            val intent = Intent(context, LocalAgentService::class.java).apply { action = LocalAgentIntents.ACTION_START }
            intent.putExtra(LocalAgentIntents.EXTRA_GOAL, trimmedGoal)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocalAgentService::class.java).apply { action = LocalAgentIntents.ACTION_STOP }
            context.startService(intent)
        }

        fun readScreenAloud(context: Context) {
            val intent = Intent(context, LocalAgentService::class.java).apply {
                action = LocalAgentIntents.ACTION_READ_SCREEN_ALOUD
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
