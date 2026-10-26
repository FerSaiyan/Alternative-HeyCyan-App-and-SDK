package com.fersaiyan.cyanbridge.localagent.tasker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.ai.router.AgentInferenceRouter
import com.fersaiyan.cyanbridge.ai.router.UiPlanningImageDecision
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class TaskerScreenshotTest {
    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    ) + ByteArray(32) { it.toByte() }

    private fun observation(screenshot: String?) = JSONObject()
        .put("package_name", "com.example")
        .put("text_summary", "Hello")
        .apply { if (screenshot != null) put(TaskerAgentContract.FIELD_SCREENSHOT_BASE64, screenshot) }
        .toString()

    @Test
    fun screenshotIsReadFromTheObservationPayloadOnlyWhenItIsAnImage() {
        val encoded = Base64.getEncoder().encodeToString(png)
        assertArrayEquals(png, TaskerAgentContract.screenshotFromJson(observation(encoded)))
        // Tasker may wrap long base64 output.
        assertArrayEquals(png, TaskerAgentContract.screenshotFromJson(observation(encoded.chunked(16).joinToString("\n"))))
        assertEquals("png", TaskerAgentContract.imageExtension(png))

        val tooLarge = png + ByteArray(TaskerAgentContract.MAX_SCREENSHOT_BYTES)
        for (rejected in listOf(
            null,
            "",
            "not base64!",
            Base64.getEncoder().encodeToString("plain text, not an image".toByteArray()),
            Base64.getEncoder().encodeToString(tooLarge),
        )) {
            assertNull(rejected, TaskerAgentContract.screenshotFromJson(observation(rejected)))
        }
        assertEquals("Hello", TaskerAgentContract.observationFromJson(observation(encoded)).screenText)
    }

    @Test
    fun remotePlannerGetsTheScreenshotOnlyWithConsent() {
        fun decide(image: Boolean, remote: Boolean, allowRemote: Boolean) =
            AgentInferenceRouter.uiPlanningImageDecision(image, remote, allowRemote)

        assertEquals(UiPlanningImageDecision.TEXT_ONLY, decide(image = false, remote = true, allowRemote = true))
        assertEquals(UiPlanningImageDecision.ATTACH, decide(image = true, remote = false, allowRemote = false))
        assertEquals(UiPlanningImageDecision.REMOTE_NOT_ALLOWED, decide(image = true, remote = true, allowRemote = false))
        assertEquals(UiPlanningImageDecision.ATTACH, decide(image = true, remote = true, allowRemote = true))

        // Only CyanBridge's own copy counts as a usable image, and it is deleted after use.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val saved = TaskerScreenshotStore.save(context, png)!!
        assertEquals(saved.path, TaskerScreenshotStore.usablePath(context, saved.path))
        val outside = File(context.filesDir, "outside.png").apply { writeBytes(png) }
        assertNull(TaskerScreenshotStore.usablePath(context, outside.path))
        assertNull(TaskerScreenshotStore.usablePath(context, "${saved.parent}/../${saved.parentFile!!.name}/missing.png"))
        TaskerScreenshotStore.delete(context, saved.path)
        assertFalse(saved.exists())
    }
}
