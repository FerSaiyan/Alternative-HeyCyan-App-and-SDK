package com.fersaiyan.cyanbridge.audio

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionKind
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionRequest
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioSessionCoordinatorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val opened = mutableListOf<AudioSessionHandle>()

    @After
    fun closeSessions() {
        opened.forEach(AudioSessionHandle::close)
        assertTrue(AudioSessionCoordinator.snapshot().isEmpty())
    }

    private class Events : AudioSessionListener {
        val log = mutableListOf<String>()
        override fun onSuspended() { log += "suspended" }
        override fun onResumed() { log += "resumed" }
        override fun onEnded() { log += "ended" }
    }

    private fun open(request: AudioSessionRequest, listener: AudioSessionListener = AudioSessionListener.NONE) =
        AudioSessionCoordinator.tryOpen(context, request, listener)?.also { opened += it }

    @Test
    fun spokenApprovalSuspendsLiveAndHandsTheMicrophoneBack() {
        val liveEvents = Events()
        val live = open(AudioSessionRequest.Conversation("GeminiLiveClient"), liveEvents)!!
        assertTrue(live.isActive)

        val approval = open(AudioSessionRequest.VoiceCapture("LocalAgentApprovalVoice"))!!
        assertFalse(live.isActive)
        assertEquals(listOf("suspended"), liveEvents.log)

        approval.close()
        assertTrue(live.isActive)
        assertEquals(listOf("suspended", "resumed"), liveEvents.log)
    }

    @Test
    fun onlyOneVoicePluginAndPluginsWaitForLive() {
        val translatorEvents = Events()
        val translator = open(AudioSessionRequest.ContinuousRecognition("HandsFreeTranslatorService"), translatorEvents)!!
        val denied = AudioSessionCoordinator.open(context, AudioSessionRequest.ContinuousRecognition("LiveCaptionRelayService"))
        assertTrue(denied is AudioSessionGrant.Denied)
        assertEquals("HandsFreeTranslatorService", (denied as AudioSessionGrant.Denied).blockingOwner)

        val live = open(AudioSessionRequest.Conversation("GeminiLiveClient"))!!
        assertEquals(AudioSessionState.SUSPENDED, translator.state)
        live.close()
        assertEquals(listOf("suspended", "resumed"), translatorEvents.log)
    }

    @Test
    fun meetingCaptureCoexistsWithLiveAndEndsAnAmbientProbe() {
        val ambientEvents = Events()
        val ambient = open(AudioSessionRequest.AmbientListening("AmbientSpeech"), ambientEvents)!!
        val meeting = open(AudioSessionRequest.Recording("MeetingCaptureService"))!!
        assertEquals(listOf("ended"), ambientEvents.log)
        assertFalse(ambient.isActive)

        val live = open(AudioSessionRequest.Conversation("GeminiLiveClient"))!!
        assertTrue(meeting.isActive)
        assertTrue(live.isActive)
        assertNull(AudioSessionCoordinator.tryOpen(context, AudioSessionRequest.AmbientListening("AmbientSpeech")))
    }

    @Test
    fun closingIsIdempotentAndFreesTheSlot() {
        val first = open(AudioSessionRequest.VoiceCapture("AudioQuestionRecorder"))!!
        assertNull(AudioSessionCoordinator.tryOpen(context, AudioSessionRequest.VoiceCapture("StudioApprovalHandler")))
        first.close()
        first.close()
        assertTrue(first.isClosed)
        assertTrue(open(AudioSessionRequest.VoiceCapture("StudioApprovalHandler"))!!.isActive)
    }

    @Test
    fun cancelledCaptureReleasesItsSession() = runTest {
        val started = CompletableDeferred<Unit>()
        val capture = async {
            AudioSessionCoordinator.tryOpen(context, AudioSessionRequest.VoiceCapture("LocalAgentApprovalVoice"))!!.use {
                started.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        started.await()
        assertEquals(AudioSessionKind.VOICE_COMMAND, AudioSessionCoordinator.snapshot().single().kind)
        capture.cancel()
        yield()
        assertTrue(AudioSessionCoordinator.snapshot().isEmpty())
    }

    @Test
    fun routeWithoutBluetoothKeepsThePhoneAndIsRestoredByTheLastUser() {
        audioManager.mode = AudioManager.MODE_NORMAL
        val live = open(AudioSessionRequest.Conversation("GeminiLiveClient"))!!
        val route = live.connectRouteNow()
        assertNull(route.inputDevice)
        assertFalse(route.bluetoothCandidatesPresent)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audioManager.mode)

        // A phone-only recording joins without touching the mode the conversation set.
        val meeting = open(AudioSessionRequest.Recording("MeetingCaptureService"))!!
        meeting.connectRouteNow()
        live.close()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audioManager.mode)
        meeting.close()
        assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
    }

    @Test
    fun leavingTheRouteRestoresItButKeepsTheMicrophoneSlot() {
        audioManager.mode = AudioManager.MODE_NORMAL
        val question = open(AudioSessionRequest.VoiceCapture("AssistantVoice"))!!
        question.connectRouteNow()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audioManager.mode)
        question.leaveRoute()
        assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
        assertTrue(question.isActive)
        assertNull(AudioSessionCoordinator.tryOpen(context, AudioSessionRequest.VoiceCapture("Other")))
    }

    @Test
    fun systemDefaultRouteNeverChangesTheMode() {
        audioManager.mode = AudioManager.MODE_NORMAL
        val note = open(AudioSessionRequest.VoiceCapture("ChatThreadActivity", route = com.fersaiyan.cyanbridge.shared.audio.AudioRoutePreference.SYSTEM_DEFAULT))!!
        assertFalse(note.connectRouteNow().bluetoothActive)
        assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
    }

    @Test
    fun focusIsOwnedByTheHandleAndAbandonedOnClose() {
        val live = open(AudioSessionRequest.Conversation("GeminiLiveClient"))!!
        assertTrue(live.requestFocus())
        assertTrue(live.hasFocus)
        live.close()
        assertFalse(live.hasFocus)
        assertFalse(live.requestFocus())
    }

    @Test
    fun speechFlagWaitsForIdle() = runTest {
        AudioSessionCoordinator.markSpeechBusy()
        assertTrue(AudioSessionCoordinator.isSpeechBusy())
        val waiter = async { AudioSessionCoordinator.awaitSpeechIdle(60_000) }
        yield()
        assertFalse(waiter.isCompleted)
        AudioSessionCoordinator.markSpeechIdle()
        waiter.await()
        assertFalse(AudioSessionCoordinator.isSpeechBusy())
    }
}
