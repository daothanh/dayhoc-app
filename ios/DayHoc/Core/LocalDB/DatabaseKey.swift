import Foundation
import Security

/// Khóa 32 byte ngẫu nhiên của CSDL cục bộ, giữ trong Keychain (chỉ đọc được sau lần mở khóa đầu để tác vụ nền
/// chạy được, không theo bản sao lưu sang máy khác).
struct DatabaseKey: Sendable {
    var service = "vn.dayhoc.app.localdb"
    var account = "key"

    private var baseQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    /// `nil` khi chắc chắn không có khóa (item không tồn tại hoặc sai độ dài). Lỗi Keychain tạm thời (máy khóa,
    /// dịch vụ bận…) thì ném ra để thử lại sau, không được coi là mất khóa.
    func load() throws -> Data? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw KeychainError(status: status) }
        guard let data = item as? Data, data.count == 32 else { return nil }
        return data
    }

    func create() throws -> Data {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw KeychainError(status: errSecAllocate)
        }
        let data = Data(bytes)
        SecItemDelete(baseQuery as CFDictionary)
        var query = baseQuery
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError(status: status) }
        return data
    }

    func delete() { SecItemDelete(baseQuery as CFDictionary) }
}
