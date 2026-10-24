package com.fersaiyan.cyanbridge.shared.memoryvault.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

/** The portability actual is the same JCA code as the Android one. */
class VaultCryptoTest {
    @Test
    fun aesGcmRoundTripWorks() {
        val key = VaultCrypto.newAesKeyBytes()
        val plaintext = "hello secure memory".encodeToByteArray()
        val aad = "ref:abc".encodeToByteArray()

        val envelope = VaultCrypto.encryptAesGcm(key, plaintext, aad)
        val decrypted = VaultCrypto.decryptAesGcm(key, envelope, aad)

        assertContentEquals(plaintext, decrypted)
        assertNotEquals(plaintext.decodeToString(), envelope.ciphertext.decodeToString())
    }

    @Test
    fun wrongAadFailsAuthentication() {
        val key = VaultCrypto.newAesKeyBytes()
        val plaintext = "memory payload".encodeToByteArray()
        val envelope = VaultCrypto.encryptAesGcm(key, plaintext, "a".encodeToByteArray())
        assertFails { VaultCrypto.decryptAesGcm(key, envelope, "b".encodeToByteArray()) }
    }
}
