package com.fersaiyan.cyanbridge.shared.platform

import android.content.Context
import android.content.SharedPreferences

private lateinit var appContext: Context

fun initPlatformPreferences(context: Context) {
    appContext = context.applicationContext
}

actual class PlatformPreferences(val sharedPreferences: SharedPreferences) {
    private var batch: SharedPreferences.Editor? = null

    private inline fun write(change: SharedPreferences.Editor.() -> Unit) {
        val editor = batch
        if (editor != null) editor.change() else sharedPreferences.edit().apply(change).apply()
    }

    /**
     * Applies every write in [block] as one edit. With [commit] the edit is written
     * synchronously and the result reports whether it reached disk.
     */
    fun edit(commit: Boolean = false, block: PlatformPreferences.() -> Unit): Boolean {
        val editor = sharedPreferences.edit()
        batch = editor
        try {
            block()
        } finally {
            batch = null
        }
        return if (commit) editor.commit() else true.also { editor.apply() }
    }

    actual fun getString(key: String, defaultValue: String): String =
        sharedPreferences.getString(key, defaultValue) ?: defaultValue
    actual fun putString(key: String, value: String) = write { putString(key, value) }
    actual fun getInt(key: String, defaultValue: Int): Int = sharedPreferences.getInt(key, defaultValue)
    actual fun putInt(key: String, value: Int) = write { putInt(key, value) }
    actual fun getLong(key: String, defaultValue: Long): Long = sharedPreferences.getLong(key, defaultValue)
    actual fun putLong(key: String, value: Long) = write { putLong(key, value) }
    actual fun getFloat(key: String, defaultValue: Float): Float = sharedPreferences.getFloat(key, defaultValue)
    actual fun putFloat(key: String, value: Float) = write { putFloat(key, value) }
    actual fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        sharedPreferences.getBoolean(key, defaultValue)
    actual fun putBoolean(key: String, value: Boolean) = write { putBoolean(key, value) }
    actual fun getStringSet(key: String): Set<String>? = sharedPreferences.getStringSet(key, null)?.toSet()
    actual fun putStringSet(key: String, value: Set<String>) = write { putStringSet(key, HashSet(value)) }
    actual fun remove(key: String) = write { remove(key) }
    actual fun clear() = write { clear() }
    actual fun contains(key: String): Boolean = sharedPreferences.contains(key)
}

actual fun createPlatformPreferences(name: String): PlatformPreferences {
    check(::appContext.isInitialized) { "Call initPlatformPreferences(context) before creating preferences" }
    return PlatformPreferences(appContext.getSharedPreferences(name, Context.MODE_PRIVATE))
}

internal actual val usesIosPreferenceNames: Boolean = false
