package com.fersaiyan.cyanbridge.ui.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.fersaiyan.cyanbridge.shared.accessibility.VisionAssistancePreferences
import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences

/** Android store for [VisionAssistancePreferences]; usable before the shared platform preferences are initialised. */
fun Context.visionAssistancePreferences(): PlatformPreferences =
    PlatformPreferences(getSharedPreferences(VisionAssistancePreferences.PREFS_NAME, Context.MODE_PRIVATE))

object ScreenReaderDetector {
    /**
     * True when TalkBack (or another explore-by-touch reader) is on, or any other app's spoken-feedback
     * service is enabled. CyanBridge's own automation service reports generic feedback and is ignored.
     * Used only to offer vision assistance, never to switch it on.
     */
    fun isRunning(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        if (!manager.isEnabled) return false
        if (manager.isTouchExplorationEnabled) return true
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN)
            .any { it.resolveInfo?.serviceInfo?.packageName != context.packageName }
    }
}
