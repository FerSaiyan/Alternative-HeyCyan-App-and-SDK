package com.fersaiyan.cyanbridge.analytics

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.ui.AcquisitionReasonDialog
import java.util.concurrent.atomic.AtomicBoolean

/** Registers foreground-only product analytics without counting background service process work. */
object ProductAnalyticsLifecycle {
    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(ProductAnalyticsLifecycleCallbacks(application))
    }
}

private class ProductAnalyticsLifecycleCallbacks(
    private val application: Application,
) : Application.ActivityLifecycleCallbacks {
    private val acquisitionPromptInFlight = AtomicBoolean(false)

    override fun onActivityStarted(activity: Activity) {
        AnalyticsClient.recordDailyHeartbeat(application)
        if (
            activity is MainActivity &&
            !AnalyticsPreferences.isAcquisitionComplete(application) &&
            acquisitionPromptInFlight.compareAndSet(false, true)
        ) {
            AcquisitionReasonDialog.show(activity)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
