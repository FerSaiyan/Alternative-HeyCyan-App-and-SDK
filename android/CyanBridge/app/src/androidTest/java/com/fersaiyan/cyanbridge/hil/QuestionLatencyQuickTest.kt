package com.fersaiyan.cyanbridge.hil

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.ai.transcription.OfflineQuestionTranscriber
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogRepository
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import com.fersaiyan.cyanbridge.localmodels.session.LocalChatSessionManager
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import com.fersaiyan.cyanbridge.localmodels.settings.LocalGenerationSettings
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelRuntime
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minutes-long latency check of the two answer paths as the user experiences them, timed from the
 * moment the question window closes (end of the 1.8 s endpoint silence) to the first and last token:
 *
 *   stt    recording → on-device recognizer (real-time replay, as the app does) → text answer
 *   audio  recording → model hears the audio directly (the app's layout: system text, bare audio)
 *
 * Each runs with the model already loaded ("warm") and unloaded at the wake ("cold": the app's
 * input-phase preload starts at t=0 and overlaps the window). Flows: "voice" (wake word; the window
 * is lead + speech + endpoint silence) and "photo" (photo button: the window and a simulated image
 * transfer of ql_transfer_ms run in parallel; the answer starts when both are done and includes the
 * photo). Fixture clips are padded with the leading/trailing silence a real recording contains.
 *
 * Args: -e ql_model <.litertlm> -e ql_fixtures <dir> -e ql_questions <jsonl>, optional -e ql_backend
 * GPU|CPU, -e ql_langs en,pt-BR, -e ql_per_class 1, -e ql_transfer_ms 3000, -e ql_states cold,warm,
 * -e ql_flows voice,photo, -e ql_paths stt,audio, -e ql_out <jsonl> (default: the app's external files
 * dir, question-latency.jsonl).
 */
@RunWith(AndroidJUnit4::class)
class QuestionLatencyQuickTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun answerLatencyAfterQuestionWindow() = runBlocking {
        val modelPath = args.getString("ql_model").orEmpty()
        val fixtures = File(args.getString("ql_fixtures").orEmpty())
        val questionsFile = File(args.getString("ql_questions").orEmpty())
        assumeTrue("pass -e ql_model, -e ql_fixtures, -e ql_questions",
            File(modelPath).isFile && File(fixtures, "manifest.json").isFile && questionsFile.isFile)
        assumeFalse("a remote OpenAI server is active", RemoteOpenAiPrefs.isActive(context))
        val langs = (args.getString("ql_langs") ?: "en,pt-BR").split(',')
        val perClass = args.getString("ql_per_class")?.toInt() ?: 1
        val transferMs = args.getString("ql_transfer_ms")?.toLong() ?: 3_000L
        val states = (args.getString("ql_states") ?: "cold,warm").split(',')
        val flows = (args.getString("ql_flows") ?: "voice,photo").split(',')
        val paths = (args.getString("ql_paths") ?: "stt,audio").split(',')
        val out = File(args.getString("ql_out") ?: File(context.getExternalFilesDir(null), "question-latency.jsonl").path)

        val clips = JSONObject(File(fixtures, "manifest.json").readText()).getJSONArray("clips")
        val audioById = LinkedHashMap<String, File>()
        for (i in 0 until clips.length()) {
            val clip = clips.getJSONObject(i)
            audioById.putIfAbsent(clip.getString("id"), File(fixtures, clip.getString("file")))
        }
        val all = questionsFile.readLines().filter { it.isNotBlank() }.map(::JSONObject)
            .filter { audioById[it.getString("id")]?.isFile == true }
        val rows = langs.flatMap { lang ->
            listOf("visual", "plain").flatMap { cls ->
                all.filter { it.getString("lang") == lang && it.getString("class") == cls && it.optString("split") == "test" }.take(perClass)
            }
        }
        val photo = fixtures.resolve("images").listFiles { f -> f.extension == "jpg" }?.minByOrNull { it.name }

        val saved = PREFS.associateWith { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap() }
        try {
            PREFS.forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
            val model = LocalModelStorageRepository.registerImportedModel(context, File(modelPath).nameWithoutExtension, File(modelPath))
            LocalModelStorageRepository.setSelectedModelId(context, model.id)
            LocalModelSettingsRepository.saveForModel(
                context, model.id,
                LocalGenerationSettings.defaultsFor(LocalModelCatalogRepository.findById(model.catalogId)).copy(
                    modelRuntime = LocalModelRuntime.LITERT,
                    computeBackend = if (args.getString("ql_backend") == "GPU") LocalComputeBackend.GPU else LocalComputeBackend.CPU,
                    contextSize = 4096, temperature = 0.0, topK = 1, seed = 0, maxTokens = 64,
                ),
            )
            val provider = LocalModelsProvider()
            for (row in rows) {
                val lang = row.getString("lang")
                val recording = padded(audioById.getValue(row.getString("id")))
                val speechMs = wavMs(audioById.getValue(row.getString("id")))
                val windowMs = LEAD_MS + speechMs + ENDPOINT_SILENCE_MS
                for (flow in flows) for (state in states) for (path in paths) {
                    if (state == "cold") LocalChatSessionManager.unload() else provider.prepareSelectedModel(context)
                    val readyAfterMs = if (flow == "photo") maxOf(windowMs, transferMs) else windowMs
                    val result = coroutineScope {
                        val wake = SystemClock.elapsedRealtime()
                        // The app's input-phase preload starts at the wake and runs during the window.
                        val preload = async { provider.prepareSelectedModel(context); SystemClock.elapsedRealtime() - wake }
                        delay(readyAfterMs)
                        val ready = SystemClock.elapsedRealtime()
                        var sttMs = -1L
                        var heard: String? = null
                        if (path == "stt") {
                            heard = runCatching { OfflineQuestionTranscriber(context).transcribe(recording, lang) }.getOrNull()
                            sttMs = SystemClock.elapsedRealtime() - ready
                        }
                        val loadMs = preload.await()
                        val loadWaitMs = (wake + loadMs - ready).coerceAtLeast(0)
                        var first = -1L
                        val images = if (flow == "photo" && photo != null) listOf(photo.path) else emptyList()
                        val text = if (path == "stt") {
                            if (heard == null) "" else provider.streamChat(context,
                                listOf(mapOf("role" to "user", "content" to heard)), imagePaths = images, maxTokens = 64,
                                onToken = { if (first < 0) first = SystemClock.elapsedRealtime() - ready })
                        } else {
                            // The app's spoken-question layout: system text, bare audio (+ photo).
                            provider.streamChat(context, listOf(
                                mapOf("role" to "system", "content" to com.fersaiyan.cyanbridge.ai.vision.ImageQuestionDefaults
                                    .spokenQuestionInstruction(lang) + " " +
                                    com.fersaiyan.cyanbridge.ai.vision.ImageQuestionDefaults.currentContextInstruction()),
                                mapOf("role" to "user", "content" to ""),
                            ),
                                audioPath = recording.path, imagePaths = images, maxTokens = 64,
                                onToken = { if (first < 0) first = SystemClock.elapsedRealtime() - ready })
                        }
                        JSONObject().put("loadMs", loadMs).put("loadWaitMs", loadWaitMs).put("sttMs", sttMs)
                            .put("sttOk", path != "stt" || heard != null).put("heard", heard)
                            .put("ttftMs", first).put("doneMs", SystemClock.elapsedRealtime() - ready).put("answer", text.trim().take(160))
                    }
                    emit(out, result.put("kind", "row").put("id", row.getString("id")).put("lang", lang)
                        .put("class", row.getString("class")).put("flow", flow).put("state", state).put("path", path)
                        .put("speechMs", speechMs).put("windowMs", windowMs).put("readyAfterMs", readyAfterMs))
                }
                recording.delete()
            }
        } finally {
            runCatching { LocalChatSessionManager.unload() }
            saved.forEach { (name, values) -> restore(name, values) }
        }
    }

    /**
     * Where does a second turn's time go? Same engine and audio; times turn 2 ("now answer") after a
     * short turn 1, with the decide-once conversation option (enableResponseFormat) off and on, the
     * regex response format on turn 1, and a text-only history. Run with
     * -e class ...QuestionLatencyQuickTest#secondTurnProbe (same ql_* args; ql_per_class clips per class).
     */
    @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
    @Test
    fun secondTurnProbe() = runBlocking {
        val modelPath = args.getString("ql_model").orEmpty()
        val fixtures = File(args.getString("ql_fixtures").orEmpty())
        val questionsFile = File(args.getString("ql_questions").orEmpty())
        assumeTrue(File(modelPath).isFile && File(fixtures, "manifest.json").isFile && questionsFile.isFile)
        val out = File(args.getString("ql_out") ?: File(context.getExternalFilesDir(null), "question-latency.jsonl").path)
        val clips = JSONObject(File(fixtures, "manifest.json").readText()).getJSONArray("clips")
        val audioById = LinkedHashMap<String, File>()
        for (i in 0 until clips.length()) {
            val clip = clips.getJSONObject(i)
            audioById.putIfAbsent(clip.getString("id"), File(fixtures, clip.getString("file")))
        }
        val perClass = args.getString("ql_per_class")?.toInt() ?: 1
        val rows = questionsFile.readLines().filter { it.isNotBlank() }.map(::JSONObject)
            .filter { audioById[it.getString("id")]?.isFile == true && it.optString("split") == "test" }
            .let { all -> (args.getString("ql_langs") ?: "en").split(',').flatMap { lang -> listOf("visual", "plain").flatMap { c ->
                all.filter { it.getString("lang") == lang && it.getString("class") == c }.take(perClass) } } }
        val gpu = args.getString("ql_backend") == "GPU"
        val t0 = SystemClock.elapsedRealtime()
        // The app runs vision on CPU threads and MTP on (LiteRtLocalInferenceEngine); A/B them here.
        val visionGpu = args.getString("ql_vision") == "GPU"
        val mtp = args.getString("ql_mtp")?.toBooleanStrictOrNull()
        val previousMtp = com.google.ai.edge.litertlm.ExperimentalFlags.enableSpeculativeDecoding
        com.google.ai.edge.litertlm.ExperimentalFlags.enableSpeculativeDecoding = mtp
        val engine = try {
            com.google.ai.edge.litertlm.Engine(com.google.ai.edge.litertlm.EngineConfig(
                modelPath = modelPath,
                backend = if (gpu) com.google.ai.edge.litertlm.Backend.GPU() else com.google.ai.edge.litertlm.Backend.CPU(8),
                visionBackend = if (visionGpu) com.google.ai.edge.litertlm.Backend.GPU() else com.google.ai.edge.litertlm.Backend.CPU(8),
                audioBackend = com.google.ai.edge.litertlm.Backend.CPU(8),
                maxNumTokens = 4096, cacheDir = context.cacheDir.path,
            )).also { it.initialize() }
        } finally {
            com.google.ai.edge.litertlm.ExperimentalFlags.enableSpeculativeDecoding = previousMtp
        }
        val photo = fixtures.resolve("images").listFiles { f -> f.extension == "jpg" }!!.minByOrNull { it.name }!!
        val image = com.google.ai.edge.litertlm.Content.ImageFile(photo.path)
        emit(out, JSONObject().put("kind", "probe_load").put("ms", SystemClock.elapsedRealtime() - t0).put("gpu", gpu)
            .put("visionGpu", visionGpu).put("mtp", mtp?.toString() ?: "default"))
        val repeat = "Write the user's question on one line starting with \"Q: \". Write nothing else."
        try {
            for (row in rows) {
                val recording = padded(audioById.getValue(row.getString("id")))
                val audio = com.google.ai.edge.litertlm.Content.AudioFile(recording.path)
                for (variant in (args.getString("ql_variants") ?: "single,two_turn,decide_stream,camera,single_image,single").split(',')) {
                    // "_sys": the app's default system prompt as the system turn (LocalModelsProvider);
                    // "_raw": the unpadded fixture, as QuestionPathBenchmarkTest sends it.
                    val config = com.google.ai.edge.litertlm.ConversationConfig(
                        systemInstruction = com.google.ai.edge.litertlm.Contents.of(
                            if ("_sys" in variant) com.fersaiyan.cyanbridge.localmodels.settings.LocalGenerationSettings.DEFAULT_SYSTEM_PROMPT else ""),
                        samplerConfig = com.google.ai.edge.litertlm.SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
                        enableResponseFormat = variant == "two_turn_rf" || variant == "decide_stream" || variant == "camera" || variant == "conv_rf",
                    )
                    val convStart = SystemClock.elapsedRealtime()
                    engine.createConversation(config).use { conv ->
                        val r = JSONObject().put("kind", "probe").put("id", row.getString("id")).put("variant", variant)
                            .put("convMs", SystemClock.elapsedRealtime() - convStart)
                        val clip = if ("_raw" in variant) com.google.ai.edge.litertlm.Content.AudioFile(audioById.getValue(row.getString("id")).path) else audio
                        if (variant.startsWith("single") && variant != "single_image") {
                            val t = turn(conv, com.google.ai.edge.litertlm.Contents.of(listOf(clip,
                                com.google.ai.edge.litertlm.Content.Text(AUDIO_PROMPT))), 64, regex = false)
                            r.put("t1ttft", t.ttftMs).put("t1ms", t.totalMs).put("t1", t.text.take(120))
                        } else if (variant.startsWith("p_")) {
                            // Audio-direct prompt wordings (all with the app's system prompt when "_sys").
                            val text = com.google.ai.edge.litertlm.Content.Text(PROMPTS.getValue(variant.removeSuffix("_sys")))
                            val parts = when {
                                variant.startsWith("p_before") -> listOf(text, audio)
                                variant.startsWith("p_none") -> listOf(audio)
                                else -> listOf(audio, text)
                            }
                            val t = turn(conv, com.google.ai.edge.litertlm.Contents.of(parts), 64, regex = false)
                            r.put("t1ttft", t.ttftMs).put("t1ms", t.totalMs).put("t1", t.text.take(160))
                        } else if (variant == "conv" || variant == "conv_rf") {
                            Unit
                        } else if (variant == "single_image") {
                            val t = turn(conv, com.google.ai.edge.litertlm.Contents.of(listOf(image, audio,
                                com.google.ai.edge.litertlm.Content.Text(AUDIO_PROMPT))), 64, regex = false)
                            r.put("t1ttft", t.ttftMs).put("t1ms", t.totalMs).put("t1", t.text.take(120))
                        } else if (variant == "decide_stream" || variant == "camera") {
                            // Exactly the app's streaming decide-once turn 1 (no output cap), then its answer turn.
                            val first = com.google.ai.edge.litertlm.Contents.of(listOf(audio, com.google.ai.edge.litertlm.Content.Text(
                                "$AUDIO_PROMPT\n\n${com.fersaiyan.cyanbridge.localmodels.engine.DecideOncePrompt.ROUTING_INSTRUCTION}")))
                            val t1 = turn(conv, first, null, regex = false, marks = r)
                            // "camera" sends the photo as the app's camera route does, whatever the label.
                            val second = if (variant == "camera") {
                                com.google.ai.edge.litertlm.Contents.of(listOf(image, com.google.ai.edge.litertlm.Content.Text(
                                    com.fersaiyan.cyanbridge.localmodels.engine.DecideOnceRequest(imageWhenNeeded = { null }).imageInstruction)))
                            } else {
                                com.google.ai.edge.litertlm.Contents.of(com.google.ai.edge.litertlm.Content.Text(
                                    com.fersaiyan.cyanbridge.localmodels.engine.DecideOncePrompt.ANSWER_INSTRUCTION))
                            }
                            val t2 = turn(conv, second, 64, regex = false)
                            r.put("t1ttft", t1.ttftMs).put("t1ms", t1.totalMs).put("t1", t1.text.take(300))
                                .put("t2ttft", t2.ttftMs).put("t2ms", t2.totalMs).put("t2", t2.text.take(120))
                        } else {
                            val first = if (variant == "text_two_turn") {
                                com.google.ai.edge.litertlm.Contents.of(com.google.ai.edge.litertlm.Content.Text(
                                    "Question: what is the capital of Australia?\n$repeat"))
                            } else {
                                com.google.ai.edge.litertlm.Contents.of(listOf(audio, com.google.ai.edge.litertlm.Content.Text(repeat)))
                            }
                            val t1 = turn(conv, first, 32, regex = variant == "two_turn_rf_regex")
                            val t2 = turn(conv, com.google.ai.edge.litertlm.Contents.of(
                                com.google.ai.edge.litertlm.Content.Text("Now answer the question. Do not repeat it.")), 64, regex = false)
                            r.put("t1ttft", t1.ttftMs).put("t1ms", t1.totalMs).put("t1", t1.text.take(120))
                                .put("t2ttft", t2.ttftMs).put("t2ms", t2.totalMs).put("t2", t2.text.take(120))
                        }
                        emit(out, r)
                    }
                }
                recording.delete()
            }
        } finally {
            engine.close()
        }
    }

    private suspend fun turn(
        conv: com.google.ai.edge.litertlm.Conversation,
        contents: com.google.ai.edge.litertlm.Contents,
        maxTokens: Int?,
        regex: Boolean,
        marks: JSONObject? = null,
    ): Timed {
        val start = SystemClock.elapsedRealtime()
        var first = -1L
        val text = StringBuilder()
        val flow = if (regex) {
            conv.sendMessageAsync(contents, emptyMap<String, Any>(), maxOutputToken = maxTokens,
                responseFormat = com.google.ai.edge.litertlm.ResponseFormat.regex("Q: [^\\n]{1,240}"))
        } else if (maxTokens != null) {
            conv.sendMessageAsync(contents, emptyMap<String, Any>(), maxOutputToken = maxTokens)
        } else {
            conv.sendMessageAsync(contents, emptyMap<String, Any>())
        }
        flow.collect { message ->
            val current = message.contents.contents.filterIsInstance<com.google.ai.edge.litertlm.Content.Text>().joinToString("") { it.text }
            if (current.isNotEmpty() && first < 0) first = SystemClock.elapsedRealtime() - start
            // Chunks may be deltas or the accumulated reply.
            if (text.isNotEmpty() && current.startsWith(text)) text.setLength(0)
            text.append(current)
            if (marks != null && !marks.has("labelMs") && ("⊲A⊳" in text || "⊲B⊳" in text)) {
                marks.put("labelMs", SystemClock.elapsedRealtime() - start)
            }
        }
        return Timed(text.toString(), first, SystemClock.elapsedRealtime() - start)
    }

    private data class Timed(val text: String, val ttftMs: Long, val totalMs: Long)

    /** The fixture with the reaction-time lead and the endpoint silence a real recording contains. */
    private fun padded(wav: File): File {
        val bytes = wav.readBytes()
        val data = bytes.copyOfRange(dataOffset(bytes), bytes.size)
        val lead = ByteArray((LEAD_MS * 32).toInt())
        val trail = ByteArray((ENDPOINT_SILENCE_MS * 32).toInt())
        val pcm = lead + data + trail
        val file = File.createTempFile("latency-", ".wav", context.cacheDir)
        RandomAccessFile(file, "rw").use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(pcm.size)
            out.write(header.array())
            out.write(pcm)
        }
        return file
    }

    private fun wavMs(wav: File): Long {
        val bytes = wav.readBytes()
        return (bytes.size - dataOffset(bytes)) / 32L
    }

    /** Fixtures are 16 kHz mono 16-bit PCM; finds the "data" chunk past any extra chunks. */
    private fun dataOffset(bytes: ByteArray): Int {
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4)
            val size = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") return offset + 8
            offset += 8 + size + (size and 1)
        }
        error("no data chunk")
    }

    private fun emit(out: File, row: JSONObject) {
        println("QL_ROW $row")
        out.parentFile?.mkdirs()
        out.appendText("$row\n")
    }

    @Suppress("UNCHECKED_CAST")
    private fun restore(name: String, values: Map<String, *>) {
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value as Set<String>)
            }
        }
        editor.commit()
    }

    private companion object {
        val PREFS = listOf("local_models_registry", "local_model_projectors", "local_model_settings")
        const val AUDIO_PROMPT = "Answer the question spoken in the attached audio. Do not merely transcribe it."
        val PROMPTS = mapOf(
            "p_current" to AUDIO_PROMPT,
            "p_before" to "The recording that follows is the user asking you a question out loud. Answer it.",
            "p_none" to "",
            "p_heard" to "The user just asked you this question out loud (recording attached). Answer it directly.",
            "p_qline" to "First write the user's spoken question on one line starting with \"Q: \", then answer it on the next line.",
        )
        const val LEAD_MS = 500L
        const val ENDPOINT_SILENCE_MS = 1_800L
    }
}
