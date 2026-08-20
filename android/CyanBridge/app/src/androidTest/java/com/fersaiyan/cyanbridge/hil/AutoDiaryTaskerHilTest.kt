package com.fersaiyan.cyanbridge.hil

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.plugins.autodiary.AutoDiaryCaptureCoordinator
import com.fersaiyan.cyanbridge.plugins.autodiary.AutoDiaryService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AutoDiaryTaskerHilTest {
    @Test
    fun taskerObservationIsStoredInExistingMemoryPipeline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        HilTestSupport.requireTaskerStack(context)

        ActivityScenario.launch(HilFixtureActivity::class.java).use {
            AutoDiaryService.enable(context)
            Thread.sleep(500)

            val before = LocalAgentMemoryStore.screenCaptureLastUpdatedAtMs(context, today())
            val result = runBlocking { AutoDiaryCaptureCoordinator.captureOnce(context) }
            assertTrue("AutoDiary Tasker capture failed: ${result.detail}", result.success)

            val lines = LocalAgentMemoryStore.readScreenCaptureLines(context, today(), maxLines = 50)
            assertTrue(
                "AutoDiary capture did not reach the existing Memory Vault screen-capture store",
                lines.any { it.contains(HilFixtureActivity.MARKER_TEXT) },
            )
            val after = LocalAgentMemoryStore.screenCaptureLastUpdatedAtMs(context, today())
            assertTrue("Memory Vault timestamp did not advance after AutoDiary capture", after >= before)
        }
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
