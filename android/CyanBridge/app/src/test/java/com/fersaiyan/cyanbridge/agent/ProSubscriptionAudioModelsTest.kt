package com.fersaiyan.cyanbridge.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.shared.prefs.ProPrefs
import com.fersaiyan.cyanbridge.shared.prefs.clear
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProSubscriptionAudioModelsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clear() {
        ProPrefs.Ai.clear(context)
    }

    @Test
    fun unknownCatalogKeepsSttFirst() {
        ProSubscriptionAiPrefs.setQuestionsModel(context, "google/gemini-3.7-flash")
        assertFalse(ProSubscriptionAiPrefs.questionsModelHearsAudio(context))
        assertTrue(ProSubscriptionAiPrefs.audioCapableModelsStale(context))
    }

    @Test
    fun questionsModelHearsAudioOnlyWhenTheRelayListsIt() {
        ProSubscriptionAiPrefs.saveAudioCapableModels(context, listOf("google/gemini-3.7-flash", "xiaomi/mimo-v2.6-flash"), nowMs = 1_000L)
        ProSubscriptionAiPrefs.setQuestionsModel(context, "Google/Gemini-3.7-Flash")
        assertTrue(ProSubscriptionAiPrefs.questionsModelHearsAudio(context))
        ProSubscriptionAiPrefs.setQuestionsModel(context, "deepseek/deepseek-v4-flash-vision-exp")
        assertFalse(ProSubscriptionAiPrefs.questionsModelHearsAudio(context))
        assertFalse(ProSubscriptionAiPrefs.audioCapableModelsStale(context, nowMs = 2_000L))
        assertTrue(ProSubscriptionAiPrefs.audioCapableModelsStale(context, nowMs = 1_000L + 25 * 60 * 60 * 1000L))
    }
}
