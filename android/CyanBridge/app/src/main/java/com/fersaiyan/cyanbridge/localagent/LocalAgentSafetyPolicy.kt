package com.fersaiyan.cyanbridge.localagent

import android.content.Context

/**
 * Legacy compatibility hook.
 *
 * Package-level screen blocking moved to the Tasker observation profile so blocked screen
 * contents never cross into CyanBridge. Keep this hook for old call sites, but do not apply
 * the obsolete SharedPreferences blacklist as a second policy layer.
 */
object LocalAgentSafetyPolicy {
    fun blockedReason(@Suppress("UNUSED_PARAMETER") context: Context, packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return null
    }
}
