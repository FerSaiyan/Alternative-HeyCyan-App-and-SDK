package com.fersaiyan.cyanbridge.hil

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.localmodels.engine.LlamaCppTextEmbeddingEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sqrt

/**
 * Opt-in real-model benchmark over the shared CyanBridge mobile-action corpus.
 * The host runner parses JEV_CAL_RESULT lines and performs threshold fitting;
 * this device layer only performs real CPU inference and reports raw scores.
 */
@RunWith(AndroidJUnit4::class)
class RealDecisionCalibrationEmulatorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun benchmarkRealEmbeddingModelsAgainstMobileActionCorpus() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val dataset = File(args.getString("jev_calibration_dataset_path").orEmpty())
        require(dataset.isFile) { "Missing calibration dataset: ${dataset.absolutePath}" }
        val maxCases = args.getString("jev_calibration_max_cases")?.toIntOrNull()
        val cases = dataset.readLines()
            .filter { it.isNotBlank() }
            .map(::JSONObject)
            .let { allCases -> if (maxCases == null) allCases else allCases.take(maxCases) }
        val models = listOf(
            ModelSpec(
                name = "EmbeddingGemma-300M-Q8_0",
                file = File(args.getString("jev_embedding_gemma_path").orEmpty()),
                gemma = true,
            ),
            ModelSpec(
                name = "Qwen3-Embedding-0.6B-Q8_0",
                file = File(args.getString("jev_embedding_qwen_path").orEmpty()),
                gemma = false,
            ),
        )
        models.forEach { spec ->
            require(spec.file.isFile) { "Missing ${spec.name}: ${spec.file.absolutePath}" }
            println("JEV_CAL_MODEL_START name=${spec.name} bytes=${spec.file.length()} cases=${cases.size}")
            LlamaCppTextEmbeddingEngine(context, spec.file, contextSize = 2048, cpuThreads = 4).use { engine ->
                // Warm model loading separately from per-decision latency.
                val warmStarted = System.nanoTime()
                val warm = engine.embed(formatQuery(spec, "warmup"))
                val warmMs = (System.nanoTime() - warmStarted) / 1_000_000L
                val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                println(
                    "JEV_CAL_MODEL_READY name=${spec.name} dim=${warm.dimension} warmupMs=$warmMs " +
                        "totalPssKb=${memory.totalPss} nativePssKb=${memory.nativePss}",
                )

                cases.forEachIndexed { index, item ->
                    val expected = item.getJSONObject("expected").getString("candidateId")
                    if (expected == "policy_block") {
                        println(
                            "JEV_CAL_RESULT " + JSONObject()
                                .put("model", spec.name)
                                .put("id", item.getString("id"))
                                .put("split", item.getString("split"))
                                .put("locale", item.getString("locale"))
                                .put("expected", expected)
                                .put("policyBoundary", true),
                        )
                        return@forEachIndexed
                    }
                    // Tasker/CyanBridge has already converted the observation into
                    // the bounded candidates below. Repeating app/screen labels in
                    // the retrieval query can swamp the goal (for example,
                    // "YouTube Home" spuriously favors Tap Home over Search).
                    val queryResult = engine.embed(formatQuery(spec, item.getString("goal")))
                    val candidates = item.getJSONArray("candidates")
                    val scores = ArrayList<Pair<String, Float>>(candidates.length())
                    var candidateMs = 0L
                    for (candidateIndex in 0 until candidates.length()) {
                        val candidate = candidates.getJSONObject(candidateIndex)
                        val result = engine.embed(formatCandidate(spec, candidate.getString("description")))
                        candidateMs += result.elapsedMs
                        scores += candidate.getString("id") to cosine(queryResult.vector, result.vector)
                    }
                    val ranked = scores.sortedWith(compareByDescending<Pair<String, Float>> { it.second }.thenBy { it.first })
                    val selected = ranked.first()
                    val second = ranked[1]
                    val output = JSONObject()
                        .put("model", spec.name)
                        .put("id", item.getString("id"))
                        .put("split", item.getString("split"))
                        .put("locale", item.getString("locale"))
                        .put("expected", expected)
                        .put("selected", selected.first)
                        .put("topScore", selected.second.toDouble())
                        .put("margin", (selected.second - second.second).toDouble())
                        .put("queryMs", queryResult.elapsedMs)
                        .put("candidateMs", candidateMs)
                        .put("coldDecisionMs", queryResult.elapsedMs + candidateMs)
                        .put("candidateCount", candidates.length())
                        .put("policyBoundary", false)
                    println("JEV_CAL_RESULT $output")
                    if ((index + 1) % 20 == 0) {
                        println("JEV_CAL_PROGRESS name=${spec.name} cases=${index + 1}/${cases.size}")
                    }
                }
            }
            println("JEV_CAL_MODEL_END name=${spec.name}")
        }
    }

    private fun formatQuery(spec: ModelSpec, text: String): String = if (spec.gemma) {
        "task: classification | query: $text"
    } else {
        "Instruct: Given a mobile task, retrieve the one safe next action from bounded Tasker-observed candidates. " +
            "Match requests across languages; use the detailed planner when no candidate is sufficient.\n" +
            "Query:$text"
    }

    private fun formatCandidate(spec: ModelSpec, description: String): String = if (spec.gemma) {
        "task: classification | query: $description"
    } else {
        description
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Embedding dimension mismatch ${a.size} vs ${b.size}" }
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (index in a.indices) {
            dot += a[index].toDouble() * b[index].toDouble()
            aa += a[index].toDouble() * a[index].toDouble()
            bb += b[index].toDouble() * b[index].toDouble()
        }
        return (dot / (sqrt(aa) * sqrt(bb))).toFloat()
    }

    private data class ModelSpec(val name: String, val file: File, val gemma: Boolean)
}
