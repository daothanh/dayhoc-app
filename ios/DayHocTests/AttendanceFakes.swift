import Foundation
import os
@testable import DayHoc

struct InjectedError: Error {}

actor InMemoryOutbox: OutboxStore {
    private var failRemove = false
    private var failAll = false
    private var cancelOnRemove = false
    func setCancelOnRemove(_ on: Bool) { cancelOnRemove = on }
    func setFailRemove(_ on: Bool) { failRemove = on }
    func setFailAll(_ on: Bool) { failAll = on }
    private var list: [OutboxBatch]
    private var subs: [UUID: AsyncStream<[OutboxBatch]>.Continuation] = [:]

    init(_ initial: [OutboxBatch] = []) { list = initial }

    func snapshot() -> [OutboxBatch] { list }
    func all() throws -> [OutboxBatch] {
        if failAll { throw InjectedError() }
        return list
    }
    func enqueue(_ b: OutboxBatch) { list.append(b); publish() }
    func remove(_ key: String) throws {
        if cancelOnRemove { throw CancellationError() }
        if failRemove { throw InjectedError() }
        list.removeAll { $0.idempotencyKey == key }
        publish()
    }
    func addAttempt(_ key: String) { update(key) { $0.attempts += 1 } }
    func markFailed(_ key: String, code: String) { update(key) { $0.state = .failed; $0.errorCode = code } }
    func retryFailed(sessionId: String) {
        list = list.map { var b = $0; if b.sessionId == sessionId, b.state == .failed { b.state = .pending; b.errorCode = nil; b.attempts = 0 }; return b }
        publish()
    }
    nonisolated func batches() -> AsyncStream<[OutboxBatch]> {
        let (stream, c) = AsyncStream.makeStream(of: [OutboxBatch].self, bufferingPolicy: .bufferingNewest(1))
        Task { await self.sub(c) }
        return stream
    }
    private func sub(_ c: AsyncStream<[OutboxBatch]>.Continuation) { subs[UUID()] = c; c.yield(list) }
    private func update(_ key: String, _ f: (inout OutboxBatch) -> Void) {
        if let i = list.firstIndex(where: { $0.idempotencyKey == key }) { f(&list[i]) }
        publish()
    }
    private func publish() { subs.values.forEach { $0.yield(list) } }
}

actor InMemoryRoster: RosterCache {
    var map: [String: String] = [:]
    func get(sessionId: String, owner: OutboxOwner) -> String? { map[sessionId] }
    func put(sessionId: String, owner: OutboxOwner, json: String) { map[sessionId] = json }
    func remove(sessionId: String) { map[sessionId] = nil }
    func clear() { map = [:] }
}

struct FixedDevice: DeviceIdProvider {
    func deviceId() -> String { "device-1" }
}

final class FakeConnectivity: ConnectivityMonitor, Sendable {
    private let online = OSAllocatedUnfairLock(initialState: true)
    init(online: Bool = true) { self.online.withLock { $0 = online } }
    var isOnline: Bool { online.withLock { $0 } }
    func set(_ on: Bool) { online.withLock { $0 = on } }
    func updates() -> AsyncStream<Bool> { AsyncStream { $0.yield(self.isOnline) } }
}

/// `get`/`sync` theo kịch bản; `sync` ghi lại (khóa, body) để test kiểm.
final class FakeService: AttendanceService, Sendable {
    private let state = OSAllocatedUnfairLock(initialState: (
        get: { @Sendable () throws -> SessionAttendanceDTO in throw APIError(kind: .invalidResponse) },
        sync: { @Sendable (_: String, _: SyncRequestDTO) async throws -> SyncResultDTO in throw APIError(kind: .invalidResponse) },
        calls: [(String, SyncRequestDTO)]()
    ))

    var syncCalls: [(key: String, body: SyncRequestDTO)] { state.withLock { $0.calls.map { (key: $0.0, body: $0.1) } } }

    func setGet(_ f: @escaping @Sendable () throws -> SessionAttendanceDTO) { state.withLock { $0.get = f } }
    func setSync(_ f: @escaping @Sendable (String, SyncRequestDTO) async throws -> SyncResultDTO) { state.withLock { $0.sync = f } }

    func get(sessionId: String) async throws -> SessionAttendanceDTO { try state.withLock { $0.get }() }
    func sync(key: String, body: SyncRequestDTO) async throws -> SyncResultDTO {
        let f = state.withLock { s -> @Sendable (String, SyncRequestDTO) async throws -> SyncResultDTO in
            s.calls.append((key, body))
            return s.sync
        }
        return try await f(key, body)
    }
    func repeatWeekdays(classId: String) async throws -> [Int] { [] }

    static func ok(_ req: SyncRequestDTO, parents: Int = 0, results: [MutationResultDTO]? = nil) -> SyncResultDTO {
        SyncResultDTO(
            sessionId: req.sessionId, sessionStatus: "taught",
            results: results ?? req.mutations.map { MutationResultDTO(clientMutationId: $0.clientMutationId, studentId: $0.studentId, result: "applied") },
            parentsToNotify: parents
        )
    }

    static func each(_ req: SyncRequestDTO, _ result: String, code: String? = nil) -> [MutationResultDTO] {
        req.mutations.map { MutationResultDTO(clientMutationId: $0.clientMutationId, studentId: $0.studentId, result: result, errorCode: code) }
    }
}

let testOwner = OutboxOwner(userId: "u1", tenantId: "t1")

func httpError(_ status: Int, _ code: String) -> APIError { APIError(kind: .http, status: status, code: code) }

func item(_ id: String, _ name: String, _ status: String? = nil, note: String? = nil) -> AttendanceItemDTO {
    AttendanceItemDTO(studentId: id, fullName: name, status: status, note: note)
}

func attendanceDto(_ items: [AttendanceItemDTO], status: String = "planned", canEdit: Bool = true) -> SessionAttendanceDTO {
    SessionAttendanceDTO(
        sessionId: "sess-1", classId: "class-1", className: "Toán 9A", sessionDate: "2026-11-09",
        startsAt: "2026-11-09T17:30:00+07:00", sessionStatus: status, canEdit: canEdit, editableUntil: nil, items: items
    )
}

func makeBatch(_ key: String, session: String = "sess-1", attempts: Int = 0, owner: OutboxOwner = testOwner) -> OutboxBatch {
    OutboxBatch(
        idempotencyKey: key, sessionId: session, owner: owner, deviceId: "device-1", deviceTime: "2026-11-09T17:45:00.000+07:00",
        mutations: [OutboxMutation(clientMutationId: "m-\(key)", studentId: "s1", status: "unexcused", note: nil, clientUpdatedAt: "2026-11-09T17:40:00.000+07:00")],
        markSessionTaught: true, createdAt: 1, attempts: attempts
    )
}

func makeEngine(_ service: FakeService, _ outbox: InMemoryOutbox, _ roster: InMemoryRoster = InMemoryRoster(), owner: OutboxOwner? = testOwner) -> AttendanceSyncEngine {
    AttendanceSyncEngine(api: service, outbox: outbox, roster: roster) { owner }
}
