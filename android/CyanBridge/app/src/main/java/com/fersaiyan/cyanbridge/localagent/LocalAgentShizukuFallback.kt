package com.fersaiyan.cyanbridge.localagent

import android.content.Context

/**
 * Compatibility shim retained temporarily so existing settings code keeps compiling while
 * Shizuku support is removed from the app. There is no privileged execution path here.
 */
object LocalAgentShizukuFallback {
    enum class Availability(val statusText: String) {
        UNAVAILABLE("Shizuku support removed"),
    }

    fun availability(): Availability = Availability.UNAVAILABLE

    fun requestPermission(context: Context): String = "Shizuku support has been removed".also {
        LocalAgentPrefs.setShizukuFallbackEnabled(context, false)
        LocalAgentPrefs.setShizukuStatus(context, it)
    }

    suspend fun performAfterAccessibilityFailure(
        context: Context,
        action: LocalAgentAction,
    ): Boolean {
        LocalAgentPrefs.setShizukuFallbackEnabled(context, false)
        LocalAgentPrefs.setShizukuStatus(context, "Shizuku support removed")
        return false
    }

    fun disconnect(context: Context) {
        LocalAgentPrefs.setShizukuFallbackEnabled(context, false)
        LocalAgentPrefs.setShizukuStatus(context, "Shizuku support removed")
    }

    internal fun supportsFixedInputOperation(
        action: LocalAgentAction,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean = false
}
