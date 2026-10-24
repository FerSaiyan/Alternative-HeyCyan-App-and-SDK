import CryptoKit
import CyanBridgeShared
import Foundation
import Security

/// CryptoKit AES-GCM and Keychain storage for the shared Kotlin code.
final class IosSecurityBridgeImpl: NSObject, IosSecurityBridge {
    static let shared = IosSecurityBridgeImpl()
    private static let tagLength = 16
    private static let service = "com.cyanbridge.vault"

    static func register() {
        IosSecurityRegistry.shared.bridge = shared
    }

    func sealAesGcm(key: Data, nonce: Data, plaintext: Data, aad: Data?) -> Data? {
        guard let gcmNonce = try? AES.GCM.Nonce(data: nonce) else { return nil }
        let symmetricKey = SymmetricKey(data: key)
        let box: AES.GCM.SealedBox?
        if let aad {
            box = try? AES.GCM.seal(plaintext, using: symmetricKey, nonce: gcmNonce, authenticating: aad)
        } else {
            box = try? AES.GCM.seal(plaintext, using: symmetricKey, nonce: gcmNonce)
        }
        guard let box else { return nil }
        return box.ciphertext + box.tag
    }

    func openAesGcm(key: Data, nonce: Data, sealed: Data, aad: Data?) -> Data? {
        guard sealed.count >= Self.tagLength,
              let gcmNonce = try? AES.GCM.Nonce(data: nonce),
              let box = try? AES.GCM.SealedBox(
                nonce: gcmNonce,
                ciphertext: sealed.prefix(sealed.count - Self.tagLength),
                tag: sealed.suffix(Self.tagLength)
              )
        else { return nil }
        let symmetricKey = SymmetricKey(data: key)
        if let aad {
            return try? AES.GCM.open(box, using: symmetricKey, authenticating: aad)
        }
        return try? AES.GCM.open(box, using: symmetricKey)
    }

    func keychainSet(account: String, value: Data) -> Bool {
        keychainDelete(account: account)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData as String: value,
        ]
        return SecItemAdd(query as CFDictionary, nil) == errSecSuccess
    }

    func keychainGet(account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    func keychainDelete(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
