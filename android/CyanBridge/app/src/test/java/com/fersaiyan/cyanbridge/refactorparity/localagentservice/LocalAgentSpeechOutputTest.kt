package com.fersaiyan.cyanbridge.refactorparity.localagentservice

import com.fersaiyan.cyanbridge.localagent.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalAgentSpeechOutputTest {
    private val fixture = LocalAgentServiceFixture()
    private val tts get() = fixture.tts
    private val speech get() = fixture.speech

    @Before
    @After
    fun resetAudioSession() {
        AudioSessionCoordinator.markIdle()
    }

    @Test
    fun `initialize sets the language only when the engine is ready`() {
        speech.initialize()
        tts.onInit!!.invoke(false)
        assertNull(tts.appliedLanguage)

        val other = LocalAgentServiceFixture()
        other.readySpeech()
        assertEquals(Locale.US, other.tts.appliedLanguage)
    }

    @Test
    fun `speak and wait trims, truncates and uses a clock based utterance id`() = runTest {
        fixture.readySpeech()
        fixture.now = 42L

        speech.speakAndWaitBestEffort("   ${"a".repeat(200)}   ")
        speech.speakAndWaitBestEffort("b".repeat(300), maxLength = 240)

        assertEquals("a".repeat(160), tts.spoken[0].first)
        assertEquals("local_agent_result_42", tts.spoken[0].second)
        assertEquals("b".repeat(240), tts.spoken[1].first)
        assertFalse(AudioSessionCoordinator.isBusy())
    }

    @Test
    fun `speak and wait skips blank text and an unavailable device`() = runTest {
        fixture.readySpeech()
        speech.speakAndWaitBestEffort("   ")
        fixture.device.availability = LocalAgentDeviceState.Availability.LOCKED
        speech.speakAndWaitBestEffort("hello")
        speech.speakBestEffort("hello")

        assertTrue(tts.spoken.isEmpty())
    }

    @Test
    fun `speak and wait gives up after three seconds when the engine never initializes`() = runTest {
        speech.initialize()

        speech.speakAndWaitBestEffort("hello")

        assertEquals(3_000L, currentTime)
        assertTrue(tts.spoken.isEmpty())
        assertFalse(AudioSessionCoordinator.isBusy())
    }

    @Test
    fun `speak best effort waits for init and uses the demo utterance id`() = runTest {
        speech.initialize()
        val pending = async { speech.speakBestEffort("hello") }
        runCurrent()
        assertTrue(tts.spoken.isEmpty())

        tts.onInit!!.invoke(true)
        pending.await()

        assertEquals(listOf("hello" to "local_agent_demo"), tts.spoken)
    }

    @Test
    fun `speak best effort without initialize skips speaking`() = runTest {
        speech.speakBestEffort("hello")

        assertTrue(tts.spoken.isEmpty())
        assertEquals(0L, currentTime)
    }

    @Test
    fun `audio session stays busy until the utterance finishes`() = runTest {
        fixture.readySpeech()
        tts.finishImmediately = false

        val pending = async { speech.speakAndWaitBestEffort("hello") }
        runCurrent()
        assertTrue(AudioSessionCoordinator.isBusy())

        advanceTimeBy(1_000L)
        tts.finish("some_other_utterance")
        runCurrent()
        assertTrue(AudioSessionCoordinator.isBusy())

        tts.finish(tts.spoken.single().second)
        pending.await()
        assertFalse(AudioSessionCoordinator.isBusy())
        assertTrue(currentTime < 8_000L)
    }

    @Test
    fun `speak and wait stops waiting after thirty two polls`() = runTest {
        fixture.readySpeech()
        tts.finishImmediately = false

        speech.speakAndWaitBestEffort("hello")

        assertEquals(32 * 250L, currentTime)
        assertEquals(0, tts.stopCount)
        assertFalse(AudioSessionCoordinator.isBusy())
    }

    @Test
    fun `speak and wait stops the engine when the device becomes unavailable`() = runTest {
        fixture.readySpeech()
        tts.finishImmediately = false

        val pending = async { speech.speakAndWaitBestEffort("hello") }
        advanceTimeBy(600L)
        fixture.device.availability = LocalAgentDeviceState.Availability.NON_INTERACTIVE
        pending.await()

        assertEquals(1, tts.stopCount)
        assertEquals(750L, currentTime)
        assertFalse(AudioSessionCoordinator.isBusy())
    }

    @Test
    fun `rejected speak call releases the audio session immediately`() = runTest {
        fixture.readySpeech()
        tts.speakResult = false

        speech.speakAndWaitBestEffort("hello")

        assertEquals(0L, currentTime)
        assertFalse(AudioSessionCoordinator.isBusy())
    }

    @Test
    fun `shutdown releases the engine and later speech is skipped`() = runTest {
        fixture.readySpeech()

        speech.stop()
        speech.shutdown()
        speech.speakAndWaitBestEffort("hello")
        speech.stop()

        assertEquals(1, tts.shutdownCount)
        assertEquals(2, tts.stopCount)
        assertTrue(tts.spoken.isEmpty())
    }
}
