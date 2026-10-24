package com.fersaiyan.cyanbridge.refactorparity.localagentservice

import com.fersaiyan.cyanbridge.localagent.*
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Test doubles for the ports used by [LocalAgentService]'s runtime classes. */
internal class FakeLocalAgentDevice : LocalAgentDevice {
    var availability = LocalAgentDeviceState.Availability.READY
    var notificationPermission = true
    var accessibilityConnected = true
    var screenText: String? = "Inbox"
    var activePackage: String? = "com.example.app"
    val blockedPackages = mutableSetOf<String>()
    var performResult = true
    val performed = mutableListOf<LocalAgentAction>()
    var availabilityChecks = 0
    var accessibilityChecks = 0

    /** Optional override consulted on every availability check (after the counter increments). */
    var availabilityFn: ((check: Int) -> LocalAgentDeviceState.Availability)? = null
    var accessibilityFn: ((check: Int) -> Boolean)? = null

    override fun availability(): LocalAgentDeviceState.Availability {
        availabilityChecks++
        return availabilityFn?.invoke(availabilityChecks) ?: availability
    }

    override fun hasNotificationPermission(): Boolean = notificationPermission

    override fun isAccessibilityConnected(): Boolean {
        accessibilityChecks++
        return accessibilityFn?.invoke(accessibilityChecks) ?: accessibilityConnected
    }

    override fun observe(): LocalAgentObservation = LocalAgentObservation(
        createdAtMs = 0L,
        packageName = activePackage,
        screenText = screenText,
        screenSnapshot = null,
    )

    override fun snapshotScreenText(): String? = screenText

    override fun activeWindowPackageName(): String? = activePackage

    override fun privacyBlockedReason(packageName: String): String? =
        if (packageName in blockedPackages) "blocked" else null

    override suspend fun perform(action: LocalAgentAction): Boolean {
        performed += action
        return performResult
    }
}

internal class FakeLocalAgentSettings : LocalAgentSettings {
    var automationEnabled = true
    var maxSteps = 5

    override fun isAutomationEnabled(): Boolean = automationEnabled
    override fun maxSteps(): Int = maxSteps
    override fun approvalListeningCue(): String = "Answer now."
    override fun completionSpeech(): String = "Task finished."
}

internal class FakeLocalAgentTaskMemory : LocalAgentTaskMemory {
    val savedSkills = mutableMapOf<String, LocalAgentSkillStore.Skill>()
    val history = mutableListOf<LocalAgentTaskHistory.Entry>()
    val successfulSkills = mutableListOf<Pair<String, List<LocalAgentAction>>>()
    val replayFailures = mutableListOf<String>()
    val screenCaptures = mutableListOf<Pair<String, String>>()

    override fun findSavedSkill(goal: String): LocalAgentSkillStore.Skill? = savedSkills[goal]
    override fun recordReplayFailure(goal: String) {
        replayFailures += goal
    }
    override fun recordTaskHistory(entry: LocalAgentTaskHistory.Entry) {
        history += entry
    }
    override fun recordSuccessfulSkill(goal: String, actions: List<LocalAgentAction>) {
        successfulSkills += goal to actions.toList()
    }
    override fun appendScreenCapture(packageName: String, text: String) {
        screenCaptures += packageName to text
    }
}

internal class FakeLocalAgentStatusReporter : LocalAgentStatusReporter {
    var currentStatus: String = "Idle"
    var currentLastError: String = ""
    val events = mutableListOf<String>()

    data class Emitted(
        val status: String,
        val lastError: String,
        val taskId: Long?,
        val isTerminal: Boolean,
        val userMessage: String?,
    )

    val emitted = mutableListOf<Emitted>()

    override fun setStatus(status: String) {
        currentStatus = status
        events += "status:$status"
    }

    override fun setLastError(error: String) {
        currentLastError = error
        events += "error:$error"
    }

    override fun clearLastError() {
        currentLastError = ""
        events += "clearError"
    }

    override fun emitStatus(taskId: Long?, isTerminal: Boolean, userMessage: String?) {
        emitted += Emitted(currentStatus, currentLastError, taskId, isTerminal, userMessage)
        events += "emit"
    }
}

