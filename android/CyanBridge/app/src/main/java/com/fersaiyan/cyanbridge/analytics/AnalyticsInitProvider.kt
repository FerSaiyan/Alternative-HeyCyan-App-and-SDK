package com.fersaiyan.cyanbridge.analytics

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.ui.AcquisitionReasonActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zero-data ContentProvider used only to register foreground activity callbacks.
 * Counting activity starts rather than process creation avoids treating background
 * services as app opens.
 */
class AnalyticsInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return false
        app.registerActivityLifecycleCallbacks(ProductAnalyticsLifecycleCallbacks(app))
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

private class ProductAnalyticsLifecycleCallbacks(
    private val appContext: Context,
) : Application.ActivityLifecycleCallbacks {
    private val acquisitionPromptInFlight = AtomicBoolean(false)

    override fun onActivityStarted(activity: Activity) {
        AnalyticsClient.recordDailyHeartbeat(appContext)
        if (
            activity is MainActivity &&
            !AnalyticsPreferences.isAcquisitionComplete(appContext) &&
            acquisitionPromptInFlight.compareAndSet(false, true)
        ) {
            activity.startActivity(Intent(activity, AcquisitionReasonActivity::class.java))
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
