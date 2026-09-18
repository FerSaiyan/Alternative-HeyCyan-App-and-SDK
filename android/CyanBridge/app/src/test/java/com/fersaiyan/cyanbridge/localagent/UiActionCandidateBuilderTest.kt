package com.fersaiyan.cyanbridge.localagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiActionCandidateBuilderTest {
    private fun obs(vararg nodes: LocalAgentScreenNode) = LocalAgentObservation(
        createdAtMs = 1L,
        packageName = "com.google.android.youtube",
        screenText = "YouTube",
        screenSnapshot = LocalAgentScreenSnapshot(
            packageName = "com.google.android.youtube",
            textSummary = "YouTube home",
            nodes = nodes.toList(),
        ),
    )

    private fun node(i: Int, text: String, clickable: Boolean = true, editable: Boolean = false) =
        LocalAgentScreenNode(
            index = i, depth = 0, text = text, contentDescription = "", className = "Button",
            viewId = "", isClickable = clickable, isEditable = editable, isScrollable = false,
            bounds = LocalAgentNodeBounds(0, 0, 100, 50),
        )

    @Test fun `builds bounded candidates with finish`() {
        val built = UiActionCandidateBuilder.build(
            "Play the latest Linus Tech Tips video",
            obs(node(12, "Search"), node(13, "Home"), node(24, "Library")),
        )
        println("JEV_CI ui candidates=${built.candidates.map { "${it.label}=${it.description}" }}")
        assertTrue(built.candidates.size in 2..8)
        assertTrue(built.candidates.any { it.description.contains("Finish", ignoreCase = true) })
        assertEquals(built.candidates.size, built.keys.size)
        assertEquals(listOf("A", "B", "C", "D", "E", "F", "G", "H").take(built.candidates.size), built.candidates.map { it.label })
    }

    @Test fun `caps at eight candidates`() {
        val nodes = (0 until 20).map { node(it, "Item $it") }
        val built = UiActionCandidateBuilder.build("do things", obs(*nodes.toTypedArray()))
        println("JEV_CI capped size=${built.candidates.size}")
        assertTrue(built.candidates.size <= 8)
    }

    @Test fun `extracts type spans without generation`() {
        val spans = UiActionCandidateBuilder.extractTypeSpans("Search YouTube for Mega Lucario deck")
        println("JEV_CI spans=$spans")
        assertTrue(spans.any { it.contains("Mega Lucario deck") })
        val quoted = UiActionCandidateBuilder.extractTypeSpans("Type \"hello world\" into search")
        assertTrue(quoted.any { it.contains("hello world") })
    }

    @Test fun `empty observation still yields actions`() {
        val built = UiActionCandidateBuilder.build("do something", obs())
        println("JEV_CI empty-obs candidates=${built.candidates.map { it.description }}")
        assertTrue(built.candidates.isNotEmpty())
    }
}
