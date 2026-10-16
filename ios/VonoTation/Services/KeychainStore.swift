import Foundation
import Security

struct ProviderCredentials: Codable {
    let key: String
    let endpoint: String
}

enum KeychainStore {
    private static let service = "app.voiceflow.ios.provider"
    private static let account = "api-key"
    static func read() throws -> ProviderCredentials? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service,
                                    kSecAttrAccount as String: account,
                                    kSecReturnData as String: true,
                                    kSecMatchLimit as String: kSecMatchLimitOne]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data,
              let value = try? JSONDecoder().decode(ProviderCredentials.self, from: data) else {
            throw VonoError.message("The saved API key is unavailable. Unlock the device and try again.")
        }
        return value
    }
    static func save(_ key: String, endpoint: String) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service,
                                    kSecAttrAccount as String: account]
        if key.isEmpty {
            let status = SecItemDelete(query as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else {
                throw VonoError.message("The API key could not be removed.")
            }
            return
        }
        let attributes: [String: Any] = [kSecValueData as String: try JSONEncoder().encode(ProviderCredentials(key: key, endpoint: endpoint)),
                                         kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var item = query; attributes.forEach { item[$0.key] = $0.value }
            guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else {
                throw VonoError.message("The API key could not be saved securely.")
            }
        } else if status != errSecSuccess { throw VonoError.message("The API key could not be updated securely.") }
    }
}
