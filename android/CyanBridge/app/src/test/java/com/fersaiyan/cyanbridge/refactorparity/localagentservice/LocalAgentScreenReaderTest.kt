package com.fersaiyan.cyanbridge.refactorparity.localagentservice

import com.fersaiyan.cyanbridge.localagent.*
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalAgentScreenReaderTest {
    private val fixture = LocalAgentServiceFixture()

    @Before
    fun setUp() {
        AudioSessionCoordinator.markIdle()
        fixture.readySpeech()
    }

    @After
    fun tearDown() {
        AudioSessionCoordinator.markIdle()
    }

    private fun TestScope.reader() = LocalAgentScreenReader(
        session = fixture.session,
        device = fixture.device,
        memory = fixture.memory,
        status = fixture.status,
        speech = fixture.speech,
        host = fixture.host,
        scope = this,
    )

    @Test
    fun `standalone read speaks the visible text and stops the service`() = runTest {
        fixture.device.screenText = " Inbox \n\n  3 unread \n"

        assertTrue(reader().readCurrentScreenAloud())
        assertTrue(fixture.session.screenReadInProgress.get())
        advanceUntilIdle()

        assertEquals(listOf("Reading the visible screen. Inbox. 3 unread"), fixture.spokenTexts)
        assertFalse(fixture.session.screenReadInProgress.get())
        assertEquals(
            listOf(
                "status:Reading current screen", "clearError", "emit",
                "status:Read current screen", "clearError", "emit",
            ),
            fixture.status.events,
        )
        assertEquals(listOf("startForeground:Reading current screen", "stopForegroundAndSelf"), fixture.host.events)
    }

    @Test
    fun `read during a running task stays in the background`() = runTest {
        fixture.running.set(true)

        assertTrue(reader().readCurrentScreenAloud())
        advanceUntilIdle()

        assertEquals(listOf("Reading the visible screen. Inbox"), fixture.spokenTexts)
        assertTrue(fixture.status.events.isEmpty())
        assertTrue(fixture.host.events.isEmpty())
    }

    @Test
    fun `read text is limited to twenty lines and 750 characters`() = runTest {
        fixture.device.screenText = (1..30).joinToString("\n") { "line $it" }
        reader().readCurrentScreenAloud()
        advanceUntilIdle()
        val expectedLines = (1..20).joinToString(". ") { "line $it" }
        // speakAndWaitBestEffort keeps its default 160-character limit.
        assertEquals("Reading the visible screen. $expectedLines".take(160), fixture.spokenTexts.single())

        val long = LocalAgentServiceFixture()
        long.readySpeech()
        long.device.screenText = "y".repeat(2_000)
        LocalAgentScreenReader(long.session, long.device, long.memory, long.status, long.speech, long.host, this)
            .readCurrentScreenAloud()
        advanceUntilIdle()
        assertEquals(750, LocalAgentScreenReader.MAX_SCREEN_READ_ALOUD_CHARS)
        assertEquals("Reading the visible screen. ${"y".repeat(750)}".take(160), long.spokenTexts.single())
    }

    @Test
    fun `blocked package is not read`() = runTest {
        fixture.device.blockedPackages += "com.example.app"

        assertFalse(reader().readCurrentScreenAloud())

        assertEquals(
            listOf("status:Screen reading blocked by privacy settings", "error:privacy_blacklisted_app", "emit"),
            fixture.status.events,
        )
        assertEquals(listOf("stopSelf"), fixture.host.events)
        assertTrue(fixture.spokenTexts.isEmpty())
    }

    @Test
    fun `unknown package and missing prerequisites are reported`() = runTest {
        fixture.device.activePackage = " "
        assertFalse(reader().readCurrentScreenAloud())
        assertEquals(listOf("status:Unable to verify current app", "error:screen_package_unknown", "emit"), fixture.status.events)

        fixture.status.events.clear()
        fixture.device.accessibilityConnected = false
        assertFalse(reader().readCurrentScreenAloud())
        assertEquals(listOf("error:accessibility_not_connected", "emit"), fixture.status.events)

        fixture.status.events.clear()
        fixture.device.notificationPermission = false
        assertFalse(reader().readCurrentScreenAloud())
        assertEquals(listOf("error:missing_post_notifications", "emit"), fixture.status.events)

        fixture.status.events.clear()
        fixture.device.availability = LocalAgentDeviceState.Availability.LOCKED
        assertFalse(reader().readCurrentScreenAloud())
        assertEquals(listOf("status:Screen reading unavailable: Phone is locked", "error:device_locked", "emit"), fixture.status.events)
        assertEquals(List(4) { "stopSelf" }, fixture.host.events)
    }

    @Test
    fun `empty screen speaks a fallback without waiting`() = runTest {
        fixture.device.screenText = " \n "

        assertFalse(reader().readCurrentScreenAloud())
        advanceUntilIdle()

        assertEquals(listOf("I couldn't read any text on the screen." to "local_agent_demo"), fixture.tts.spoken)
        assertFalse(fixture.session.screenReadInProgress.get())
        assertEquals(
            listOf("status:Reading current screen", "clearError", "emit", "error:empty_screen_text", "emit"),
            fixture.status.events,
        )
        assertEquals(listOf("startForeground:Reading current screen", "stopForegroundAndSelf"), fixture.host.events)
    }

    @Test
    fun `lock before speaking reports the stop and still releases the service`() = runTest {
        reader().readCurrentScreenAloud()
        fixture.device.availability = LocalAgentDeviceState.Availability.LOCKED
        advanceUntilIdle()

        assertTrue(fixture.spokenTexts.isEmpty())
        assertEquals(
            listOf(
                "status:Reading current screen", "clearError", "emit",
                "status:Screen reading stopped: Phone is locked", "error:device_locked", "emit",
                "status:Read current screen", "clearError", "emit",
            ),
            fixture.status.events,
        )
    }

    @Test
    fun `demo waits five seconds, stores the capture and reads it`() = runTest {
        fixture.device.screenText = "a\n\nb"

        reader().runDemo()
        runCurrent()
        assertEquals(listOf("status:Demo: reading in 5s…", "clearError", "emit"), fixture.status.events)
        advanceTimeBy(4_999L)
        assertTrue(fixture.memory.screenCaptures.isEmpty())
        advanceUntilIdle()

        assertEquals(listOf("(demo)" to "a\n\nb"), fixture.memory.screenCaptures)
        assertEquals(listOf("Reading your screen. a. b"), fixture.spokenTexts)
        assertEquals("Demo: spoke (4 chars)", fixture.status.currentStatus)
        assertEquals(listOf("stopSelf"), fixture.host.events)
    }

    @Test
    fun `demo without accessibility fails immediately`() = runTest {
        fixture.device.accessibilityConnected = false

        reader().runDemo()
        advanceUntilIdle()

        assertEquals(listOf("status:Demo: failed", "error:accessibility_not_connected", "emit"), fixture.status.events)
        assertEquals(listOf("stopSelf"), fixture.host.events)
    }

    @Test
    fun `demo with no text speaks the fallback`() = runTest {
        fixture.device.screenText = null

        reader().runDemo()
        advanceUntilIdle()

        assertEquals("Demo: no text found", fixture.status.currentStatus)
        assertEquals("empty_screen_text", fixture.status.currentLastError)
        assertEquals(listOf("I couldn't read any text on the screen."), fixture.spokenTexts)
        assertTrue(fixture.memory.screenCaptures.isEmpty())
    }
}
