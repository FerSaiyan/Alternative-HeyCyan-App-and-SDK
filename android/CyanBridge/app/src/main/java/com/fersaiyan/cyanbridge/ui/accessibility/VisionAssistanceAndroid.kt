package com.fersaiyan.cyanbridge.ui.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.fersaiyan.cyanbridge.shared.prefs.AppPrefs
import com.fersaiyan.cyanbridge.shared.prefs.store
import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences

/** Android store for the vision-assistance choice; usable before the shared platform preferences are initialised. */
fun Context.visionAssistancePreferences(): PlatformPreferences = AppPrefs.Accessibility.store(this)

object ScreenReaderDetector {
    /**
     * True when TalkBack (or another explore-by-touch reader) is on, or another app's spoken-feedback
     * service is enabled. CyanBridge has no accessibility service of its own; the package check only
     * keeps that true if one is ever added. Used only to offer vision assistance, never to switch it on.
     */
    fun isRunning(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        if (!manager.isEnabled) return false
        if (manager.isTouchExplorationEnabled) return true
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN)
            .any { it.resolveInfo?.serviceInfo?.packageName != context.packageName }
    }
}
