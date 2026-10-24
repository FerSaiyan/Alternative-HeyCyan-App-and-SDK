package com.fersaiyan.cyanbridge.shared.memoryvault.crypto

import com.fersaiyan.cyanbridge.shared.platform.IosSecurityRegistry
import com.fersaiyan.cyanbridge.shared.platform.toKotlinBytes
import com.fersaiyan.cyanbridge.shared.platform.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.CoreCrypto.kCCSuccess

/**
 * iOS VaultCrypto: SecRandomCopyBytes, AES-256-GCM through CryptoKit (Swift host
 * bridge) and PBKDF2-HMAC-SHA256 through CommonCrypto.
 */
@OptIn(ExperimentalForeignApi::class)
actual object VaultCrypto {
    const val CRYPTO_VERSION: Int = 1
    private const val AES_KEY_BYTES: Int = 32
    private const val GCM_NONCE_BYTES: Int = 12
    private const val PBKDF2_ITERATIONS: UInt = 150_000u

    actual fun randomBytes(size: Int): ByteArray {
        val buffer = ByteArray(size)
        if (size == 0) return buffer
        buffer.usePinned { pinned ->
            val status = platform.Security.SecRandomCopyBytes(
                platform.Security.kSecRandomDefault,
                size.toULong(),
                pinned.addressOf(0),
            )
            if (status != 0) throw RuntimeException("SecRandomCopyBytes failed with status $status")
        }
        return buffer
    }

    actual fun newAesKeyBytes(): ByteArray = randomBytes(AES_KEY_BYTES)

    actual fun encryptAesGcm(keyBytes: ByteArray, plaintext: ByteArray, aad: ByteArray?): CipherEnvelope {
        require(keyBytes.size == AES_KEY_BYTES) { "Key must be $AES_KEY_BYTES bytes" }
        val nonce = randomBytes(GCM_NONCE_BYTES)
        val sealed = IosSecurityRegistry.require()
            .sealAesGcm(keyBytes.toNSData(), nonce.toNSData(), plaintext.toNSData(), aad?.toNSData())
            ?: throw RuntimeException("AES-GCM encryption failed")
        return CipherEnvelope(version = CRYPTO_VERSION, nonce = nonce, ciphertext = sealed.toKotlinBytes())
    }

    actual fun decryptAesGcm(keyBytes: ByteArray, envelope: CipherEnvelope, aad: ByteArray?): ByteArray {
        require(keyBytes.size == AES_KEY_BYTES) { "Key must be $AES_KEY_BYTES bytes" }
        return IosSecurityRegistry.require()
            .openAesGcm(keyBytes.toNSData(), envelope.nonce.toNSData(), envelope.ciphertext.toNSData(), aad?.toNSData())
            ?.toKotlinBytes()
            ?: throw RuntimeException("GCM authentication tag mismatch")
    }

    actual fun derivePassphraseKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val password = passphrase.concatToString()
        val derived = ByteArray(AES_KEY_BYTES)
        val status = salt.usePinned { saltPin ->
            derived.usePinned { derivedPin ->
                CCKeyDerivationPBKDF(
                    algorithm = kCCPBKDF2,
                    password = password,
                    passwordLen = password.encodeToByteArray().size.toULong(),
                    salt = if (salt.isEmpty()) null else saltPin.addressOf(0).reinterpret(),
                    saltLen = salt.size.toULong(),
                    prf = kCCPRFHmacAlgSHA256,
                    rounds = PBKDF2_ITERATIONS,
                    derivedKey = derivedPin.addressOf(0).reinterpret(),
                    derivedKeyLen = derived.size.toULong(),
                )
            }
        }
        check(status == kCCSuccess) { "PBKDF2 failed with status $status" }
        return derived
    }

    actual fun destroy(bytes: ByteArray?) {
        if (bytes == null) return
        for (i in bytes.indices) bytes[i] = 0
    }
}
