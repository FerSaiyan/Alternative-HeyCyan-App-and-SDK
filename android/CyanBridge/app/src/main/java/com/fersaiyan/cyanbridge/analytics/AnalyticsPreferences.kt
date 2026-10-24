package com.fersaiyan.cyanbridge.analytics

import android.content.Context
import com.fersaiyan.cyanbridge.shared.prefs.AppPrefs.Analytics
import com.fersaiyan.cyanbridge.shared.prefs.edit
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.remove
import com.fersaiyan.cyanbridge.shared.prefs.set

/** Privacy controls and local delivery state for first-party product analytics. */
object AnalyticsPreferences {
    /** No operational heartbeat is sent until the user has seen and saved this choice. */
    fun isSharingEnabled(context: Context): Boolean =
        Analytics.sharingChoiceMade[context] && Analytics.sharingEnabled[context]

    /** The disclosure UI may recommend sharing while still requiring an explicit saved choice. */
    fun suggestedSharingChoice(): Boolean = true

    fun setSharingEnabled(context: Context, enabled: Boolean) {
        Analytics.edit(context) {
            this[Analytics.sharingEnabled] = enabled
            this[Analytics.sharingChoiceMade] = true
        }
    }

    fun getLastHeartbeatDay(context: Context): String = Analytics.lastHeartbeatDay[context]

    fun setLastHeartbeatDay(context: Context, day: String) {
        Analytics.lastHeartbeatDay[context] = day
    }

    fun isAcquisitionComplete(context: Context): Boolean = Analytics.acquisitionComplete[context]

    fun markAcquisitionComplete(context: Context) {
        Analytics.acquisitionComplete[context] = true
    }

    fun getPendingAcquisition(context: Context): String = Analytics.pendingAcquisition[context]

    fun setPendingAcquisition(context: Context, json: String?) {
        if (json.isNullOrBlank()) Analytics.pendingAcquisition.remove(context) else Analytics.pendingAcquisition[context] = json
    }
}
