package com.fersaiyan.cyanbridge.localagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiActionCandidateBuilderTest {
    private fun obs(
        vararg nodes: LocalAgentScreenNode,
        packageName: String = "com.google.android.youtube",
        screenText: String = "YouTube",
        textSummary: String = "YouTube home",
    ) = LocalAgentObservation(
        createdAtMs = 1L,
        packageName = packageName,
        screenText = screenText,
        screenSnapshot = LocalAgentScreenSnapshot(
            packageName = packageName,
            textSummary = textSummary,
            nodes = nodes.toList(),
        ),
    )

    private fun node(i: Int, text: String, clickable: Boolean = true, editable: Boolean = false) =
        LocalAgentScreenNode(
            index = i, depth = 0, text = text, contentDescription = "", className = "Button",
            viewId = "", isClickable = clickable, isEditable = editable, isScrollable = false,
            bounds = LocalAgentNodeBounds(0, 0, 100, 50),
        )

    @Test fun `builds bounded candidates with detailed fallback`() {
        val built = UiActionCandidateBuilder.build(
            "Play the latest Linus Tech Tips video",
            obs(node(12, "Search"), node(13, "Home"), node(24, "Library")),
        )
        println("JEV_CI ui candidates=${built.candidates.map { "${it.label}=${it.description}" }}")
        assertTrue(built.candidates.size in 2..8)
        assertTrue(built.keys.contains("detailed_planner"))
        assertEquals(built.candidates.size, built.keys.size)
        assertEquals(built.candidates.size, built.nodeIndices.size)
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

        val multiStep = UiActionCandidateBuilder.extractTypeSpans(
            "Open YouTube, search for Linus Tech Tips, open a result and start playback",
        )
        assertEquals("Linus Tech Tips", multiStep.first())
    }

    @Test fun `open app candidate precedes unrelated screen nodes and keeps node identity`() {
        val built = UiActionCandidateBuilder.build(
            "Open Chrome, search for CyanBridge",
            obs(node(12, "Unrelated"), node(42, "Search")),
        )
        assertEquals("open_app", built.keys.first())
        assertTrue(built.candidates.first().description.contains("Chrome"))
        assertEquals(42, built.nodeIndices[built.keys.indexOf("click_node")])
        assertEquals("Chrome", UiActionCandidateBuilder.extractTargetApp("Open Chrome. On the test page, search."))

        val alreadyOpen = UiActionCandidateBuilder.build(
            "Open YouTube, search for Linus Tech Tips",
            obs(node(42, "Search")),
        )
        assertTrue("Do not reopen the current app", "open_app" !in alreadyOpen.keys)
    }

    @Test fun `empty observation still yields actions`() {
        val built = UiActionCandidateBuilder.build("do something", obs())
        println("JEV_CI empty-obs candidates=${built.candidates.map { it.description }}")
        assertTrue(built.candidates.isNotEmpty())
    }

    @Test fun `uses reduced AutoInput node metadata and prioritizes goal match`() {
        val reduced = listOf(
            node(1, "Home", clickable = false),
            node(2, "Search", clickable = false),
            LocalAgentScreenNode(
                index = 3,
                depth = 0,
                text = "",
                contentDescription = "",
                className = "",
                viewId = "com.google.android.youtube:id/search_edit_text",
                isClickable = false,
                isEditable = false,
                isScrollable = false,
                bounds = LocalAgentNodeBounds(50, 80, 50, 80),
            ),
        )
        val built = UiActionCandidateBuilder.build(
            "Search YouTube for Linus Tech Tips",
            obs(*reduced.toTypedArray()),
        )
        assertEquals("type_text", built.keys.first())
        assertTrue("type_text" in built.keys)
        assertEquals("Linus Tech Tips", built.typeSpans.first())
    }

    @Test fun `already visible target suppresses duplicate typing and prioritizes action label`() {
        val observation = obs(
            node(1, "CYANBRIDGE_HIL_WEB_SEARCH_72941", clickable = false),
            node(2, "Search query", clickable = false),
            node(3, "Search", clickable = false),
            LocalAgentScreenNode(
                index = 4,
                depth = 0,
                text = "",
                contentDescription = "",
                className = "",
                viewId = "search_edit_text",
                isClickable = false,
                isEditable = false,
                isScrollable = false,
                bounds = LocalAgentNodeBounds(50, 80, 50, 80),
            ),
        ).copy(screenText = "local agent architecture Search")
        val built = UiActionCandidateBuilder.build(
            "Search for 'local agent architecture' and open the first result",
            observation,
        )

        assertTrue("type_text" !in built.keys)
        assertTrue(built.candidates.first().description.startsWith("Tap \"Search\""))
    }

    @Test fun `search result outranks browser chrome and editable address bar`() {
        val observation = obs(
            node(1, "Search results", clickable = false),
            node(2, "Open the home page", clickable = true),
            node(3, "Borealis local-agent architecture — first result", clickable = false),
            LocalAgentScreenNode(
                index = 9,
                depth = 0,
                text = "127.0.0.1/search?q=local",
                contentDescription = "",
                className = "",
                viewId = "url_bar",
                isClickable = false,
                isEditable = false,
                isScrollable = false,
                bounds = LocalAgentNodeBounds(0, 0, 100, 50),
            ),
        )
        val built = UiActionCandidateBuilder.build(
            "Search for 'local agent architecture', open the first result, then summarize it",
            observation,
        )

        assertTrue("type_text" !in built.keys)
        assertTrue(built.candidates.first().description.contains("Borealis local-agent architecture"))
        assertTrue(built.candidates.none { it.description.contains("home page") })
    }

    @Test fun `article content promotes detailed grounded-answer planner`() {
        val observation = obs(
            node(1, "Borealis local-agent architecture", clickable = false),
            node(2, "Borealis uses exactly 37 amber modules. CyanBridge owns planning and safety, while Tasker executes approved UI actions.", clickable = false),
        )
        val built = UiActionCandidateBuilder.build(
            "Search for 'local agent architecture', open the first result, then finish with a concise summary of what the page says",
            observation,
        )

        assertEquals("detailed_planner", built.keys.first())
    }

    @Test fun `selected candidate maps to its exact node coordinates`() {
        val first = node(12, "Unrelated").copy(bounds = LocalAgentNodeBounds(0, 0, 100, 50))
        val search = node(42, "Search").copy(bounds = LocalAgentNodeBounds(300, 400, 500, 520))
        val observation = obs(first, search)
        val built = UiActionCandidateBuilder.build("Search YouTube for Linus Tech Tips", observation)
        val selected = built.nodeIndices.indexOf(42)

        val action = RemoteUiControlLocalAgentBrain().mapCandidateKeyToAction(
            candidateIndex = selected,
            key = built.keys[selected],
            taskState = LocalAgentTaskState(
                goal = "Search YouTube for Linus Tech Tips",
                maxSteps = 10,
                startedAtMs = 1L,
            ),
            observation = observation,
            built = built,
        )

        assertEquals(LocalAgentAction.ClickCoord(400, 460), action)
    }

    @Test fun `type candidate focuses its exact node before typing`() {
        val editable = LocalAgentScreenNode(
            index = 7,
            depth = 0,
            text = "",
            contentDescription = "",
            className = "",
            viewId = "search_edit_text",
            isClickable = false,
            isEditable = false,
            isScrollable = false,
            bounds = LocalAgentNodeBounds(120, 300, 920, 420),
        )
        val observation = obs(editable)
        val built = UiActionCandidateBuilder.build("Search YouTube for Linus Tech Tips", observation)
        val selected = built.keys.indexOf("type_text")
        val actions = RemoteUiControlLocalAgentBrain().mapCandidateKeyToActions(
            candidateIndex = selected,
            key = "type_text",
            taskState = LocalAgentTaskState(
                goal = "Search YouTube for Linus Tech Tips",
                maxSteps = 10,
                startedAtMs = 1L,
            ),
            observation = observation,
            built = built,
        )

        assertEquals(
            listOf(
                LocalAgentAction.ClickCoord(520, 360),
                LocalAgentAction.TypeText("Linus Tech Tips", null),
            ),
            actions,
        )
    }

    @Test fun `populated search form does not promote grounded-answer planner`() {
        // Live failure 2026-09-20: the typed query echoes the goal keywords,
        // which prematurely promoted detailed_planner on the search form while
        // the goal still requires opening the first result.
        val observation = obs(
            node(3, "Search news", clickable = false),
            node(4, "latest smartglasses news", clickable = false),
            LocalAgentScreenNode(
                index = 2,
                depth = 0,
                text = "",
                contentDescription = "",
                className = "",
                viewId = "query",
                isClickable = false,
                isEditable = true,
                isScrollable = false,
                bounds = LocalAgentNodeBounds(120, 300, 920, 420),
            ),
            packageName = "com.android.chrome",
            screenText = "CYANBRIDGE_HIL_NEWS_SEARCH_73551 latest smartglasses news",
            textSummary = "Search page",
        )
        val built = UiActionCandidateBuilder.build(
            "Open Chrome. On the CyanBridge HIL Search page, search for 'latest smartglasses news', " +
                "open the first result, read only the first visible article without scrolling, " +
                "then finish with a concise summary of what the page says.",
            observation,
        )

        assertTrue("generic fallback must remain", "detailed_planner" in built.keys)
        assertTrue(
            "grounded-answer planner must not lead on an unsubmitted form: ${built.keys}",
            built.keys.first() != "detailed_planner",
        )
    }
}
