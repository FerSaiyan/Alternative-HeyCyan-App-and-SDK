package com.fersaiyan.cyanbridge.shared.platform

import platform.Foundation.NSUserDefaults

actual class PlatformPreferences(private val defaults: NSUserDefaults) {
    actual fun getString(key: String, defaultValue: String): String =
        (defaults.stringForKey(key)) ?: defaultValue

    actual fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    actual fun getInt(key: String, defaultValue: Int): Int =
        defaults.integerForKey(key).toInt().takeIf { defaults.objectForKey(key) != null } ?: defaultValue

    actual fun putInt(key: String, value: Int) {
        defaults.setInteger(value.toLong(), forKey = key)
    }

    actual fun getLong(key: String, defaultValue: Long): Long =
        defaults.integerForKey(key).takeIf { defaults.objectForKey(key) != null } ?: defaultValue

    actual fun putLong(key: String, value: Long) {
        defaults.setInteger(value, forKey = key)
    }

    actual fun getFloat(key: String, defaultValue: Float): Float =
        defaults.floatForKey(key).takeIf { defaults.objectForKey(key) != null } ?: defaultValue

    actual fun putFloat(key: String, value: Float) {
        defaults.setFloat(value, forKey = key)
    }

    actual fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        defaults.boolForKey(key).takeIf { defaults.objectForKey(key) != null } ?: defaultValue

    actual fun putBoolean(key: String, value: Boolean) {
        defaults.setBool(value, forKey = key)
    }

    actual fun getStringSet(key: String): Set<String>? =
        defaults.arrayForKey(key)?.filterIsInstance<String>()?.toSet()

    actual fun putStringSet(key: String, value: Set<String>) {
        defaults.setObject(value.toList(), forKey = key)
    }

    actual fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }

    actual fun clear() {
        defaults.dictionaryRepresentation().keys.forEach { key ->
            if (key is String) defaults.removeObjectForKey(key)
        }
    }

    actual fun contains(key: String): Boolean = defaults.objectForKey(key) != null
}

actual fun createPlatformPreferences(name: String): PlatformPreferences {
    val defaults = NSUserDefaults(suiteName = name) ?: NSUserDefaults.standardUserDefaults
    return PlatformPreferences(defaults)
}

internal actual val usesIosPreferenceNames: Boolean = true
