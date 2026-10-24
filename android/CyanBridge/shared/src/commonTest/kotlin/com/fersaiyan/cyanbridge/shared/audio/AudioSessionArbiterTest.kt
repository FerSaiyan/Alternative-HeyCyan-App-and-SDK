package com.fersaiyan.cyanbridge.shared.audio

import com.fersaiyan.cyanbridge.shared.audio.AudioConflictOutcome.COEXIST
import com.fersaiyan.cyanbridge.shared.audio.AudioConflictOutcome.DENY_NEWCOMER
import com.fersaiyan.cyanbridge.shared.audio.AudioConflictOutcome.END_HOLDER
import com.fersaiyan.cyanbridge.shared.audio.AudioConflictOutcome.SUSPEND_HOLDER
import com.fersaiyan.cyanbridge.shared.audio.AudioConflictOutcome.WAIT_NEWCOMER
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionKind.AMBIENT_LISTENING
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionKind.CONTINUOUS_RECOGNITION
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionKind.CONVERSATION
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionKind.PLAYBACK
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionKind.RECORDING
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionKind.VOICE_COMMAND
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AudioSessionArbiterTest {
    private val live = AudioSessionRequest.Conversation("GeminiLive")
    private val approval = AudioSessionRequest.VoiceCapture("LocalAgentApproval")
    private val studio = AudioSessionRequest.VoiceCapture("StudioApproval")
    private val meeting = AudioSessionRequest.Recording("MeetingCapture")
    private val ambient = AudioSessionRequest.AmbientListening("AmbientSpeech")
    private val translator = AudioSessionRequest.ContinuousRecognition("HandsFreeTranslator")
    private val captions = AudioSessionRequest.ContinuousRecognition("LiveCaptionRelay")
    private val tts = AudioSessionRequest.Playback("LocalAgentSpeech")

    @Test
    fun policyTableMatchesTheDocumentedMatrix() {
        val expected = mapOf(
            VOICE_COMMAND to listOf(DENY_NEWCOMER, SUSPEND_HOLDER, COEXIST, SUSPEND_HOLDER, END_HOLDER),
            CONVERSATION to listOf(WAIT_NEWCOMER, DENY_NEWCOMER, COEXIST, SUSPEND_HOLDER, END_HOLDER),
            RECORDING to listOf(COEXIST, COEXIST, DENY_NEWCOMER, COEXIST, END_HOLDER),
            CONTINUOUS_RECOGNITION to listOf(WAIT_NEWCOMER, WAIT_NEWCOMER, COEXIST, DENY_NEWCOMER, END_HOLDER),
            AMBIENT_LISTENING to listOf(DENY_NEWCOMER, DENY_NEWCOMER, DENY_NEWCOMER, DENY_NEWCOMER, DENY_NEWCOMER),
        )
        val holders = listOf(VOICE_COMMAND, CONVERSATION, RECORDING, CONTINUOUS_RECOGNITION, AMBIENT_LISTENING)
        expected.forEach { (newcomer, row) ->
            holders.zip(row).forEach { (holder, outcome) ->
                assertEquals(outcome, AudioArbitrationPolicy.resolve(newcomer, holder), "$newcomer vs $holder")
            }
        }
        AudioSessionKind.entries.forEach {
            assertEquals(COEXIST, AudioArbitrationPolicy.resolve(PLAYBACK, it))
            assertEquals(COEXIST, AudioArbitrationPolicy.resolve(it, PLAYBACK))
        }
    }

    @Test
    fun spokenApprovalSuspendsLiveAndLiveResumesWhenApprovalEnds() {
        val arbiter = AudioSessionArbiter()
        val liveId = arbiter.grantedId(live)

        val granted = assertIs<AudioArbitration.Granted>(arbiter.request(approval))
        assertEquals(AudioSessionState.ACTIVE, granted.state)
        assertEquals(listOf(AudioSessionTransition(liveId, "GeminiLive", CONVERSATION, AudioSessionState.SUSPENDED)), granted.transitions)
        assertEquals(AudioSessionState.SUSPENDED, arbiter.state(liveId))

        val resumed = arbiter.release(granted.sessionId)
        assertEquals(listOf(AudioSessionTransition(liveId, "GeminiLive", CONVERSATION, AudioSessionState.ACTIVE)), resumed)
        assertEquals(AudioSessionState.ACTIVE, arbiter.state(liveId))
    }

    @Test
    fun liveStartedDuringASpokenApprovalWaitsForTheMicrophone() {
        val arbiter = AudioSessionArbiter()
        val approvalId = arbiter.grantedId(approval)

        val liveGrant = assertIs<AudioArbitration.Granted>(arbiter.request(live))
        assertEquals(AudioSessionState.SUSPENDED, liveGrant.state)
        assertTrue(liveGrant.transitions.isEmpty())
        assertEquals(AudioSessionState.ACTIVE, arbiter.state(approvalId))

        val transitions = arbiter.release(approvalId)
        assertEquals(AudioSessionState.ACTIVE, transitions.single().state)
        assertEquals(liveGrant.sessionId, transitions.single().sessionId)
    }

    @Test
    fun secondSpokenPromptIsDeniedWhileTheFirstIsListening() {
        val arbiter = AudioSessionArbiter()
        arbiter.grantedId(approval)

        val denied = assertIs<AudioArbitration.Denied>(arbiter.request(studio))
        assertEquals("LocalAgentApproval", denied.blockingOwner)
        assertEquals(VOICE_COMMAND, denied.blockingKind)
    }

    @Test
    fun meetingRecordingCoexistsWithLiveAndApprovalsAndIsNeverInterrupted() {
        val arbiter = AudioSessionArbiter()
        val meetingId = arbiter.grantedId(meeting)

        val liveGrant = assertIs<AudioArbitration.Granted>(arbiter.request(live))
        assertEquals(AudioSessionState.ACTIVE, liveGrant.state)
        val approvalGrant = assertIs<AudioArbitration.Granted>(arbiter.request(approval))
        assertEquals(
            listOf(AudioSessionTransition(liveGrant.sessionId, "GeminiLive", CONVERSATION, AudioSessionState.SUSPENDED)),
            approvalGrant.transitions,
        )
        assertEquals(AudioSessionState.ACTIVE, arbiter.state(meetingId))
        assertIs<AudioArbitration.Denied>(arbiter.request(AudioSessionRequest.Recording("Other")))
    }

    @Test
    fun ambientProbeYieldsToEverySessionAndIsEndedByNewcomers() {
        val arbiter = AudioSessionArbiter()
        listOf(live, approval, meeting, translator).forEach { holder ->
            val local = AudioSessionArbiter()
            local.grantedId(holder)
            assertIs<AudioArbitration.Denied>(local.request(ambient), "ambient vs ${holder.owner}")
        }

        val ambientId = arbiter.grantedId(ambient)
        val meetingGrant = assertIs<AudioArbitration.Granted>(arbiter.request(meeting))
        assertEquals(
            listOf(AudioSessionTransition(ambientId, "AmbientSpeech", AMBIENT_LISTENING, AudioSessionState.ENDED)),
            meetingGrant.transitions,
        )
        assertEquals(AudioSessionState.ENDED, arbiter.state(ambientId))
        // An ended probe is gone: releasing it later is harmless and resumes nothing.
        assertTrue(arbiter.release(ambientId).isEmpty())
    }

    @Test
    fun onlyOneVoicePluginListensAndPluginsPauseForLive() {
        val arbiter = AudioSessionArbiter()
        val translatorId = arbiter.grantedId(translator)

        val denied = assertIs<AudioArbitration.Denied>(arbiter.request(captions))
        assertEquals("HandsFreeTranslator", denied.blockingOwner)

        val liveGrant = assertIs<AudioArbitration.Granted>(arbiter.request(live))
        assertEquals(AudioSessionState.SUSPENDED, arbiter.state(translatorId))
        // A second plugin is still refused while the first one only waits.
        assertIs<AudioArbitration.Denied>(arbiter.request(captions))

        val resumed = arbiter.release(liveGrant.sessionId)
        assertEquals(listOf(translatorId), resumed.map { it.sessionId })
        assertEquals(AudioSessionState.ACTIVE, arbiter.state(translatorId))
    }

    @Test
    fun pluginStartedDuringLiveWaitsAndResumesAfterwards() {
        val arbiter = AudioSessionArbiter()
        val liveId = arbiter.grantedId(live)
        val pluginGrant = assertIs<AudioArbitration.Granted>(arbiter.request(translator))
        assertEquals(AudioSessionState.SUSPENDED, pluginGrant.state)

        assertEquals(listOf(pluginGrant.sessionId), arbiter.release(liveId).map { it.sessionId })
    }

    @Test
    fun waitingSessionsResumeHighestPriorityFirstAndLowerOnesKeepWaiting() {
        val arbiter = AudioSessionArbiter()
        val pluginId = arbiter.grantedId(translator)
        val liveId = arbiter.grantedId(live)
        val approvalId = arbiter.grantedId(approval)
        assertEquals(AudioSessionState.SUSPENDED, arbiter.state(liveId))
        assertEquals(AudioSessionState.SUSPENDED, arbiter.state(pluginId))

        val transitions = arbiter.release(approvalId)
        assertEquals(listOf(liveId to AudioSessionState.ACTIVE), transitions.map { it.sessionId to it.state })
        assertEquals(AudioSessionState.SUSPENDED, arbiter.state(pluginId))

        assertEquals(listOf(pluginId to AudioSessionState.ACTIVE), arbiter.release(liveId).map { it.sessionId to it.state })
    }

    @Test
    fun cancelledWaitingSessionIsRemovedWithoutTouchingOthers() {
        val arbiter = AudioSessionArbiter()
        val approvalId = arbiter.grantedId(approval)
        val liveGrant = assertIs<AudioArbitration.Granted>(arbiter.request(live))

        assertTrue(arbiter.release(liveGrant.sessionId).isEmpty())
        assertEquals(AudioSessionState.ENDED, arbiter.state(liveGrant.sessionId))
        assertTrue(arbiter.release(approvalId).isEmpty())
        assertTrue(arbiter.snapshot().isEmpty())
    }

    @Test
    fun playbackNeverConflictsAndIsTrackedSeparately() {
        val arbiter = AudioSessionArbiter()
        arbiter.grantedId(approval)
        val ttsGrant = assertIs<AudioArbitration.Granted>(arbiter.request(tts))
        assertEquals(AudioSessionState.ACTIVE, ttsGrant.state)
        assertTrue(ttsGrant.transitions.isEmpty())
        assertTrue(arbiter.hasActive(PLAYBACK))
        arbiter.release(ttsGrant.sessionId)
        assertTrue(!arbiter.hasActive(PLAYBACK))
    }

    @Test
    fun releasingTwiceIsANoOp() {
        val arbiter = AudioSessionArbiter()
        val id = arbiter.grantedId(meeting)
        arbiter.release(id)
        assertTrue(arbiter.release(id).isEmpty())
    }

    private fun AudioSessionArbiter.grantedId(request: AudioSessionRequest): Long =
        assertIs<AudioArbitration.Granted>(request(request)).sessionId
}
