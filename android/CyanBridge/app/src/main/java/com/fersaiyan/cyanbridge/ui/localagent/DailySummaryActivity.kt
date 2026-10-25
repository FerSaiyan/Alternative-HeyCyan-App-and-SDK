package com.fersaiyan.cyanbridge.ui.localagent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.fersaiyan.cyanbridge.localagent.dailysummary.DailySummaryGenerator
import com.fersaiyan.cyanbridge.localagent.dailysummary.DailySummaryPrefs
import com.fersaiyan.cyanbridge.localagent.dailysummary.DailySummaryRegenerateWorker
import com.fersaiyan.cyanbridge.localagent.dailysummary.DailySummaryRunHistory
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.shared.ui.localagent.DailySummaryEstimate
import com.fersaiyan.cyanbridge.shared.ui.localagent.DailySummaryPlatform
import com.fersaiyan.cyanbridge.shared.ui.localagent.DailySummaryScreen
import com.fersaiyan.cyanbridge.shared.ui.localagent.DailySummaryViewModel
import com.fersaiyan.cyanbridge.shared.ui.localagent.DailySummaryWork
import com.fersaiyan.cyanbridge.shared.ui.UiMessage
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.showToast
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DailySummaryActivity : AppCompatActivity() {

    private val date: String by lazy {
        intent.getStringExtra(EXTRA_DATE)?.trim().orEmpty().ifBlank {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis()))
        }
    }

    private val file by lazy { LocalAgentMemoryStore.dailySummaryFileForDate(this, date) }

    private val viewModel by viewModels<DailySummaryViewModel> {
        viewModelFactory { initializer { DailySummaryViewModel(AndroidDailySummaryPlatform(applicationContext, date)) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocalAgentMemoryStore.ensureSeedFiles(this)
        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                DailySummaryScreen(
                    title = "Daily summary ($date)",
                    path = file.absolutePath,
                    status = viewModel.status,
                    summary = viewModel.summary,
                    isBusy = viewModel.isRegenerating,
                    progress = viewModel.progress,
                    progressTitle = viewModel.progressTitle,
                    progressDetail = viewModel.progressDetail,
                    onRefresh = viewModel::refresh,
                    onRegenerate = viewModel::regenerate,
                    onShare = ::shareCurrent,
                    onBack = ::finish,
                )
            }
        }

        viewModel.refresh()
        // Observed with this Activity's lifecycle, so a finished run is reported when the screen shows.
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(
            DailySummaryRegenerateWorker.uniqueWorkName(date),
        ).observe(this) { infos -> viewModel.onWork(infos.firstOrNull()?.toDailySummaryWork()) }
    }

    private fun WorkInfo.toDailySummaryWork(): DailySummaryWork = when (state) {
        WorkInfo.State.ENQUEUED,
        WorkInfo.State.BLOCKED,
        WorkInfo.State.RUNNING -> DailySummaryWork(
            state = DailySummaryWork.State.RUNNING,
            percent = progress.getInt(DailySummaryRegenerateWorker.KEY_PROGRESS_PERCENT, 0),
            etaMs = progress.getLong(DailySummaryRegenerateWorker.KEY_ETA_MS, 0L),
            stage = progress.getString(DailySummaryRegenerateWorker.KEY_STAGE).orEmpty(),
            provider = progress.getString(DailySummaryRegenerateWorker.KEY_PROVIDER).orEmpty(),
            sampleCount = progress.getInt(DailySummaryRegenerateWorker.KEY_SAMPLE_COUNT, 0),
            bulletDone = progress.getInt(DailySummaryRegenerateWorker.KEY_BULLET_DONE, 0),
            bulletTotal = progress.getInt(DailySummaryRegenerateWorker.KEY_BULLET_TOTAL, 0),
        )
        WorkInfo.State.SUCCEEDED -> DailySummaryWork(DailySummaryWork.State.SUCCEEDED)
        WorkInfo.State.FAILED,
        WorkInfo.State.CANCELLED -> DailySummaryWork(
            state = DailySummaryWork.State.FAILED,
            error = outputData.getString(DailySummaryRegenerateWorker.KEY_ERROR).orEmpty(),
        )
    }

    private fun shareCurrent() {
        val content = viewModel.summary.trim()
        if (content.isBlank()) {
            Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
            return
        }

        val payload = buildString {
            append("Daily summary ($date)\n")
            append("File: ${file.absolutePath}\n\n")
            append(content)
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Daily summary ($date)")
            putExtra(Intent.EXTRA_TEXT, payload)
        }

        startActivity(Intent.createChooser(intent, "Share daily summary"))
    }

    companion object {
        const val EXTRA_DATE = "extra_date"
    }
}

private class AndroidDailySummaryPlatform(private val context: Context, private val date: String) : DailySummaryPlatform {
    override fun readSummary() = LocalAgentMemoryStore.readText(LocalAgentMemoryStore.dailySummaryFileForDate(context, date))

    override fun lastGeneratedTime(): String? = DailySummaryPrefs.getLastGeneratedAtMs(context, date)
        .takeIf { it > 0L }
        ?.let { SimpleDateFormat("HH:mm", Locale.US).format(Date(it)) }

    override fun remainingCooldownMs() = DailySummaryPrefs.remainingCooldownMs(context, date)

    override fun estimate(): DailySummaryEstimate {
        val estimate = DailySummaryRunHistory.estimate(
            context = context,
            providerHint = DailySummaryGenerator.providerHint(context),
            inputTokens = DailySummaryGenerator.estimateInputTokensForDate(context, date),
        )
        return DailySummaryEstimate(estimate.provider, estimate.expectedTotalMs, estimate.sampleCount)
    }

    override fun providerHint() = DailySummaryGenerator.providerHint(context)

    override fun startRegeneration() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            DailySummaryRegenerateWorker.uniqueWorkName(date),
            ExistingWorkPolicy.REPLACE,
            DailySummaryRegenerateWorker.buildRequest(date),
        )
    }

    override fun showMessage(message: UiMessage) = context.showToast(message)
}
