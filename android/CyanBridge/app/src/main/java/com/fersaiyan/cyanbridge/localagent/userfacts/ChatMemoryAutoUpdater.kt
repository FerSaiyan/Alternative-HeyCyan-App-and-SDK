package com.fersaiyan.cyanbridge.localagent.userfacts

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyFactsStorage
import com.fersaiyan.cyanbridge.shared.prefs.MemoryPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ChatMemoryAutoUpdater {
    private const val TAG = "ChatMemoryAuto"

    data class Applied(
        val addedCandidateUserFacts: Int = 0,
        val addedDailyFacts: Int = 0,
    )

    /**
     * Extracts candidate facts from a normal chat message and stores them locally.
     *
     * - Candidate USER facts -> user_facts_candidates/YYYY-MM-DD.md (for later review)
     * - Candidate DAILY facts -> daily_facts/YYYY-MM-DD.md (draft/unconfirmed)
     */
    suspend fun extractAndStore(
        context: Context,
        userMessage: String,
        assistantReply: String,
        date: String = todayString(),
        throttleMs: Long = 25_000L,
    ): Applied {
        try {
            val now = System.currentTimeMillis()
            val last = MemoryPrefs.ChatMemory.lastExtractAtMs[context]
            if (now - last < throttleMs) return Applied()

            if (!MemoryPrefs.ChatMemory.autoSaveDailyFacts[context] &&
                !MemoryPrefs.ChatMemory.extractUserFactCandidates[context]
            ) {
                return Applied()
            }

            MemoryPrefs.ChatMemory.lastExtractAtMs[context] = now

            val extracted = ChatMemoryExtractor.extract(
                context = context,
                userMessage = userMessage,
                assistantReply = assistantReply,
                date = date,
            ).getOrElse {
                Log.w(TAG, "Extraction failed: ${it.message}")
                return Applied()
            }

            var addedCand = 0
            var addedDaily = 0

            if (MemoryPrefs.ChatMemory.extractUserFactCandidates[context] && extracted.candidateUserFacts.isNotEmpty()) {
                val before = CandidateUserFactsStorage.load(context, date).size
                CandidateUserFactsStorage.append(context, date, extracted.candidateUserFacts)
                val after = CandidateUserFactsStorage.load(context, date).size
                addedCand = (after - before).coerceAtLeast(0)
            }

            if (MemoryPrefs.ChatMemory.autoSaveDailyFacts[context] && extracted.candidateDailyFacts.isNotEmpty()) {
                val before = DailyFactsStorage.load(context, date).draft.size
                DailyFactsStorage.appendDraft(context, date, extracted.candidateDailyFacts)
                val after = DailyFactsStorage.load(context, date).draft.size
                addedDaily = (after - before).coerceAtLeast(0)
            }

            return Applied(
                addedCandidateUserFacts = addedCand,
                addedDailyFacts = addedDaily,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "extractAndStore failed: ${t.message}")
            return Applied()
        }
    }

    private fun todayString(nowMs: Long = System.currentTimeMillis()): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))
    }
}
