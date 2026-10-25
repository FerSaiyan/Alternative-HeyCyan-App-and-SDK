package com.fersaiyan.cyanbridge.shared.live

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class GeminiLiveViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val session = RecordingSession()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun sessionStatesDriveTheScreenAndAnIdleAnswerClosesIt() = runTest(dispatcher) {
        val vm = GeminiLiveViewModel(session) { false }
        vm.onCreate(autoStart = true)
        assertEquals("Connecting to CyanBridge Live relay", vm.state.value.status)
        assertEquals(listOf("start"), session.calls)

        vm.onStateChanged(GeminiLiveState.LISTENING, "")
        runCurrent()
        assertEquals("Listening", vm.state.value.status)
        assertEquals("Microphone: on   Glasses vision: relay (server holds key)", vm.state.value.indicators)
        assertEquals("Session 0:00", vm.state.value.elapsed)
        assertTrue(vm.state.value.active)

        vm.onModelTurnComplete()
        advanceTimeBy(GeminiLiveViewModel.POST_RESPONSE_IDLE_MS + 1)
        assertEquals(listOf("stopVision", "stop"), session.calls.takeLast(2))

        vm.onStateChanged(GeminiLiveState.STOPPED, "")
        assertEquals("Session not running", vm.state.value.elapsed)
        assertEquals("Microphone: off   Glasses vision: off", vm.state.value.indicators)
        assertEquals(false, vm.state.value.active)
    }

    @Test
    fun permissionRoutes() {
        assertEquals(GeminiLiveRoute.DENIED, GeminiLiveRoute.of(microphoneGranted = false, notificationsAllowed = true))
        assertEquals(GeminiLiveRoute.BACKGROUND_SERVICE, GeminiLiveRoute.of(microphoneGranted = true, notificationsAllowed = true))
        assertEquals(GeminiLiveRoute.VISIBLE_PREVIEW, GeminiLiveRoute.of(microphoneGranted = true, notificationsAllowed = false))
    }
}

private class RecordingSession : GeminiLiveSession {
    val calls = mutableListOf<String>()
    override fun microphoneGranted() = true
    override fun notificationsGranted() = true
    override fun start() { calls += "start" }
    override fun stop() { calls += "stop" }
    override fun startVision() { calls += "startVision" }
    override fun stopVision() { calls += "stopVision" }
    override fun pause() { calls += "pause" }
    override fun resume() { calls += "resume" }
    override fun setImageButtonEnabled(enabled: Boolean) = Unit
    override fun sendInitialTurn() { calls += "initialTurn" }
    override fun close() = Unit
}
