package com.fersaiyan.cyanbridge.hil

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.localagent.TaskerExecutionBackend
import com.fersaiyan.cyanbridge.localagent.UiActionCandidateBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only Tasker parity probe for a Settings screen sampled separately by Artemis. */
@RunWith(AndroidJUnit4::class)
class TaskerSettingsArtemisParityHilTest {
    @Test fun observesSettingsControlAndMeasuresCandidateRetention() = runBlocking {
        HilTestSupport.requireOrSkip(
            InstrumentationRegistry.getArguments().getString("hil_artemis_parity") == "true",
            "Opt into reversible Settings-only Tasker observation",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        HilTestSupport.requireTaskerStack(context)
        // Launch the root screen for setup. The normal Settings launcher can
        // resume a nested page where the same label is only a heading.
        context.startActivity(Intent().setClassName("com.android.settings", "com.android.settings.Settings")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        delay(700L)
        val observation = requireNotNull(TaskerExecutionBackend.observe(context)) {
            "Tasker did not return a Settings observation"
        }
        assertEquals("com.android.settings", observation.packageName)
        val nodes = observation.screenSnapshot?.nodes.orEmpty()
        val target = "Network & internet"
        val present = nodes.any { it.text == target || it.contentDescription == target }
        val labels = nodes.map { it.text.ifBlank { it.contentDescription } }.toSet()
        assertTrue("Settings home was not observed through Tasker",
            "Search Settings" in labels && "Apps" in labels && "Internet" !in labels && "SIMs" !in labels)
        val candidates = UiActionCandidateBuilder.build(
            goal = "Navigate to Network & internet settings",
            observation = observation,
        )
        val retained = candidates.candidates.any { it.description.contains(target, ignoreCase = true) }
        // No raw personal screen text or node coordinates in the HIL log.
        println("ARTEMIS_TASKER_SETTINGS_PARITY provider=TASKER_AUTOINPUT " +
            "package=${observation.packageName} nodes=${nodes.size} " +
            "targetObserved=$present targetRetained=$retained " +
            "offered=${candidates.candidates.size}")
        assertTrue("Tasker Settings observation omitted the visible target", present)
        assertTrue("CyanBridge candidate filter dropped a visible Settings target", retained)
    }
}
