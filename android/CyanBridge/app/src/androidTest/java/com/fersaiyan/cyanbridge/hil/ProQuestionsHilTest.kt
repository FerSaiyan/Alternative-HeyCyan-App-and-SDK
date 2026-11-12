package com.fersaiyan.cyanbridge.hil

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionRelayClient
import com.fersaiyan.cyanbridge.agent.ProSubscriptionServerPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionVerifier
import com.fersaiyan.cyanbridge.ai.image.GlassesQuestionTestHooks
import com.fersaiyan.cyanbridge.ai.live.GeminiLiveForegroundService
import com.fersaiyan.cyanbridge.ai.router.QuestionRoutingPrefs
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionDefaults
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.shared.billing.ProLiveModelChoice
import com.fersaiyan.cyanbridge.shared.glasses.AiWakeWordRoute
import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import com.fersaiyan.cyanbridge.shared.prefs.AssistantPrefs
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.fersaiyan.cyanbridge.shared.settings.QuestionRoutingMode
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Paid, network-backed Pro question checks on the emulator's verified Pro account (opt-in:
 * `-e pro_questions true`). Drives the real glasses question flow through [GlassesQuestionTestHooks]
 * with distinct photos (`-e pro_images <device dir>` holding red_circle.jpg, blue_square.jpg and
 * green_triangle.jpg) so each answer proves which image the model saw.
 *
 * - [everyPickerModelAnswersPhotoAndVoiceQuestions]: every non-Live model of the Questions picker.
 * - [liveSessionAnswersEveryPhoto]: Gemini Live (Economy, then Private) through the foreground
 *   service: the second and third photo in one session, Live ↔ non-Live switching, a photo after a
 *   voice question, the "Initializing Live" cue, and the 60 s idle close.
 * - [livePreviewWithoutNotificationsAnswersEachPhoto]: the notifications-denied preview route. Revoking
 *   POST_NOTIFICATIONS kills the app process (and this instrumentation with it), so the host denies it
 *   before `am instrument` and passes `-e pro_notifications_denied true`; in that mode only this test runs:
 *   `pm revoke <pkg> android.permission.POST_NOTIFICATIONS` and `pm set-permission-flags <pkg>
 *   android.permission.POST_NOTIFICATIONS user-fixed` (no prompt), then clear-permission-flags and grant.
 *
 * Prints PRO_Q lines (model, question, reply). Logs never contain the account token.
 */
