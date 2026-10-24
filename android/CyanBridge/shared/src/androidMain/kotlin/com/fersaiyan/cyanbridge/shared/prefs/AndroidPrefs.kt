package com.fersaiyan.cyanbridge.shared.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences

/** The SharedPreferences behind this file; a secure file opens its Keystore-encrypted copy. */
fun PrefsFile.sharedPreferences(context: Context): SharedPreferences {
    if (!secure) return context.getSharedPreferences(name, Context.MODE_PRIVATE)
    val appContext = context.applicationContext
    val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        appContext,
        name,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}

fun PrefsFile.store(context: Context): PlatformPreferences = PlatformPreferences(sharedPreferences(context))

/** Applies the writes in [block] as one edit; see [PlatformPreferences.edit]. */
fun PrefsFile.edit(context: Context, commit: Boolean = false, block: PlatformPreferences.() -> Unit): Boolean =
    store(context).edit(commit, block)

fun PrefsFile.clear(context: Context) = store(context).clear()

operator fun <T> PrefValue<T>.get(context: Context): T = read(file.store(context))

operator fun <T> PrefValue<T>.set(context: Context, value: T) = write(file.store(context), value)

fun Pref<*>.isSet(context: Context): Boolean = isSet(file.store(context))

fun Pref<*>.remove(context: Context) = remove(file.store(context))
