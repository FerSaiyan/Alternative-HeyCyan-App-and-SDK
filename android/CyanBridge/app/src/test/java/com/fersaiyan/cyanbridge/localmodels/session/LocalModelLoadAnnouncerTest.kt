package com.fersaiyan.cyanbridge.localmodels.session

import org.junit.Assert.*
import org.junit.Test

class LocalModelLoadAnnouncerTest {
    private var now = 0L
    private val announcer = LocalModelLoadAnnouncer(clock = { now })
    private val spoken = mutableListOf<String>()

    @Test fun `warm question stays silent`() {
        announcer.arm { spoken += it }
        announcer.disarm()
        assertTrue(spoken.isEmpty())
    }

    @Test fun `load already running when the question is recorded announces once`() {
        announcer.onColdLoadStarted("gemma")
        announcer.arm { spoken += it }
        announcer.arm { spoken += it } // re-armed later in the same question
        announcer.onColdLoadStarted("gemma-audio") // a second model for the same question
        assertEquals(listOf("gemma"), spoken)
    }

    @Test fun `load starting after arming announces, and the next question can announce again`() {
        announcer.arm { spoken += it }
        announcer.onColdLoadStarted("gemma")
        announcer.onColdLoadFinished()
        announcer.disarm()
        announcer.arm { spoken += it }
        announcer.disarm() // warm second question
        announcer.arm { spoken += it }
        announcer.onColdLoadStarted("gemma") // unloaded in between, cold again
        assertEquals(listOf("gemma", "gemma"), spoken)
    }

    @Test fun `load finished during recording is not announced`() {
        announcer.onColdLoadStarted("gemma")
        announcer.onColdLoadFinished()
        announcer.arm { spoken += it }
        assertTrue(spoken.isEmpty())
        assertFalse(announcer.isColdLoading)
    }

    @Test fun `unarmed loads (chat screens) and stale arms are silent`() {
        announcer.onColdLoadStarted("gemma")
        announcer.onColdLoadFinished()
        announcer.arm { spoken += it }
        now += 4 * 60_000L
        announcer.onColdLoadStarted("gemma")
        assertTrue(spoken.isEmpty())
    }
}
