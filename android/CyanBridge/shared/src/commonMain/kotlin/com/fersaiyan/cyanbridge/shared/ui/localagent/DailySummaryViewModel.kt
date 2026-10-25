package com.fersaiyan.cyanbridge.shared.ui.localagent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.fersaiyan.cyanbridge.shared.ui.UiMessage

/** A run of the background summary job as the platform's scheduler reports it. */
data class DailySummaryWork(
    val state: State,
    val percent: Int = 0,
    val etaMs: Long = 0L,
    val stage: String = "",
    val provider: String = "",
    val sampleCount: Int = 0,
    val bulletDone: Int = 0,
    val bulletTotal: Int = 0,
    val error: String = "",
) {
    enum class State { RUNNING, SUCCEEDED, FAILED }
}

/** How long a regeneration should take, from recent runs with the same provider. */
data class DailySummaryEstimate(val provider: String, val expectedTotalMs: Long, val sampleCount: Int)

interface DailySummaryPlatform {
    /** The saved summary for the day, or blank. */
    fun readSummary(): String

    /** "HH:mm" of the last generation, or null before the first one. */
    fun lastGeneratedTime(): String?
    fun remainingCooldownMs(): Long
    fun estimate(): DailySummaryEstimate
    fun providerHint(): String

    /** Starts the background job, replacing a running one; the host reports it back through [DailySummaryViewModel.onWork]. */
    fun startRegeneration()
    fun showMessage(message: UiMessage)
}

/** The day's summary file and the progress of regenerating it in the background. */
class DailySummaryViewModel(private val platform: DailySummaryPlatform) : ViewModel() {
    var summary by mutableStateOf("")
        private set
    var status by mutableStateOf("")
        private set
    var isRegenerating by mutableStateOf(false)
        private set
    var progress by mutableIntStateOf(0)
        private set
    var progressTitle by mutableStateOf("Regenerating daily summary")
        private set
    var progressDetail by mutableStateOf("Estimating remaining time...")
        private set

    init {
        refresh()
    }

    fun refresh() {
        summary = platform.readSummary().trimEnd().ifBlank { "(No daily summary generated yet. Tap Regenerate.)" }
        status = platform.lastGeneratedTime()?.let { "Last generated: $it" } ?: "Not generated yet"
    }

    fun regenerate() {
        val cooldown = platform.remainingCooldownMs()
        if (cooldown > 0L) {
            platform.showMessage(UiMessage("Please wait ${(cooldown / 1000L).coerceAtLeast(1L)}s before regenerating."))
            return
        }
        isRegenerating = true
        val estimate = platform.estimate()
        progress = 1
        progressTitle = "Regenerating daily summary (${estimate.provider})"
        progressDetail = etaText(estimate.expectedTotalMs, estimate.sampleCount, stage = "Queued")
        status = "Generating…"
        platform.startRegeneration()
        platform.showMessage(UiMessage("Generating daily summary in background…"))
    }

    /** The latest run of the job, or null when it never ran for this day. */
    fun onWork(work: DailySummaryWork?) {
        when (work?.state) {
            null -> isRegenerating = false
            DailySummaryWork.State.RUNNING -> {
                isRegenerating = true
                val percent = work.percent.coerceIn(0, 100)
                progress = percent
                progressTitle = "Regenerating daily summary (${work.provider.trim().ifBlank { platform.providerHint() }})"
                progressDetail = etaText(
                    etaMs = work.etaMs,
                    sampleCount = work.sampleCount,
                    stage = work.stage.trim().ifBlank { "Generating summary" },
                    bulletDone = work.bulletDone,
                    bulletTotal = work.bulletTotal,
                )
                status = if (percent > 0) "Generating… $percent%" else "Generating…"
            }
            DailySummaryWork.State.SUCCEEDED -> {
                isRegenerating = false
                progress = 100
                status = "Generation complete"
                refresh()
                platform.showMessage(UiMessage("Daily summary saved"))
            }
            DailySummaryWork.State.FAILED -> {
                isRegenerating = false
                status = "Generation failed"
                platform.showMessage(UiMessage(work.error.trim().ifBlank { "Failed to regenerate summary" }, long = true))
            }
        }
    }

    companion object {
        fun etaText(etaMs: Long, sampleCount: Int, stage: String, bulletDone: Int = 0, bulletTotal: Int = 0): String {
            val totalSeconds = etaMs.coerceAtLeast(0L) / 1000L
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            val etaLabel = if (minutes > 0L) "${minutes}m ${seconds.toString().padStart(2, '0')}s" else "${seconds}s"
            val sampleLabel = if (sampleCount > 0) "avg of last ${sampleCount.coerceAtMost(3)} runs" else "cold-start estimate"
            val bulletLabel = if (bulletTotal > 0) " · bullets $bulletDone/$bulletTotal" else ""
            return "$stage$bulletLabel · ETA ~$etaLabel ($sampleLabel)"
        }
    }
}
