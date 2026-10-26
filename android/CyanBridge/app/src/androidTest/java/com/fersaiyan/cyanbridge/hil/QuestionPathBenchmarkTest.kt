package com.fersaiyan.cyanbridge.hil

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.ai.decision.SingleTokenDecisionEngine
import com.fersaiyan.cyanbridge.ai.router.AgentInferenceRouter
import com.fersaiyan.cyanbridge.ai.router.AssistantRequest
import com.fersaiyan.cyanbridge.ai.router.AssistantRequestRouter
import com.fersaiyan.cyanbridge.ai.router.AssistantRequestSource
import com.fersaiyan.cyanbridge.ai.transcription.OfflineQuestionTranscriber
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionDefaults
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogRepository
import com.fersaiyan.cyanbridge.localmodels.engine.DecideOnceRequest
import com.fersaiyan.cyanbridge.localmodels.engine.DecideOnceRoute
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import com.fersaiyan.cyanbridge.localmodels.session.LocalChatSessionManager
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import com.fersaiyan.cyanbridge.localmodels.settings.LocalGenerationSettings
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelRuntime
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Times the candidate glasses-question paths on one device through the app's own code, per fixture
 * question (training/glasses_questions_v1, audio from make_glasses_question_fixtures.py):
 *
 *   stt          on-device transcription (availability per language, latency, transcript)
 *   transcribed  transcript → assistant router decision (single-token engine) → text answer
 *   audio        recorded audio straight to the model (the app's spoken-question layout: system text, bare audio)
 *   decide_once  decide-once turn on the audio; the camera route receives the fixture photo
 *   decide_once_stream  same, but the answer streams from turn 1 (DecideOnceRequest.streamAnswer)
 *
 * Arguments: -e qb_model <.litertlm> (CPU/GPU/NPU via -e qb_backend), -e qb_fixtures <dir with
 * manifest.json, audio/, images/>, -e qb_questions <questions.jsonl>, optional -e qb_langs en,pt-BR
 * -e qb_splits test -e qb_limit N -e qb_paths stt,transcribed,audio,decide_once -e qb_out <jsonl>.
 * One QB_ROW JSON line per question and path goes to logcat and qb_out (default: the app's external
 * files dir, /sdcard/Android/data/com.fersaiyan.cyanbridge/files/question-bench.jsonl). Model settings preferences
 * are snapshotted and restored.
 */
@RunWith(AndroidJUnit4::class)
class QuestionPathBenchmarkTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun benchmarkQuestionPaths() = runBlocking {
        val modelPath = args.getString("qb_model").orEmpty()
        val fixtures = File(args.getString("qb_fixtures").orEmpty())
        val questionsFile = File(args.getString("qb_questions").orEmpty())
        assumeTrue("pass -e qb_model, -e qb_fixtures, -e qb_questions",
            File(modelPath).isFile && File(fixtures, "manifest.json").isFile && questionsFile.isFile)
        assumeFalse("a remote OpenAI server is active", RemoteOpenAiPrefs.isActive(context))
        val paths = (args.getString("qb_paths") ?: "stt,transcribed,audio,decide_once,decide_once_stream").split(',').toSet()
        val langs = args.getString("qb_langs")?.split(',')?.toSet()
        val splits = (args.getString("qb_splits") ?: "dev,test").split(',').toSet()
        val limit = args.getString("qb_limit")?.toInt() ?: Int.MAX_VALUE
        val out = File(args.getString("qb_out") ?: File(context.getExternalFilesDir(null), "question-bench.jsonl").path)

        val clips = JSONObject(File(fixtures, "manifest.json").readText()).getJSONArray("clips")
        val audioById = LinkedHashMap<String, File>()
        for (i in 0 until clips.length()) {
            val clip = clips.getJSONObject(i)
            audioById.putIfAbsent(clip.getString("id"), File(fixtures, clip.getString("file")))
        }
        val rows = questionsFile.readLines().filter { it.isNotBlank() }.map(::JSONObject)
            .filter { it.getString("split") in splits && (langs == null || it.getString("lang") in langs) }
            .filter { audioById[it.getString("id")]?.isFile == true }
            .take(limit)

        val saved = PREFS.associateWith { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap() }
        try {
            PREFS.forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
            val model = LocalModelStorageRepository.registerImportedModel(context, File(modelPath).nameWithoutExtension, File(modelPath))
            LocalModelStorageRepository.setSelectedModelId(context, model.id)
            LocalModelSettingsRepository.saveForModel(
                context, model.id,
                LocalGenerationSettings.defaultsFor(LocalModelCatalogRepository.findById(model.catalogId)).copy(
                    modelRuntime = LocalModelRuntime.LITERT,
                    computeBackend = when (args.getString("qb_backend")) {
                        "GPU" -> LocalComputeBackend.GPU
                        "NPU" -> LocalComputeBackend.NPU_EXPERIMENTAL
                        else -> LocalComputeBackend.CPU
                    },
                    contextSize = 4096, temperature = 0.0, topK = 1, seed = 0, maxTokens = 64,
                ),
            )
            val provider = LocalModelsProvider()
            val loadStart = SystemClock.elapsedRealtime()
            val load = provider.prepareSelectedModel(context)
            emit(out, JSONObject().put("kind", "load").put("ms", SystemClock.elapsedRealtime() - loadStart)
                .put("backend", load?.activeBackend?.name).put("fallback", load?.fallbackReason).put("rows", rows.size))

            val router = AssistantRequestRouter { providerType ->
                SingleTokenDecisionEngine(generate = { system, user ->
                    AgentInferenceRouter.completeDecisionToken(context, "bench-decision", system, user, 8, providerType)
                })
            }
            for (row in rows) {
                val id = row.getString("id")
                val lang = row.getString("lang")
                val audio = audioById.getValue(id)
                val image = row.optString("image").takeIf { it.isNotBlank() && it != "null" }
                    ?.let { name -> fixtures.resolve("images").listFiles()?.firstOrNull { it.nameWithoutExtension == name.substringBeforeLast('.') } }
                // One failing path is recorded and the benchmark moves on.
                suspend fun guarded(path: String, block: suspend () -> Unit) {
                    if (path !in paths && !(path == "stt" && "transcribed" in paths)) return
                    try {
                        block()
                    } catch (error: Exception) {
                        emit(out, JSONObject().put("kind", "row").put("path", path).put("id", id).put("lang", lang)
                            .put("class", row.getString("class")).put("error", error.javaClass.simpleName + ": " + error.message?.take(160)))
                    }
                }
                fun base(path: String) = JSONObject().put("kind", "row").put("path", path).put("id", id).put("lang", lang)
                    .put("class", row.getString("class"))

                var transcript: String? = null
                guarded("stt") {
                    val t0 = SystemClock.elapsedRealtime()
                    transcript = runCatching { OfflineQuestionTranscriber(context).transcribe(audio, lang) }.getOrNull()
                    emit(out, base("stt").put("ms", SystemClock.elapsedRealtime() - t0).put("available", transcript != null)
                        .put("transcript", transcript))
                }
                val heard = transcript
                if (heard != null) guarded("transcribed") {
                    val t0 = SystemClock.elapsedRealtime()
                    val route = router.route(context, AssistantRequest(heard, AssistantRequestSource.GLASSES_VOICE), AgentProviderType.LOCAL_AGENT)
                    val decisionMs = SystemClock.elapsedRealtime() - t0
                    val answer = timed { onToken -> provider.streamChat(context, listOf(mapOf("role" to "user", "content" to heard)),
                        onToken = onToken, maxTokens = 64) }
                    emit(out, base("transcribed").put("decisionMs", decisionMs).put("route", route.intent.name)
                        .put("ttftMs", answer.ttftMs).put("answerMs", answer.totalMs).put("answer", answer.text.take(200)))
                }
                guarded("audio") {
                    val answer = timed { onToken -> provider.streamChat(context,
                        spokenMessages(lang), onToken = onToken,
                        audioPath = audio.path, maxTokens = 64) }
                    emit(out, base("audio").put("ttftMs", answer.ttftMs).put("answerMs", answer.totalMs).put("answer", answer.text.take(200)))
                }
                guarded("decide_once") {
                    var route: DecideOnceRoute? = null
                    var decisionMs = -1L
                    val answer = timed { onToken -> provider.streamChat(context,
                        spokenMessages(lang, decideOnce = true), onToken = onToken,
                        audioPath = audio.path, maxTokens = 64,
                        decideOnce = DecideOnceRequest(
                            imageWhenNeeded = { (image ?: fallbackImage(fixtures))?.path },
                            onRoute = { r, ms -> route = r; decisionMs = ms },
                        )) }
                    emit(out, base("decide_once").put("route", route?.name).put("decisionMs", decisionMs)
                        .put("ttftMs", answer.ttftMs).put("answerMs", answer.totalMs).put("answer", answer.text.take(200)))
                }
                guarded("decide_once_stream") {
                    var route: DecideOnceRoute? = null
                    var decisionMs = -1L
                    val answer = timed { onToken -> provider.streamChat(context,
                        spokenMessages(lang, decideOnce = true), onToken = onToken,
                        audioPath = audio.path, maxTokens = 64,
                        decideOnce = DecideOnceRequest(
                            streamAnswer = true,
                            imageWhenNeeded = { (image ?: fallbackImage(fixtures))?.path },
                            onRoute = { r, ms -> route = r; decisionMs = ms },
                        )) }
                    emit(out, base("decide_once_stream").put("route", route?.name).put("decisionMs", decisionMs)
                        .put("ttftMs", answer.ttftMs).put("answerMs", answer.totalMs).put("answer", answer.text.take(200)))
                }
            }
        } finally {
            runCatching { LocalChatSessionManager.unload() }
            saved.forEach { (name, values) -> restore(name, values) }
        }
    }

    /** What AssistantProviderQueries.query sends for a spoken question: system text, bare audio. */
    private fun spokenMessages(lang: String, decideOnce: Boolean = false) = listOf(
        mapOf("role" to "system", "content" to ImageQuestionDefaults.spokenQuestionInstruction(lang, allowQuestionEcho = decideOnce) +
            " " + ImageQuestionDefaults.currentContextInstruction()),
        mapOf("role" to "user", "content" to ""),
    )

    private data class Timed(val text: String, val ttftMs: Long, val totalMs: Long)

    private suspend fun timed(block: suspend (onToken: (String) -> Unit) -> String): Timed {
        val start = SystemClock.elapsedRealtime()
        var first = -1L
        val text = block { if (first < 0) first = SystemClock.elapsedRealtime() - start }
        return Timed(text.trim(), first, SystemClock.elapsedRealtime() - start)
    }

    private fun fallbackImage(fixtures: File): File? =
        fixtures.resolve("images").listFiles { f -> f.extension == "jpg" }?.minByOrNull { it.name }

    private fun emit(out: File, row: JSONObject) {
        println("QB_ROW $row")
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
    }
}
