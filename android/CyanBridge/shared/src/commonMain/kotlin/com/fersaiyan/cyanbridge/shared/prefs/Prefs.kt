package com.fersaiyan.cyanbridge.shared.prefs

import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences
import com.fersaiyan.cyanbridge.shared.platform.createPlatformPreferences
import com.fersaiyan.cyanbridge.shared.platform.usesIosPreferenceNames

/** The value type a preference is stored as. Changing it would crash or reset installed apps. */
enum class PrefType { STRING, INT, LONG, FLOAT, BOOLEAN, STRING_SET }

/** A typed value kept in one [PrefsFile]: a stored [Pref] or a [Pref.map]ped view of one. */
interface PrefValue<T> {
    val file: PrefsFile
    fun read(store: PlatformPreferences): T
    fun write(store: PlatformPreferences, value: T)

    /** Reads with the platform's own store; Android callers use the Context accessors instead. */
    fun get(): T
    fun set(value: T)
}

/**
 * One stored key with its type and default. A null [default] reads as null while the key is
 * absent, and writing null removes the key.
 */
class Pref<T> internal constructor(
    override val file: PrefsFile,
    val key: String,
    val type: PrefType,
    val default: T,
    private val iosFile: String? = null,
    private val iosKey: String = key,
) : PrefValue<T> {
    /** Store (Android SharedPreferences file / iOS defaults suite) holding this key. */
    val storeName: String get() = if (usesIosPreferenceNames) iosFile ?: file.storeName else file.name
    val storeKey: String get() = if (usesIosPreferenceNames) iosKey else key

    /** Where the iOS app keeps this key, when it differs from the Android file and key. */
    val iosLocation: String?
        get() {
            val suite = iosFile ?: file.iosName
            return if (suite == file.name && iosKey == key) null else "$suite/$iosKey"
        }

    @Suppress("UNCHECKED_CAST")
    override fun read(store: PlatformPreferences): T {
        if (default == null && !store.contains(storeKey)) return default
        return when (type) {
            PrefType.STRING -> store.getString(storeKey, default as String? ?: "")
            PrefType.INT -> store.getInt(storeKey, default as Int? ?: 0)
            PrefType.LONG -> store.getLong(storeKey, default as Long? ?: 0L)
            PrefType.FLOAT -> store.getFloat(storeKey, default as Float? ?: 0f)
            PrefType.BOOLEAN -> store.getBoolean(storeKey, default as Boolean? ?: false)
            PrefType.STRING_SET -> store.getStringSet(storeKey) ?: default
        } as T
    }

    override fun write(store: PlatformPreferences, value: T) {
        when (value) {
            null -> store.remove(storeKey)
            is String -> store.putString(storeKey, value)
            is Int -> store.putInt(storeKey, value)
            is Long -> store.putLong(storeKey, value)
            is Float -> store.putFloat(storeKey, value)
            is Boolean -> store.putBoolean(storeKey, value)
            is Set<*> -> store.putStringSet(storeKey, value.map { it as String }.toSet())
            else -> error("Unsupported preference value for $key")
        }
    }

    fun isSet(store: PlatformPreferences): Boolean = store.contains(storeKey)
    fun remove(store: PlatformPreferences) = store.remove(storeKey)

    override fun get(): T = read(createPlatformPreferences(storeName))
    override fun set(value: T) = write(createPlatformPreferences(storeName), value)

    /** A view that decodes on read and encodes on write; the stored type stays [type]. */
    fun <R> map(decode: (T) -> R, encode: (R) -> T): PrefValue<R> = Mapped(this, decode, encode)

    override fun toString(): String = "${file.name}/$key"
}

private class Mapped<S, T>(
    private val stored: Pref<S>,
    private val decode: (S) -> T,
    private val encode: (T) -> S,
) : PrefValue<T> {
    override val file: PrefsFile get() = stored.file
    override fun read(store: PlatformPreferences): T = decode(stored.read(store))
    override fun write(store: PlatformPreferences, value: T) = stored.write(store, encode(value))
    override fun get(): T = decode(stored.get())
    override fun set(value: T) = stored.set(encode(value))
}

operator fun <T> PlatformPreferences.get(pref: PrefValue<T>): T = pref.read(this)

