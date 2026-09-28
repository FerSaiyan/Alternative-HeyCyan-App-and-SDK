package com.fersaiyan.cyanbridge.hil

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.ai.decision.DecisionCandidate
import com.fersaiyan.cyanbridge.ai.decision.EmbeddingDecisionEngine
import com.fersaiyan.cyanbridge.localmodels.engine.LlamaCppTextEmbeddingEngine
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Opt-in probe: does the Gemma gate separate PRODUCTION candidate text
 * (with "(node N)" suffixes and quoted labels) and does sanitizing help?
 *
 * Each case runs twice: raw descriptions vs sanitized (node suffix + quotes
 * stripped). Reports top/margin/accepted for both. Model path supplied by the
 * host runner; nothing is committed.
 */
@RunWith(AndroidJUnit4::class)
class RealProductionDescriptionProbe {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    data class Case(
        val id: String,
        val state: String,
        val candidates: List<String>,
        val expected: Int,
    )

    private val cases = listOf(
        Case(
            id = "yt_home_search",
            state = "Phone automation goal: Play the latest Linus Tech Tips video on YouTube.\nCurrent screen:\nYouTube Home",
            candidates = listOf(
                "Tap \"Search\" (node 4)",
                "Tap \"Go to channel Theo\" (node 15)",
                "Tap \"YouTube Premium\" (node 0)",
                "Tap \"Sponsored video\" (node 12)",
                "Scroll down to reveal more content",
                "Press back",
                "Use the detailed planner for another action or a grounded final answer",
            ),
            expected = 0,
        ),
        Case(
            id = "yt_search_type",
            state = "Phone automation goal: Search for 'Linus Tech Tips'.\nCurrent screen:\nYouTube search field is focused",
            candidates = listOf(
                "Focus and type into \"Search YouTube\" (node 2)",
                "Tap \"Voice search\" (node 0)",
                "Tap \"Navigate up\" (node 1)",
                "Scroll down to reveal more content",
                "Press back",
                "Use the detailed planner for another action or a grounded final answer",
            ),
            expected = 0,
        ),
        Case(
            id = "yt_results_first",
            state = "Phone automation goal: Open the first Linus Tech Tips result.\nCurrent screen:\nSearch results: Linus Tech Tips New GPU Review; Relaxing music mix",
            candidates = listOf(
                "Tap \"Linus Tech Tips New GPU Review\" (node 21)",
                "Tap \"Relaxing music mix\" (node 22)",
                "Scroll down for more results",
                "Use the detailed planner for another action or a grounded final answer",
            ),
            expected = 0,
        ),
    )

    @Test
    fun probeRawVsSanitizedProductionDescriptions() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val modelFile = File(args.getString("jev_embedding_gemma_path").orEmpty())
        require(modelFile.isFile) { "Missing Gemma GGUF: ${modelFile.absolutePath}" }
        LlamaCppTextEmbeddingEngine(context, modelFile).use { engine ->
            engine.embed("task: classification | query: warmup")
            val labels = listOf("A", "B", "C", "D", "E", "F", "G", "H")
            for (case in cases) {
                val decided = labels.take(case.candidates.size)
                    .mapIndexed { i, l -> DecisionCandidate(l, case.candidates[i]) }
                for ((variant, candidateText) in listOf(
                    "raw" to { c: DecisionCandidate -> "task: classification | query: ${c.description}" },
                    "sanitized" to { c: DecisionCandidate ->
                        "task: classification | query: " + c.description
                            .replace(Regex("""\s*\(node \d+\)\s*$"""), "")
                            .replace("\"", "")
                    },
                )) {
                    val gate = EmbeddingDecisionEngine(
                        embedder = engine,
                        queryText = { "task: classification | query: ${it.take(1000)}" },
                        candidateText = candidateText,
                        minimumTopScore = -1f,
                        minimumMargin = 0f,
                    )
                    val out = gate.choose(case.state, decided, "prod-probe")
                    println(
                        "JEV_PROBE id=${case.id} variant=$variant selected=${out.label} " +
                            "expected=${decided[case.expected].label} topScore=${out.topScore} " +
                            "margin=${out.margin} raw='${out.rawOutput.take(300)}'",
                    )
                }
            }
            println("JEV_PROBE_DONE cases=${cases.size}")
        }
    }
}
