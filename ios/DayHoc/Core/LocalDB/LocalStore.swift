import Foundation
import os

/// CSDL cục bộ mã hóa toàn file bằng SQLCipher (Technical Spec 6.4 mục 7): outbox điểm danh và bản chụp danh sách
/// học sinh. Khóa 32 byte ngẫu nhiên sinh lần đầu, giữ trong Keychain; không mở được CSDL chưa mã hóa.
/// `wipe` xóa CSDL và khóa khi đăng xuất (mã thiết bị giữ theo lần cài đặt).
actor LocalStore: OutboxStore, RosterCache, DeviceIdProvider {
    private static let log = Logger(subsystem: "vn.dayhoc.app", category: "localdb")
    private let directory: URL
    private let key: DatabaseKey
    nonisolated(unsafe) private let defaults: UserDefaults // UserDefaults an toàn đa luồng; SDK chưa khai Sendable
    private var db: SQLiteDB?
    private let changes = Broadcaster<[OutboxBatch]>(replayLast: true, buffer: 1)
    /// Tạo mã thiết bị phải có khóa: hai luồng gọi cùng lúc không được sinh hai mã.
    private static let deviceIdLock = NSLock()

    private static let rosterTTL: Int64 = 14 * 24 * 3600 * 1000

    init(directory: URL? = nil, key: DatabaseKey = DatabaseKey(), defaults: UserDefaults = .standard) {
        self.directory = directory ?? URL.applicationSupportDirectory
        self.key = key
        self.defaults = defaults
    }

    private var path: String { directory.appending(path: "dayhoc_local.db").path }

    // MARK: Mở CSDL

    private func open() throws -> SQLiteDB {
        if let db { return db }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var data = try key.load()
        if data == nil {
            // Khóa mất chắc chắn (Keychain bị xóa/không hợp lệ): CSDL cũ không giải mã được nữa, bỏ rồi tạo mới.
            removeFiles()
            data = try key.create()
        }
        let opened: SQLiteDB
        do {
            opened = try SQLiteDB(path: path, key: data!)
        } catch let e as SQLiteError where e.isNotADatabase {
            removeFiles()
            opened = try SQLiteDB(path: path, key: data!)
        }
        try opened.run("""
            CREATE TABLE IF NOT EXISTS outbox_batches(
              idempotencyKey TEXT PRIMARY KEY, sessionId TEXT NOT NULL, ownerUserId TEXT NOT NULL, tenantId TEXT NOT NULL,
              deviceId TEXT NOT NULL, deviceTime TEXT NOT NULL, mutationsJson TEXT NOT NULL, markSessionTaught INTEGER NOT NULL,
              createdAt INTEGER NOT NULL, attempts INTEGER NOT NULL, state TEXT NOT NULL, errorCode TEXT)
            """)
        try opened.run("""
            CREATE TABLE IF NOT EXISTS roster_cache(
              sessionId TEXT NOT NULL, ownerUserId TEXT NOT NULL, tenantId TEXT NOT NULL, json TEXT NOT NULL,
              cachedAt INTEGER NOT NULL, PRIMARY KEY(sessionId, ownerUserId, tenantId))
            """)
        // Bản sao lưu iCloud/máy tính không được mang tên học sinh đi.
        for suffix in ["", "-wal", "-shm"] {
            var url = URL(fileURLWithPath: path + suffix)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            do { try url.setResourceValues(values) } catch {
                Self.log.error("Không đặt được isExcludedFromBackup: \(error.localizedDescription, privacy: .public)")
            }
        }
        db = opened
        return opened
    }

    private func removeFiles() {
        db?.close()
        db = nil
        for suffix in ["", "-wal", "-shm", "-journal"] { try? FileManager.default.removeItem(atPath: path + suffix) }
    }

    /// Đăng xuất: xóa CSDL và khóa. Gọi sau khi đã hủy việc đồng bộ nền.
    func wipe() {
        removeFiles()
        key.delete()
        changes.send([])
    }

    /// Gắn chủ mới: chỉ bản chụp danh sách (có tên học sinh) của chủ khác bị xóa. Lô của chủ cũ giữ nguyên trong
    /// outbox, không gửi, chờ chủ đó đăng nhập lại.
    func purgeForeignRosters(owner: OutboxOwner) throws {
        try open().run("DELETE FROM roster_cache WHERE ownerUserId != ? OR tenantId != ?", [.text(owner.userId), .text(owner.tenantId)])
    }

    // MARK: Outbox

    func all() throws -> [OutboxBatch] {
        try open().query("""
            SELECT idempotencyKey, sessionId, ownerUserId, tenantId, deviceId, deviceTime, mutationsJson, markSessionTaught,
                   createdAt, attempts, state, errorCode FROM outbox_batches ORDER BY createdAt, idempotencyKey
            """) { r in
            let owner = OutboxOwner(userId: r.text(2) ?? "", tenantId: r.text(3) ?? "")
            let mutations = r.text(6).flatMap { try? JSONDecoder().decode([OutboxMutation].self, from: Data($0.utf8)) }
            // Dòng hỏng (không đọc được nội dung): đánh dấu lỗi riêng, không làm sập các lô khác.
            return OutboxBatch(
                idempotencyKey: r.text(0) ?? "", sessionId: r.text(1) ?? "", owner: owner,
                deviceId: r.text(4) ?? "", deviceTime: r.text(5) ?? "", mutations: mutations ?? [],
                markSessionTaught: r.int(7) != 0, createdAt: r.int(8), attempts: Int(r.int(9)),
                state: mutations == nil ? .failed : OutboxState(rawValue: r.text(10) ?? "") ?? .failed,
                errorCode: mutations == nil ? "CORRUPT" : r.text(11)
            )
        }
    }

    func enqueue(_ b: OutboxBatch) throws {
        let json = String(decoding: try JSONEncoder().encode(b.mutations), as: UTF8.self)
        try open().run("""
            INSERT OR REPLACE INTO outbox_batches VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            """, [.text(b.idempotencyKey), .text(b.sessionId), .text(b.owner.userId), .text(b.owner.tenantId),
                  .text(b.deviceId), .text(b.deviceTime), .text(json), .int(b.markSessionTaught ? 1 : 0),
                  .int(b.createdAt), .int(Int64(b.attempts)), .text(b.state.rawValue), b.errorCode.map { .text($0) } ?? .null])
        publishCurrent()
    }

    func remove(_ key: String) throws {
        try open().run("DELETE FROM outbox_batches WHERE idempotencyKey = ?", [.text(key)])
        publishCurrent()
    }

    func addAttempt(_ key: String) throws {
        try open().run("UPDATE outbox_batches SET attempts = attempts + 1 WHERE idempotencyKey = ?", [.text(key)])
    }

    func markFailed(_ key: String, code: String) throws {
        try open().run("UPDATE outbox_batches SET state = 'failed', errorCode = ? WHERE idempotencyKey = ?", [.text(code), .text(key)])
        publishCurrent()
    }

    /// Dòng hỏng (`CORRUPT`) không bao giờ về pending: nội dung không đọc được nên không gửi được.
    func retryFailed(sessionId: String) throws {
        let db = try open()
        for b in try all() where b.sessionId == sessionId && b.state == .failed && b.errorCode != "CORRUPT" {
            try db.run("UPDATE outbox_batches SET state = 'pending', errorCode = NULL, attempts = 0 WHERE idempotencyKey = ?", [.text(b.idempotencyKey)])
        }
        publishCurrent()
    }

    /// Đăng ký đồng bộ trước khi trả về; người nghe mới nhận ngay danh sách hiện tại.
    nonisolated func batches() -> AsyncStream<[OutboxBatch]> {
        let stream = changes.stream()
        Task { await self.publishSnapshotIfPresent() }
        return stream
    }

    /// Chưa có CSDL (chưa đăng nhập, vừa đăng xuất) thì phát danh sách rỗng, không tạo CSDL mới.
    private func publishSnapshotIfPresent() {
        if db == nil && !FileManager.default.fileExists(atPath: path) { changes.send([]); return }
        publishCurrent()
    }

    /// Lỗi đọc sau khi ghi không được làm hỏng thao tác ghi đã xong.
    private func publishCurrent() {
        if let list = try? all() { changes.send(list) }
    }

    // MARK: Bản chụp danh sách

    func get(sessionId: String, owner: OutboxOwner) -> String? {
        try? open().query("SELECT json FROM roster_cache WHERE sessionId = ? AND ownerUserId = ? AND tenantId = ?",
                          [.text(sessionId), .text(owner.userId), .text(owner.tenantId)]) { $0.text(0) }.first ?? nil
    }

    func put(sessionId: String, owner: OutboxOwner, json: String) {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        guard let db = try? open() else { return }
        try? db.run("INSERT OR REPLACE INTO roster_cache VALUES(?,?,?,?,?)",
                    [.text(sessionId), .text(owner.userId), .text(owner.tenantId), .text(json), .int(now)])
        try? db.run("DELETE FROM roster_cache WHERE cachedAt < ?", [.int(now - Self.rosterTTL)])
    }

    func remove(sessionId: String) {
        try? open().run("DELETE FROM roster_cache WHERE sessionId = ?", [.text(sessionId)])
    }

    // MARK: Mã thiết bị

    nonisolated func deviceId() -> String {
        Self.deviceIdLock.lock()
        defer { Self.deviceIdLock.unlock() }
        if let id = defaults.string(forKey: "attendance.deviceId") { return id }
        let id = UUID().uuidString.lowercased()
        defaults.set(id, forKey: "attendance.deviceId")
        return id
    }
}
