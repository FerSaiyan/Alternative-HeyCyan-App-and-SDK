package com.fersaiyan.cyanbridge.localagent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentServiceActionExecutorTest {

    @Test
    fun `read screen aloud stays in app and other actions go to the device`() = runBlocking {
        var screenReads = 0
        val performed = mutableListOf<LocalAgentAction>()
        val executor = LocalAgentServiceActionExecutor(
            readScreenAloud = { screenReads++; false },
            performAction = { performed += it; true },
        )

        assertFalse(executor.execute(LocalAgentAction.ReadScreenAloud))
        assertTrue(executor.execute(LocalAgentAction.GlobalBack))
        assertTrue(executor.execute(LocalAgentAction.ClickText("OK")))
        executor.ensureNotCancelled()

        assertEquals(1, screenReads)
        assertEquals(listOf(LocalAgentAction.GlobalBack, LocalAgentAction.ClickText("OK")), performed)
    }
}
