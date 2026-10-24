package com.fersaiyan.cyanbridge.localagent.daily

import android.content.Context
import com.fersaiyan.cyanbridge.shared.prefs.MemoryPrefs.NightlyEnrichment
import com.fersaiyan.cyanbridge.shared.prefs.edit
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set

object NightlyEnrichmentPrefs {
    enum class Status { COLLECTING, PROCESSING, READY, REVIEWED }

    fun markProcessing(context: Context, date: String) {
        NightlyEnrichment.status[date.trim()][context] = Status.PROCESSING.name
    }

    fun markReady(context: Context, date: String, count: Int) {
        NightlyEnrichment.edit(context) {
            this[NightlyEnrichment.status[date.trim()]] = Status.READY.name
            this[NightlyEnrichment.count[date.trim()]] = count.coerceAtLeast(0)
        }
    }

    fun markReviewed(context: Context, date: String) {
        NightlyEnrichment.status[date.trim()][context] = Status.REVIEWED.name
    }

    fun isReady(context: Context, date: String): Boolean =
        NightlyEnrichment.status[date.trim()][context] == Status.READY.name

    fun claimNotification(context: Context, date: String): Boolean {
        if (!isReady(context, date)) return false
        val notified = NightlyEnrichment.notified[date.trim()]
        if (notified[context]) return false
        return NightlyEnrichment.edit(context, commit = true) { this[notified] = true }
    }
}
