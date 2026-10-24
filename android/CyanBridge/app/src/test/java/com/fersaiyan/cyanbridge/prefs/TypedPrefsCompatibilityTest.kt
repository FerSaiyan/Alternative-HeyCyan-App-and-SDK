package com.fersaiyan.cyanbridge.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.shared.glasses.AiWakeWordRoute
import com.fersaiyan.cyanbridge.shared.prefs.AgentPrefs
import com.fersaiyan.cyanbridge.shared.prefs.AllPrefs
import com.fersaiyan.cyanbridge.shared.prefs.AssistantPrefs
import com.fersaiyan.cyanbridge.shared.prefs.MediaPrefs
import com.fersaiyan.cyanbridge.shared.prefs.MemoryPrefs
import com.fersaiyan.cyanbridge.shared.prefs.edit
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings saved by earlier builds, straight through SharedPreferences, read back unchanged. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TypedPrefsCompatibilityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun raw(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @After
    fun clear() {
        AllPrefs.files.filterNot { it.secure }.forEach { raw(it.name).edit().clear().commit() }
    }

    @Test
    fun `values written by earlier builds read back with their types`() {
        raw("ai_wake_word").edit().putString("route", "IMAGE_QUESTION").commit()
        raw("local_agent_prefs").edit()
            .putInt("max_steps", 500)
            .putFloat("embedding_margin_threshold", 0.25f)
            .putLong("telegram_update_offset", 42L)
            .putBoolean("hide_system_apps", false)
            .putStringSet("capture_blacklist", setOf("com.example"))
            .commit()
        raw("daily_summary_prefs").edit().putLong("last_generated_ts_2026-10-24", 7L).commit()

        assertEquals(AiWakeWordRoute.IMAGE_QUESTION, AssistantPrefs.WakeWord.route[context])
        assertEquals(200, AgentPrefs.LocalAgent.maxSteps[context])
        assertEquals(0.25f, AgentPrefs.LocalAgent.embeddingMarginThreshold[context])
        assertEquals(42L, AgentPrefs.LocalAgent.telegramUpdateOffset[context])
        assertFalse(AgentPrefs.LocalAgent.hideSystemApps[context])
        assertEquals(setOf("com.example"), AgentPrefs.LocalAgent.captureBlacklist[context])
        assertEquals(7L, MemoryPrefs.DailySummary.lastGeneratedAtMs["2026-10-24"][context])
    }

    @Test
    fun `unset values read their declared defaults`() {
        assertEquals(AiWakeWordRoute.VOICE_QUESTION, AssistantPrefs.WakeWord.route[context])
        assertNull(AssistantPrefs.Language.selected[context])
        assertEquals(8, AgentPrefs.LocalAgent.maxSteps[context])
        assertTrue(AssistantPrefs.GeminiLive.proEconomy[context])
        assertNull(MediaPrefs.MeetingCapture.startAtMs[context])
    }

    @Test
    fun `typed writes land in the same file, key and type`() {
        AssistantPrefs.Language.selected[context] = "PORTUGUESE_BRAZIL"
        MediaPrefs.MeetingCapture.edit(context, commit = true) {
            this[MediaPrefs.MeetingCapture.isRecording] = true
            this[MediaPrefs.MeetingCapture.startAtMs] = 99L
            this[MediaPrefs.MeetingCapture.source] = null
        }

        assertEquals("PORTUGUESE_BRAZIL", raw("app_language").getString("selected_language", null))
        assertTrue(raw("meeting_capture").getBoolean("is_recording", false))
        assertEquals(99L, raw("meeting_capture").getLong("start_at_ms", 0L))
        assertFalse(raw("meeting_capture").contains("source"))
    }
}
