package com.fersaiyan.cyanbridge.localagent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalAgentSkillStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        LocalAgentSkillStore.clear(context)
    }

    @After
    fun tearDown() {
        LocalAgentSkillStore.clear(context)
    }

    @Test
    fun `records and retrieves exact low risk navigation skill`() {
        LocalAgentSkillStore.recordSuccessful(
            context,
            "Open Settings",
            listOf(LocalAgentAction.OpenApp("Settings"), LocalAgentAction.GlobalHome),
        )

        val skill = LocalAgentSkillStore.findExact(context, "  open   settings ")

        assertEquals(2, skill?.actions?.size)
        assertTrue(skill?.actions?.first() is LocalAgentAction.OpenApp)
    }

    @Test
    fun `does not persist medium risk click as automatic replay skill`() {
        LocalAgentSkillStore.recordSuccessful(
            context,
            "Tap purchase",
            listOf(LocalAgentAction.ClickText("Purchase")),
        )

        assertNull(LocalAgentSkillStore.findExact(context, "tap purchase"))
    }
}
