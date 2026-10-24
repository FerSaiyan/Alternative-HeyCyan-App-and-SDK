package com.fersaiyan.cyanbridge.shared.platform

/**
 * Cross-platform key-value storage abstraction.
 * Android uses SharedPreferences; iOS uses NSUserDefaults.
 */
expect class PlatformPreferences {
    fun getString(key: String, defaultValue: String): String
    fun putString(key: String, value: String)
    fun getInt(key: String, defaultValue: Int): Int
    fun putInt(key: String, value: Int)
    fun getLong(key: String, defaultValue: Long): Long
    fun putLong(key: String, value: Long)
    fun getFloat(key: String, defaultValue: Float): Float
    fun putFloat(key: String, value: Float)
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getStringSet(key: String): Set<String>?
    fun putStringSet(key: String, value: Set<String>)
    fun remove(key: String)
    fun clear()
    fun contains(key: String): Boolean
}

/**
 * Factory to create or retrieve a named preferences store.
 */
expect fun createPlatformPreferences(name: String): PlatformPreferences

/** True on iOS, whose app kept some settings under its own suite and key names. */
internal expect val usesIosPreferenceNames: Boolean
