package com.fersaiyan.cyanbridge.localagent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Start/stop/approval state machine behind [LocalAgentService]'s commands.
 *
 * Owns no Android objects itself: the service supplies the ports, its coroutine scope and a
 * factory for the per-task [LocalAgentTaskLoop.ActionBatchRunner].
 */
class LocalAgentRuntime(
    private val session: LocalAgentSession,
    private val device: LocalAgentDevice,
    private val settings: LocalAgentSettings,
    private val status: LocalAgentStatusReporter,
    private val speech: LocalAgentSpeechOutput,
    private val host: LocalAgentServiceHost,
    private val scope: CoroutineScope,
    private val taskLoop: LocalAgentTaskLoop,
    private val screenReader: LocalAgentScreenReader,
    private val stepEngineFactory: (LocalAgentStepEngine.LocalAgentActionExecutor) -> LocalAgentTaskLoop.ActionBatchRunner,
) {

    fun startLoop(goalOverride: String?) {
        if (!settings.isAutomationEnabled()) {
            status.setStatus("Disabled")
            status.setLastError("local_agent_automation_disabled")
            status.emitStatus()
            host.stopSelf()
            return
        }

        if (!device.hasNotificationPermission()) {
            status.setStatus("Waiting for notification permission")
            status.setLastError("missing_post_notifications")
            status.emitStatus()
            host.stopSelf()
            return
        }

        device.availability()
            .takeIf { it != LocalAgentDeviceState.Availability.READY }
            ?.let { availability ->
                status.setStatus("Unavailable: ${availability.statusText}")
                status.setLastError(availability.errorCode)
                status.emitStatus()
                host.stopSelf()
                return
            }

        if (session.running.getAndSet(true)) {
            Log.i(TAG, "startLoop: already running")
            status.emitStatus()
            return
        }

        session.cancelRequested.set(false)
        val goal = goalOverride?.trim().orEmpty()
        if (goal.isBlank()) {
            Log.i(TAG, "startLoop: missing goal")
            status.setStatus("Missing goal")
            status.setLastError("missing_goal")
            status.emitStatus()
            if (session.running.getAndSet(false)) host.stopSelf()
            return
        }
        val maxSteps = settings.maxSteps()

        session.lastError.set(null)
        status.setStatus("Running")
        status.clearLastError()
        status.emitStatus()

        host.startForeground("Running: ${goal.take(48)}")

        val engine = stepEngineFactory(
            LocalAgentServiceActionExecutor(
                readScreenAloud = screenReader::readCurrentScreenAloud,
                performAction = device::perform,
            )
        )

        session.loopJob = scope.launch {
            taskLoop.run(goal = goal, maxSteps = maxSteps, engine = engine)
        }
    }

    /**
     * Called when the user approves a pending action from [PendingActionsActivity].
     * Signals the paused loop to resume.
     */
    fun resumeAfterApproval(rejected: Boolean = false) {
        if (device.availability() != LocalAgentDeviceState.Availability.READY) {
            session.approvalDeferred?.complete(false)
            stopForUnavailableDevice()
            return
        }
        val deferred = session.approvalDeferred
        if (deferred != null && !deferred.isCompleted) {
            Log.i(TAG, "resumeAfterApproval: signalling deferred rejected=$rejected")
            deferred.complete(!rejected)
        } else {
            Log.w(TAG, "resumeAfterApproval: no deferred waiting")
            status.emitStatus()
        }
    }

    fun stopLoop(
        reason: String,
        status: String = "Stopped",
        error: String? = null,
    ) {
        session.cancelRequested.set(true)
        if (!session.running.getAndSet(false)) {
            if (error != null) {
                session.lastError.set(error)
                this.status.setStatus(status)
                this.status.setLastError(error)
                this.status.emitStatus()
            }
            host.stopSelf()
            return
        }
        Log.i(TAG, "Stopping loop: reason=$reason")

        session.loopJob?.cancel()
        session.loopJob = null

        this.status.setStatus(status)
        if (error != null) {
            session.lastError.set(error)
            this.status.setLastError(error)
        }
        // Keep the previous error for ordinary user stops.
        this.status.emitStatus()

        host.updateNotification(status)

        host.stopForegroundAndSelf()
    }

    fun stopForUnavailableDevice() {
        val availability = device.availability()
        if (availability == LocalAgentDeviceState.Availability.READY) return
        if (!session.running.get() && !session.screenReadInProgress.get()) return

        session.pausedTaskState = null
        session.rejectPendingApproval()
        session.screenReadInProgress.set(false)
        speech.stop()
        stopLoop(
            reason = availability.errorCode,
            status = "Stopped: ${availability.statusText}",
            error = availability.errorCode,
        )
    }

    /** True while a task loop or a screen read keeps the service busy. */
    fun isBusy(): Boolean = session.running.get() || session.screenReadInProgress.get()

    private companion object {
        private const val TAG = LOCAL_AGENT_SERVICE_LOG_TAG
    }
}
