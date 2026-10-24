package com.fersaiyan.cyanbridge.ai

import android.content.Context
import com.fersaiyan.cyanbridge.shared.glasses.AiWakeWordRoute
import com.fersaiyan.cyanbridge.shared.prefs.AssistantPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get

/** Kept for MainActivity's wake-word handler; other code reads [AssistantPrefs.WakeWord]. */
object AiWakeWordPreferences {
    fun route(context: Context): AiWakeWordRoute = AssistantPrefs.WakeWord.route[context]
}
