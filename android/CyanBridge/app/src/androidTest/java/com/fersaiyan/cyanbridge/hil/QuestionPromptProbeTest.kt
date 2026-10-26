package com.fersaiyan.cyanbridge.hil

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionDefaults
import com.fersaiyan.cyanbridge.localmodels.settings.LocalGenerationSettings
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Which prompt layout lets Gemma 4 answer a spoken question (optionally with a glasses photo) without
 * rewriting the question first? Drives the LiteRT Engine directly, one fresh conversation per
 * question and variant, greedy, 64 output tokens:
 *
 *   base   the app today: default system prompt; user turn = audio + "Answer the question spoken in…"
 *   qline  the app's system prompt; user turn = audio + "write Q: <question>, then answer" (reference)
 *   g      glasses system prompt (the user speaks; a photo may come along); user turn = audio only
 *   gtag   same system prompt; user turn = "Voice message:" + audio (Google's label-before-clip layout)
 *   route  glasses system prompt + route labels A–E first; user turn = audio only
 *   route2 glasses system prompt + bracketed route words ([answer], [camera], [search], [phone],
 *          [ignore]) first, with one text example per route; user turn = audio only
 *
 * Each variant runs without and with a photo ("+img": the photo comes first in the user turn; visual
 * questions get their own photo, others an unrelated one, as on an image-mapped wake).
 *
 * Args: -e qp_model <.litertlm> -e qp_fixtures <dir> -e qp_questions <jsonl> (repeatable as
 * comma-separated pairs: qp_fixtures2/qp_questions2 for extra catalogs), -e qp_backend GPU|NPU|CPU,
 * optional -e qp_langs en,pt-BR -e qp_classes visual,plain -e qp_per_class 1 -e qp_variants
 * base,qline,g,gtag,route -e qp_images both|no|only -e qp_audio CPU|GPU -e qp_out <jsonl>.
 */
@RunWith(AndroidJUnit4::class)
class QuestionPromptProbeTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @OptIn(ExperimentalApi::class)
    @Test
    fun promptLayouts() = runBlocking {
        val modelPath = args.getString("qp_model").orEmpty()
        assumeTrue("pass -e qp_model", File(modelPath).isFile)
        val out = File(args.getString("qp_out") ?: File(context.getExternalFilesDir(null), "question-prompts.jsonl").path)
        val langs = (args.getString("qp_langs") ?: "en,pt-BR,de,zh-CN").split(',')
        val classes = (args.getString("qp_classes") ?: "visual,plain,web,time,action,ignore").split(',')
        val perClass = args.getString("qp_per_class")?.toInt() ?: 1
        val variants = (args.getString("qp_variants") ?: "base,qline,g,gtag,route").split(',')
        val images = args.getString("qp_images") ?: "both"

        // Questions from one or more fixture catalogs (dir with manifest.json + audio/, images/).
        data class Q(val row: JSONObject, val audio: File, val image: File?)
        val catalogs = listOfNotNull(
            args.getString("qp_fixtures")?.let { it to args.getString("qp_questions").orEmpty() },
            args.getString("qp_fixtures2")?.let { it to args.getString("qp_questions2").orEmpty() },
        )
        val all = ArrayList<Q>()
        var unrelatedPhoto: File? = null
        for ((dir, questions) in catalogs) {
            val fixtures = File(dir)
            val clips = JSONObject(File(fixtures, "manifest.json").readText()).getJSONArray("clips")
            val audioById = LinkedHashMap<String, File>()
            for (i in 0 until clips.length()) {
                val clip = clips.getJSONObject(i)
                audioById.putIfAbsent(clip.getString("id"), File(fixtures, clip.getString("file")))
            }
            val photos = fixtures.resolve("images").listFiles { f -> f.extension == "jpg" }.orEmpty()
            if (unrelatedPhoto == null) unrelatedPhoto = photos.firstOrNull { it.name.startsWith("banana") } ?: photos.minByOrNull { it.name }
            File(questions).readLines().filter { it.isNotBlank() }.map(::JSONObject).forEach { row ->
                val audio = audioById[row.getString("id")] ?: return@forEach
                val image = row.optString("image").takeIf { it.isNotBlank() && it != "null" }
                    ?.let { name -> photos.firstOrNull { it.nameWithoutExtension == name } }
                all += Q(row, audio, image)
            }
        }
        val rows = langs.flatMap { lang ->
            classes.flatMap { cls ->
                all.filter { it.row.getString("lang") == lang && it.row.getString("class") == cls && it.row.optString("split") == "test" }
                    .take(perClass)
            }
        }
        assumeTrue("no fixture rows", rows.isNotEmpty())

        val backendName = args.getString("qp_backend") ?: "GPU"
        // The app's engine setup: GPU main with GPU vision and MTP; NPU main with GPU vision; audio on CPU.
        val (backend, vision) = when (backendName) {
            "NPU" -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir) to Backend.GPU()
            "CPU" -> Backend.CPU(8) to Backend.CPU(8)
            else -> Backend.GPU() to Backend.GPU()
        }
        val t0 = SystemClock.elapsedRealtime()
        val previousMtp = ExperimentalFlags.enableSpeculativeDecoding
        ExperimentalFlags.enableSpeculativeDecoding = if (backendName == "GPU") true else null
        val engine = try {
            Engine(EngineConfig(
                modelPath = modelPath, backend = backend, visionBackend = vision,
                // -e qp_audio GPU tries the audio encoder on the GPU (the app keeps it on CPU threads).
                audioBackend = if (args.getString("qp_audio") == "GPU") Backend.GPU() else Backend.CPU(8),
                maxNumTokens = 4096, cacheDir = context.cacheDir.path,
            )).also { it.initialize() }
        } finally {
            ExperimentalFlags.enableSpeculativeDecoding = previousMtp
        }
        emit(out, JSONObject().put("kind", "load").put("ms", SystemClock.elapsedRealtime() - t0).put("backend", backendName)
            .put("model", File(modelPath).name).put("rows", rows.size).put("audioBackend", args.getString("qp_audio") ?: "CPU"))
        val sampler = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0)
        try {
            // LiteRT-LM 0.18: audio must run once before the first image (see LiteRtLocalInferenceEngine).
            engine.createConversation(ConversationConfig(samplerConfig = sampler)).use { primer ->
                ask(primer, Contents.of(listOf(Content.AudioFile(rows.first().audio.path), Content.Text("."))), 4)
            }
            val clock = ImageQuestionDefaults.currentContextInstruction()
            for (q in rows) {
                val photo = q.image ?: unrelatedPhoto
                val withPhoto = when (images) { "no" -> listOf(false); "only" -> listOf(true); else -> listOf(false, true) }
                for (variant in variants) for (img in withPhoto) {
                    val system = when (variant) {
                        "base", "qline" -> LocalGenerationSettings.DEFAULT_SYSTEM_PROMPT
                        "route" -> "$GLASSES_SYSTEM\n\n$ROUTE_LABELS"
                        "route2" -> "$GLASSES_SYSTEM\n\n$ROUTE_WORDS"
                        else -> GLASSES_SYSTEM
                    } + "\n\n" + clock
                    val parts = ArrayList<Content>()
                    if (img && photo != null) parts += Content.ImageFile(photo.path)
                    if (variant == "gtag") parts += Content.Text("Voice message:")
                    parts += Content.AudioFile(q.audio.path)
                    when (variant) {
                        "base" -> parts += Content.Text(BASE_PROMPT)
                        "qline" -> parts += Content.Text(QLINE_PROMPT)
                    }
                    val reply = engine.createConversation(ConversationConfig(
                        systemInstruction = Contents.of(system), samplerConfig = sampler,
                    )).use { conv -> ask(conv, Contents.of(parts), 64) }
                    emit(out, JSONObject().put("kind", "row").put("backend", backendName).put("variant", variant + if (img) "+img" else "")
                        .put("id", q.row.getString("id")).put("lang", q.row.getString("lang")).put("class", q.row.getString("class"))
                        .put("text", q.row.getString("text")).put("expect", q.row.optJSONArray("expect"))
                        .put("photo", if (img) photo?.nameWithoutExtension else null)
                        .put("ttftMs", reply.ttftMs).put("doneMs", reply.totalMs).put("answer", reply.text.take(300)))
                }
            }
        } finally {
            engine.close()
        }
    }

    private class Reply(val text: String, val ttftMs: Long, val totalMs: Long)

    private suspend fun ask(conv: com.google.ai.edge.litertlm.Conversation, contents: Contents, maxTokens: Int): Reply {
        val start = SystemClock.elapsedRealtime()
        var first = -1L
        val text = StringBuilder()
        conv.sendMessageAsync(contents, emptyMap<String, Any>(), maxOutputToken = maxTokens).collect { message ->
            val current = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            if (current.isEmpty()) return@collect
            if (first < 0) first = SystemClock.elapsedRealtime() - start
            // Chunks may be deltas or the accumulated reply.
            if (text.isNotEmpty() && current.startsWith(text)) text.setLength(0)
            text.append(current)
        }
        return Reply(text.toString().trim(), first, SystemClock.elapsedRealtime() - start)
    }

    private fun emit(out: File, row: JSONObject) {
        println("QP_ROW $row")
        out.parentFile?.mkdirs()
        out.appendText("$row\n")
    }

    private companion object {
        const val BASE_PROMPT = "Answer the question spoken in the attached audio. Do not merely transcribe it."
        const val QLINE_PROMPT = "First write the user's spoken question on one line starting with \"Q: \", then answer it on the next line."
        const val GLASSES_SYSTEM =
            "You are the voice assistant in the user's smart glasses. The user talks to you out loud: each user " +
                "message is a recording of their voice, sometimes together with a photo from the glasses camera of what " +
                "they are looking at. Reply directly to what they said, in the language they spoke. Never repeat, quote " +
                "or transcribe their words. Use the photo only when their words refer to something they see. Give the " +
                "most useful answer first, in one or two short sentences."
        const val ROUTE_LABELS =
            "Start every reply with exactly one label:\n" +
                "⊲A⊳ you can answer now: write the label, then the answer.\n" +
                "⊲B⊳ answering needs the glasses camera and no photo is attached: write only the label.\n" +
                "⊲C⊳ answering needs current information from the internet (weather, news, prices, opening hours, " +
                "sports, events): write only the label.\n" +
                "⊲D⊳ the user asks you to do something on their phone (open an app, play music, call, send a message, " +
                "set a timer, navigate): write only the label.\n" +
                "⊲E⊳ the user is not talking to you, or wants to stop: write only the label."
        const val ROUTE_WORDS =
            "Begin every reply with one tag in square brackets:\n" +
                "[answer] then your answer: you can answer from what they said, the photo if one is attached, and what you know.\n" +
                "[camera] and nothing else: answering needs to see what they are looking at, and no photo is attached.\n" +
                "[search] and nothing else: answering needs current information from the internet (weather, news, prices, " +
                "scores, opening hours).\n" +
                "[phone] and nothing else: they ask you to do something on their phone (open an app, play music, call, " +
                "send a message, set a timer, navigate).\n" +
                "[ignore] and nothing else: they are talking to someone else, not to you, or they want to stop.\n" +
                "Examples (the user's words are written here; in the conversation they are spoken):\n" +
                "\"What's 15 percent of 80?\" → [answer] 12.\n" +
                "\"What does this label say?\" → [camera]\n" +
                "\"Will it rain tomorrow?\" → [search]\n" +
                "\"Play some jazz on YouTube.\" → [phone]\n" +
                "\"Mom, dinner's ready!\" → [ignore]"
    }
}
