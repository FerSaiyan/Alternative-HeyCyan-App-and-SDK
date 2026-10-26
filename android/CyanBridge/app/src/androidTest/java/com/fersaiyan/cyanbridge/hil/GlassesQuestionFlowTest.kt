package com.fersaiyan.cyanbridge.hil

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.shared.prefs.AssistantPrefs
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.fersaiyan.cyanbridge.ai.image.GlassesQuestionTestHooks
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderType
import com.fersaiyan.cyanbridge.ai.router.QuestionRoutingPrefs
import com.fersaiyan.cyanbridge.shared.assistant.SpokenConversation
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.ai.search.WebSearchAssist
import com.fersaiyan.cyanbridge.ai.search.WebSearchEngine
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionDefaults
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogRepository
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import com.fersaiyan.cyanbridge.localmodels.session.LocalChatSessionManager
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import com.fersaiyan.cyanbridge.localmodels.settings.LocalGenerationSettings
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelRuntime
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelProjectorStore
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.shared.glasses.AiWakeWordRoute
import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.fersaiyan.cyanbridge.shared.settings.QuestionRoutingMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Drives the real glasses question flow in MainActivity (wake word or AI photo button → question
 * capture → on-device transcript or raw audio → routing → photo → local LiteRT model → speech)
 * with fixture question audio and photos through [GlassesQuestionTestHooks] (debug builds only).
 *
 * Required: -e gq_model <device path to a .litertlm with image+audio (gemma-4-E2B-it.litertlm), or a
 *   multimodal .gguf with -e gq_projector <mmproj .gguf>>
 * Fixtures default to the hermetic androidTest assets in glasses_questions/; override with
 *   -e gq_visual_audio <wav> -e gq_visual_image <jpg> -e gq_visual_expect <word,word>
 *   -e gq_plain_audio <wav> -e gq_plain_expect <word,word>
 * Optional: -e gq_backend CPU|GPU (default CPU), -e gq_strict true (answers must contain the
 * expected words), -e gq_target true (also run the decide-once target behaviour, expected to fail
 * until that flow lands).
 *
 * Settings touched are snapshotted and restored. Logs GQ_FLOW lines with timings.
 */
