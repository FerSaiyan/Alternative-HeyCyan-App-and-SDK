package com.fersaiyan.cyanbridge.hil

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionDefaults
import com.fersaiyan.cyanbridge.localmodels.engine.EngineLoadConfig
import com.fersaiyan.cyanbridge.localmodels.engine.GenerationConfig
import com.fersaiyan.cyanbridge.localmodels.engine.LiteRtLocalInferenceEngine
import com.fersaiyan.cyanbridge.localmodels.engine.SessionTurn
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import com.fersaiyan.cyanbridge.shared.assistant.SpokenDecisionParser
import com.fersaiyan.cyanbridge.shared.assistant.SpokenQuestionFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Photo + spoken questions when the photo and the recording arrive in parallel (LiteRtLocalInferenceEngine
 * prepareFirstPart), on the app's engine with one loaded model. Per question (glasses_questions: color_of_object
 * with socks_yellow.jpg, read_sign with sign_no_parking.jpg, plant questions with plant_aloe.jpg; en + pt-BR):
 * - "together": both parts in one request after both arrived (2.5.0);
 * - "photo_first" / "audio_first": the first part prefilled, then the request when the other arrives, with and
 *   without the one-word model reply after the prefilled part ([ack]).
 * Times run from "the second part is ready" (the request) to the first token. Answers are logged for review.
 * "decide_*" (SMART photo questions, branch smart-image-questions): the spoken questions' system turn (route tags)
 * over more question kinds (the photo is unrelated for the non-visual ones), as the app runs them: together and photo
 * first with the photo attached (PHOTO_QUESTION_NOTE; a [camera] reply is an answer about it, a bare one gets one more
 * turn), recording first decided on the recording alone (an [answer] or [camera] reply then gets the photo in the
 * conversation: photo_turn_* times run from "the photo is ready"). Logs the route, the first token (the tag) and the
 * first spoken word (answer_s). "decide_together_spare" keeps no conversation, so the next question starts from the
 * primed spare (GPU/NPU) or the cloned root (CPU), as a photo question does once the previous follow-up window lapsed.
 * Not part of CI: skips unless pq_model points at a .litertlm file. pq_backend GPU | NPU | CPU, pq_out,
 * pq_modes (comma-separated, default all), pq_cache=false (no system-prompt cache: decide_together then prefills the
 * system turn every time; with the cache a CPU backend clones the spoken questions' primed root).
 */
@RunWith(AndroidJUnit4::class)
class PhotoQuestionPrefillTest {
    /** Clip family -> its photo and the expected route of a decided photo question. */
    private val decided = mapOf(
        "color_of_object" to ("socks_yellow.jpg" to "answer"),
        "read_sign" to ("sign_no_parking.jpg" to "answer"),
        "tell_me_more" to ("plant_aloe.jpg" to "answer"),
        "capital_city" to ("socks_yellow.jpg" to "answer"),
        "tell_joke" to ("plant_aloe.jpg" to "answer"),
        "weather_today" to ("sign_no_parking.jpg" to "search"),
        "next_holiday" to ("plant_aloe.jpg" to "search"),
        "play_song" to ("socks_yellow.jpg" to "phone"),
        "talk_to_other" to ("plant_aloe.jpg" to "ignore"),
    )

    private class Timed(val text: String, val ttftS: Double?, val firstWordS: Double?, val totalS: Double, val error: String?)

    /** One request: first token, first speakable word (after the route tag) and total time. */
    private suspend fun timed(engine: LiteRtLocalInferenceEngine, config: GenerationConfig): Timed {
        val started = System.nanoTime()
        var firstToken = 0L
        var firstWord = 0L
        val streamed = StringBuilder()
        val result = runCatching {
            engine.generate(config) {
                val now = System.nanoTime()
                if (firstToken == 0L && it.isNotEmpty()) firstToken = now
                streamed.append(it)
                if (firstWord == 0L && SpokenDecisionParser.speakablePrefix(streamed.toString()).isNotEmpty()) firstWord = now
            }
        }
        fun since(t: Long) = if (t > 0) (t - started) / 1e9 else null
        return Timed(result.getOrNull()?.text.orEmpty(), since(firstToken), since(firstWord), (System.nanoTime() - started) / 1e9,
            result.exceptionOrNull()?.toString())
    }

    @Test fun photoQuestionPrefill() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val source = args.getString("pq_model").orEmpty()
        val stageDir = File(context.getExternalFilesDir(null), "litert-eval-in").apply { mkdirs() }
        val model = if (source.startsWith("/data/local/tmp/")) File(stageDir, File(source).name).also { dst ->
            if (!dst.isFile) instrumentation.uiAutomation.executeShellCommand("cp $source ${dst.absolutePath}").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { s -> s.readBytes() }
            }
        } else File(source)
        assumeTrue("Requires pq_model", model.isFile)
        val out = File(args.getString("pq_out") ?: File(context.getExternalFilesDir(null), "litert-eval/photo_prefill.jsonl").path)
        out.parentFile?.mkdirs()
        fun log(map: Map<String, Any?>) {
            val line = JSONObject(map).toString()
            println("PQ $line")
            out.appendText(line + "\n")
        }
        val assets = instrumentation.context.assets
        fun asset(name: String) = File(context.cacheDir, "pq_$name").also { f ->
            if (!f.isFile) assets.open("glasses_questions/$name").use { input -> f.outputStream().use { input.copyTo(it) } }
        }
        val wavs = assets.list("glasses_questions").orEmpty().filter { it.endsWith(".wav") }
        val pairs = wavs.mapNotNull { wav ->
            val photo = when {
                "color_of_object" in wav -> "socks_yellow.jpg"
                "read_sign" in wav -> "sign_no_parking.jpg"
                else -> null
            } ?: return@mapNotNull null
            Triple(wav, asset(wav), asset(photo))
        }
        val decidedClips = wavs.mapNotNull { wav ->
            val (photo, want) = decided[wav.substringAfter('_').substringBefore("__")] ?: return@mapNotNull null
            listOf(wav, asset(wav), asset(photo), want)
        }

        val backend = when (args.getString("pq_backend")) {
            "CPU" -> LocalComputeBackend.CPU
            "NPU" -> LocalComputeBackend.NPU_EXPERIMENTAL
            else -> LocalComputeBackend.GPU
        }
        LiteRtLocalInferenceEngine.promptCacheEnabled = args.getString("pq_cache") != "false"
        val engine = LiteRtLocalInferenceEngine(context)
        val load = engine.loadModel(model.path, EngineLoadConfig(4096, 4, backend, -1, speculativeDecoding = false))
        log(mapOf(
            "event" to "loaded", "backend" to load.activeBackend.name, "model" to model.name, "device" to android.os.Build.MODEL,
            "cache" to LiteRtLocalInferenceEngine.promptCacheEnabled,
        ))
        val system = ImageQuestionDefaults.spokenQuestionInstruction("en")
        // The decided photo question: the spoken questions' system turn (identical, so cacheable) and the photo note.
        val decisionSystem = "$system\n\n${ImageQuestionDefaults.SPOKEN_DECISION_ROUTES}"
        val clock = ImageQuestionDefaults.currentContextInstruction()
        fun config(image: File?, audio: File?, decide: Boolean = false) = GenerationConfig(
            prompt = "", temperature = 0.0, topP = 1.0, topK = 1, maxTokens = 48, repetitionPenalty = 1.0, seed = 0,
            structuredJson = false, imagePaths = listOfNotNull(image?.path), audioPath = audio?.path,
            systemInstruction = if (decide) decisionSystem else system, turnContext = clock,
            photoNote = ImageQuestionDefaults.PHOTO_QUESTION_NOTE.takeIf { decide },
        )
        // Warm-up (first request pays one-time costs), not logged.
        runCatching { engine.generate(config(pairs.first().third, pairs.first().second)) { } }

        val allModes = listOf(
            "together", "photo_first", "audio_first", "photo_first_noack", "audio_first_noack",
            "decide_together", "decide_photo_first", "decide_audio_first", "decide_together_spare",
        )
        val modes = args.getString("pq_modes")?.split(',')?.filter { it in allModes } ?: allModes
        for (mode in modes) {
            LiteRtLocalInferenceEngine.photoTurnAck = !mode.endsWith("noack")
            val decide = mode.startsWith("decide_")
            val arrival = mode.removePrefix("decide_").removeSuffix("_spare")
            val questions = if (decide) decidedClips else pairs.map { (name, wav, photo) -> listOf(name, wav, photo, null) }
            var hits = 0
            for ((name, wav, photo, want) in questions) {
                name as String; wav as File; photo as File
                // The app keeps a decided photo question's conversation open (follow-ups); the 2.5.1 one did not.
                // "_spare": no conversation kept (as after the follow-up window), so on GPU/NPU the engine primes the
                // spoken questions' system turn after each question and the next photo question starts from it.
                val session = SessionTurn("pq", continueExisting = false).takeIf { decide && !mode.endsWith("_spare") }
                var prefillS: Double? = null
                // Decided, recording first: the app decides on the recording alone (no prefill, no photo yet).
                val alone = decide && arrival == "audio_first"
                if (!alone && (arrival.startsWith("photo_first") || arrival.startsWith("audio_first"))) {
                    val t = System.nanoTime()
                    val ok = engine.prepareFirstPart(if (arrival.startsWith("photo")) config(photo, null, decide) else config(null, wav, decide))
                    prefillS = (System.nanoTime() - t) / 1e9
                    if (!ok) log(mapOf("event" to "prefill_failed", "mode" to mode, "clip" to name))
                }
                // Recording first, the app keeps only the route (32 tokens: AndroidSpokenQuestionPorts.ROUTE_ONLY_MAX_TOKENS).
                val first = timed(
                    engine,
                    if (alone) config(null, wav, decide).copy(session = session, maxTokens = 32) else config(photo, wav, decide).copy(session = session),
                )
                val decision = SpokenDecisionParser.parse(first.text, photoAttached = !alone)
                // The answer about the photo when the reply was [camera]: recording first, the photo is added to the
                // conversation once it arrives; a bare [camera] with the photo attached gets one more turn.
                val photoTurn = when {
                    !decide -> null
                    alone && decision.route.tag in setOf("camera", "answer") ->
                        config(photo, null).copy(turnContext = null, session = SessionTurn("pq", true, SpokenQuestionFlow.PHOTO_NOTE))
                    decision.askedCamera && decision.payload.isBlank() ->
                        config(null, null).copy(prompt = SpokenQuestionFlow.PHOTO_ANSWER_NOTE, turnContext = null, session = SessionTurn("pq", true))
                    else -> null
                }
                val second = photoTurn?.let { timed(engine, it) }
                val route = when {
                    !decide -> null
                    decision.route.tag == "camera" -> "answer" // recording first: answered with the photo
                    else -> decision.route.tag
                }
                if (route != null && route == want) hits++
                log(mapOf(
                    "event" to "q", "mode" to mode, "clip" to name, "prefill_s" to prefillS,
                    "ttft_s" to first.ttftS, "answer_s" to first.firstWordS, "total_s" to first.totalS,
                    // Recording first: from "the photo is ready" (the photo turn) to its first token.
                    "photo_turn_ttft_s" to second?.ttftS, "photo_turn_answer_s" to second?.firstWordS,
                    "expected" to want, "route" to route, "first_tag" to decision.route.tag.takeIf { decide },
                    "asked_camera" to decision.askedCamera.takeIf { decide },
                    "reply" to first.text.take(160), "photo_reply" to second?.text?.take(120), "error" to first.error,
                ))
                // Stands in for the spoken answer (the spare is primed meanwhile on GPU/NPU when no conversation is kept).
                kotlinx.coroutines.delay(1_500L)
            }
            if (decide) log(mapOf("event" to "summary", "mode" to mode, "hits" to hits, "n" to questions.size))
        }
        LiteRtLocalInferenceEngine.photoTurnAck = true
        LiteRtLocalInferenceEngine.promptCacheEnabled = true
        engine.unloadModel()
    }
}