internal class FakeLocalAgentServiceHost : LocalAgentServiceHost {
    val events = mutableListOf<String>()

    override fun startForeground(content: String) {
        events += "startForeground:$content"
    }

    override fun updateNotification(content: String) {
        events += "notify:$content"
    }

    override fun stopForegroundAndSelf() {
        events += "stopForegroundAndSelf"
    }

    override fun stopSelf() {
        events += "stopSelf"
    }
}

internal class FakeLocalAgentTtsEngine : LocalAgentTtsEngine {
    var onInit: ((Boolean) -> Unit)? = null
    var appliedLanguage: Locale? = null
    val spoken = mutableListOf<Pair<String, String>>()
    var speakResult = true
    var finishImmediately = true
    var stopCount = 0
    var shutdownCount = 0
    private var listener: ((String?) -> Unit)? = null

    fun finish(utteranceId: String?) {
        listener?.invoke(utteranceId)
    }

    override fun setLanguage(locale: Locale) {
        appliedLanguage = locale
    }

    override fun speak(text: String, utteranceId: String): Boolean {
        spoken += text to utteranceId
        if (speakResult && finishImmediately) listener?.invoke(utteranceId)
        return speakResult
    }

    override fun setOnUtteranceFinished(onFinished: (utteranceId: String?) -> Unit) {
        listener = onFinished
    }

    override fun stop() {
        stopCount++
    }

    override fun shutdown() {
        shutdownCount++
    }
}

/** Wires the fakes together the way [LocalAgentService] wires the real ports. */
internal class LocalAgentServiceFixture {
    val running = AtomicBoolean(false)
    val lastError = AtomicReference<String?>(null)
    val session = LocalAgentSession(running, lastError)
    val device = FakeLocalAgentDevice()
    val settings = FakeLocalAgentSettings()
    val memory = FakeLocalAgentTaskMemory()
    val status = FakeLocalAgentStatusReporter()
    val host = FakeLocalAgentServiceHost()
    val tts = FakeLocalAgentTtsEngine()
    var now = 1_000L

    val speech = LocalAgentSpeechOutput(
        isDeviceReady = { device.availability == LocalAgentDeviceState.Availability.READY },
        currentLocale = { Locale.US },
        engineFactory = { onInit -> tts.also { it.onInit = onInit } },
        clock = { now },
    )

    /** Initializes the speech output with a ready engine. */
    fun readySpeech() {
        speech.initialize()
        tts.onInit!!.invoke(true)
    }

    val spokenTexts: List<String> get() = tts.spoken.map { it.first }

    fun taskLoop(planner: LocalAgentTaskLoop.Planner): LocalAgentTaskLoop = LocalAgentTaskLoop(
        session = session,
        device = device,
        settings = settings,
        memory = memory,
        status = status,
        speech = speech,
        host = host,
        plannerProvider = { planner },
        clock = { now },
    )
}

/** Mirrors [LocalAgentStepEngine]'s summaries for the actions tests use. */
internal class FakeActionBatchRunner(
    private val result: (List<LocalAgentAction>) -> LocalAgentStepEngine.ExecutionSummary = ::defaultSummary,
) : LocalAgentTaskLoop.ActionBatchRunner {
    val batches = mutableListOf<List<LocalAgentAction>>()

    override suspend fun execute(actions: List<LocalAgentAction>): LocalAgentStepEngine.ExecutionSummary {
        batches += actions
        return result(actions)
    }

    companion object {
        fun defaultSummary(actions: List<LocalAgentAction>): LocalAgentStepEngine.ExecutionSummary {
            val results = mutableListOf<String>()
            for (action in actions) {
                if (action is LocalAgentAction.Finish) {
                    results += action.message?.takeIf { it.isNotBlank() } ?: "Task marked complete"
                    return LocalAgentStepEngine.ExecutionSummary(results, haltedForApproval = false, finished = true)
                }
                results += "${action.javaClass.simpleName}: ok(tasker)"
            }
            return LocalAgentStepEngine.ExecutionSummary(results, haltedForApproval = false, finished = false)
        }
    }
}
