package com.fersaiyan.cyanbridge.hil

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.localagent.LocalAgentAction
import com.fersaiyan.cyanbridge.localagent.LocalAgentApprovalVoiceSession
import com.fersaiyan.cyanbridge.localagent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.localagent.actions.LocalAgentApprovalClarifier
import com.fersaiyan.cyanbridge.localagent.actions.LocalAgentApprovalCoordinator
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HilFixtureSmokeTest {
    @Test
    fun fixtureExposesStableSelectors() {
        ActivityScenario.launch(HilFixtureActivity::class.java).use {
            onView(withId(R.id.hil_marker))
                .check(matches(withText(HilFixtureActivity.MARKER_TEXT)))
                .check(matches(isDisplayed()))
            onView(withId(R.id.hil_status))
                .check(matches(withText(HilFixtureActivity.STATUS_READY)))
            onView(withId(R.id.hil_click_button))
                .check(matches(withText(HilFixtureActivity.CLICK_BUTTON_TEXT)))
            onView(withId(R.id.hil_input))
                .check(matches(withHint(HilFixtureActivity.INPUT_HINT)))
        }
    }

    @Test
    fun ambiguousVoiceApprovalGetsClarifiedAndKeepsWaiting() = runBlocking {
        HilTestSupport.requireOrSkip(
            HilTestSupport.hardwareRequired,
            "Conversational voice approval runs in the required Tasker HIL layer, not the lightweight emulator smoke layer",
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val action = LocalAgentAction.SendEmail(
            to = "voice-approval-hil@example.invalid",
            subject = "CyanBridge voice approval HIL",
            body = "This message is never sent. It only validates hands-free confirmation.",
        )
        val goal = "Prepare a test email, but do not send it without spoken confirmation."

        LocalAgentPrefs.clearLastApprovalVoicePrompt(context)
        LocalAgentPrefs.clearLastApprovalVoiceReply(context)
        val statuses = mutableListOf<String>()

        LocalAgentApprovalVoiceSession(context) { status ->
            synchronized(statuses) { statuses += status }
        }.use { session ->
            val initialPrompt = LocalAgentApprovalClarifier.initialPrompt(action)
            val initialReply = async {
                session.askAndListen(initialPrompt, timeoutMs = 15_000L)
            }
            delay(500L)
            session.submitExternalReply("maybe")

            assertEquals("maybe", initialReply.await())
            assertEquals(
                LocalAgentApprovalCoordinator.ReplyKind.UNKNOWN,
                LocalAgentApprovalCoordinator.classifyReply("maybe"),
            )
            assertEquals("maybe", LocalAgentPrefs.getLastApprovalVoiceReply(context))
            assertTrue(
                "Initial spoken confirmation did not expose the pending email",
                LocalAgentPrefs.getLastApprovalVoicePrompt(context).contains("voice-approval-hil@example.invalid"),
            )

            val clarification = withTimeoutOrNull(60_000L) {
                LocalAgentApprovalClarifier.clarificationPrompt(
                    context = context,
                    originalGoal = goal,
                    action = action,
                    ambiguousReply = "maybe",
                )
            } ?: LocalAgentApprovalClarifier.ClarificationResult(
                text = LocalAgentApprovalClarifier.fallbackClarification(action, "maybe"),
                usedModel = false,
                detail = "fallback:hil_clarification_timeout",
            )

            println(
                "CYANBRIDGE_VOICE_APPROVAL_HIL clarification_source=" +
                    (if (clarification.usedModel) "model" else "fallback") +
                    " detail=${clarification.detail} text=${clarification.text}",
            )
            assertNotEquals(initialPrompt, clarification.text)
            val clarificationLower = clarification.text.lowercase()
            assertTrue(
                "Clarification must preserve an explicit yes/no choice: ${clarification.text}",
                clarificationLower.contains("yes") && clarificationLower.contains("no"),
            )

            val clarificationReply = async {
                session.askAndListen(clarification.text, timeoutMs = 15_000L)
            }
            delay(500L)
            session.submitExternalReply("no")

            assertEquals("no", clarificationReply.await())
            assertEquals(
                LocalAgentApprovalCoordinator.ReplyKind.REJECT,
                LocalAgentApprovalCoordinator.classifyReply("no"),
            )
            assertEquals("no", LocalAgentPrefs.getLastApprovalVoiceReply(context))
            assertEquals(clarification.text, LocalAgentPrefs.getLastApprovalVoicePrompt(context))
        }

        val statusSnapshot = synchronized(statuses) { statuses.toList() }
        assertTrue(
            "Voice session never entered its spoken-prompt state: $statusSnapshot",
            statusSnapshot.any { it.contains("Speaking voice confirmation", ignoreCase = true) },
        )
        assertTrue(
            "Voice session never entered its wait-for-answer state: $statusSnapshot",
            statusSnapshot.any { it.contains("Listening for confirmation", ignoreCase = true) },
        )
    }
}
