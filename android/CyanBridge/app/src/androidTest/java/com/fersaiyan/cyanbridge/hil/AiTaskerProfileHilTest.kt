package com.fersaiyan.cyanbridge.hil

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.ai.image.ImageQuestionBroadcast
import com.fersaiyan.cyanbridge.ai.image.TaskerImageProfileCompatibility
import com.fersaiyan.cyanbridge.ai.image.TaskerImageProfileStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiTaskerProfileHilTest {
    @Test
    fun geminiAndChatGptProfilesHandshakeWithoutCrossTalk() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        HilTestSupport.requireTaskerStack(context)

        verifyProfile(
            assistantLabel = "Gemini",
            expectedTarget = "gemini",
            expectedVersion = TaskerImageProfileCompatibility.GEMINI_PROFILE_VERSION,
        )
        verifyProfile(
            assistantLabel = "ChatGPT",
            expectedTarget = "chatgpt",
            expectedVersion = TaskerImageProfileCompatibility.CHATGPT_PROFILE_VERSION,
        )
    }

    private fun verifyProfile(
        assistantLabel: String,
        expectedTarget: String,
        expectedVersion: String,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = TaskerImageProfileStore.beginVerification(context)
        context.sendBroadcast(
            Intent("${context.packageName}.AI_EVENT").apply {
                setPackage(HilTestSupport.TASKER_PACKAGE)
                putExtra(ImageQuestionBroadcast.EXTRA_TYPE, "profile_check")
                putExtra(ImageQuestionBroadcast.EXTRA_ASSISTANT, assistantLabel)
                putExtra("profile_token", token)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            },
        )

        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            if (
                TaskerImageProfileStore.target(context) == expectedTarget &&
                TaskerImageProfileStore.version(context) == expectedVersion
            ) {
                break
            }
            Thread.sleep(100)
        }

        assertEquals("Wrong Tasker profile answered $assistantLabel handshake", expectedTarget, TaskerImageProfileStore.target(context))
        assertEquals("Wrong/outdated Tasker profile version for $assistantLabel", expectedVersion, TaskerImageProfileStore.version(context))
    }
}
