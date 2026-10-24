package com.fersaiyan.cyanbridge.refactorparity.localagentservice

import com.fersaiyan.cyanbridge.localagent.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalAgentRuntimeTest {
    private val fixture = LocalAgentServiceFixture()
    private val runners = mutableListOf<FakeActionBatchRunner>()
    private val executors = mutableListOf<LocalAgentStepEngine.LocalAgentActionExecutor>()
    private var planner = LocalAgentTaskLoop.Planner { _, _ -> awaitCancellation() }

    @Before
    fun setUp() {
        AudioSessionCoordinator.markIdle()
        fixture.readySpeech()
    }

    @After
    fun tearDown() {
        AudioSessionCoordinator.markIdle()
    }

    private fun runtime(scope: CoroutineScope): LocalAgentRuntime {
        val screenReader = LocalAgentScreenReader(
            session = fixture.session,
            device = fixture.device,
            memory = fixture.memory,
            status = fixture.status,
            speech = fixture.speech,
            host = fixture.host,
            scope = scope,
        )
        return LocalAgentRuntime(
            session = fixture.session,
            device = fixture.device,
            settings = fixture.settings,
            status = fixture.status,
            speech = fixture.speech,
            host = fixture.host,
            scope = scope,
            taskLoop = fixture.taskLoop { taskState, observation -> planner.next(taskState, observation) },
            screenReader = screenReader,
            stepEngineFactory = { executor ->
                executors += executor
                FakeActionBatchRunner().also { runners += it }
            },
        )
    }

    private fun TestScope.started(goal: String? = "open mail"): LocalAgentRuntime =
        runtime(backgroundScope).also { it.startLoop(goal) }

    @Test
    fun `disabled automation stops the service without running`() = runTest {
        fixture.settings.automationEnabled = false

        started()

        assertEquals(listOf("status:Disabled", "error:local_agent_automation_disabled", "emit"), fixture.status.events)
        assertEquals(listOf("stopSelf"), fixture.host.events)
        assertFalse(fixture.running.get())
    }

    @Test
    fun `missing notification permission stops the service`() = runTest {
        fixture.device.notificationPermission = false

        started()

        assertEquals(
            listOf("status:Waiting for notification permission", "error:missing_post_notifications", "emit"),
            fixture.status.events,
        )
        assertEquals(listOf("stopSelf"), fixture.host.events)
    }

    @Test
    fun `unavailable device stops the service`() = runTest {
        fixture.device.availability = LocalAgentDeviceState.Availability.UNAVAILABLE

        started()

        assertEquals(
            listOf("status:Unavailable: Unable to verify phone lock state", "error:device_state_unavailable", "emit"),
            fixture.status.events,
        )
        assertEquals(listOf("stopSelf"), fixture.host.events)
    }

    @Test
    fun `blank goal resets running and stops the service`() = runTest {
        started(goal = "   ")

        assertEquals(listOf("status:Missing goal", "error:missing_goal", "emit"), fixture.status.events)
        assertEquals(listOf("stopSelf"), fixture.host.events)
        assertFalse(fixture.running.get())
    }

    @Test
    fun `start while running only re-emits status`() = runTest {
        fixture.running.set(true)

        started()

        assertEquals(listOf("emit"), fixture.status.events)
        assertTrue(fixture.host.events.isEmpty())
        assertTrue(fixture.running.get())
    }

    @Test
    fun `start goes foreground and launches the task loop`() = runTest {
        fixture.lastError.set("old")
        fixture.settings.maxSteps = 7
        val seen = CompletableDeferred<LocalAgentTaskState>()
        planner = LocalAgentTaskLoop.Planner { taskState, _ ->
            seen.complete(taskState)
            awaitCancellation()
        }

        started(goal = "  open mail  ")
        runCurrent()

        assertTrue(fixture.running.get())
        assertNull(fixture.lastError.get())
        assertFalse(fixture.session.cancelRequested.get())
        assertEquals(listOf("startForeground:Running: open mail"), fixture.host.events)
        assertEquals(
            listOf("status:Running", "clearError", "emit", "status:Running step 1/7", "emit"),
            fixture.status.events,
        )
        assertNotNull(fixture.session.loopJob)
        assertEquals("open mail", seen.await().goal)
        assertEquals(7, seen.await().maxSteps)
        assertTrue(executors.single() is LocalAgentServiceActionExecutor)
    }

    @Test
    fun `start foreground text is truncated to 48 characters`() = runTest {
        started(goal = "x".repeat(60))

        assertEquals("startForeground:Running: ${"x".repeat(48)}", fixture.host.events.first())
    }

    @Test
    fun `user stop cancels the loop and keeps the previous error`() = runTest {
        val runtime = started()
        runCurrent()
        val job = fixture.session.loopJob!!
        fixture.status.events.clear()
        fixture.status.currentLastError = "previous"

        runtime.stopLoop(reason = "user")

        assertTrue(job.isCancelled)
        assertNull(fixture.session.loopJob)
        assertTrue(fixture.session.cancelRequested.get())
        assertFalse(fixture.running.get())
        assertEquals(listOf("status:Stopped", "emit"), fixture.status.events)
        assertEquals("previous", fixture.status.emitted.last().lastError)
        assertEquals(
            listOf("startForeground:Running: open mail", "notify:Stopped", "stopForegroundAndSelf"),
            fixture.host.events,
        )
    }

    @Test
    fun `stop when idle only stops the service`() = runTest {
        val runtime = runtime(backgroundScope)

        runtime.stopLoop(reason = "user")

        assertTrue(fixture.status.events.isEmpty())
        assertEquals(listOf("stopSelf"), fixture.host.events)
        assertTrue(fixture.session.cancelRequested.get())
    }

    @Test
    fun `stop when idle with an error records it`() = runTest {
        val runtime = runtime(backgroundScope)

        runtime.stopLoop(reason = "x", status = "Stopped: X", error = "err")

        assertEquals(listOf("status:Stopped: X", "error:err", "emit"), fixture.status.events)
        assertEquals("err", fixture.lastError.get())
        assertEquals(listOf("stopSelf"), fixture.host.events)
    }

    @Test
    fun `resume completes the waiting approval`() = runTest {
        val runtime = runtime(backgroundScope)
        val approval = CompletableDeferred<Boolean>()
        fixture.session.approvalDeferred = approval

        runtime.resumeAfterApproval(rejected = false)

        assertTrue(approval.await())
        assertSame(approval, fixture.session.approvalDeferred)

        val rejected = CompletableDeferred<Boolean>()
        fixture.session.approvalDeferred = rejected
        runtime.resumeAfterApproval(rejected = true)
        assertFalse(rejected.await())
        assertTrue(fixture.status.events.isEmpty())
    }

    @Test
    fun `resume without a waiting approval re-emits status`() = runTest {
        val runtime = runtime(backgroundScope)

        runtime.resumeAfterApproval()

        assertEquals(listOf("emit"), fixture.status.events)
    }

    @Test
    fun `resume on a locked phone rejects and stops the running task`() = runTest {
        val runtime = runtime(backgroundScope)
        val approval = CompletableDeferred<Boolean>()
        fixture.session.approvalDeferred = approval
        fixture.session.pausedTaskState = LocalAgentTaskState("g", 3, 0L)
        fixture.session.loopJob = Job()
        fixture.running.set(true)
        fixture.device.availability = LocalAgentDeviceState.Availability.LOCKED

        runtime.resumeAfterApproval()

        assertFalse(approval.await())
        assertNull(fixture.session.approvalDeferred)
        assertNull(fixture.session.pausedTaskState)
        assertEquals(1, fixture.tts.stopCount)
        assertEquals("device_locked", fixture.lastError.get())
        assertEquals(listOf("status:Stopped: Phone is locked", "error:device_locked", "emit"), fixture.status.events)
        assertEquals(listOf("notify:Stopped: Phone is locked", "stopForegroundAndSelf"), fixture.host.events)
    }

    @Test
    fun `screen off while idle does nothing`() = runTest {
        val runtime = runtime(backgroundScope)
        fixture.device.availability = LocalAgentDeviceState.Availability.NON_INTERACTIVE

        runtime.stopForUnavailableDevice()

        assertTrue(fixture.status.events.isEmpty())
        assertTrue(fixture.host.events.isEmpty())
        assertEquals(0, fixture.tts.stopCount)
    }

    @Test
    fun `screen off during a screen read stops speech`() = runTest {
        val runtime = runtime(backgroundScope)
        fixture.session.screenReadInProgress.set(true)
        fixture.device.availability = LocalAgentDeviceState.Availability.NON_INTERACTIVE

        assertTrue(runtime.isBusy())
        runtime.stopForUnavailableDevice()

        assertFalse(fixture.session.screenReadInProgress.get())
        assertFalse(runtime.isBusy())
        assertEquals(1, fixture.tts.stopCount)
        assertEquals(
            listOf("status:Stopped: Phone screen is off or inactive", "error:device_not_interactive", "emit"),
            fixture.status.events,
        )
        assertEquals(listOf("stopSelf"), fixture.host.events)
    }

    @Test
    fun `ready device ignores the screen off signal`() = runTest {
        val runtime = runtime(backgroundScope)
        fixture.running.set(true)

        runtime.stopForUnavailableDevice()

        assertTrue(fixture.running.get())
        assertTrue(fixture.host.events.isEmpty())
    }
}
