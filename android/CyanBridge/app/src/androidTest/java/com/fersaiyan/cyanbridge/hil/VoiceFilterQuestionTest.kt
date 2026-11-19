package com.fersaiyan.cyanbridge.hil

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.ai.image.GlassesQuestionTestHooks
import com.fersaiyan.cyanbridge.ai.router.QuestionRoutingPrefs
import com.fersaiyan.cyanbridge.ai.speaker.EnrollmentInfo
import com.fersaiyan.cyanbridge.ai.speaker.SpeakerModelStore
import com.fersaiyan.cyanbridge.ai.speaker.SpeakerModels
import com.fersaiyan.cyanbridge.ai.speaker.TemplateResult
import com.fersaiyan.cyanbridge.ai.speaker.VoiceProfile
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionDefaults
import com.fersaiyan.cyanbridge.shared.glasses.AiWakeWordRoute
import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import com.fersaiyan.cyanbridge.shared.prefs.AssistantPrefs
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.fersaiyan.cyanbridge.shared.settings.QuestionRoutingMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * "Answer only my voice" through the real glasses question flow (debug wake word → question
 * capture → speaker check → provider), with the hermetic Piper clips in androidTest assets:
 * the wearer enrolls with three piper-lessac phrases; the other voice is piper-faber (scores 0.09 with
 * the default model, below its 0.13 threshold; kokoro-af_heart scores 0.19 and is answered as uncertain).
 *
 * Required: -e vf_model <device path to the default speaker model .tflite> (not in the repo;
 * docs/AUDIO_SPEAKER_FILTER.md). Skipped without it. Optional: -e vf_routing SMART|CLASSIC (default
 * CLASSIC; SMART needs a local model that hears audio). Any provider works: the test checks whether
 * a provider request was issued ("provider_request" event), not the answer.
 */