@RunWith(AndroidJUnit4::class)
class GlassesQuestionFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val context: Context = instrumentation.targetContext
    private val events = LinkedBlockingQueue<Pair<String, String>>()
    private lateinit var saved: Map<String, Map<String, *>>
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var fixtures: File
    private var userFactsBackup: String? = null

    @Before
    fun setUp() {
        val modelPath = args.getString("gq_model").orEmpty()
        assumeTrue("pass -e gq_model", modelPath.isNotBlank() && File(modelPath).isFile)
        assumeFalse("a remote OpenAI server is active", RemoteOpenAiPrefs.isActive(context))
        saved = PREFS.associateWith { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap() }
        listOf("android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS").forEach {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $it").close()
        }
        instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow").close()
        fixtures = File(context.cacheDir, "gq-fixtures").apply { deleteRecursively(); mkdirs() }
        copyAssets("glasses_questions")

        // No onboarding or battery guide on top of MainActivity (cyanbridge_prefs is restored afterwards).
        context.getSharedPreferences("cyanbridge_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_completed", true)
            .putBoolean("battery_opt_guide_suppress", true)
            .commit()
        LocalAgentPrefs.setGlassesAssistantMode(context, GlassesAssistantMode.CUSTOM_AI_PROVIDER)
        LocalAgentPrefs.setProviderType(context, AgentProviderType.LOCAL_AGENT)
        AiProviderPrefs.setProvider(context, AiProviderType.LOCAL_MODELS)
        listOf("local_models_registry", "local_model_projectors", "local_model_settings").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        val model = LocalModelStorageRepository.registerImportedModel(context, File(modelPath).nameWithoutExtension, File(modelPath))
        args.getString("gq_projector")?.let { LocalModelProjectorStore.attach(context, model, File(it)) }
        LocalModelStorageRepository.setSelectedModelId(context, model.id)
        LocalModelSettingsRepository.saveForModel(
            context,
            model.id,
            LocalGenerationSettings.defaultsFor(LocalModelCatalogRepository.findById(model.catalogId)).copy(
                modelRuntime = if (modelPath.endsWith(".gguf", true)) LocalModelRuntime.LLAMA_CPP else LocalModelRuntime.LITERT,
                computeBackend = if (args.getString("gq_backend") == "GPU") LocalComputeBackend.GPU else LocalComputeBackend.CPU,
                contextSize = 4096,
                temperature = 0.0,
                topK = 1,
                seed = 0,
                maxTokens = 48,
            ),
        )
        SpokenConversation.reset()
        // Through the store (Memory Vault), as the app reads it.
        userFactsBackup = LocalAgentMemoryStore.readText(LocalAgentMemoryStore.userFactsFile(context))
        GlassesQuestionTestHooks.events = { event, detail ->
            println("GQ_FLOW event=$event detail=${detail.replace("\n", " ").take(240)}")
            events += event to detail
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        SystemClock.sleep(3_000)
    }

    @After
    fun tearDown() {
        GlassesQuestionTestHooks.reset()
        SpokenConversation.reset()
        scenario?.close()
        userFactsBackup?.let { LocalAgentMemoryStore.writeText(LocalAgentMemoryStore.userFactsFile(context), it) }
        runCatching { runBlocking { LocalChatSessionManager.unload() } }
        if (::saved.isInitialized) {
            saved.forEach { (name, values) -> restore(name, values) }
        }
    }

    // ---- Current behaviour (must pass) ----

    @Test
    fun photoButtonSendsPhotoAndSpokenQuestionTogether() {
        QuestionRoutingPrefs.setMode(context, QuestionRoutingMode.CLASSIC)
        val result = ask(DEBUG_PHOTO_BUTTON, audio = visualAudio(), photo = visualImage())
        assertEquals("photo button answers with the photo", 1, result.images)
        assertTrue("the spoken question reached the model", result.questionOrAudio)
        checkAnswer(result.reply, visualExpect())
    }

    @Test
    fun classicWakeOnImageRouteTakesPhotoAndKeepsTheQuestion() {
        QuestionRoutingPrefs.setMode(context, QuestionRoutingMode.CLASSIC)
        AssistantPrefs.WakeWord.route[context] = AiWakeWordRoute.IMAGE_QUESTION
        val result = ask(DEBUG_WAKE, audio = visualAudio(), photo = visualImage())
        assertEquals(1, result.images)
        assertTrue(result.questionOrAudio)
        checkAnswer(result.reply, visualExpect())
    }

    @Test
    fun classicWakeOnVoiceRouteAnswersWithoutPhoto() {
        QuestionRoutingPrefs.setMode(context, QuestionRoutingMode.CLASSIC)
        AssistantPrefs.WakeWord.route[context] = AiWakeWordRoute.VOICE_QUESTION
        val result = ask(DEBUG_WAKE, audio = plainAudio(), photo = visualImage())
        assertEquals("a voice question takes no photo", 0, result.images)
        checkAnswer(result.reply, plainExpect())
    }

    @Test
    fun initializingCueOnlyWhenTheModelColdLoads() {
        QuestionRoutingPrefs.setMode(context, QuestionRoutingMode.CLASSIC)
        AssistantPrefs.WakeWord.route[context] = AiWakeWordRoute.VOICE_QUESTION
        runBlocking { LocalChatSessionManager.unload() }
        val cold = ask(DEBUG_WAKE, audio = plainAudio(), photo = null)
        val warm = ask(DEBUG_WAKE, audio = plainAudio(), photo = null)
        val cue = ImageQuestionDefaults.initializingLocalModelCueForLanguage(languageTag())
        assertEquals("cold question speaks the cue once", 1, cold.spoken.count { it == cue })
        assertEquals("warm question stays silent", 0, warm.spoken.count { it == cue })
    }

    // ---- Decision model (SMART, the default; opt-in here with -e gq_target true) ----

    @Test
    fun smartVoiceVisualQuestionTakesPhotoAndReusesAudio() {
        assumeTrue("pass -e gq_target true", args.getString("gq_target") == "true")
        QuestionRoutingPrefs.setMode(context, QuestionRoutingMode.SMART)
        AssistantPrefs.WakeWord.route[context] = AiWakeWordRoute.VOICE_QUESTION
        val result = ask(DEBUG_WAKE, audio = visualAudio(), photo = visualImage())
        assertEquals("CAMERA", result.route)
        assertEquals("visual question takes a photo", 1, result.images)
        assertTrue("the same recording is answered, not re-asked", result.questionOrAudio)
        checkAnswer(result.reply, visualExpect())
    }

    @Test
    fun smartVoicePlainQuestionAnswersWithoutPhoto() {
        assumeTrue("pass -e gq_target true", args.getString("gq_target") == "true")
        QuestionRoutingPrefs.setMode(context, QuestionRoutingMode.SMART)
        AssistantPrefs.WakeWord.route[context] = AiWakeWordRoute.VOICE_QUESTION
        val result = ask(DEBUG_WAKE, audio = plainAudio(), photo = visualImage())
        assertEquals("ANSWER", result.route)
        assertEquals("plain question is answered without the image", 0, result.images)
        checkAnswer(result.reply, plainExpect())
    }

    @Test
    fun smartVoicePhoneTaskGoesToTheLocalAgent() {
        assumeTrue("pass -e gq_target true", args.getString("gq_target") == "true")
        QuestionRoutingPrefs.setMode(context, QuestionRoutingMode.SMART)
        AssistantPrefs.WakeWord.route[context] = AiWakeWordRoute.VOICE_QUESTION
        // Hermetic: phone control stays off (local_agent_prefs is restored afterwards), so no app is driven.
        LocalAgentPrefs.setLocalAgentAutomationEnabled(context, false)
        val result = ask(DEBUG_WAKE, audio = fixture("gq_phone_audio", DEFAULT_PHONE_AUDIO), photo = null)
        assertEquals("PHONE", result.route)
        assertTrue("phone route reached the agent gate: ${result.spoken}",
            result.spoken.any { "Enable Local Agent phone control" in it })
        assertEquals("no photo for a phone task", 0, result.images)
    }

    @Test
    fun smartSpeechToSomeoneElseIsIgnored() {
        assumeTrue("pass -e gq_target true", args.getString("gq_target") == "true")
        QuestionRoutingPrefs.setMode(context, QuestionRoutingMode.SMART)
        AssistantPrefs.WakeWord.route[context] = AiWakeWordRoute.VOICE_QUESTION
        val result = ask(DEBUG_WAKE, audio = fixture("gq_ignore_audio", DEFAULT_IGNORE_AUDIO), photo = null)
        println("GQ_FLOW ignore route=${result.route} spoken=${result.spoken}")
        // Zero-shot ignore is weak (host/device probes); strict mode requires it.
        if (args.getString("gq_strict") == "true") assertEquals("IGNORE", result.route)
    }

    @Test
    fun smartWebQuestionReadsTheWebAnswer() {
        assumeTrue("pass -e gq_target true", args.getString("gq_target") == "true")
        QuestionRoutingPrefs.setMode(context, QuestionRoutingMode.SMART)
        AssistantPrefs.WakeWord.route[context] = AiWakeWordRoute.VOICE_QUESTION
        // "What's the weather like today?" (needs the network). -e gq_web_engine GOOGLE|DUCKDUCKGO
        QuestionRoutingPrefs.setWebSearchEngine(context, webEngine())
        // -e gq_web_results_only true: answer from the top results as if there were no AI Overview.
        GlassesQuestionTestHooks.webResultsOnly = args.getString("gq_web_results_only") == "true"
        // -e gq_app_language PORTUGUESE_BRAZIL: the app language (search language, cues) without switching locales.
        args.getString("gq_app_language")?.let { language ->
            context.getSharedPreferences("app_language", Context.MODE_PRIVATE).edit().putString("selected_language", language).commit()
        }
        val result = ask(DEBUG_WAKE, audio = fixture("gq_web_audio", DEFAULT_WEB_AUDIO), photo = null)
        assertEquals("SEARCH", result.route)
        val expectedFailure = args.getString("gq_web_expect_failure")
        if (expectedFailure != null) {
            // The device is offline on purpose: the search fails fast and the reply says so.
            assertTrue("search failure $expectedFailure: ${result.web}", result.web?.contains("failure=$expectedFailure") == true)
        } else {
            assertTrue("a web search ran: ${result.web}", result.web?.contains("failure=null") == true)
        }
        assertTrue("the answer was read out: ${result.spoken}", result.spoken.any { it.contains(result.reply.take(40)) })
        assertTrue("the search found nothing: ${result.reply}", "no relevant information" !in result.reply.lowercase())
        checkAnswer(result.reply, words("gq_web_expect", ""))
    }

    /** A search engine from the app's off-screen WebView alone (no model). -e gq_web_engine GOOGLE|DUCKDUCKGO */
    @Test
    fun webSearchFromTheAppWebView() {
        assumeTrue("pass -e gq_target true", args.getString("gq_target") == "true")
        val queries = (args.getString("gq_web_queries")
            ?: "how tall is the eiffel tower in feet|weather in lisbon tomorrow|vai chover em lisboa amanhã#pt-BR").split('|')
        val results = queries.map { query ->
            // "query#pt-BR" passes a language tag (Google's hl).
            query to runBlocking {
                WebSearchAssist.search(
                    context, query.substringBefore('#'), webEngine(), query.substringAfter('#', "").ifBlank { null },
                )
            }
        }
        results.forEach { (query, r) ->
            println("GQ_WEB engine=${r.engine} query='$query' failure=${r.failure} ms=${r.elapsedMs} answer=${r.answer} infoBox=${r.infoBox?.take(160)?.replace('\n', '/')} results=${r.snippets.map { it.take(90) }}")
        }
        // -e gq_web_expect_failure NETWORK: the device is offline on purpose.
        val expectedFailure = args.getString("gq_web_expect_failure")?.let { WebSearchAssist.Failure.valueOf(it) }
        if (expectedFailure != null) {
            assertTrue("expected $expectedFailure", results.all { it.second.failure == expectedFailure })
            return
        }
        assertTrue("the engine blocked the WebView", results.none { it.second.blocked })
        assertTrue("no AI answer for the English query", results.first().second.answer != null)
    }

    // ---- Follow-ups and memory (local LiteRT keeps the conversation open between turns) ----

    /**
     * "What's the capital of Australia?" then follow-ups only answerable from the first turn. Every turn after
     * the first must continue the open conversation. -e gq_followup_clips a.wav,b.wav,… overrides the chain.
     */
    @Test
    fun smartFollowUpsContinueTheConversation() {
        assumeTrue("pass -e gq_target true", args.getString("gq_target") == "true")
        QuestionRoutingPrefs.setMode(context, QuestionRoutingMode.SMART)
        AssistantPrefs.WakeWord.route[context] = AiWakeWordRoute.VOICE_QUESTION
        val clips = (args.getString("gq_followup_clips") ?: DEFAULT_FOLLOWUP_CHAIN).split(',').map { fixture(it.trim()) }
        val turns = askChain(clips)
        assertEquals("every follow-up was answered", clips.size, turns.size)
        turns.drop(1).forEach { assertEquals("turn ${it.n} continued the conversation", true, it.continued) }
        // Lenient by default (2B model): the population answer must mention Canberra's size.
        checkAnswer(turns[1].reply, listOf("thousand", "000", "Canberra"))
    }

    /** Keeps asking follow-ups (cycling the chain) to see how many fit a 4k context before it restarts. */
    @Test
    fun smartFollowUpChainUntilTheContextIsFull() {
        val count = args.getString("gq_followup_turns")?.toInt() ?: 0
        assumeTrue("pass -e gq_target true -e gq_followup_turns N", args.getString("gq_target") == "true" && count > 0)
        QuestionRoutingPrefs.setMode(context, QuestionRoutingMode.SMART)
        AssistantPrefs.WakeWord.route[context] = AiWakeWordRoute.VOICE_QUESTION
        val chain = (args.getString("gq_followup_clips") ?: LONG_FOLLOWUP_CHAIN).split(',').map { fixture(it.trim()) }
        val turns = askChain(List(count) { chain[it % chain.size] })
        val restarts = turns.drop(1).filter { !it.continued }.map { it.n }
        println("GQ_CHAIN turns=${turns.size} restartedAt=$restarts tokens=${turns.map { it.sessionTokens }} decisionMs=${turns.map { it.decisionMs }}")
        assertEquals("every follow-up was answered", count, turns.size)
    }

    /**
     * Memory questions with fictional facts in USER_FACTS.md (-e gq_memory on, default) or none (off).
     * -e gq_memory_clips a.wav,… overrides the questions; controls run with the same facts.
     */
    @Test
    fun smartMemoryQuestions() {
        assumeTrue("pass -e gq_target true", args.getString("gq_target") == "true")
        QuestionRoutingPrefs.setMode(context, QuestionRoutingMode.SMART)
        AssistantPrefs.WakeWord.route[context] = AiWakeWordRoute.VOICE_QUESTION
        val withFacts = args.getString("gq_memory") != "off"
        val factsFile = LocalAgentMemoryStore.userFactsFile(context)
        LocalAgentMemoryStore.writeText(factsFile, if (withFacts) TEST_USER_FACTS else "# User Facts\n\n- Name: \n- Preferences: \n\n")
        assertEquals("facts stored", withFacts, LocalAgentMemoryStore.readText(factsFile).contains("Biscuit"))
        val questions = (args.getString("gq_memory_clips") ?: DEFAULT_MEMORY_QUESTIONS).split(',').map { it.trim() }
        val results = questions.map { name ->
            SpokenConversation.reset()
            val result = ask(DEBUG_WAKE, audio = fixture(name), photo = visualImage())
            val expect = MEMORY_EXPECT.entries.firstOrNull { name.contains(it.key) }?.value.orEmpty()
            val ok = expect.isEmpty() || expect.any { result.reply.contains(it, ignoreCase = true) }
            println("GQ_MEMORY facts=$withFacts clip=$name route=${result.route} ok=$ok ms=${result.ms} reply=${result.reply.take(160)}")
            ok
        }
        println("GQ_MEMORY facts=$withFacts correct=${results.count { it }}/${results.size}")
        if (args.getString("gq_strict") == "true" && withFacts) assertTrue("memory answers", results.all { it })
    }

    // ---- helpers ----

    private data class ChainTurn(
        val n: Int,
        val route: String?,
        val continued: Boolean,
        val sessionTokens: Int?,
        val decisionMs: Long?,
        val reply: String,
    )

    /** Asks clips[0] after a wake event and each next clip in the follow-up window after the previous answer. */
    private fun askChain(clips: List<File>): List<ChainTurn> {
        events.clear()
        var next = 0
        GlassesQuestionTestHooks.questionAudio = { clips.getOrNull(next)?.also { next++ } }
        GlassesQuestionTestHooks.photo = { visualImage() }
        scenario!!.onActivity { activity -> activity.debugGlassesEvent(DEBUG_WAKE) }
        val turns = mutableListOf<ChainTurn>()
        var route: String? = null
        var decisionMs: Long? = null
        var turnInfo: String? = null
        val deadline = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(args.getString("gq_timeout_min")?.toLong() ?: 8) * clips.size
        while (turns.size < clips.size) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            check(remaining > 0) { "chain stalled after ${turns.size} turns" }
            val (name, detail) = events.poll(remaining, TimeUnit.MILLISECONDS) ?: continue
            when (name) {
                "spoken_decision" -> {
                    route = Regex("route=(\\w+)").find(detail)?.groupValues?.get(1)
                    decisionMs = Regex("ms=(\\d+)").find(detail)?.groupValues?.get(1)?.toLong()
                }
                "spoken_turn" -> turnInfo = detail
                "reply" -> {
                    val reply = detail.substringAfter("text=")
                    if (reply.startsWith("Local Models error")) error("turn ${turns.size + 1} failed: $reply")
                    val turn = ChainTurn(
                        n = turns.size + 1,
                        route = route,
                        continued = turnInfo?.contains("continued=true") == true,
                        sessionTokens = turnInfo?.let { Regex("sessionTokens=(\\d+)").find(it)?.groupValues?.get(1)?.toInt() },
                        decisionMs = decisionMs,
                        reply = reply,
                    )
                    println("GQ_CHAIN turn=${turn.n} route=${turn.route} continued=${turn.continued} tokens=${turn.sessionTokens} decisionMs=${turn.decisionMs} reply=${reply.take(160)}")
                    turns += turn
                    route = null; decisionMs = null; turnInfo = null
                }
            }
        }
        SystemClock.sleep(7_000)
        return turns
    }

    private fun fixture(name: String): File =
        (if (name.startsWith("/")) File(name) else File(fixtures, name)).also { check(it.isFile) { "missing fixture $it" } }

    private data class FlowResult(
        val images: Int,
        val questionOrAudio: Boolean,
        val reply: String,
        val spoken: List<String>,
        val ms: Long,
        /** SpokenRoute name from the decision model, or null on the classic path. */
        val route: String? = null,
        /** The web_search event detail on the search route. */
        val web: String? = null,
    )

    private fun ask(event: String, audio: File, photo: File?): FlowResult {
        events.clear()
        var served = false
        GlassesQuestionTestHooks.questionAudio = { if (served) null else audio.also { served = true } }
        GlassesQuestionTestHooks.photo = photo?.let { file -> { file } }
        val started = SystemClock.elapsedRealtime()
        scenario!!.onActivity { activity -> activity.debugGlassesEvent(event) }
        val spoken = mutableListOf<String>()
        var imageQuery: String? = null
        var route: String? = null
        var web: String? = null
        val deadline = started + TimeUnit.MINUTES.toMillis(args.getString("gq_timeout_min")?.toLong() ?: 8)
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            check(remaining > 0) { "no model reply within the timeout; events so far: spoken=$spoken imageQuery=$imageQuery" }
            val (name, detail) = events.poll(remaining, TimeUnit.MILLISECONDS) ?: continue
            when (name) {
                "speak" -> spoken += detail
                "image_query" -> imageQuery = detail
                "spoken_decision" -> route = Regex("route=(\\w+)").find(detail)?.groupValues?.get(1)
                "web_search" -> web = detail
                "reply" -> {
                    val reply = detail.substringAfter("text=")
                    val ms = SystemClock.elapsedRealtime() - started
                    val images = Regex("images=(\\d+)").find(detail)?.groupValues?.get(1)?.toInt() ?: 0
                    val hasAudio = detail.contains("audio=true")
                    // Let the follow-up window (silent) and TTS settle before the next question;
                    // also clears the 5 s image-query debounce.
                    SystemClock.sleep(7_000)
                    // Late speech, and the route event when it came after the reply.
                    generateSequence { events.poll() }.forEach { (late, lateDetail) ->
                        when (late) {
                            "speak" -> spoken += lateDetail
                            "spoken_decision" -> route = Regex("route=(\\w+)").find(lateDetail)?.groupValues?.get(1)
                            "web_search" -> web = lateDetail
                        }
                    }
                    val questioned = hasAudio || imageQuery?.contains("question=true") == true
                    println(
                        "GQ_FLOW result event=$event images=$images questionOrAudio=$questioned route=$route " +
                            "ms=$ms web=$web reply=${reply.take(160)}",
                    )
                    return FlowResult(images, questioned, reply, spoken, ms, route, web)
                }
            }
        }
    }

    private fun checkAnswer(reply: String, expect: List<String>) {
        assertTrue("model error: $reply", !reply.startsWith("Local Models error"))
        if (expect.isEmpty()) return
        val ok = expect.any { reply.contains(it, ignoreCase = true) }
        println("GQ_FLOW answer_check expect=$expect ok=$ok")
        if (args.getString("gq_strict") == "true") assertTrue("answer lacks any of $expect: $reply", ok)
    }

    private fun webEngine(): WebSearchEngine =
        args.getString("gq_web_engine")?.let { WebSearchEngine.valueOf(it) } ?: WebSearchEngine.DUCKDUCKGO

    private fun fixture(arg: String, default: String): File =
        File(args.getString(arg) ?: File(fixtures, default).path).also { check(it.isFile) { "missing fixture $it" } }

    private fun visualAudio() = fixture("gq_visual_audio", DEFAULT_VISUAL_AUDIO)
    private fun visualImage() = fixture("gq_visual_image", DEFAULT_VISUAL_IMAGE)
    private fun plainAudio() = fixture("gq_plain_audio", DEFAULT_PLAIN_AUDIO)
    private fun visualExpect() = words("gq_visual_expect", DEFAULT_VISUAL_EXPECT)
    private fun plainExpect() = words("gq_plain_expect", DEFAULT_PLAIN_EXPECT)
    private fun words(arg: String, default: String) = (args.getString(arg) ?: default).split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun languageTag(): String =
        context.resources.configuration.locales[0].toLanguageTag()

    private fun copyAssets(dir: String) {
        val assets = instrumentation.context.assets
        (assets.list(dir) ?: emptyArray()).forEach { name ->
            assets.open("$dir/$name").use { input -> File(fixtures, name).outputStream().use { input.copyTo(it) } }
        }
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
        const val DEBUG_WAKE = MainActivity.DEBUG_EVENT_WAKE
        const val DEBUG_PHOTO_BUTTON = MainActivity.DEBUG_EVENT_PHOTO_BUTTON
        val PREFS = listOf(
            "local_models_registry", "local_model_projectors", "local_model_settings",
            "question_routing", "ai_wake_word", "local_agent_prefs", "ai_provider_prefs", "cyanbridge_prefs", "app_language",
        )
        // Hermetic fixtures (app/src/androidTest/assets/glasses_questions, see its manifest.json):
        // "What color are these socks?" + a photo of yellow socks; "What's the capital of Australia?".
        const val DEFAULT_VISUAL_AUDIO = "en_color_of_object__piper-lessac.wav"
        const val DEFAULT_VISUAL_IMAGE = "socks_yellow.jpg"
        const val DEFAULT_PLAIN_AUDIO = "en_capital_city__piper-lessac.wav"
        const val DEFAULT_PHONE_AUDIO = "en_play_song__piper-lessac.wav"
        // The piper clip of this sentence intermittently fails the AVD audio encoder (executor.cc:817).
        const val DEFAULT_IGNORE_AUDIO = "en_talk_to_other__kokoro-af_heart.wav"
        const val DEFAULT_WEB_AUDIO = "en_weather_today__piper-lessac.wav"
        const val DEFAULT_FOLLOWUP_CHAIN = "en_capital_city__piper-lessac.wav,en_followup_population__piper-lessac.wav," +
            "en_followup_building__piper-lessac.wav,en_tell_me_more__piper-lessac.wav"
        const val LONG_FOLLOWUP_CHAIN = DEFAULT_FOLLOWUP_CHAIN + ",en_tell_joke__piper-lessac.wav,en_next_holiday__piper-lessac.wav"
        const val DEFAULT_MEMORY_QUESTIONS = "en_memory_wife__piper-lessac.wav,en_memory_allergy__piper-lessac.wav," +
            "en_memory_dog__piper-lessac.wav,en_memory_dentist__piper-lessac.wav,en_capital_city__piper-lessac.wav," +
            "en_color_of_object__piper-lessac.wav"
        val MEMORY_EXPECT = mapOf(
            "memory_wife" to listOf("Ana"), "memory_allergy" to listOf("peanut"), "memory_dog" to listOf("Biscuit"),
            "memory_dentist" to listOf("Thursday"), "capital_city" to listOf("Canberra"), "color_of_object" to listOf("yellow"),
        )
        /** Fictional facts (same as tools/benchmarks/eval_spoken_route_tags.py MEMORY). */
        const val TEST_USER_FACTS = "# User Facts\n\n- Name: Rafael. Lives in Ribeirão Preto, Brazil; works as a software developer.\n" +
            "- Wife: Ana. Dog: Biscuit, a beagle.\n- Allergic to peanuts.\n- Dentist appointment on Thursday at 3 pm.\n" +
            "- Likes Pokémon TCG, running and Palmeiras.\n- Prefers short answers and Celsius.\n"
        const val DEFAULT_VISUAL_EXPECT = "yellow"
        const val DEFAULT_PLAIN_EXPECT = "canberra"
    }
}
