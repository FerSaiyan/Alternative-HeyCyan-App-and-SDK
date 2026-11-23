package com.fersaiyan.cyanbridge.hil

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * Where the slow first spoken answer after "Optimizing for this phone" comes from (2026-11-23: 6.0 s on the Tab S10+
 * vs ~1.2 s warm). Loads: cold (fills the engine cache, like the optimize step), then reload + two spoken questions,
 * then reload + one question, each load in the same engine object. If the second reload's first question is fast,
 * the cost is one-time (prime audio in the optimize step); if slow, it is paid after every load. Also logs the
 * engine cache files. Not part of CI: skips unless fa_model points at a .litertlm file.
 */
@RunWith(AndroidJUnit4::class)
class FirstAudioLatencyTest {
    @Test fun firstAudioLatency() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val source = args.getString("fa_model").orEmpty()
        val external = checkNotNull(context.getExternalFilesDir(null))
        val stageDir = File(external, "litert-eval-in").apply { mkdirs() }
        val model = if (source.startsWith("/data/local/tmp/")) File(stageDir, File(source).name).also { dst ->
            if (!dst.isFile) instrumentation.uiAutomation.executeShellCommand("cp $source ${dst.absolutePath}").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { s -> s.readBytes() }
            }
        } else File(source)
        assumeTrue("Requires fa_model", model.isFile)
        val out = File(args.getString("fa_out") ?: File(external, "litert-eval/first_audio.jsonl").path)
        out.parentFile?.mkdirs()
        fun log(map: Map<String, Any?>) {
            val line = JSONObject(map).toString()
            println("FA $line")
            out.appendText(line + "\n")
        }
        val clips = listOf("en_capital_city__piper-lessac.wav", "en_tell_joke__piper-lessac.wav").map { name ->
            File(context.cacheDir, "fa_$name").also { f ->
                instrumentation.context.assets.open("glasses_questions/$name").use { i -> f.outputStream().use { i.copyTo(it) } }
            }
        }
        val engine = LiteRtLocalInferenceEngine(context)
        val config = EngineLoadConfig(4096, 4, LocalComputeBackend.GPU, -1, speculativeDecoding = false)
        suspend fun load(label: String) {
            val t = System.nanoTime()
            val result = engine.loadModel(model.path, config)
            log(mapOf("event" to "load", "label" to label, "s" to (System.nanoTime() - t) / 1e9, "backend" to result.activeBackend.name))
        }
        suspend fun ask(label: String, clip: File) {
            val t = System.nanoTime()
            var first = 0L
            val reply = engine.generate(
                GenerationConfig(
                    prompt = "", temperature = 0.0, topP = 1.0, topK = 1, maxTokens = 24, repetitionPenalty = 1.0, seed = 0,
                    structuredJson = false, audioPath = clip.path,
                    systemInstruction = "Answer the spoken question in one short sentence.",
                ),
            ) { if (first == 0L && it.isNotEmpty()) first = System.nanoTime() }
            log(mapOf("event" to "ask", "label" to label, "ttft_s" to (first - t) / 1e9, "total_s" to (System.nanoTime() - t) / 1e9, "reply" to reply.text.take(80)))
        }
        val warm = args.getString("fa_warm") != "false"
        load("cold")
        // As the download's "Optimizing" step does; the engine also warms audio after each load in the background.
        if (warm) engine.warmUpAudio()
        engine.unloadModel()
        load("reload1")
        // The preload starts at the wake cue; the question arrives after the user spoke (~4 s).
        if (warm) kotlinx.coroutines.delay(4_000L)
        ask("reload1_q1", clips[0])
        ask("reload1_q2", clips[1])
        engine.unloadModel()
        load("reload2")
        if (warm) kotlinx.coroutines.delay(4_000L)
        ask("reload2_q1", clips[0])
        ask("reload2_q2", clips[1])
        engine.unloadModel()
        val cache = context.cacheDir.listFiles().orEmpty().filter { it.name.contains("cache") }.associate { it.name to it.length() }
        log(mapOf("event" to "cache", "files" to cache.toString()))
    }
}
