package com.fersaiyan.cyanbridge.shared.platform

import java.util.concurrent.ConcurrentHashMap

private val stores = ConcurrentHashMap<String, ConcurrentHashMap<String, Any>>()

actual class PlatformPreferences(private val name: String) {
    private val store = stores.getOrPut(name) { ConcurrentHashMap() }

    actual fun getString(key: String, defaultValue: String): String = store[key] as String? ?: defaultValue
    actual fun putString(key: String, value: String) { store[key] = value }
    actual fun getInt(key: String, defaultValue: Int): Int = store[key] as Int? ?: defaultValue
    actual fun putInt(key: String, value: Int) { store[key] = value }
    actual fun getLong(key: String, defaultValue: Long): Long = store[key] as Long? ?: defaultValue
    actual fun putLong(key: String, value: Long) { store[key] = value }
    actual fun getFloat(key: String, defaultValue: Float): Float = store[key] as Float? ?: defaultValue
    actual fun putFloat(key: String, value: Float) { store[key] = value }
    actual fun getBoolean(key: String, defaultValue: Boolean): Boolean = store[key] as Boolean? ?: defaultValue
    actual fun putBoolean(key: String, value: Boolean) { store[key] = value }
    @Suppress("UNCHECKED_CAST")
    actual fun getStringSet(key: String): Set<String>? = store[key] as Set<String>?
    actual fun putStringSet(key: String, value: Set<String>) { store[key] = value.toSet() }
    actual fun remove(key: String) { store.remove(key) }
    actual fun clear() { store.clear() }
    actual fun contains(key: String): Boolean = store.containsKey(key)
}

actual fun createPlatformPreferences(name: String): PlatformPreferences = PlatformPreferences(name)

internal actual val usesIosPreferenceNames: Boolean = false
