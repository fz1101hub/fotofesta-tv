import FotoFestaCore
import Foundation
import Security

/// Token Google nel Portachiavi: cifrati dal sistema e mai inclusi nei backup in chiaro.
final class KeychainTokenStore: TokenStore {
    private let service = "it.fotofesta.tv.google"

    var accessToken: String? {
        get { read("access") }
        set { write("access", newValue) }
    }

    var refreshToken: String? {
        get { read("refresh") }
        set { write("refresh", newValue) }
    }

    var expiresAt: Date? {
        get { read("expires").flatMap(TimeInterval.init).map(Date.init(timeIntervalSince1970:)) }
        set { write("expires", newValue.map { String($0.timeIntervalSince1970) }) }
    }

    func clear() {
        for account in ["access", "refresh", "expires"] { write(account, nil) }
    }

    private func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    private func read(_ account: String) -> String? {
        var request = query(account)
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(request as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func write(_ account: String, _ value: String?) {
        SecItemDelete(query(account) as CFDictionary)
        guard let value else { return }
        var item = query(account)
        item[kSecValueData as String] = Data(value.utf8)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(item as CFDictionary, nil)
    }
}
