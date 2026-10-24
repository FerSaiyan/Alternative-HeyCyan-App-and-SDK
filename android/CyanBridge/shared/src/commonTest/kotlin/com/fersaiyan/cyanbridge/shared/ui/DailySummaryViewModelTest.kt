package com.fersaiyan.cyanbridge.shared.ui

import com.fersaiyan.cyanbridge.shared.ui.localagent.DailySummaryEstimate
import com.fersaiyan.cyanbridge.shared.ui.localagent.DailySummaryPlatform
import com.fersaiyan.cyanbridge.shared.ui.localagent.DailySummaryViewModel
import com.fersaiyan.cyanbridge.shared.ui.localagent.DailySummaryWork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DailySummaryViewModelTest {
    private val messages = mutableListOf<String>()
    private var saved = ""

    private val viewModel = DailySummaryViewModel(object : DailySummaryPlatform {
        override fun readSummary() = saved
        override fun lastGeneratedTime() = if (saved.isBlank()) null else "09:30"
        override fun remainingCooldownMs() = 0L
        override fun estimate() = DailySummaryEstimate("local", 125_000L, 0)
        override fun providerHint() = "local"
        override fun startRegeneration() = Unit
        override fun showMessage(message: String, long: Boolean) { messages += message }
    })

    @Test
    fun regenerationReportsProgressAndReloadsTheSummaryWhenDone() {
        assertEquals("Not generated yet", viewModel.status)

        viewModel.regenerate()
        assertEquals("Queued · ETA ~2m 05s (cold-start estimate)", viewModel.progressDetail)

        viewModel.onWork(DailySummaryWork(DailySummaryWork.State.RUNNING, percent = 40, etaMs = 9_000, bulletDone = 2, bulletTotal = 5, sampleCount = 4))
        assertEquals("Generating… 40%", viewModel.status)
        assertEquals("Regenerating daily summary (local)", viewModel.progressTitle)
        assertEquals("Generating summary · bullets 2/5 · ETA ~9s (avg of last 3 runs)", viewModel.progressDetail)

        saved = "- Walked to work"
        viewModel.onWork(DailySummaryWork(DailySummaryWork.State.SUCCEEDED))
        assertFalse(viewModel.isRegenerating)
        assertEquals("- Walked to work", viewModel.summary)
        assertEquals("Last generated: 09:30", viewModel.status)
        assertEquals(listOf("Generating daily summary in background…", "Daily summary saved"), messages)
    }
}
