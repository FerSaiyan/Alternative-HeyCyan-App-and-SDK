package com.fersaiyan.cyanbridge.ai.questions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.ai.image.GlassesQuestionTestHooks
import com.fersaiyan.cyanbridge.audio.AudioSessionCoordinator
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionRequest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QuestionMicrophoneTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun reset() {
        GlassesQuestionTestHooks.reset()
    }

    @Test
    fun anotherSpokenPromptRefusesTheQuestionBeforeTheCue() = runTest {
        val events = mutableListOf<String>()
        GlassesQuestionTestHooks.events = { event, detail -> events += "$event $detail" }
        val enrollment = AudioSessionCoordinator.tryOpen(context, AudioSessionRequest.VoiceCapture("VoiceEnrollment"))!!
        try {
            val microphone = QuestionMicrophone(
                context = context,
                speaker = AssistantSpeaker(context),
                scope = { this },
                languageTag = { "en" },
                deviceAudio = { null },
                onQuestionRecorded = {},
                audioGoesToModel = { true },
            )
            val captured = microphone.capture(timeoutMs = 5_000L)
            assertEquals("The microphone is busy; please ask again", captured.error)
            assertFalse(captured.heardSpeech)
            assertTrue("no cue is spoken", events.isEmpty())
            assertEquals(listOf("VoiceEnrollment"), AudioSessionCoordinator.snapshot().map { it.owner })
        } finally {
            enrollment.close()
        }
    }
}
