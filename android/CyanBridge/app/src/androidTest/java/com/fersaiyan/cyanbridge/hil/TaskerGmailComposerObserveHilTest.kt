package com.fersaiyan.cyanbridge.hil

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.localagent.tasker.TaskerAgentBridge
import com.fersaiyan.cyanbridge.localagent.tasker.TaskerAgentContract
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, read-only boundary check with a previously opened harmless Gmail draft. */
@RunWith(AndroidJUnit4::class)
class TaskerGmailComposerObserveHilTest {
    @Test fun taskerObservesExternalGmailComposerWithoutSending() {
        HilTestSupport.requireOrSkip(
            InstrumentationRegistry.getArguments().getString("hil_gmail_observe") == "true",
            "Open a Gmail SENDTO draft and opt in to the read-only observer check",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        HilTestSupport.requireTaskerStack(context)
        val response = runBlocking {
            TaskerAgentBridge.requestObservation(context, timeoutMs = 32_000L)
        }
        assertTrue("Tasker did not return a Gmail composer observation: ${response.error}", response.success)
        val observation = TaskerAgentContract.observationFromJson(response.payload.orEmpty())
        assertEquals(HilTestSupport.GMAIL_PACKAGE, observation.packageName)
        assertTrue("Gmail composer marker missing from Tasker observation",
            observation.screenText.orEmpty().contains("CB-HIL-READONLY-QUERY"))
    }
}
