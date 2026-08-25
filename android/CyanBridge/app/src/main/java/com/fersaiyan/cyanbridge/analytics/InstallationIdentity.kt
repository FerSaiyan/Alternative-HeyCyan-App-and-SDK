package com.fersaiyan.cyanbridge.analytics

import android.content.Context
import java.util.UUID

/**
 * Stable pseudonymous installation identity shared with the existing Pro feedback flow.
 * This is deliberately separate from the Pro bearer token.
 */
object InstallationIdentity {
    private const val PREFS = "pro_feature_feedback"
    private const val KEY_INSTALLATION_ID = "installation_id"

    fun getOrCreate(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_INSTALLATION_ID, "").orEmpty().trim()
        if (existing.isNotBlank()) return existing

        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALLATION_ID, generated).apply()
        return generated
    }
}
