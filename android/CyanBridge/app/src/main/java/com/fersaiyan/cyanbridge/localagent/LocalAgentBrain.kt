package com.fersaiyan.cyanbridge.localagent

import android.content.Context
import com.fersaiyan.cyanbridge.ai.router.AgentInferenceRouter

/**
 * "Brain" interface: takes an observation and returns a JSON plan.
 *
 * Keep it pluggable so we can later route to a local LLM, remote endpoint,
 * or a scripted policy.
 */
interface LocalAgentBrain {
    suspend fun next(
        context: Context,
        taskState: LocalAgentTaskState,
        observation: LocalAgentObservation,
    ): LocalAgentBrainOutput
}

data class LocalAgentBrainOutput(
    val actions: List<LocalAgentAction> = emptyList(),
    val note: String? = null,
    val isComplete: Boolean = false,
)

class NoOpLocalAgentBrain : LocalAgentBrain {
    override suspend fun next(
        context: Context,
        taskState: LocalAgentTaskState,
        observation: LocalAgentObservation,
    ): LocalAgentBrainOutput {
        return LocalAgentBrainOutput(actions = emptyList(), note = "noop", isComplete = false)
    }
}

class RemoteUiControlLocalAgentBrain : LocalAgentBrain {

    override suspend fun next(
        context: Context,
        taskState: LocalAgentTaskState,
        observation: LocalAgentObservation,
    ): LocalAgentBrainOutput {
        LocalAgentSafetyPolicy.blockedReason(context, observation.packageName)?.let { reason ->
            return LocalAgentBrainOutput(
                actions = listOf(LocalAgentAction.Finish(reason)),
                note = reason,
                isComplete = true,
            )
        }

        if (taskState.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            return LocalAgentBrainOutput(
                actions = listOf(LocalAgentAction.Finish("Stopped after repeated failures.")),
                note = "Too many consecutive failures.",
                isComplete = true,
            )
        }

        val prompt = LocalAgentUiControlProtocol.buildPrompt(
            LocalAgentUiControlProtocol.StepContext(
                goal = taskState.goal,
                observation = observation,
                stepIndex = taskState.stepIndex,
                maxSteps = taskState.maxSteps,
                previousActionResult = taskState.previousActionResult,
                consecutiveFailures = taskState.consecutiveFailures,
            )
        )

        val screenshot = when {
            LocalAgentPrefs.isScreenshotPlanningEnabled(context) &&
                AgentInferenceRouter.isRemotePlanner(context) &&
                !LocalAgentPrefs.isRemoteScreenshotUploadEnabled(context) -> {
                LocalAgentPrefs.setScreenshotStatus(
                    context,
                    "Remote screenshot upload is off; used text-only planning.",
                )
                null
            }

            else -> when (val capture = LocalAgentScreenshotCapture.captureForPlanning(context, observation)) {
                is LocalAgentScreenshotCapture.Capture.Available -> capture
                is LocalAgentScreenshotCapture.Capture.Unavailable -> {
                    LocalAgentPrefs.setScreenshotStatus(context, capture.reason)
                    null
                }
            }
        }

        val raw = try {
            val inference = AgentInferenceRouter.completeUiPlanning(
                context = context,
                sessionId = "local-agent-ui-${taskState.startedAtMs}",
                systemPrompt = prompt.system + USER_ANSWER_GROUNDING_PROMPT,
                userPrompt = prompt.user,
                imagePath = screenshot?.file?.absolutePath,
                allowRemoteImageUpload = LocalAgentPrefs.isRemoteScreenshotUploadEnabled(context),
            )
            LocalAgentPrefs.setScreenshotStatus(context, inference.mediaStatus)
            inference.content
        } finally {
            // Screenshots are transient planner attachments, never history or memory artifacts.
            LocalAgentScreenshotCapture.delete(screenshot)
        }

        val decision = LocalAgentUiControlProtocol.parseDecision(raw)
        return LocalAgentBrainOutput(
            actions = listOf(decision.action.toLocalAgentAction()),
            note = decision.reasoning,
            isComplete = decision.isComplete,
        )
    }

    private fun LocalAgentUiControlProtocol.Action.toLocalAgentAction(): LocalAgentAction {
        return when (this) {
            LocalAgentUiControlProtocol.NoOp -> LocalAgentAction.Wait(250L)
            is LocalAgentUiControlProtocol.Wait -> LocalAgentAction.Wait(ms)
            is LocalAgentUiControlProtocol.ClickText -> LocalAgentAction.ClickText(text)
            is LocalAgentUiControlProtocol.ClickCoord -> LocalAgentAction.ClickCoord(x, y)
            is LocalAgentUiControlProtocol.TypeText -> LocalAgentAction.TypeText(text, hint)
            LocalAgentUiControlProtocol.PressEnter -> LocalAgentAction.PressEnter
            is LocalAgentUiControlProtocol.Scroll -> LocalAgentAction.Scroll(
                when (direction) {
                    LocalAgentUiControlProtocol.Direction.up -> LocalAgentAction.Direction.UP
                    LocalAgentUiControlProtocol.Direction.down -> LocalAgentAction.Direction.DOWN
                }
            )
            is LocalAgentUiControlProtocol.Swipe -> LocalAgentAction.Swipe(
                startX, startY, endX, endY, durationMs
            )
            is LocalAgentUiControlProtocol.LongPress -> LocalAgentAction.LongPress(x, y, durationMs)
            LocalAgentUiControlProtocol.PressBack -> LocalAgentAction.GlobalBack
            LocalAgentUiControlProtocol.PressHome -> LocalAgentAction.GlobalHome
            LocalAgentUiControlProtocol.OpenNotifications -> LocalAgentAction.OpenNotifications
            LocalAgentUiControlProtocol.OpenRecents -> LocalAgentAction.OpenRecents
            is LocalAgentUiControlProtocol.OpenApp -> LocalAgentAction.OpenApp(appName)
            is LocalAgentUiControlProtocol.MakeCall -> LocalAgentAction.MakeCall(number)
            is LocalAgentUiControlProtocol.SendSms -> LocalAgentAction.SendSms(number, message)
            is LocalAgentUiControlProtocol.SendEmail -> LocalAgentAction.SendEmail(to, subject, body)
            is LocalAgentUiControlProtocol.SetAlarm -> LocalAgentAction.SetAlarm(hour, minute, label)
            LocalAgentUiControlProtocol.OpenContacts -> LocalAgentAction.OpenContacts
            LocalAgentUiControlProtocol.ToggleWifi -> LocalAgentAction.ToggleWifi
            LocalAgentUiControlProtocol.ToggleBluetooth -> LocalAgentAction.ToggleBluetooth
            LocalAgentUiControlProtocol.ToggleFlashlight -> LocalAgentAction.ToggleFlashlight
            LocalAgentUiControlProtocol.ReadScreenAloud -> LocalAgentAction.ReadScreenAloud
            is LocalAgentUiControlProtocol.Finish -> LocalAgentAction.Finish(message)
        }
    }

    private companion object {
        private const val MAX_CONSECUTIVE_FAILURES = 5
        private const val USER_ANSWER_GROUNDING_PROMPT = """


If the task asks you to read, extract, compare, explain, or summarize information from the UI, do not finish until the relevant source content is actually visible in the CURRENT SCREEN TEXT DUMP. Base the answer only on observed source text; do not invent missing facts. When you finish such a task, return action "finish" with params.message containing the concise user-facing answer. The finish message is the answer CyanBridge will deliver to the user.
"""
    }
}
