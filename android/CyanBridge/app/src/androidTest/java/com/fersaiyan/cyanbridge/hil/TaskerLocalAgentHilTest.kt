package com.fersaiyan.cyanbridge.hil

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.localagent.LocalAgentAction
import com.fersaiyan.cyanbridge.localagent.TaskerExecutionBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskerLocalAgentHilTest {
    @Test
    fun taskerObservesExecutesAndBlocksFixtureAtObservationBoundary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        HilTestSupport.requireTaskerStack(context)

        ActivityScenario.launch(HilFixtureActivity::class.java).use {
            Thread.sleep(500)

            try {
                setBlockedPackages(context, "")
                Thread.sleep(400)

                val ready = runBlocking { TaskerExecutionBackend.isReady(context) }
                HilTestSupport.requireOrSkip(ready, "Tasker Local Agent profile did not answer the readiness probe")

                val observation = runBlocking { TaskerExecutionBackend.observe(context) }
                assertNotNull("Tasker did not return a Local Agent observation", observation)
                assertTrue(
                    "Fixture marker missing from Tasker/AutoInput observation: ${observation?.screenText}",
                    observation?.screenText?.contains(HilFixtureActivity.MARKER_TEXT) == true,
                )

                val clickResult = runBlocking {
                    TaskerExecutionBackend.execute(
                        context,
                        LocalAgentAction.ClickText(HilFixtureActivity.CLICK_BUTTON_TEXT),
                    )
                }
                assertTrue("Tasker click failed: ${clickResult.detail}", clickResult.success)
                onView(withId(R.id.hil_status)).check(matches(withText("HIL_CLICK_COUNT=1")))

                // Focus is created by the test harness; the text mutation itself is executed by Tasker.
                onView(withId(R.id.hil_input)).perform(click())
                val typed = "HIL_TYPED_72941"
                val typeResult = runBlocking {
                    TaskerExecutionBackend.execute(
                        context,
                        LocalAgentAction.TypeText(typed),
                    )
                }
                assertTrue("Tasker type_text failed: ${typeResult.detail}", typeResult.success)
                onView(withId(R.id.hil_input)).check(matches(withText(typed)))

                // Privacy regression check: Tasker must reject this package before returning screen text.
                setBlockedPackages(context, context.packageName)
                Thread.sleep(500)
                val blocked = runBlocking {
                    com.fersaiyan.cyanbridge.localagent.tasker.TaskerAgentBridge.requestObservation(
                        context,
                        timeoutMs = 5_000L,
                    )
                }
                assertFalse("Blocked package unexpectedly returned a successful observation", blocked.success)
                assertTrue(
                    "Blocked-package failure did not identify the current package: ${blocked.error}",
                    blocked.error == "blocked_package:${context.packageName}",
                )
            } finally {
                setBlockedPackages(context, "")
            }
        }
    }

    private fun setBlockedPackages(context: android.content.Context, packages: String) {
        context.sendBroadcast(
            Intent(ACTION_HIL_SET_LOCALAGENT_BLOCKED)
                .setPackage(HilTestSupport.TASKER_PACKAGE)
                .putExtra("packages", packages),
        )
    }

    companion object {
        private const val ACTION_HIL_SET_LOCALAGENT_BLOCKED =
            "com.fersaiyan.cyanbridge.HIL_SET_LOCALAGENT_BLOCKED"
    }
}
