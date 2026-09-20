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

class RemoteUiControlLocalAgentBrain(
    private val decisionEngineProvider: (() -> com.fersaiyan.cyanbridge.ai.decision.LocalDecisionEngine?)? = null,
) : LocalAgentBrain {

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

        // Jev-like fast path: score enumerated legal actions with ~1 token.
        // Falls through to JSON generation for NEED_TEXT / open-ended params.
        tryCandidateDecision(taskState, observation)?.let { return it }

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

    private suspend fun tryCandidateDecision(
        taskState: LocalAgentTaskState,
        observation: LocalAgentObservation,
    ): LocalAgentBrainOutput? {
        val engine = runCatching { decisionEngineProvider?.invoke() }.getOrNull()
            ?: LocalAgentDecisionBridge.engine()
            ?: return null
        return try {
            val built = UiActionCandidateBuilder.build(taskState.goal, observation, taskState.previousActionResult)
            if (built.candidates.size < 2) return null
            // An explicit leading "Open/Launch <app>" goal is safe to map deterministically.
            // Avoid spending a model call on it, and never let a weak classifier prematurely
            // finish while the requested app is not even in the foreground.
            built.keys.indexOf("open_app").takeIf { it >= 0 }?.let { openIndex ->
                val action = mapCandidateKeyToAction(
                    openIndex,
                    built.keys[openIndex],
                    taskState,
                    observation,
                    built,
                ) ?: return null
                return LocalAgentBrainOutput(
                    actions = listOf(action),
                    note = "deterministic explicit app launch",
                    isComplete = false,
                )
            }
            val screenSummary = observation.screenSnapshot?.toCompressedPromptText(taskState.goal)
                ?: observation.screenText.orEmpty()
            // SingleTokenDecisionEngine adds the bounded options and letter-only instructions.
            // Keep this state compact enough for CPU-first Android inference.
            val state = buildString {
                appendLine("Phone automation goal: ${taskState.goal.trim().take(500)}")
                taskState.previousActionResult?.takeIf { it.isNotBlank() }?.let {
                    appendLine("Previous result: ${it.trim().take(240)}")
                }
                appendLine("Current screen:")
                append(screenSummary.trim().take(1_200))
            }.trim()
            android.util.Log.d(TAG, "Jev-like UI candidates=${built.candidates.map { it.label to it.description }} spans=${built.typeSpans}")
            val decision = engine.choose(state, built.candidates, debugTag = "local-agent-ui")
            android.util.Log.i(TAG, "Jev-like UI choice=${decision.label} conf=${decision.confidence} raw='${decision.rawOutput.take(200)}'")
            if (decision.abstained || decision.confidence < CANDIDATE_MIN_CONFIDENCE) {
                android.util.Log.i(TAG, "Jev-like UI confidence below threshold, using JSON planner")
                return null
            }
            val key = built.keys.getOrNull(decision.index) ?: return null
            mapCandidateKeyToActions(decision.index, key, taskState, observation, built)?.let { actions ->
                LocalAgentBrainOutput(
                    actions = actions,
                    note = "jev-candidate ${decision.label} conf=${decision.confidence}",
                    isComplete = actions.lastOrNull() is LocalAgentAction.Finish,
                )
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Jev-like UI candidate path failed, falling back to JSON: ${t.message}")
            null
        }
    }

    internal fun mapCandidateKeyToAction(
        candidateIndex: Int,
        key: String,
        taskState: LocalAgentTaskState,
        observation: LocalAgentObservation,
        built: UiActionCandidateBuilder.Built,
    ): LocalAgentAction? {
        val nodes = observation.screenSnapshot?.nodes.orEmpty()
        return when (key) {
            "open_app" -> {
                val appName = UiActionCandidateBuilder.extractTargetApp(taskState.goal) ?: return null
                LocalAgentAction.OpenApp(appName)
            }
            "click_node" -> {
                val selectedNodeIndex = built.nodeIndices.getOrNull(candidateIndex)
                val node = nodes.firstOrNull { it.index == selectedNodeIndex }
                    ?: return null
                LocalAgentAction.ClickCoord(node.bounds.centerX, node.bounds.centerY)
            }
            "type_text" -> {
                val span = built.typeSpans.firstOrNull()?.trim().orEmpty()
                if (span.isBlank()) return null
                val hint = nodes.firstOrNull { it.isEditable }?.hintText?.trim()?.takeIf { it.isNotBlank() }
                LocalAgentAction.TypeText(span, hint)
            }
            "scroll" -> LocalAgentAction.Scroll(LocalAgentAction.Direction.DOWN)
            "press_back" -> LocalAgentAction.GlobalBack
            // Null intentionally enters the structured JSON planner. Bounded classification cannot
            // synthesize open-ended parameters or a grounded user-facing final answer.
            "detailed_planner" -> null
            else -> null
        }
    }

    internal fun mapCandidateKeyToActions(
        candidateIndex: Int,
        key: String,
        taskState: LocalAgentTaskState,
        observation: LocalAgentObservation,
        built: UiActionCandidateBuilder.Built,
    ): List<LocalAgentAction>? {
        val action = mapCandidateKeyToAction(candidateIndex, key, taskState, observation, built)
            ?: return null
        if (action !is LocalAgentAction.TypeText) return listOf(action)

        // AutoInput's compact command types into the focused field. Reduced observations do not
        // preserve focus/editable flags, so focus the exact node represented by this candidate
        // before typing instead of hoping an unrelated field is already focused.
        val selectedNodeIndex = built.nodeIndices.getOrNull(candidateIndex)
        val node = observation.screenSnapshot?.nodes.orEmpty()
            .firstOrNull { it.index == selectedNodeIndex }
            ?: return null
        return listOf(
            LocalAgentAction.ClickCoord(node.bounds.centerX, node.bounds.centerY),
            action,
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
        private const val TAG = "LocalAgentBrain"
        private const val MAX_CONSECUTIVE_FAILURES = 5
        /** Minimum confidence to accept a Jev-like candidate pick; else JSON fallback. */
        private const val CANDIDATE_MIN_CONFIDENCE = 0.55f
        private const val USER_ANSWER_GROUNDING_PROMPT = """


If the task asks you to read, extract, compare, explain, or summarize information from the UI, do not finish until the relevant source content is actually visible in the CURRENT SCREEN TEXT DUMP. Base the answer only on observed source text; do not invent missing facts. When you finish such a task, return action "finish" with params.message containing the concise user-facing answer. The finish message is the answer CyanBridge will deliver to the user.

For email tasks, use send_email only after you have the final recipient, subject, and body. send_email is a HIGH-risk action owned by CyanBridge policy and must wait for explicit user approval. After PREVIOUS ACTION RESULT says SendEmail was approved and executed through Tasker, re-read the current email-app screen. The approved action opens the prepared composer; it does NOT by itself prove the message was submitted. If the compose screen is visible, click the visible Send control through Tasker. Do not finish or claim the email was sent until a subsequent observation shows the compose screen is gone, the inbox/sent state is visible, or another clear sent confirmation is visible.

If PREVIOUS ACTION RESULT says an action is unsupported or not configured, do not repeat that action; choose a supported visible alternative such as an exact-text button instead.
"""
    }
}
