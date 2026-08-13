package com.fersaiyan.cyanbridge.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.shared.glasses.GlassesAssistantMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalAgentPrefsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `new installs default glasses questions to local models`() {
        context.getSharedPreferences("local_agent_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        assertEquals(GlassesAssistantMode.CUSTOM_AI_PROVIDER, LocalAgentPrefs.getGlassesAssistantMode(context))
        assertEquals(
            com.fersaiyan.cyanbridge.shared.settings.AgentProviderType.LOCAL_AGENT,
            LocalAgentPrefs.getProviderType(context),
        )
    }

    @Test
    fun `glasses assistant mode persists every supported selection`() {
        GlassesAssistantMode.entries.forEach { mode ->
            LocalAgentPrefs.setGlassesAssistantMode(context, mode)
            assertEquals(mode, LocalAgentPrefs.getGlassesAssistantMode(context))
        }
    }

    @Test
    fun `legacy assistant selections migrate to the two supported modes`() {
        val prefs = context.getSharedPreferences("local_agent_prefs", Context.MODE_PRIVATE)
        listOf("GEMINI", "CHAT_GPT", "PHONE_DEFAULT").forEach { legacy ->
            prefs.edit().putString("glasses_assistant_mode", legacy).commit()
            assertEquals(GlassesAssistantMode.PHONE_ASSISTANT, LocalAgentPrefs.getGlassesAssistantMode(context))
        }

        prefs.edit().putString("glasses_assistant_mode", "CHOSEN_PROVIDER").commit()
        assertEquals(GlassesAssistantMode.CUSTOM_AI_PROVIDER, LocalAgentPrefs.getGlassesAssistantMode(context))
        assertEquals("CUSTOM_AI_PROVIDER", prefs.getString("glasses_assistant_mode", null))
    }
}
