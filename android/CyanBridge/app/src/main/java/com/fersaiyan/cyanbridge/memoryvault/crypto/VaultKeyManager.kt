package com.fersaiyan.cyanbridge.memoryvault.crypto

import android.content.Context
import android.util.Base64
import com.fersaiyan.cyanbridge.shared.memoryvault.crypto.CipherEnvelope
import com.fersaiyan.cyanbridge.shared.memoryvault.crypto.VaultCrypto
import java.util.concurrent.atomic.AtomicReference

object VaultKeyManager {
    private const val PREFS = "memory_vault_keys"
    private const val KEY_MASTER_DEVICE_WRAPPED = "master_device_wrapped"
    private const val KEY_PASS_SALT = "passphrase_salt"
    private const val KEY_MASTER_PASS_WRAPPED = "master_pass_wrapped"
    private const val KEY_REQUIRES_PASSPHRASE = "requires_passphrase"

    private val cachedMasterKey = AtomicReference<ByteArray?>(null)

    fun requiresPassphrase(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_REQUIRES_PASSPHRASE, false)
    }

    fun ensureMasterKeyExists(context: Context) {
        val p = prefs(context)
        if (!p.getString(KEY_MASTER_DEVICE_WRAPPED, null).isNullOrBlank()) return

        val master = VaultCrypto.newAesKeyBytes()
        val wrapped = DeviceKeyManager.wrap(master)
        p.edit().putString(KEY_MASTER_DEVICE_WRAPPED, wrapped.encode()).apply()
        cachedMasterKey.set(master.copyOf())
        VaultCrypto.destroy(master)
    }

    fun unlockWithDevice(context: Context): Boolean {
        ensureMasterKeyExists(context)
        if (requiresPassphrase(context)) return false

        val wrapped = DeviceWrappedBlob.decode(prefs(context).getString(KEY_MASTER_DEVICE_WRAPPED, null)) ?: return false
        val master = runCatching { DeviceKeyManager.unwrap(wrapped) }.getOrNull() ?: return false
        replaceCached(master)
        return true
    }

    fun unlockWithPassphrase(context: Context, passphrase: CharArray): Boolean {
        ensureMasterKeyExists(context)
        val p = prefs(context)
        val saltB64 = p.getString(KEY_PASS_SALT, null)
        val wrappedB64 = p.getString(KEY_MASTER_PASS_WRAPPED, null)
        if (saltB64.isNullOrBlank() || wrappedB64.isNullOrBlank()) return false

        val salt = runCatching { Base64.decode(saltB64, Base64.NO_WRAP) }.getOrNull() ?: return false
        val blob = DeviceWrappedBlob.decode(wrappedB64) ?: return false
        val derived = runCatching { VaultCrypto.derivePassphraseKey(passphrase, salt) }.getOrNull() ?: return false
        val master = runCatching {
            VaultCrypto.decryptAesGcm(
                keyBytes = derived,
                envelope = CipherEnvelope(
                    version = VaultCrypto.CRYPTO_VERSION,
                    nonce = blob.nonce,
                    ciphertext = blob.ciphertext,
                )
            )
        }.getOrNull()
        VaultCrypto.destroy(derived)
        if (master == null) return false
        replaceCached(master)
        return true
    }

    fun setPassphrase(context: Context, passphrase: CharArray): Boolean {
        val master = getUnlockedMasterKey(context) ?: return false
        val salt = VaultCrypto.randomBytes(16)
        val derived = runCatching { VaultCrypto.derivePassphraseKey(passphrase, salt) }.getOrNull() ?: return false
        val envelope = runCatching {
            VaultCrypto.encryptAesGcm(keyBytes = derived, plaintext = master)
        }.getOrNull() ?: run {
            VaultCrypto.destroy(derived)
            return false
        }
        VaultCrypto.destroy(derived)

        prefs(context).edit()
            .putBoolean(KEY_REQUIRES_PASSPHRASE, true)
            .putString(KEY_PASS_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(
                KEY_MASTER_PASS_WRAPPED,
                DeviceWrappedBlob(nonce = envelope.nonce, ciphertext = envelope.ciphertext).encode(),
            )
            .apply()
        VaultCrypto.destroy(salt)
        return true
    }

    fun clearPassphrase(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_REQUIRES_PASSPHRASE, false)
            .remove(KEY_PASS_SALT)
            .remove(KEY_MASTER_PASS_WRAPPED)
            .apply()
    }

    fun getUnlockedMasterKey(context: Context): ByteArray? {
        val cached = cachedMasterKey.get()
        if (cached != null) return cached.copyOf()
        if (!requiresPassphrase(context)) {
            if (unlockWithDevice(context)) return cachedMasterKey.get()?.copyOf()
        }
        return null
    }

    fun lock() {
        val existing = cachedMasterKey.getAndSet(null)
        VaultCrypto.destroy(existing)
    }

    fun clearAll(context: Context) {
        lock()
        prefs(context).edit().clear().apply()
    }

    private fun replaceCached(bytes: ByteArray) {
        val prev = cachedMasterKey.getAndSet(bytes.copyOf())
        VaultCrypto.destroy(prev)
        VaultCrypto.destroy(bytes)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
