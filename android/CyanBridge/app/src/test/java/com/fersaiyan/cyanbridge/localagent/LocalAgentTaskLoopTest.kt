package com.fersaiyan.cyanbridge.localagent

import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalAgentTaskLoopTest {
    private val fixture = LocalAgentServiceFixture()

    @Before
    fun setUp() {
        AudioSessionCoordinator.markIdle()
        fixture.readySpeech()
        fixture.running.set(true)
    }

    @After
    fun tearDown() {
        AudioSessionCoordinator.markIdle()
    }

    private fun plannerOf(vararg outputs: LocalAgentBrainOutput): Pair<LocalAgentTaskLoop.Planner, MutableList<LocalAgentTaskState>> {
        val seen = mutableListOf<LocalAgentTaskState>()
        var index = 0
        val planner = LocalAgentTaskLoop.Planner { taskState, _ ->
            seen += taskState
            outputs[minOf(index++, outputs.lastIndex)]
        }
        return planner to seen
    }

    private fun finish(message: String? = "Done") = LocalAgentBrainOutput(
        actions = listOf(LocalAgentAction.Finish(message)),
        note = "finishing",
    )

    @Test
    fun `finish completes the task, records it and speaks the completion phrase`() = runTest {
        val (planner, seen) = plannerOf(finish())
        val runner = FakeActionBatchRunner()

        fixture.taskLoop(planner).run(goal = "open mail", maxSteps = 5, engine = runner)

        assertEquals(1, seen.size)
        assertEquals("open mail", seen.single().goal)
        assertEquals(1_000L, seen.single().startedAtMs)
        assertEquals(
            listOf(
                "status:Running step 1/5", "emit",
                "status:Completed", "clearError", "emit",
            ),
            fixture.status.events,
        )
        val terminal = fixture.status.emitted.last()
        assertEquals(1_000L, terminal.taskId)
        assertTrue(terminal.isTerminal)
        assertEquals("Task finished.", terminal.userMessage)
        assertEquals(listOf("Task finished."), fixture.spokenTexts)
        assertEquals(listOf("notify:Done", "stopForegroundAndSelf"), fixture.host.events)
        assertFalse(fixture.running.get())
        assertNull(fixture.lastError.get())
        assertEquals(
            listOf(LocalAgentTaskHistory.Entry("open mail", "Completed", 1, false, fixture.memory.history.single().createdAtMs)),
            fixture.memory.history,
        )
        assertEquals(listOf("open mail" to emptyList<LocalAgentAction>()), fixture.memory.successfulSkills)
        // Let the destination app settle is skipped because the task completed.
        assertEquals(0L, currentTime)
    }

    @Test
    fun `failed finish message is spoken as a failure`() = runTest {
        val (planner, _) = plannerOf(finish("Couldn't find the button"))

        fixture.taskLoop(planner).run(goal = "g", maxSteps = 5, engine = FakeActionBatchRunner())

        assertEquals("I couldn't finish that task.", fixture.status.emitted.last().userMessage)
        assertEquals(listOf("I couldn't finish that task."), fixture.spokenTexts)
    }

    @Test
    fun `reaching max steps stops with max_steps_reached`() = runTest {
        val click = LocalAgentBrainOutput(actions = listOf(LocalAgentAction.ClickText("Next")), note = "click")
        val (planner, seen) = plannerOf(click)
        val runner = FakeActionBatchRunner()

        fixture.taskLoop(planner).run(goal = "g", maxSteps = 2, engine = runner)

        assertEquals(2, runner.batches.size)
        assertEquals("click | ClickText: ok(tasker)", seen[1].previousActionResult)
        val terminal = fixture.status.emitted.last()
        assertEquals("Stopped", terminal.status)
        assertEquals("max_steps_reached", terminal.lastError)
        assertEquals("I couldn't finish that task.", terminal.userMessage)
        assertEquals("max_steps_reached", fixture.lastError.get())
        assertEquals(listOf("notify:Reached max steps (2)", "stopForegroundAndSelf"), fixture.host.events)
        assertEquals(3, fixture.memory.history.single().stepCount)
        assertEquals("Stopped", fixture.memory.history.single().status)
        assertTrue(fixture.memory.successfulSkills.isEmpty())
        // Two ClickText settle delays.
        assertEquals(2 * 1_500L, currentTime)
    }

    @Test
    fun `repeated identical actions are blocked instead of executed`() = runTest {
        val click = LocalAgentBrainOutput(actions = listOf(LocalAgentAction.ClickText("Next")))
        val (planner, seen) = plannerOf(click)
        val runner = FakeActionBatchRunner()

        fixture.taskLoop(planner).run(goal = "g", maxSteps = 4, engine = runner)

        assertEquals(2, runner.batches.size)
        assertEquals(4, seen.size)
        val blocked = LocalAgentRuntimePolicy.repeatLimitMessage(LocalAgentAction.ClickText("Next"))
        assertEquals(blocked, seen[3].previousActionResult)
        assertEquals(1, seen[3].consecutiveFailures)
        assertEquals("Stopped", fixture.status.emitted.last().status)
    }

    @Test
    fun `unavailable device stops the task silently before planning`() = runTest {
        fixture.device.availability = LocalAgentDeviceState.Availability.LOCKED
        val (planner, seen) = plannerOf(finish())
        val approval = kotlinx.coroutines.CompletableDeferred<Boolean>()
        fixture.session.approvalDeferred = approval

        fixture.taskLoop(planner).run(goal = "g", maxSteps = 5, engine = FakeActionBatchRunner())

        assertTrue(seen.isEmpty())
        assertFalse(approval.await())
        assertNull(fixture.session.approvalDeferred)
        val terminal = fixture.status.emitted.single()
        assertEquals("Stopped: Phone is locked", terminal.status)
        assertEquals("device_locked", terminal.lastError)
        assertEquals("", terminal.userMessage)
        assertTrue(fixture.spokenTexts.isEmpty())
        assertEquals(listOf("notify:Stopped: Phone is locked", "stopForegroundAndSelf"), fixture.host.events)
        assertEquals("Stopped", fixture.memory.history.single().status)
    }

    @Test
    fun `device halt reported by the step engine stops the task`() = runTest {
        val click = LocalAgentBrainOutput(actions = listOf(LocalAgentAction.ClickText("Next")))
        val (planner, _) = plannerOf(click)
        val runner = FakeActionBatchRunner {
            LocalAgentStepEngine.ExecutionSummary(
                actionResults = listOf("ClickText: blocked_device_state"),
                haltedForApproval = false,
                finished = false,
                haltedForDeviceState = true,
                deviceAvailability = LocalAgentDeviceState.Availability.NON_INTERACTIVE,
            )
        }

        fixture.taskLoop(planner).run(goal = "g", maxSteps = 5, engine = runner)

        assertEquals("Stopped: Phone screen is off or inactive", fixture.status.emitted.last().status)
        assertEquals("device_not_interactive", fixture.lastError.get())
    }

    @Test
    fun `missing accessibility waits one second and warns once`() = runTest {
        fixture.device.accessibilityFn = { check -> check > 2 }
        val (planner, _) = plannerOf(finish())

        fixture.taskLoop(planner).run(goal = "g", maxSteps = 5, engine = FakeActionBatchRunner())

        assertEquals(2_000L, currentTime)
        assertEquals(
            listOf(
                "status:Waiting for accessibility", "error:accessibility_not_connected", "emit",
                "status:Waiting for accessibility", "error:accessibility_not_connected", "emit",
                "status:Running step 1/5", "emit",
                "status:Completed", "clearError", "emit",
            ),
            fixture.status.events,
        )
    }

    @Test
    fun `brain timeout finishes the task after sixty seconds`() = runTest {
        val planner = LocalAgentTaskLoop.Planner { _, _ -> awaitCancellation() }
        val runner = FakeActionBatchRunner()

        fixture.taskLoop(planner).run(goal = "g", maxSteps = 5, engine = runner)

        assertEquals(60_000L, currentTime)
        assertEquals(listOf(listOf<LocalAgentAction>(LocalAgentAction.Finish("Brain call timed out."))), runner.batches)
        assertEquals("Completed", fixture.status.emitted.last().status)
        assertEquals(listOf("notify:Brain call timed out.", "stopForegroundAndSelf"), fixture.host.events)
        // A timeout is announced with the normal completion phrase.
        assertEquals("Task finished.", fixture.status.emitted.last().userMessage)
    }

    @Test
    fun `planner errors are reported and the loop backs off`() = runTest {
        var calls = 0
        val planner = LocalAgentTaskLoop.Planner { _, _ ->
            if (calls++ == 0) error("boom") else finish()
        }

        fixture.taskLoop(planner).run(goal = "g", maxSteps = 5, engine = FakeActionBatchRunner())

        assertTrue(fixture.status.events.containsAll(listOf("status:Error", "error:boom")))
        // 1s back-off plus the default settle delay.
        assertEquals(1_000L + 750L, currentTime)
        assertEquals("Completed", fixture.status.emitted.last().status)
        assertEquals(2, fixture.memory.history.single().stepCount)
    }

    @Test
    fun `saved skill is replayed before planning and replay failures are recorded`() = runTest {
        val skillActions = listOf(LocalAgentAction.OpenApp("Mail"), LocalAgentAction.ClickText("Inbox"))
        fixture.memory.savedSkills["open inbox"] = LocalAgentSkillStore.Skill("open inbox", skillActions, 0L)
        val (planner, seen) = plannerOf(finish())
        val runner = FakeActionBatchRunner { actions ->
            if (actions == skillActions) {
                LocalAgentStepEngine.ExecutionSummary(listOf("OpenApp: failed"), false, false)
            } else {
                FakeActionBatchRunner.defaultSummary(actions)
            }
        }

        fixture.taskLoop(planner).run(goal = "open inbox", maxSteps = 5, engine = runner)

        assertEquals(skillActions, runner.batches.first())
        assertEquals(listOf("open inbox"), fixture.memory.replayFailures)
        assertEquals(1, seen.size)
        assertEquals(
            "Replaying a saved low-risk navigation skill. | OpenApp: failed",
            seen.single().previousActionResult,
        )
        assertTrue(fixture.memory.history.single().usedSavedSkill)
    }

    @Test
    fun `repeated failures run a recovery action`() = runTest {
        fixture.device.screenText = "Loading, please wait"
        val click = LocalAgentBrainOutput(actions = listOf(LocalAgentAction.ClickText("Go")))
        var call = 0
        val planner = LocalAgentTaskLoop.Planner { _, _ -> if (call++ < 2) click else finish() }
        val runner = FakeActionBatchRunner { actions ->
            if (actions.single() is LocalAgentAction.Finish) {
                FakeActionBatchRunner.defaultSummary(actions)
            } else {
                LocalAgentStepEngine.ExecutionSummary(listOf("ClickText: failed"), false, false)
            }
        }

        fixture.taskLoop(planner).run(goal = "g", maxSteps = 5, engine = runner)

        assertEquals(listOf<LocalAgentAction>(LocalAgentAction.Wait(2_000L)), fixture.device.performed)
        assertTrue(fixture.status.events.any { it.startsWith("status:Recovering: ") })
    }

    @Test
    fun `step engine tasker failure suffix is not treated as a failed action`() = runTest {
        // LocalAgentStepEngine reports "failed(tasker)", which does not end with "failed".
        fixture.device.screenText = "Loading, please wait"
        val skillActions = listOf(LocalAgentAction.OpenApp("Mail"))
        fixture.memory.savedSkills["g"] = LocalAgentSkillStore.Skill("g", skillActions, 0L)
        val click = LocalAgentBrainOutput(actions = listOf(LocalAgentAction.ClickText("Go")))
        var call = 0
        val planner = LocalAgentTaskLoop.Planner { _, _ -> if (call++ < 2) click else finish() }
        val seen = mutableListOf<Int>()
        val runner = FakeActionBatchRunner { actions ->
            if (actions.single() is LocalAgentAction.Finish) {
                FakeActionBatchRunner.defaultSummary(actions)
            } else {
                LocalAgentStepEngine.ExecutionSummary(listOf("X: failed(tasker)"), false, false)
            }
        }
        val loop = fixture.taskLoop { taskState, observation ->
            seen += taskState.consecutiveFailures
            planner.next(taskState, observation)
        }

        loop.run(goal = "g", maxSteps = 5, engine = runner)

        assertTrue(fixture.memory.replayFailures.isEmpty())
        assertTrue(fixture.device.performed.isEmpty())
        assertEquals(listOf(0, 0, 0), seen)
        val go = LocalAgentAction.ClickText("Go")
        assertEquals(listOf("g" to skillActions + go + go), fixture.memory.successfulSkills)
    }

    @Test
    fun `approved action continues the task`() = runTest {
        val sms = LocalAgentAction.SendSms("123", "hi")
        var call = 0
        val planner = LocalAgentTaskLoop.Planner { _, _ ->
            if (call++ == 0) LocalAgentBrainOutput(actions = listOf(sms)) else finish()
        }
        val runner = FakeActionBatchRunner { actions ->
            if (actions == listOf(sms)) {
                LocalAgentStepEngine.ExecutionSummary(listOf("SendSms: queued_for_approval"), true, false)
            } else {
                FakeActionBatchRunner.defaultSummary(actions)
            }
        }
        val loopJob = Job()
        fixture.session.loopJob = loopJob

        val task = launch { fixture.taskLoop(planner).run(goal = "text bob", maxSteps = 5, engine = runner) }
        runCurrent()

        val approval = fixture.session.approvalDeferred
        assertNotNull(approval)
        assertEquals("Current agent task", fixture.session.pausedTaskState?.goal)
        assertEquals(listOf("notify:Waiting for your approval"), fixture.host.events)
        assertEquals(listOf("Send a message to 123. Answer now."), fixture.spokenTexts)

        advanceTimeBy(1_200L)
        approval!!.complete(true)
        task.join()

        assertNull(fixture.session.approvalDeferred)
        assertEquals("Completed", fixture.status.emitted.last().status)
        assertEquals(listOf("text bob" to listOf<LocalAgentAction>(sms)), fixture.memory.successfulSkills)
        // The paused state recorded for the approval is left in the session.
        assertEquals("Current agent task", fixture.session.pausedTaskState?.goal)
        loopJob.cancel()
    }

    @Test
    fun `rejected action stops the task`() = runTest {
        val sms = LocalAgentAction.SendSms("123", "hi")
        val (planner, _) = plannerOf(LocalAgentBrainOutput(actions = listOf(sms)))
        val runner = FakeActionBatchRunner {
            LocalAgentStepEngine.ExecutionSummary(listOf("SendSms: queued_for_approval"), true, false)
        }

        val task = launch { fixture.taskLoop(planner).run(goal = "g", maxSteps = 5, engine = runner) }
        runCurrent()
        assertNull(fixture.session.pausedTaskState)
        fixture.session.approvalDeferred!!.complete(false)
        task.join()

        val terminal = fixture.status.emitted.last()
        assertEquals("Stopped", terminal.status)
        assertEquals("action_rejected", terminal.lastError)
        assertEquals("Action was not approved.", terminal.userMessage)
        assertEquals(
            listOf("notify:Waiting for your approval", "notify:Action rejected", "stopForegroundAndSelf"),
            fixture.host.events,
        )
    }

    @Test
    fun `device lock while waiting for approval stops the task`() = runTest {
        val sms = LocalAgentAction.SendSms("123", "hi")
        val (planner, _) = plannerOf(LocalAgentBrainOutput(actions = listOf(sms)))
        val runner = FakeActionBatchRunner {
            LocalAgentStepEngine.ExecutionSummary(listOf("SendSms: queued_for_approval"), true, false)
        }

        val task = launch { fixture.taskLoop(planner).run(goal = "g", maxSteps = 5, engine = runner) }
        runCurrent()
        val approval = fixture.session.approvalDeferred!!
        fixture.device.availability = LocalAgentDeviceState.Availability.LOCKED
        advanceTimeBy(600L)
        task.join()

        assertFalse(approval.await())
        assertEquals("Stopped", fixture.status.emitted.last().status)
        assertEquals("action_rejected", fixture.lastError.get())
    }

    @Test
    fun `cancel flag alone ends the loop as max steps reached`() = runTest {
        val click = LocalAgentBrainOutput(actions = listOf(LocalAgentAction.ClickText("Next")))
        val (planner, _) = plannerOf(click)
        val runner = FakeActionBatchRunner { actions ->
            fixture.session.cancelRequested.set(true)
            FakeActionBatchRunner.defaultSummary(actions)
        }

        fixture.taskLoop(planner).run(goal = "g", maxSteps = 5, engine = runner)

        // The loop exits on the flag; with the job still active it reports max steps.
        assertEquals("max_steps_reached", fixture.status.emitted.last().lastError)
        assertEquals(1, runner.batches.size)
    }

    @Test
    fun `cancelled job records nothing`() = runTest {
        val click = LocalAgentBrainOutput(actions = listOf(LocalAgentAction.ClickText("Next")))
        val (planner, _) = plannerOf(click)
        val runner = FakeActionBatchRunner()

        val task = launch { fixture.taskLoop(planner).run(goal = "g", maxSteps = 5, engine = runner) }
        runCurrent()
        task.cancel()
        task.join()

        assertTrue(fixture.memory.history.isEmpty())
        assertTrue(fixture.status.emitted.none { it.isTerminal })
        assertTrue(fixture.host.events.isEmpty())
    }

    @Test
    fun `approval descriptions`() {
        assertEquals("Open Mail.", LocalAgentTaskLoop.describeActionForApproval(LocalAgentAction.OpenApp("Mail")))
        assertEquals("Call 555.", LocalAgentTaskLoop.describeActionForApproval(LocalAgentAction.MakeCall("555")))
        assertEquals("Press back.", LocalAgentTaskLoop.describeActionForApproval(LocalAgentAction.GlobalBack))
        assertEquals("Perform the next action.", LocalAgentTaskLoop.describeActionForApproval(null))
    }
}
