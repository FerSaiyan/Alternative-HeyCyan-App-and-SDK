package com.fersaiyan.cyanbridge.analytics

import android.content.Context
import com.fersaiyan.cyanbridge.shared.prefs.ProPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import java.util.UUID

/**
 * Stable pseudonymous installation identity shared with the existing Pro feedback flow.
 * This is deliberately separate from the Pro bearer token.
 */
object InstallationIdentity {
    fun getOrCreate(context: Context): String {
        val existing = ProPrefs.Feedback.installationId[context].trim()
        if (existing.isNotBlank()) return existing

        val generated = UUID.randomUUID().toString()
        ProPrefs.Feedback.installationId[context] = generated
        return generated
    }
}
