package com.fersaiyan.cyanbridge.localagent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiActionDecisionStateTest {
    @Test fun `bounded state keeps the current control without echoing the screen dump`() {
        val nodes = listOf("Search", "Home", "Voice Search", "Linus Tech Tips — first result").mapIndexed { index, label ->
            LocalAgentScreenNode(
                index = index, depth = 0, text = label, contentDescription = "", className = "Button",
                viewId = "", isClickable = true, isEditable = false, isScrollable = false,
                bounds = LocalAgentNodeBounds(0, 0, 100, 50),
            )
        }
        val observation = LocalAgentObservation(
            createdAtMs = 1L, packageName = "com.google.android.youtube",
            screenText = "noise ".repeat(2_000),
            screenSnapshot = LocalAgentScreenSnapshot(
                packageName = "com.google.android.youtube", textSummary = "noise ".repeat(2_000), nodes = nodes,
            ),
        )
        val state = UiActionDecisionState.build(
            goal = "Open YouTube, search for Linus Tech Tips, open the first result and start playback",
            observation = observation, fallbackScreen = "noise ".repeat(2_000), previousResult = "search opened",
        )
        assertTrue(state.length <= 600)
        assertTrue(state.contains("Linus Tech Tips — first result"))
        assertTrue(state.contains("search opened"))
        assertFalse(state.contains("noise noise noise"))
    }
}
