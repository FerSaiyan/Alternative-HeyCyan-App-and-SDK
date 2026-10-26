package com.fersaiyan.cyanbridge.analytics

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.fersaiyan.cyanbridge.ui.AcquisitionReasonDialog
import java.util.concurrent.atomic.AtomicBoolean

/** Registers foreground-only product analytics without counting background service process work. */
object ProductAnalyticsLifecycle {
    private val acquisitionPromptInFlight = AtomicBoolean(false)

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(ProductAnalyticsLifecycleCallbacks(application))
    }

    /**
     * The glasses screen started: asks why the app was installed, once per process, until answered.
     * MainActivity also hosts onboarding and every other screen now, so the question follows the
     * glasses screen, not the Activity.
     */
    fun onGlassesScreenStarted(activity: Activity) {
        if (
            !AnalyticsPreferences.isAcquisitionComplete(activity) &&
            acquisitionPromptInFlight.compareAndSet(false, true)
        ) {
            AcquisitionReasonDialog.show(activity)
        }
    }
}

private class ProductAnalyticsLifecycleCallbacks(
    private val application: Application,
) : Application.ActivityLifecycleCallbacks {
    override fun onActivityStarted(activity: Activity) {
        AnalyticsClient.recordDailyHeartbeat(application)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
