package com.fersaiyan.cyanbridge.localagent.daily

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.integrations.knowledge.KnowledgeImportCoordinator
import com.fersaiyan.cyanbridge.integrations.knowledge.KnowledgeIntegrationPrefs
import com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyFactsStorage
import com.fersaiyan.cyanbridge.localagent.dailysummary.DailySummaryGenerator
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.localagent.userfacts.CandidateUserFactsStorage
import com.fersaiyan.cyanbridge.memoryvault.MemoryModeManager
import com.fersaiyan.cyanbridge.notes.NightlyEnrichmentDeferredException
import com.fersaiyan.cyanbridge.notes.NightlyNoteEnricher
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class NightlyEnrichmentWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!isNightWindow()) return Result.success()
        if (!powerEligible(START_BATTERY_PERCENT)) return Result.retry()

        val sourceDate = previousDate()
        NightlyEnrichmentPrefs.markProcessing(applicationContext, sourceDate)

        return try {
            if (KnowledgeIntegrationPrefs.autoSyncEnabled(applicationContext)) {
                runCatching { KnowledgeImportCoordinator.syncAll(applicationContext) }
            }

            NightlyNoteEnricher.run(applicationContext) { powerEligible(CONTINUE_BATTERY_PERCENT) }

            val hasScreenInput = MemoryModeManager.isScreenOcrCaptureEnabled(applicationContext) &&
                LocalAgentMemoryStore.screenCaptureLastUpdatedAtMs(applicationContext, sourceDate) > 0L
            if (hasScreenInput) {
                val summary = DailySummaryGenerator.generateAndStore(
                    context = applicationContext,
                    date = sourceDate,
                    shouldContinue = { powerEligible(CONTINUE_BATTERY_PERCENT) },
                )
                val error = summary.exceptionOrNull()
                if (error is NightlyEnrichmentDeferredException) throw error
                if (error != null && DailyFactsStorage.load(applicationContext, sourceDate).draft.isEmpty()) {
                    throw error
                }
            }

            val dailyCount = DailyFactsStorage.load(applicationContext, sourceDate).draft.size
            val candidateCount = CandidateUserFactsStorage.load(applicationContext, sourceDate).size
            val reviewCount = dailyCount + candidateCount
            NightlyEnrichmentPrefs.markReady(applicationContext, sourceDate, reviewCount)
            if (
                reviewCount > 0 &&
                LocalAgentPrefs.isDailyFactsReminderEnabled(applicationContext) &&
                NightlyEnrichmentPrefs.claimNotification(applicationContext, sourceDate)
            ) {
                applicationContext.sendBroadcast(
                    Intent(applicationContext, DailyFactsReminderReceiver::class.java)
                        .setAction(DailyFactsReminderReceiver.ACTION_REMIND)
                        .putExtra(DailyFactsReminderReceiver.EXTRA_DATE, sourceDate)
                        .putExtra(DailyFactsReminderReceiver.EXTRA_COUNT, reviewCount),
                )
            }
            Result.success()
        } catch (_: NightlyEnrichmentDeferredException) {
            Result.retry()
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    private fun powerEligible(minBatteryPercent: Int): Boolean {
        val battery = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return false
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val percent = if (level >= 0) level * 100 / scale else -1
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return plugged && percent >= minBatteryPercent
    }

    private fun isNightWindow(): Boolean = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) in 0..8

    private fun previousDate(nowMs: Long = System.currentTimeMillis()): String {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = nowMs
            add(Calendar.DAY_OF_YEAR, -1)
        }
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(calendar.timeInMillis))
    }

    companion object {
        internal const val START_BATTERY_PERCENT = 80
        internal const val CONTINUE_BATTERY_PERCENT = 75
    }
}
