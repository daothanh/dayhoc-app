import Foundation
import Testing
@testable import DayHoc

/// CSDL cục bộ thật (SQLCipher + Keychain của simulator) trong thư mục tạm, mỗi test một khóa riêng.
struct LocalStoreTests {
    private let dir = FileManager.default.temporaryDirectory.appending(path: "dayhoc-localdb-\(UUID().uuidString)")
    private let key = DatabaseKey(service: "vn.dayhoc.app.localdb.test.\(UUID().uuidString)")
    private var dbPath: String { dir.appending(path: "dayhoc_local.db").path }

    private func store() -> LocalStore {
        LocalStore(directory: dir, key: key, defaults: UserDefaults(suiteName: UUID().uuidString)!)
    }

    private func cleanup() {
        key.delete()
        try? FileManager.default.removeItem(at: dir)
    }

    @Test func outboxRoundTripKeepsStoredBodyAndOrder() async throws {
        defer { cleanup() }
        let s = store()
        try await s.enqueue(makeBatch("b"))
        var a = makeBatch("a")
        a.attempts = 2
        try await s.enqueue(a)
        let all = try await s.all()
        #expect(all.map(\.idempotencyKey) == ["a", "b"]) // cùng createdAt thì theo khóa
        #expect(all.first == a)
        #expect(all.last?.deviceTime == "2026-11-09T17:45:00.000+07:00")
        try await s.addAttempt("a")
        try await s.markFailed("b", code: "X")
        let after = try await s.all()
        #expect(after[0].attempts == 3)
        #expect(after[1].state == .failed && after[1].errorCode == "X")
        try await s.retryFailed(sessionId: "sess-1")
        #expect(try await s.all().allSatisfy { $0.state == .pending && $0.errorCode == nil })
        try await s.remove("a")
        #expect(try await s.all().map(\.idempotencyKey) == ["b"])
    }

    @Test func fileIsEncryptedAndSurvivesReopenWithSameKey() async throws {
        defer { cleanup() }
        let s = store()
        try await s.enqueue(makeBatch("a"))
        let header = try Data(contentsOf: URL(fileURLWithPath: dbPath)).prefix(16)
        #expect(String(decoding: header, as: UTF8.self) != "SQLite format 3\0")
        #expect(!(try Data(contentsOf: URL(fileURLWithPath: dbPath))).contains(Data("unexcused".utf8)))
        #expect(try await store().all().count == 1)
    }

    @Test func missingKeyStartsFreshDatabase() async throws {
        defer { cleanup() }
        let s = store()
        try await s.enqueue(makeBatch("a"))
        await s.wipe()
        #expect(!FileManager.default.fileExists(atPath: dbPath))
        #expect(try await store().all().isEmpty)
    }

    @Test func rosterIsScopedToOwnerAndWipeRemovesEverything() async throws {
        defer { cleanup() }
        let s = store()
        await s.put(sessionId: "s", owner: testOwner, json: "{\"a\":1}")
        #expect(await s.get(sessionId: "s", owner: testOwner) == "{\"a\":1}")
        #expect(await s.get(sessionId: "s", owner: OutboxOwner(userId: "u2", tenantId: "t1")) == nil)
        await s.remove(sessionId: "s")
        #expect(await s.get(sessionId: "s", owner: testOwner) == nil)
    }

    @Test func purgeForeignKeepsOnlyCurrentOwner() async throws {
        defer { cleanup() }
        let s = store()
        let other = OutboxOwner(userId: "u2", tenantId: "t1")
        try await s.enqueue(makeBatch("mine"))
        try await s.enqueue(makeBatch("theirs", owner: other))
        await s.put(sessionId: "s", owner: other, json: "{}")
        try await s.purgeForeign(owner: testOwner)
        #expect(try await s.all().map(\.idempotencyKey) == ["mine"])
        #expect(await s.get(sessionId: "s", owner: other) == nil)
    }

    @Test func batchesStreamEmitsCurrentThenChanges() async throws {
        defer { cleanup() }
        let s = store()
        var it = s.batches().makeAsyncIterator()
        #expect(await it.next()?.isEmpty == true)
        try await s.enqueue(makeBatch("a"))
        #expect(await it.next()?.count == 1)
    }

    @Test func deviceIdIsStable() {
        let d = UserDefaults(suiteName: UUID().uuidString)!
        let s = LocalStore(directory: dir, key: key, defaults: d)
        #expect(s.deviceId() == s.deviceId())
        #expect(UUID(uuidString: s.deviceId()) != nil)
    }
}
