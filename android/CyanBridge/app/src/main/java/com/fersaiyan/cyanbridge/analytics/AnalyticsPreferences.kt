package com.fersaiyan.cyanbridge.analytics

import android.content.Context

/** Privacy controls and local delivery state for first-party product analytics. */
object AnalyticsPreferences {
    private const val PREFS = "cyanbridge_product_analytics"
    private const val KEY_SHARING_ENABLED = "sharing_enabled"
    private const val KEY_LAST_HEARTBEAT_DAY = "last_heartbeat_day"
    private const val KEY_ACQUISITION_COMPLETE = "acquisition_complete"
    private const val KEY_PENDING_ACQUISITION = "pending_acquisition"

    /**
     * Anonymous operational analytics are enabled by default and can be declined on
     * the acquisition screen. No media, prompts, transcripts, contacts, or files are sent.
     */
    fun isSharingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHARING_ENABLED, true)

    fun setSharingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHARING_ENABLED, enabled)
            .apply()
    }

    fun getLastHeartbeatDay(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_HEARTBEAT_DAY, "")
            .orEmpty()

    fun setLastHeartbeatDay(context: Context, day: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_HEARTBEAT_DAY, day)
            .apply()
    }

    fun isAcquisitionComplete(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACQUISITION_COMPLETE, false)

    fun markAcquisitionComplete(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACQUISITION_COMPLETE, true)
            .apply()
    }

    fun getPendingAcquisition(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_ACQUISITION, "")
            .orEmpty()

    fun setPendingAcquisition(context: Context, json: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (json.isNullOrBlank()) remove(KEY_PENDING_ACQUISITION)
                else putString(KEY_PENDING_ACQUISITION, json)
            }
            .apply()
    }
}
