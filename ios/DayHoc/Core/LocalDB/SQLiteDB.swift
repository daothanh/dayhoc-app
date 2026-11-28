import Foundation
import SQLCipher

struct SQLiteError: Error, Equatable {
    let code: Int32
    let message: String

    /// Sai khóa hoặc file không phải CSDL SQLCipher (`SQLITE_NOTADB`): dữ liệu không đọc lại được.
    var isNotADatabase: Bool { code == SQLITE_NOTADB }
}

enum SQLiteValue {
    case text(String)
    case int(Int64)
    case null
}

/// Lớp mỏng trên C API của SQLCipher: đủ cho hai bảng của outbox. Không an toàn đa luồng:
/// chỉ dùng bên trong `LocalStore` (actor).
// shortcut: không dùng GRDB vì bản SQLCipher của GRDB chỉ có qua fork không chính thức (xem ADR NA-07);
// nâng cấp khi cần truy vấn phức tạp hơn.
final class SQLiteDB {
    private var handle: OpaquePointer?

    /// Mở (hoặc tạo) file, đặt khóa 32 byte rồi đọc thử để biết khóa có đúng không. Thư viện không có SQLCipher
    /// (`cipher_version` rỗng) thì ném lỗi: không bao giờ ghi CSDL không mã hóa.
    init(path: String, key: Data) throws {
        var db: OpaquePointer?
        let rc = sqlite3_open_v2(path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil)
        handle = db
        guard rc == SQLITE_OK else {
            let error = SQLiteError(code: rc, message: db.map { String(cString: sqlite3_errmsg($0)) } ?? "open")
            close()
            throw error
        }
        do {
            let keyRC = key.withUnsafeBytes { sqlite3_key_v2(db, nil, $0.baseAddress, Int32($0.count)) }
            guard keyRC == SQLITE_OK else { throw error(keyRC) }
            let version = try query("PRAGMA cipher_version") { $0.text(0) }.first ?? nil
            guard let version, !version.isEmpty else { throw SQLiteError(code: SQLITE_ERROR, message: "SQLCipher không khả dụng") }
            _ = try query("SELECT count(*) FROM sqlite_master") { _ in 0 }
        } catch {
            close()
            throw error
        }
    }

    deinit { close() }

    func close() {
        if let handle { sqlite3_close_v2(handle) }
        handle = nil
    }

    func run(_ sql: String, _ binds: [SQLiteValue] = []) throws {
        let stmt = try prepare(sql, binds)
        defer { sqlite3_finalize(stmt) }
        let rc = sqlite3_step(stmt)
        guard rc == SQLITE_DONE || rc == SQLITE_ROW else { throw error(rc) }
    }

    func query<T>(_ sql: String, _ binds: [SQLiteValue] = [], _ map: (Row) -> T) throws -> [T] {
        let stmt = try prepare(sql, binds)
        defer { sqlite3_finalize(stmt) }
        var out: [T] = []
        while true {
            let rc = sqlite3_step(stmt)
            if rc == SQLITE_ROW { out.append(map(Row(stmt: stmt))) } else if rc == SQLITE_DONE { return out } else { throw error(rc) }
        }
    }

    struct Row {
        fileprivate let stmt: OpaquePointer?
        func text(_ i: Int32) -> String? {
            sqlite3_column_text(stmt, i).map { String(cString: $0) }
        }
        func int(_ i: Int32) -> Int64 { sqlite3_column_int64(stmt, i) }
    }

    private func prepare(_ sql: String, _ binds: [SQLiteValue]) throws -> OpaquePointer? {
        var stmt: OpaquePointer?
        let rc = sqlite3_prepare_v2(handle, sql, -1, &stmt, nil)
        guard rc == SQLITE_OK else { throw error(rc) }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self) // SQLITE_TRANSIENT
        for (i, value) in binds.enumerated() {
            let idx = Int32(i + 1)
            let bindRC: Int32 = switch value {
            case .text(let s): sqlite3_bind_text(stmt, idx, s, -1, transient)
            case .int(let n): sqlite3_bind_int64(stmt, idx, n)
            case .null: sqlite3_bind_null(stmt, idx)
            }
            guard bindRC == SQLITE_OK else {
                sqlite3_finalize(stmt)
                throw error(bindRC)
            }
        }
        return stmt
    }

    private func error(_ rc: Int32) -> SQLiteError {
        SQLiteError(code: rc, message: handle.map { String(cString: sqlite3_errmsg($0)) } ?? "closed")
    }
}