@RunWith(AndroidJUnit4::class)
class ProQuestionsHilTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val context: Context = instrumentation.targetContext
    private val events = LinkedBlockingQueue<Pair<String, String>>()
    private lateinit var saved: Map<String, Map<String, *>>
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var fixtures: File
    private lateinit var images: File
    private val notificationsDenied = args.getString("pro_notifications_denied") == "true"

    @Before
    fun setUp() {
        assumeTrue("pass -e pro_questions true", args.getString("pro_questions") == "true")
        assumeTrue("No Pro API token on this device", ProSubscriptionServerPrefs.getApiToken(context).isNotBlank())
        val entitlement = ProSubscriptionVerifier.verifyNow(context, strictForTesting = true)
        println("PRO_Q account plan=${entitlement.plan} active=${entitlement.active}")
        assertTrue("An active paid plan is required: ${entitlement.plan}",
            entitlement.active && entitlement.plan in setOf("cheap", "standard", "max"))
        images = File(args.getString("pro_images") ?: "/data/local/tmp/pro-q")
        listOf(RED, BLUE, GREEN).forEach { check(File(images, it.file).isFile) { "push ${it.file} to $images" } }

        saved = PREFS.associateWith { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap() }
        shell("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        if (!notificationsDenied) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        fixtures = File(context.cacheDir, "pro-q-fixtures").apply { deleteRecursively(); mkdirs() }
        listOf(VOICE_COLOR, VOICE_CAPITAL, PHOTO_SOCKS).forEach { name ->
            instrumentation.context.assets.open("glasses_questions/$name").use { input ->
                File(fixtures, name).outputStream().use { input.copyTo(it) }
            }
        }
        context.getSharedPreferences("cyanbridge_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_completed", true)
            .putBoolean("battery_opt_guide_suppress", true)
            .commit()
        LocalAgentPrefs.setGlassesAssistantMode(context, GlassesAssistantMode.CUSTOM_AI_PROVIDER)
        LocalAgentPrefs.setProviderType(context, AgentProviderType.PRO_SUBSCRIPTION)
        QuestionRoutingPrefs.setMode(context, QuestionRoutingMode.CLASSIC)
        ImageQuestionPreferences.setDefaultQuestion(context, PHOTO_QUESTION)
        GlassesQuestionTestHooks.events = { event, detail ->
            println("PRO_Q event=$event detail=${detail.replace("\n", " ").take(240)}")
            events += event to detail
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        SystemClock.sleep(3_000)
    }

    @After
    fun tearDown() {
        GlassesQuestionTestHooks.reset()
        if (::saved.isInitialized) GeminiLiveForegroundService.stop(context)
        scenario?.close()
        if (::saved.isInitialized) saved.forEach { (name, values) -> restore(name, values) }
    }

    @Test
    fun everyPickerModelAnswersPhotoAndVoiceQuestions() {
        assumeFalse("notifications-denied run: only the preview test", notificationsDenied)
        val catalog = ProSubscriptionRelayClient.fetchModelCatalog(context).getOrThrow()
        catalog.models.forEach { println("PRO_Q catalog id=${it.id} vision=${it.supportsVision} audio=${it.supportsAudio} label=${it.label}") }
        catalog.liveModes.forEach { println("PRO_Q catalog live_mode=${it.id} label=${it.label}") }
        val models = catalog.models.filterNot { ProLiveModelChoice.isLiveModel(it.id) }
        assertTrue("the relay listed no question models", models.isNotEmpty())
        val failures = mutableListOf<String>()
        for (model in models) {
            ProSubscriptionAiPrefs.setQuestionsModel(context, model.id)
            AssistantPrefs.WakeWord.route[context] = AiWakeWordRoute.VOICE_QUESTION
            // Photo plus spoken question, as the glasses send it: "What color are these socks?"
            val socks = ask(MainActivity.DEBUG_EVENT_PHOTO_BUTTON, audio = File(fixtures, VOICE_COLOR), photo = File(fixtures, PHOTO_SOCKS))
            check(model, "photo+voice socks", socks, listOf("yellow"), failures, required = model.supportsVision)
            // Photo without speech: the default photo question on a distinct image.
            val blue = ask(MainActivity.DEBUG_EVENT_PHOTO_BUTTON, audio = null, photo = File(images, BLUE.file))
            check(model, "photo blue_square", blue, BLUE.words, failures, required = model.supportsVision)
            // Voice question: "What's the capital of Australia?"
            val voice = ask(MainActivity.DEBUG_EVENT_WAKE, audio = File(fixtures, VOICE_CAPITAL), photo = null)
            check(model, "voice capital", voice, listOf("Canberra"), failures, required = model.supportsAudio)
        }
        assertTrue("model answers failed: $failures", failures.isEmpty())
    }

    @Test
    fun liveSessionAnswersEveryPhoto() {
        assumeFalse("notifications-denied run: only the preview test", notificationsDenied)
        val catalog = ProSubscriptionRelayClient.fetchModelCatalog(context).getOrThrow()
        val liveId = catalog.models.map { it.id }.firstOrNull(ProLiveModelChoice::isLiveModel) ?: LIVE_FALLBACK
        val nonLive = catalog.models.firstOrNull { it.supportsVision && !ProLiveModelChoice.isLiveModel(it.id) }
        val cueChecks = mutableListOf<Pair<Boolean, Boolean>>()
        fun useLive(economy: Boolean) {
            ProSubscriptionAiPrefs.setQuestionsModel(context, liveId)
            AssistantPrefs.GeminiLive.proEconomy[context] = economy
        }

        // Economy: one session, three photos (first starts it, second and third join it).
        useLive(economy = true)
        stopLiveAndWait()
        livePhoto(RED, "economy photo 1 (starts the session)", cueChecks)
        livePhoto(BLUE, "economy photo 2 (same session)", cueChecks)
        livePhoto(GREEN, "economy photo 3 (same session)", cueChecks)

        // Live -> non-Live -> Live while the Live session is still open.
        if (nonLive != null) {
            ProSubscriptionAiPrefs.setQuestionsModel(context, nonLive.id)
            val reply = ask(MainActivity.DEBUG_EVENT_PHOTO_BUTTON, audio = null, photo = File(images, RED.file))
            println("PRO_Q switch to non-Live model=${nonLive.id} liveStillRunning=${GeminiLiveForegroundService.isRunning(context)} reply=${reply.take(200)}")
            assertTrue("non-Live answer after Live lacks red: $reply", RED.words.all { reply.contains(it, ignoreCase = true) })
        }
        useLive(economy = true)
        livePhoto(BLUE, "economy photo after switching back from non-Live", cueChecks)

        // A photo after a voice question: the voice route opens Live, then the photo joins it.
        stopLiveAndWait()
        AssistantPrefs.WakeWord.route[context] = AiWakeWordRoute.VOICE_QUESTION
        val voiceSince = System.currentTimeMillis()
        GlassesQuestionTestHooks.questionAudio = { null }
        scenario!!.onActivity { it.debugGlassesEvent(MainActivity.DEBUG_EVENT_WAKE) }
        val voiceAnswer = awaitLiveAnswer(voiceSince, expect = emptyList())
        println("PRO_Q live voice question started=${GeminiLiveForegroundService.isRunning(context)} reply=${voiceAnswer.text.take(200)}")
        livePhoto(GREEN, "economy photo after a voice question", cueChecks)

        // Private: a fresh session with two photos.
        stopLiveAndWait()
        useLive(economy = false)
        privatePhotoAfterEconomy(RED, "private photo 1 (starts the session)", cueChecks)
        val last = livePhoto(BLUE, "private photo 2 (same session)", cueChecks)

        // Idle close: armed after the last answer, still open at 59 s, closed just after 60 s.
        val armedAt = last.idleArmedAtMs ?: awaitLog(last.since, "post-response idle timeout armed", 60_000)
        checkNotNull(armedAt) { "the idle timeout was never armed" }
        sleepUntil(armedAt + 59_000)
        val runningAt59 = GeminiLiveForegroundService.isRunning(context)
        sleepUntil(armedAt + 61_500)
        val runningAt61 = GeminiLiveForegroundService.isRunning(context)
        val closedAt = awaitLog(armedAt, "Closing Live after 60 seconds", 1_000)
        println("PRO_Q idle armedAt=$armedAt runningAt59s=$runningAt59 runningAt61.5s=$runningAt61 closedAfterMs=${closedAt?.minus(armedAt)}")
        assertTrue("Live closed before 60 s", runningAt59)
        assertFalse("Live still running after 61.5 s", runningAt61)

        cueChecks.forEachIndexed { i, (wasRunning, cueSpoken) ->
            println("PRO_Q cue photo=${i + 1} sessionWasRunning=$wasRunning cueSpoken=$cueSpoken")
        }
        assertTrue("the Initializing Live cue was spoken into a running session: $cueChecks",
            cueChecks.none { (wasRunning, cueSpoken) -> wasRunning && cueSpoken })
        assertTrue("a new session started without the Initializing Live cue: $cueChecks",
            cueChecks.none { (wasRunning, cueSpoken) -> !wasRunning && !cueSpoken })
    }

    @Test
    fun livePreviewWithoutNotificationsAnswersEachPhoto() {
        assumeTrue("pass -e pro_notifications_denied true after revoking POST_NOTIFICATIONS on the host", notificationsDenied)
        assertEquals("POST_NOTIFICATIONS must be denied before the app starts", PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS))
        val catalog = ProSubscriptionRelayClient.fetchModelCatalog(context).getOrThrow()
        val liveId = catalog.models.map { it.id }.firstOrNull(ProLiveModelChoice::isLiveModel) ?: LIVE_FALLBACK
        stopLiveAndWait()
        ProSubscriptionAiPrefs.setQuestionsModel(context, liveId)
        AssistantPrefs.GeminiLive.proEconomy[context] = true
        val answers = listOf(RED, BLUE, GREEN).mapIndexed { i, image ->
            val since = System.currentTimeMillis()
            GlassesQuestionTestHooks.questionAudio = { null }
            GlassesQuestionTestHooks.photo = { File(images, image.file) }
            scenario!!.onActivity { it.debugGlassesEvent(MainActivity.DEBUG_EVENT_PHOTO_BUTTON) }
            val answer = awaitLiveAnswer(since, image.words, tag = PREVIEW_TAG)
            val sessions = liveLog(since).count { it.tag == PREVIEW_TAG && "Sending initial Live turn image=true" in it.message }
            println("PRO_Q preview photo=${i + 1} image=${image.file} newPreviewSessions=$sessions reply=${answer.text.take(200)}")
            image to answer
        }
        answers.forEach { (image, answer) ->
            assertTrue("preview answer for ${image.file} lacks ${image.words}: ${answer.text}",
                image.words.all { answer.text.contains(it, ignoreCase = true) })
        }
    }

    // ---- Live helpers ----

    private data class Image(val file: String, val words: List<String>)
    private data class LiveAnswer(val text: String, val since: Long, val idleArmedAtMs: Long?)
    private data class LogLine(val epochMs: Long, val tag: String, val message: String)

    /**
     * The relay keeps the Economy reservation for a few minutes after that session closed and refuses
     * a Private token meanwhile ("Another economy session is still active ... reservation expires in
     * approximately N seconds", seen 2026-11-12). Wait it out once, then ask again.
     */
    private fun privatePhotoAfterEconomy(image: Image, label: String, cueChecks: MutableList<Pair<Boolean, Boolean>>): LiveAnswer {
        val first = livePhoto(image, label, cueChecks, check = false)
        if (image.words.all { first.text.contains(it, ignoreCase = true) }) return first
        val waitSeconds = Regex("""reservation expires in approximately (\d+) seconds""")
            .findAll(shell("logcat -d -s GeminiLiveClient:E")).lastOrNull()?.groupValues?.get(1)?.toLongOrNull()
        assertTrue("$label: Live answer lacks ${image.words}: ${first.text}", waitSeconds != null)
        println("PRO_Q live private refused while the Economy reservation lasts; waiting ${waitSeconds}s")
        cueChecks.removeAt(cueChecks.lastIndex)
        stopLiveAndWait()
        SystemClock.sleep((waitSeconds!! + 5) * 1_000)
        return livePhoto(image, "$label (after the Economy reservation expired)", cueChecks)
    }

    private fun livePhoto(image: Image, label: String, cueChecks: MutableList<Pair<Boolean, Boolean>>, check: Boolean = true): LiveAnswer {
        val wasRunning = GeminiLiveForegroundService.isRunning(context)
        events.clear()
        val since = System.currentTimeMillis()
        GlassesQuestionTestHooks.questionAudio = { null }
        GlassesQuestionTestHooks.photo = { File(images, image.file) }
        scenario!!.onActivity { it.debugGlassesEvent(MainActivity.DEBUG_EVENT_PHOTO_BUTTON) }
        val answer = awaitLiveAnswer(since, image.words)
        val cue = ImageQuestionDefaults.initializingLiveCueForLanguage(languageTag())
        val cueSpoken = generateSequence { events.poll() }.any { (name, detail) -> name == "speak" && detail == cue }
        cueChecks += wasRunning to cueSpoken
        val joined = liveLog(since).any { "Adding a photo question to the running Live session" in it.message }
        println("PRO_Q live $label image=${image.file} sessionWasRunning=$wasRunning joinedRunningSession=$joined cue=$cueSpoken reply=${answer.text.take(200)}")
        if (check) {
            assertTrue("$label: Live answer lacks ${image.words}: ${answer.text}",
                image.words.all { answer.text.contains(it, ignoreCase = true) })
        }
        return answer
    }

    /** Model transcription after [since] once it names every [expect] word (or settles), plus the idle-armed time. */
    private fun awaitLiveAnswer(since: Long, expect: List<String>, tag: String = SERVICE_TAG): LiveAnswer {
        val deadline = SystemClock.elapsedRealtime() + 90_000
        var text = ""
        var lastChange = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() < deadline) {
            val lines = liveLog(since).filter { it.tag == tag }
            val now = lines.filter { "Gemini transcription: " in it.message }
                .joinToString("") { it.message.substringAfter("Gemini transcription: ") }
            if (now != text) { text = now; lastChange = SystemClock.elapsedRealtime() }
            val armed = lines.firstOrNull { "post-response idle timeout armed" in it.message }?.epochMs
            val named = expect.isNotEmpty() && expect.all { text.contains(it, ignoreCase = true) }
            val settled = text.isNotBlank() && SystemClock.elapsedRealtime() - lastChange > 5_000
            if ((named || settled) && (tag != SERVICE_TAG || armed != null)) return LiveAnswer(text, since, armed)
            SystemClock.sleep(1_000)
        }
        return LiveAnswer(text, since, null)
    }

    private fun awaitLog(since: Long, fragment: String, timeoutMs: Long): Long? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            liveLog(since).firstOrNull { fragment in it.message }?.let { return it.epochMs }
            SystemClock.sleep(500)
        } while (SystemClock.elapsedRealtime() < deadline)
        return null
    }

    private fun stopLiveAndWait() {
        if (!GeminiLiveForegroundService.isRunning(context)) return
        GeminiLiveForegroundService.stop(context)
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (GeminiLiveForegroundService.isRunning(context) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(500)
        SystemClock.sleep(2_000)
    }

    private fun liveLog(since: Long): List<LogLine> {
        val out = shell("logcat -d -v epoch -s $SERVICE_TAG:D $PREVIEW_TAG:D")
        return out.lineSequence().mapNotNull { line ->
            LOG_LINE.find(line)?.let { m ->
                LogLine(m.groupValues[1].toLong() * 1_000 + m.groupValues[2].toLong(), m.groupValues[3].trim(), m.groupValues[4])
            }
        }.filter { it.epochMs >= since }.toList()
    }

    private fun sleepUntil(epochMs: Long) {
        val wait = epochMs - System.currentTimeMillis()
        if (wait > 0) SystemClock.sleep(wait)
    }

    // ---- Non-Live helpers ----

    /** Fires a glasses event and returns the provider reply ("reply" hook event). */
    private fun ask(event: String, audio: File?, photo: File?): String {
        events.clear()
        var served = false
        GlassesQuestionTestHooks.questionAudio = { if (served || audio == null) null else audio.also { served = true } }
        GlassesQuestionTestHooks.photo = photo?.let { file -> { file } }
        scenario!!.onActivity { it.debugGlassesEvent(event) }
        val deadline = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(3)
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return "<no reply within 3 min>"
            val (name, detail) = events.poll(remaining, TimeUnit.MILLISECONDS) ?: continue
            if (name == "reply") {
                // Clears the 5 s image-query debounce and lets TTS settle.
                SystemClock.sleep(7_000)
                return detail.substringAfter("text=")
            }
        }
    }

    private fun check(
        model: ProSubscriptionRelayClient.ModelOption,
        question: String,
        reply: String,
        expect: List<String>,
        failures: MutableList<String>,
        required: Boolean,
    ) {
        val ok = expect.all { reply.contains(it, ignoreCase = true) }
        println("PRO_Q model=${model.id} question='$question' expect=$expect ok=$ok required=$required reply=${reply.replace("\n", " ").take(240)}")
        if (required && !ok) failures += "${model.id}: $question"
    }

    private fun shell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    private fun languageTag(): String = context.resources.configuration.locales[0].toLanguageTag()

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
        const val SERVICE_TAG = "GeminiLiveService"
        const val PREVIEW_TAG = "GeminiLiveActivity"
        const val LIVE_FALLBACK = "google/gemini-3.1-flash-live-preview"
        const val PHOTO_QUESTION = "In a few words: what colour and what shape is the object in this photo?"
        const val VOICE_COLOR = "en_color_of_object__piper-lessac.wav"
        const val VOICE_CAPITAL = "en_capital_city__piper-lessac.wav"
        const val PHOTO_SOCKS = "socks_yellow.jpg"
        val RED = Image("red_circle.jpg", listOf("red", "circle"))
        val BLUE = Image("blue_square.jpg", listOf("blue", "square"))
        val GREEN = Image("green_triangle.jpg", listOf("green", "triangle"))
        // "1731300000.123  1234  1250 D GeminiLiveService: message"
        val LOG_LINE = Regex("""^\s*(\d+)\.(\d{3})\s+\d+\s+\d+\s+[VDIWEF]\s+([^:]+):\s(.*)$""")
        val PREFS = listOf(
            "question_routing", "ai_wake_word", "local_agent_prefs", "cyanbridge_prefs", "image_questions", "gemini_live",
            "pro_subscription_ai_prefs",
        )
    }
}
