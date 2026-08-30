package com.fersaiyan.cyanbridge.localagent.daily

import android.content.Context

object NightlyEnrichmentPrefs {
    private const val PREFS = "nightly_enrichment"

    enum class Status { COLLECTING, PROCESSING, READY, REVIEWED }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(date: String, suffix: String) = "${date.trim()}_$suffix"

    fun markProcessing(context: Context, date: String) {
        prefs(context).edit().putString(key(date, "status"), Status.PROCESSING.name).apply()
    }

    fun markReady(context: Context, date: String, count: Int) {
        prefs(context).edit()
            .putString(key(date, "status"), Status.READY.name)
            .putInt(key(date, "count"), count.coerceAtLeast(0))
            .apply()
    }

    fun markReviewed(context: Context, date: String) {
        prefs(context).edit().putString(key(date, "status"), Status.REVIEWED.name).apply()
    }

    fun isReady(context: Context, date: String): Boolean =
        prefs(context).getString(key(date, "status"), Status.COLLECTING.name) == Status.READY.name

    fun claimNotification(context: Context, date: String): Boolean {
        if (!isReady(context, date)) return false
        val notificationKey = key(date, "notified")
        if (prefs(context).getBoolean(notificationKey, false)) return false
        return prefs(context).edit().putBoolean(notificationKey, true).commit()
    }
}
