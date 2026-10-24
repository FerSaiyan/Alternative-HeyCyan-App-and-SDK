package com.fersaiyan.cyanbridge.localagent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalAgentTaskHistoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        LocalAgentTaskHistory.clear(context)
    }

    @After
    fun tearDown() {
        LocalAgentTaskHistory.clear(context)
    }

    @Test
    fun `recent history is newest first`() {
        LocalAgentTaskHistory.record(
            context,
            LocalAgentTaskHistory.Entry("Open Settings", "Completed", 2, false, createdAtMs = 1L),
        )
        LocalAgentTaskHistory.record(
            context,
            LocalAgentTaskHistory.Entry("Open WhatsApp", "Stopped", 3, true, createdAtMs = 2L),
        )

        val recent = LocalAgentTaskHistory.recent(context)

        assertEquals(2, recent.size)
        assertEquals("Open WhatsApp", recent.first().goal)
        assertTrue(recent.first().usedSavedSkill)
    }
}
