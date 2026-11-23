package com.fersaiyan.cyanbridge.hil

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.SamplerConfig
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What the download-time GPU preparation (ModelDownloadForegroundService.prepareForThisPhone) saves on the first
 * question. Loads one model with the app's GPU configuration (GPU text + vision, CPU audio) three times:
 * 1. cold: empty cache dir A, model file not in the page cache yet;
 * 2. fresh: empty cache dir B (page cache now warm, nothing compiled cached);
 * 3. warm: cache dir A again (what the user's first question gets after the preparation).
 * 2 vs 3 isolates the engine cache. Each run also times a short first reply. Lists the cache files and sizes.
 * Not part of CI: skips unless gw_model points at a file. gw_context (default 4096), gw_out (results JSONL).
 */
@RunWith(AndroidJUnit4::class)
class LiteRtGpuWarmCacheTest {
    @OptIn(ExperimentalApi::class)
    @Test fun gpuWarmCache() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val stageDir = File(context.getExternalFilesDir(null), "litert-eval-in").apply { mkdirs() }
        val source = args.getString("gw_model").orEmpty()
        val model = if (source.startsWith("/data/local/tmp/")) {
            File(stageDir, File(source).name).also { dst ->
                if (!dst.isFile) {
                    instrumentation.uiAutomation.executeShellCommand("cp $source ${dst.absolutePath}").use { pfd ->
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
                    }
                }
            }
        } else {
            File(source)
        }
        val out = File(args.getString("gw_out") ?: File(context.getExternalFilesDir(null), "litert-eval/gpu_warm_cache.jsonl").path)
        out.parentFile?.mkdirs()
        val soc = if (android.os.Build.VERSION.SDK_INT >= 31) "${android.os.Build.SOC_MANUFACTURER}|${android.os.Build.SOC_MODEL}" else "?"
        out.appendText(JSONObject(mapOf("event" to "device", "model" to android.os.Build.MODEL, "soc" to soc)).toString() + "\n")
        assumeTrue("Requires gw_model", model.isFile)
        val maxTokens = args.getString("gw_context")?.toInt() ?: 4096
        val dirA = File(context.cacheDir, "gw-a").apply { deleteRecursively(); mkdirs() }
        val dirB = File(context.cacheDir, "gw-b").apply { deleteRecursively(); mkdirs() }
        for ((label, dir) in listOf("cold" to dirA, "fresh" to dirB, "warm" to dirA)) {
            val row = runCatching {
                val t0 = System.nanoTime()
                val engine = Engine(
                    EngineConfig(
                        modelPath = model.path,
                        backend = Backend.GPU(),
                        visionBackend = Backend.GPU(),
                        audioBackend = Backend.CPU(4),
                        maxNumTokens = maxTokens,
                        cacheDir = dir.path,
                    ),
                ).also { it.initialize() }
                val initS = (System.nanoTime() - t0) / 1e9
                val t1 = System.nanoTime()
                val reply = engine.createConversation(
                    ConversationConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0, seed = 0)),
                ).use { conversation -> conversation.sendMessage("Reply with only: OK", maxOutputToken = 8).toString() }
                val replyS = (System.nanoTime() - t1) / 1e9
                engine.close()
                mapOf("event" to "run", "label" to label, "init_s" to initS, "first_reply_s" to replyS, "reply" to reply.take(40))
            }.getOrElse { mapOf("event" to "run", "label" to label, "error" to it.toString()) }
            println("GW_RUN $row")
            out.appendText(JSONObject(row).toString() + "\n")
        }
        for (dir in listOf(dirA, dirB)) {
            val files = dir.walkTopDown().filter { it.isFile }.map { it.name to it.length() }.toList()
            out.appendText(JSONObject(mapOf("event" to "cache", "dir" to dir.name, "files" to files.toMap())).toString() + "\n")
            println("GW_CACHE ${dir.name} $files")
            dir.deleteRecursively()
        }
    }
}
