package com.fersaiyan.cyanbridge.memoryvault.crypto

import android.content.Context
import android.util.Base64
import com.fersaiyan.cyanbridge.shared.memoryvault.crypto.CipherEnvelope
import com.fersaiyan.cyanbridge.shared.memoryvault.crypto.VaultCrypto
import com.fersaiyan.cyanbridge.shared.prefs.MemoryPrefs.VaultKeys
import com.fersaiyan.cyanbridge.shared.prefs.clear
import com.fersaiyan.cyanbridge.shared.prefs.edit
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import java.util.concurrent.atomic.AtomicReference

object VaultKeyManager {
    private val cachedMasterKey = AtomicReference<ByteArray?>(null)

    fun requiresPassphrase(context: Context): Boolean {
        return VaultKeys.requiresPassphrase[context]
    }

    fun ensureMasterKeyExists(context: Context) {
        if (!VaultKeys.masterDeviceWrapped[context].isNullOrBlank()) return

        val master = VaultCrypto.newAesKeyBytes()
        val wrapped = DeviceKeyManager.wrap(master)
        VaultKeys.masterDeviceWrapped[context] = wrapped.encode()
        cachedMasterKey.set(master.copyOf())
        VaultCrypto.destroy(master)
    }

    fun unlockWithDevice(context: Context): Boolean {
        ensureMasterKeyExists(context)
        if (requiresPassphrase(context)) return false

        val wrapped = DeviceWrappedBlob.decode(VaultKeys.masterDeviceWrapped[context]) ?: return false
        val master = runCatching { DeviceKeyManager.unwrap(wrapped) }.getOrNull() ?: return false
        replaceCached(master)
        return true
    }

    fun unlockWithPassphrase(context: Context, passphrase: CharArray): Boolean {
        ensureMasterKeyExists(context)
        val saltB64 = VaultKeys.passphraseSalt[context]
        val wrappedB64 = VaultKeys.masterPassWrapped[context]
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

        VaultKeys.edit(context) {
            this[VaultKeys.requiresPassphrase] = true
            this[VaultKeys.passphraseSalt] = Base64.encodeToString(salt, Base64.NO_WRAP)
            this[VaultKeys.masterPassWrapped] =
                DeviceWrappedBlob(nonce = envelope.nonce, ciphertext = envelope.ciphertext).encode()
        }
        VaultCrypto.destroy(salt)
        return true
    }

    fun clearPassphrase(context: Context) {
        VaultKeys.edit(context) {
            this[VaultKeys.requiresPassphrase] = false
            this[VaultKeys.passphraseSalt] = null
            this[VaultKeys.masterPassWrapped] = null
        }
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
        VaultKeys.clear(context)
    }

    private fun replaceCached(bytes: ByteArray) {
        val prev = cachedMasterKey.getAndSet(bytes.copyOf())
        VaultCrypto.destroy(prev)
        VaultCrypto.destroy(bytes)
    }
}
