package com.fersaiyan.cyanbridge.hil

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionDefaults
import com.fersaiyan.cyanbridge.localmodels.engine.EngineLoadConfig
import com.fersaiyan.cyanbridge.localmodels.engine.GenerationConfig
import com.fersaiyan.cyanbridge.localmodels.engine.LiteRtLocalInferenceEngine
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The spoken-decision system-prompt cache on a real phone: the app's LiteRtLocalInferenceEngine answers the
 * glasses_questions recordings twice on one loaded model:
 * - "fresh": the 2.5.0 layout (time at the end of the system turn), cache off, a fresh conversation per question;
 * - "split": the 2.5.1 layout (time at the start of the user turn), cache off;
 * - "cached": the 2.5.1 layout, cache on (primed root + clone per question; the engine uses it on CPU only).
 * Records the route tag, time to first token and total time per question; the routes must match the expected tags
 * as well as the fresh layout does. Not part of CI: skips unless pc_model points at a .litertlm file.
 * Arguments: pc_model, pc_backend (GPU | CPU, default GPU), pc_out.
 */
@RunWith(AndroidJUnit4::class)
class PromptCacheRouteTest {
    private val expected = mapOf(
        "capital_city" to "answer", "color_of_object" to "camera", "next_holiday" to "answer", "play_song" to "phone",
        "read_sign" to "camera", "talk_to_other" to "ignore", "tell_joke" to "answer", "weather_today" to "search",
        "memory_allergy" to "answer", "memory_dentist" to "answer", "memory_dog" to "answer", "memory_wife" to "answer",
    )

    @Test fun promptCacheRoutes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val source = args.getString("pc_model").orEmpty()
        val stageDir = File(context.getExternalFilesDir(null), "litert-eval-in").apply { mkdirs() }
        val model = if (source.startsWith("/data/local/tmp/")) File(stageDir, File(source).name).also { dst ->
            if (!dst.isFile) instrumentation.uiAutomation.executeShellCommand("cp $source ${dst.absolutePath}").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { s -> s.readBytes() }
            }
        } else File(source)
        assumeTrue("Requires pc_model", model.isFile)
        val out = File(args.getString("pc_out") ?: File(context.getExternalFilesDir(null), "litert-eval/prompt_cache.jsonl").path)
        out.parentFile?.mkdirs()
        fun log(map: Map<String, Any?>) {
            val line = JSONObject(map).toString()
            println("PC $line")
            out.appendText(line + "\n")
        }

        // The recordings, copied out of the test APK's assets.
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val clips = assets.list("glasses_questions").orEmpty().filter { it.endsWith(".wav") }.mapNotNull { name ->
            val stem = name.substringAfter('_').substringBefore("__")
            val want = expected[stem] ?: return@mapNotNull null
            val file = File(context.cacheDir, "pc_$name")
            assets.open("glasses_questions/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
            Triple(name, file, want)
        }

        val backend = if (args.getString("pc_backend") == "CPU") LocalComputeBackend.CPU else LocalComputeBackend.GPU
        val engine = LiteRtLocalInferenceEngine(context)
        val load = engine.loadModel(model.path, EngineLoadConfig(4096, 4, backend, -1, speculativeDecoding = false))
        log(mapOf("event" to "loaded", "backend" to load.activeBackend.name, "model" to model.name, "device" to android.os.Build.MODEL))

        val routes = ImageQuestionDefaults.spokenQuestionInstruction("en") + "\n\n" + ImageQuestionDefaults.SPOKEN_DECISION_ROUTES
        val clock = ImageQuestionDefaults.currentContextInstruction()
        for (layout in listOf("fresh", "split", "cached")) {
            LiteRtLocalInferenceEngine.promptCacheEnabled = layout == "cached"
            var hits = 0
            for ((name, file, want) in clips) {
                val started = System.nanoTime()
                var firstToken = 0L
                val result = runCatching {
                    engine.generate(
                        GenerationConfig(
                            prompt = "", temperature = 0.0, topP = 1.0, topK = 1, maxTokens = 24,
                            repetitionPenalty = 1.0, seed = 0, structuredJson = false, audioPath = file.path,
                            systemInstruction = if (layout == "fresh") "$routes\n\n$clock" else routes,
                            turnContext = if (layout == "fresh") null else clock,
                        ),
                    ) { if (firstToken == 0L && it.isNotEmpty()) firstToken = System.nanoTime() }
                }
                val text = result.getOrNull()?.text.orEmpty()
                val tag = Regex("^\\s*\\[\\s*([a-z]+)\\s*]", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.lowercase()
                if (tag == want) hits++
                log(mapOf(
                    "event" to "q", "layout" to layout, "clip" to name, "expected" to want, "tag" to tag,
                    "ttft_s" to if (firstToken > 0) (firstToken - started) / 1e9 else null,
                    "total_s" to (System.nanoTime() - started) / 1e9, "reply" to text.take(80),
                    "error" to result.exceptionOrNull()?.toString(),
                ))
            }
            log(mapOf("event" to "summary", "layout" to layout, "hits" to hits, "n" to clips.size))
        }
        LiteRtLocalInferenceEngine.promptCacheEnabled = true
        engine.unloadModel()
    }
}
