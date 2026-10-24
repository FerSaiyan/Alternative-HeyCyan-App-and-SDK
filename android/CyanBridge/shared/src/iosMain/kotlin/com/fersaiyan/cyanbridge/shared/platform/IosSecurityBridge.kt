package com.fersaiyan.cyanbridge.shared.platform

import platform.Foundation.NSData

/**
 * CryptoKit AES-GCM and Keychain storage, implemented in Swift by the iOS host
 * (neither has a practical Objective-C/Kotlin interop surface).
 */
interface IosSecurityBridge {
    /** Returns ciphertext followed by the 16-byte tag, or null on failure. */
    fun sealAesGcm(key: NSData, nonce: NSData, plaintext: NSData, aad: NSData?): NSData?

    /** Returns the plaintext, or null when authentication fails. */
    fun openAesGcm(key: NSData, nonce: NSData, sealed: NSData, aad: NSData?): NSData?

    fun keychainSet(account: String, value: NSData): Boolean
    fun keychainGet(account: String): NSData?
    fun keychainDelete(account: String)
}

object IosSecurityRegistry {
    var bridge: IosSecurityBridge? = null

    internal fun require(): IosSecurityBridge =
        bridge ?: error("The iOS host did not register IosSecurityBridge")
}
