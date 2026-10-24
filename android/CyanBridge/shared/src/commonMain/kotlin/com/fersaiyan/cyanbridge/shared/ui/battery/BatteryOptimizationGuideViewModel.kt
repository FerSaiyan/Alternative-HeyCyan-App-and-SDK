package com.fersaiyan.cyanbridge.shared.ui.battery

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences
import com.fersaiyan.cyanbridge.shared.platform.platformCurrentTimeMillis
import com.fersaiyan.cyanbridge.shared.prefs.AppPrefs.Main
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set

/**
 * The battery optimization guide: whether the app is already exempt, and the user's answer.
 * [store] is the main app preference file. Each answer returns whether onboarding is complete,
 * so the host knows to close the guide rather than continue to the feature screens.
 */
class BatteryOptimizationGuideViewModel(
    private val store: PlatformPreferences,
    private val isOptimizationIgnored: () -> Boolean,
) : ViewModel() {
    var optimizationIgnored by mutableStateOf(isOptimizationIgnored())
        private set

    /** The user may have changed the exemption in system settings. */
    fun refresh() {
        optimizationIgnored = isOptimizationIgnored()
    }

    fun onContinue(): Boolean {
        if (isOptimizationIgnored()) store[Main.batteryGuideCompleted] = true
        return store[Main.onboardingCompleted]
    }

    fun onRemindLater(): Boolean {
        store[Main.batteryGuideRemindAfter] = platformCurrentTimeMillis() + REMINDER_DELAY_MS
        return store[Main.onboardingCompleted]
    }

    fun onDontShowAgain(): Boolean {
        store[Main.batteryGuideSuppressed] = true
        return store[Main.onboardingCompleted]
    }

    companion object {
        private const val REMINDER_DELAY_MS = 7L * 24 * 60 * 60 * 1000

        /** Shown until answered, unless the app is already exempt or the reminder is not due yet. */
        fun shouldShow(store: PlatformPreferences, isOptimizationIgnored: () -> Boolean): Boolean {
            if (store[Main.batteryGuideSuppressed]) return false
            if (store[Main.batteryGuideCompleted]) return false
            if (platformCurrentTimeMillis() < store[Main.batteryGuideRemindAfter]) return false
            return !isOptimizationIgnored()
        }
    }
}
