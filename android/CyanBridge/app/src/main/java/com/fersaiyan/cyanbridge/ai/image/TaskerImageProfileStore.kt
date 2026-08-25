package com.fersaiyan.cyanbridge.ai.image

import android.content.Context
import java.util.UUID

/** Stores versioned Tasker profile handshakes without making Gemini and ChatGPT overwrite each other. */
object TaskerImageProfileStore {
    private const val PREFS = "tasker_image_profile"
    private const val KEY_TARGET = "target"
    private const val KEY_VERSION = "version"
    private const val KEY_PENDING_TOKEN = "pending_token"
    private const val KEY_VERIFIED_AT_PREFIX = "verified_at_"
    private const val KEY_VERSION_PREFIX = "version_"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Last verified target, retained for backward compatibility and diagnostics. */
    fun target(context: Context): String? = prefs(context).getString(KEY_TARGET, null)

    /** Last verified version, retained for backward compatibility. */
    fun version(context: Context): String? = prefs(context).getString(KEY_VERSION, null)

    /** Version most recently verified for this concrete assistant target. */
    fun version(context: Context, target: String): String? =
        prefs(context).getString(KEY_VERSION_PREFIX + target.lowercase(), null)
            ?: if (target.equals(target(context), ignoreCase = true)) version(context) else null

    fun verifiedAt(context: Context, target: String): Long =
        prefs(context).getLong(KEY_VERIFIED_AT_PREFIX + target.lowercase(), 0L)

    fun beginVerification(context: Context): String {
        val token = UUID.randomUUID().toString()
        prefs(context).edit().putString(KEY_PENDING_TOKEN, token).apply()
        return token
    }

    fun verifyAndRecord(context: Context, target: String?, version: String?, token: String?): Boolean {
        if (target.isNullOrBlank() || version.isNullOrBlank() || token.isNullOrBlank()) return false
        if (token != prefs(context).getString(KEY_PENDING_TOKEN, null)) return false
        val normalizedTarget = target.lowercase()
        prefs(context).edit()
            .putString(KEY_TARGET, normalizedTarget)
            .putString(KEY_VERSION, version)
            .putString(KEY_VERSION_PREFIX + normalizedTarget, version)
            .putLong(KEY_VERIFIED_AT_PREFIX + normalizedTarget, System.currentTimeMillis())
            .remove(KEY_PENDING_TOKEN)
            .apply()
        return true
    }
}