/** `store[pref] = value`, also inside an Android batch edit. */
operator fun <T> PlatformPreferences.set(pref: PrefValue<T>, value: T) = pref.write(this, value)

/** Clamps on read and on write, as the hand-written getters and setters did. */
fun Pref<Int>.clamped(min: Int, max: Int): PrefValue<Int> =
    map({ it.coerceIn(min, max) }, { it.coerceIn(min, max) })

/** Clamps on read and on write, as the hand-written getters and setters did. */
fun Pref<Long>.clamped(min: Long, max: Long): PrefValue<Long> =
    map({ it.coerceIn(min, max) }, { it.coerceIn(min, max) })

/** A user prompt: trimmed and cut to [maxChars] when saved, cut again when read. */
fun Pref<String>.prompt(maxChars: Int): PrefValue<String> =
    map({ it.take(maxChars) }, { it.trim().take(maxChars) })

/** Keys built from a [pattern] whose `*` is replaced at runtime (a date, a model id...). */
class PrefFamily<T> internal constructor(
    val file: PrefsFile,
    val pattern: String,
    val type: PrefType,
    val default: T,
) {
    operator fun get(id: String): Pref<T> = Pref(file, pattern.replace("*", id), type, default)
    override fun toString(): String = "${file.name}/$pattern"
}

/**
 * One preferences file: an Android SharedPreferences name and, on iOS, an NSUserDefaults
 * suite ([iosName] when the iOS app already used another one). Declare every key once here.
 * Installed apps hold these names, so a file name, key, type or default must never change.
 * A [secure] file is encrypted on Android and never opened as plain [PlatformPreferences].
 */
abstract class PrefsFile(
    val name: String,
    val iosName: String = name,
    val secure: Boolean = false,
) {
    private val declared = mutableListOf<Any>()

    /** Every [Pref] and [PrefFamily] declared in this file, in declaration order. */
    val entries: List<Any> get() = declared

    val storeName: String get() = if (usesIosPreferenceNames) iosName else name

    /** The plain store for this file on the current platform. */
    fun store(): PlatformPreferences {
        check(!secure) { "$name is encrypted; open it through the platform's secure store" }
        return createPlatformPreferences(storeName)
    }

    private fun <T> pref(key: String, type: PrefType, default: T, iosFile: String? = null, iosKey: String = key) =
        Pref(this, key, type, default, iosFile, iosKey).also { declared += it }

    private fun <T> family(pattern: String, type: PrefType, default: T): PrefFamily<T> {
        require(pattern.count { it == '*' } == 1) { "Key pattern needs one '*': $pattern" }
        return PrefFamily(this, pattern, type, default).also { declared += it }
    }

    protected fun string(key: String, default: String, iosFile: String? = null, iosKey: String = key) =
        pref(key, PrefType.STRING, default, iosFile, iosKey)
    protected fun int(key: String, default: Int, iosFile: String? = null, iosKey: String = key) =
        pref(key, PrefType.INT, default, iosFile, iosKey)
    protected fun long(key: String, default: Long) = pref(key, PrefType.LONG, default)
    protected fun float(key: String, default: Float) = pref(key, PrefType.FLOAT, default)
    protected fun boolean(key: String, default: Boolean, iosFile: String? = null, iosKey: String = key) =
        pref(key, PrefType.BOOLEAN, default, iosFile, iosKey)

    /** Values that read as null until first written. */
    protected fun optionalString(key: String, iosFile: String? = null, iosKey: String = key) =
        pref<String?>(key, PrefType.STRING, null, iosFile, iosKey)
    protected fun optionalInt(key: String) = pref<Int?>(key, PrefType.INT, null)
    protected fun optionalLong(key: String) = pref<Long?>(key, PrefType.LONG, null)
    protected fun optionalStringSet(key: String) = pref<Set<String>?>(key, PrefType.STRING_SET, null)

    protected fun stringFamily(pattern: String, default: String?) = family(pattern, PrefType.STRING, default)
    protected fun intFamily(pattern: String, default: Int) = family(pattern, PrefType.INT, default)
    protected fun longFamily(pattern: String, default: Long) = family(pattern, PrefType.LONG, default)
    protected fun optionalLongFamily(pattern: String) = family<Long?>(pattern, PrefType.LONG, null)
    protected fun booleanFamily(pattern: String, default: Boolean) = family(pattern, PrefType.BOOLEAN, default)
}
