package com.fersaiyan.cyanbridge.ai.image

import android.content.Context
import com.fersaiyan.cyanbridge.shared.prefs.MediaPrefs.TaskerImageProfile
import com.fersaiyan.cyanbridge.shared.prefs.edit
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import java.util.UUID

/** Stores versioned Tasker profile handshakes without making Gemini and ChatGPT overwrite each other. */
object TaskerImageProfileStore {
    /** Last verified target, retained for backward compatibility and diagnostics. */
    fun target(context: Context): String? = TaskerImageProfile.target[context]

    /** Last verified version, retained for backward compatibility. */
    fun version(context: Context): String? = TaskerImageProfile.version[context]

    /** Version most recently verified for this concrete assistant target. */
    fun version(context: Context, target: String): String? =
        TaskerImageProfile.versionFor[target.lowercase()][context]
            ?: if (target.equals(target(context), ignoreCase = true)) version(context) else null

    fun verifiedAt(context: Context, target: String): Long =
        TaskerImageProfile.verifiedAt[target.lowercase()][context]

    fun beginVerification(context: Context): String {
        val token = UUID.randomUUID().toString()
        TaskerImageProfile.pendingToken[context] = token
        return token
    }

    fun verifyAndRecord(context: Context, target: String?, version: String?, token: String?): Boolean {
        if (target.isNullOrBlank() || version.isNullOrBlank() || token.isNullOrBlank()) return false
        if (token != TaskerImageProfile.pendingToken[context]) return false
        val normalizedTarget = target.lowercase()
        TaskerImageProfile.edit(context) {
            this[TaskerImageProfile.target] = normalizedTarget
            this[TaskerImageProfile.version] = version
            this[TaskerImageProfile.versionFor[normalizedTarget]] = version
            this[TaskerImageProfile.verifiedAt[normalizedTarget]] = System.currentTimeMillis()
            this[TaskerImageProfile.pendingToken] = null
        }
        return true
    }
}
