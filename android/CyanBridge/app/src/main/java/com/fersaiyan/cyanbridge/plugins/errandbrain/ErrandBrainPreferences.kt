package com.fersaiyan.cyanbridge.plugins.errandbrain

import android.content.Context
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs.ErrandBrain
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set

object ErrandBrainPreferences {
    fun isEnabled(context: Context): Boolean = ErrandBrain.enabled[context]

    fun setEnabled(context: Context, enabled: Boolean) {
        ErrandBrain.enabled[context] = enabled
    }

    fun isAutoCreateTasks(context: Context): Boolean = ErrandBrain.autoCreateTasks[context]

    fun setAutoCreateTasks(context: Context, autoCreate: Boolean) {
        ErrandBrain.autoCreateTasks[context] = autoCreate
    }

    fun isVoiceCommands(context: Context): Boolean = ErrandBrain.voiceCommands[context]

    fun setVoiceCommands(context: Context, enabled: Boolean) {
        ErrandBrain.voiceCommands[context] = enabled
    }

    fun isReminderEnabled(context: Context): Boolean = ErrandBrain.reminderEnabled[context]

    fun setReminderEnabled(context: Context, enabled: Boolean) {
        ErrandBrain.reminderEnabled[context] = enabled
    }

    fun getReminderIntervalMinutes(context: Context): Int = ErrandBrain.reminderIntervalMinutes[context]

    fun setReminderIntervalMinutes(context: Context, minutes: Int) {
        ErrandBrain.reminderIntervalMinutes[context] = minutes
    }

    fun getDefaultPriority(context: Context): String = ErrandBrain.defaultPriority[context]

    fun setDefaultPriority(context: Context, priority: String) {
        ErrandBrain.defaultPriority[context] = priority
    }

    fun getDefaultCategory(context: Context): String = ErrandBrain.defaultCategory[context]

    fun setDefaultCategory(context: Context, category: String) {
        ErrandBrain.defaultCategory[context] = category
    }

    fun getCloudModelId(context: Context): String = ErrandBrain.cloudModelId[context]

    fun setCloudModelId(context: Context, modelId: String) {
        ErrandBrain.cloudModelId[context] = modelId
    }

    fun getMaxHistory(context: Context): Int = ErrandBrain.maxHistory[context]

    fun setMaxHistory(context: Context, count: Int) {
        ErrandBrain.maxHistory[context] = count
    }

    fun getCustomPrompt(context: Context): String = ErrandBrain.customPrompt[context]

    fun setCustomPrompt(context: Context, prompt: String) {
        ErrandBrain.customPrompt[context] = prompt
    }
}