@RunWith(AndroidJUnit4::class)
class VoiceFilterQuestionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val context: Context = instrumentation.targetContext
    private val events = LinkedBlockingQueue<Pair<String, String>>()
    private lateinit var saved: Map<String, Map<String, *>>
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var fixtures: File

    @Before
    fun setUp() {
        val model = File(args.getString("vf_model").orEmpty())
        assumeTrue("pass -e vf_model", model.isFile)
        saved = PREFS.associateWith { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap() }
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO").close()
        fixtures = File(context.cacheDir, "vf-fixtures").apply { deleteRecursively(); mkdirs() }
        val assets = instrumentation.context.assets
        (ENROLL + listOf(WEARER_QUESTION, OTHER_QUESTION)).forEach { name ->
            assets.open("glasses_questions/$name").use { input -> File(fixtures, name).outputStream().use { input.copyTo(it) } }
        }
        context.getSharedPreferences("cyanbridge_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_completed", true)
            .putBoolean("battery_opt_guide_suppress", true)
            .commit()
        LocalAgentPrefs.setGlassesAssistantMode(context, GlassesAssistantMode.CUSTOM_AI_PROVIDER)
        LocalAgentPrefs.setProviderType(context, AgentProviderType.LOCAL_AGENT)
        QuestionRoutingPrefs.setMode(context, QuestionRoutingMode.valueOf(args.getString("vf_routing") ?: "CLASSIC"))
        AssistantPrefs.WakeWord.route[context] = AiWakeWordRoute.VOICE_QUESTION

        val spec = SpeakerModels.DEFAULT
        SpeakerModelStore.stagedFile(context, spec)!!.also { it.parentFile?.mkdirs(); model.copyTo(it, overwrite = true) }
        VoiceProfile.delete(context)
        val store = VoiceProfile.store(context)
        ENROLL.forEachIndexed { i, name -> store.savePendingPhrase(i, wavPcm(File(fixtures, name))) }
        store.complete(EnrollmentInfo("en-US", "test", ENROLL.size, consentAtMs = 1L, completedAtMs = 1L))
        VoiceProfile.setAnswerOnlyMyVoice(context, true)
        assertTrue(runBlocking { VoiceProfile.ensureTemplate(context, spec) } is TemplateResult.Ready)

        GlassesQuestionTestHooks.events = { event, detail ->
            println("VF_FLOW event=$event detail=${detail.replace("\n", " ").take(160)}")
            events += event to detail
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        SystemClock.sleep(3_000)
    }

    @After
    fun tearDown() {
        GlassesQuestionTestHooks.reset()
        scenario?.close()
        VoiceProfile.delete(context)
        if (::saved.isInitialized) saved.forEach { (name, values) -> restore(name, values) }
    }

    @Test
    fun anotherVoiceIsNotAnsweredAndTheWearerIs() {
        val other = ask(OTHER_QUESTION)
        assertTrue("another voice was not blocked", other.blocked != null)
        assertTrue("a provider request was made for another voice", !other.providerRequest)
        assertEquals(ImageQuestionDefaults.notWearerCueForLanguage(languageTag()), other.spoken.lastOrNull())

        val wearer = ask(WEARER_QUESTION)
        assertNull("the wearer was blocked", wearer.blocked)
        assertTrue("no provider request for the wearer", wearer.providerRequest)
    }

    @Test
    fun withTheSwitchOffOrWithoutAProfileAnotherVoiceIsAnswered() {
        VoiceProfile.setAnswerOnlyMyVoice(context, false)
        val switchedOff = ask(OTHER_QUESTION)
        assertNull(switchedOff.blocked)
        assertTrue("no provider request with the switch off", switchedOff.providerRequest)

        VoiceProfile.setAnswerOnlyMyVoice(context, true)
        VoiceProfile.delete(context)
        val deleted = ask(OTHER_QUESTION)
        assertNull(deleted.blocked)
        assertTrue("no provider request after the profile was deleted", deleted.providerRequest)
    }

    private data class Asked(val blocked: String?, val providerRequest: Boolean, val spoken: List<String>)

    /** Asks one question by the debug wake word and collects events until the flow settles. */
    private fun ask(clip: String): Asked {
        events.clear()
        var served = false
        GlassesQuestionTestHooks.questionAudio = { if (served) null else File(fixtures, clip).also { served = true } }
        scenario!!.onActivity { it.debugGlassesEvent(MainActivity.DEBUG_EVENT_WAKE) }
        var blocked: String? = null
        var provider = false
        val spoken = mutableListOf<String>()
        // A blocked question settles quickly; an answered one is followed to its reply (a local model
        // on the emulator can take minutes), so the next question does not find this one active.
        val deadline = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(args.getString("vf_timeout_min")?.toLong() ?: 6)
        var settleUntil = Long.MAX_VALUE
        while (SystemClock.elapsedRealtime() < minOf(deadline, settleUntil)) {
            val (name, detail) = events.poll(500, TimeUnit.MILLISECONDS) ?: continue
            when (name) {
                "speaker_blocked" -> { blocked = detail; settleUntil = SystemClock.elapsedRealtime() + SETTLE_MS }
                "provider_request" -> provider = true
                "reply" -> settleUntil = SystemClock.elapsedRealtime() + SETTLE_MS
                "speak" -> spoken += detail
            }
        }
        println("VF_FLOW result clip=$clip blocked=$blocked providerRequest=$provider spoken=$spoken")
        SystemClock.sleep(SETTLE_MS)
        return Asked(blocked, provider, spoken)
    }

    private fun languageTag(): String = com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences.get(context).appLanguageTag

    private fun wavPcm(file: File): ShortArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(buffer.remaining()).also { buffer.get(it) }
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
        const val SETTLE_MS = 5_000L
        val ENROLL = listOf(
            "en_memory_wife__piper-lessac.wav", "en_tell_joke__piper-lessac.wav", "en_read_sign__piper-lessac.wav",
        )
        const val WEARER_QUESTION = "en_capital_city__piper-lessac.wav"
        const val OTHER_QUESTION = "ptbr_tell_joke__piper-faber.wav"
        val PREFS = listOf("question_routing", "ai_wake_word", "local_agent_prefs", "cyanbridge_prefs")
    }
}
