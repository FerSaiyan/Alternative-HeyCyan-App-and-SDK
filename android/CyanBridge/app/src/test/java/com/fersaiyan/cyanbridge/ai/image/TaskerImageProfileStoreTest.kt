package com.fersaiyan.cyanbridge.ai.image

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskerImageProfileStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun profileHandshakeRequiresThePendingTokenAndPreservesBothAssistants() {
        context.getSharedPreferences("tasker_image_profile", Context.MODE_PRIVATE).edit().clear().commit()

        val geminiToken = TaskerImageProfileStore.beginVerification(context)
        assertFalse(TaskerImageProfileStore.verifyAndRecord(context, "gemini", "gemini-v3", "wrong"))
        assertTrue(TaskerImageProfileStore.verifyAndRecord(context, "gemini", "gemini-v3", geminiToken))
        assertEquals("gemini-v3", TaskerImageProfileStore.version(context, "gemini"))
        assertTrue(TaskerImageProfileStore.verifiedAt(context, "gemini") > 0L)

        val chatGptToken = TaskerImageProfileStore.beginVerification(context)
        assertTrue(TaskerImageProfileStore.verifyAndRecord(context, "chatgpt", "chatgpt-v1", chatGptToken))

        assertEquals("gemini-v3", TaskerImageProfileStore.version(context, "gemini"))
        assertEquals("chatgpt-v1", TaskerImageProfileStore.version(context, "chatgpt"))
        assertEquals("chatgpt", TaskerImageProfileStore.target(context))
        assertEquals("chatgpt-v1", TaskerImageProfileStore.version(context))
        assertFalse(TaskerImageProfileStore.verifyAndRecord(context, "gemini", "gemini-v3", chatGptToken))
    }
}
