import Foundation
import Testing
@testable import DayHoc

/// Cùng ca kiểm thử với `AttendanceSyncEngineTest` bên Android, theo thiết kế outbox đã sửa
/// (lô lỗi vĩnh viễn giữ lại với trạng thái `failed`, chỉ chặn buổi của nó).
struct AttendanceSyncEngineTests {
    private let service = FakeService()

    private func next(_ events: AsyncStream<SyncEvent>) async -> SyncEvent? {
        var it = events.makeAsyncIterator()
        return await it.next()
    }

    @Test func successRemovesBatchSendsStoredBodyAndDropsRosterCache() async {
        let outbox = InMemoryOutbox([makeBatch("k1")])
        let roster = InMemoryRoster()
        await roster.put(sessionId: "sess-1", owner: testOwner, json: "{}")
        let engine = makeEngine(service, outbox, roster)
        service.setSync { _, req in FakeService.ok(req) }
        let events = engine.events()

        let report = await engine.flush()

        #expect(report.result == .done)
        let call = service.syncCalls.single
        #expect(call?.key == "k1")
        #expect(call?.body.deviceId == "device-1")
        #expect(call?.body.deviceTime == "2026-11-09T17:45:00.000+07:00") // body đã lưu, không dựng lại
        #expect(call?.body.mutations.first?.clientMutationId == "m-k1")
        #expect(await outbox.snapshot().isEmpty)
        #expect(await roster.get(sessionId: "sess-1", owner: testOwner) == nil)
        #expect(await next(events) == .synced(sessionId: "sess-1"))
    }

    @Test func duplicateCountsAsSuccess() async {
        let outbox = InMemoryOutbox([makeBatch("k1")])
        service.setSync { _, req in FakeService.ok(req, results: FakeService.each(req, "duplicate")) }
        #expect(await makeEngine(service, outbox).flush().result == .done)
        #expect(await outbox.snapshot().isEmpty)
    }

    @Test func networkErrorKeepsBatchStopsAndKeepsOrder() async {
        let outbox = InMemoryOutbox([makeBatch("k1"), makeBatch("k2", session: "sess-2")])
        service.setSync { _, _ in throw APIError(kind: .offline) }
        let report = await makeEngine(service, outbox).flush()
        #expect(report.result == .retry)
        #expect(report.outcomes["k1"] == .kept(network: true))
        #expect(service.syncCalls.count == 1)
        #expect(await outbox.snapshot().map(\.idempotencyKey) == ["k1", "k2"])
        #expect(await outbox.snapshot().first?.attempts == 0)
    }

    @Test func serverErrorCountsAttemptRetriesSameKeyAndDoesNotBlockOtherSessions() async {
        let outbox = InMemoryOutbox([makeBatch("k1"), makeBatch("k2", session: "sess-2")])
        service.setSync { key, req in
            if key == "k1" { throw httpError(500, "INTERNAL_ERROR") }
            return FakeService.ok(req)
        }
        let engine = makeEngine(service, outbox)
        let first = await engine.flush()
        #expect(first.result == .retry)
        #expect(await outbox.snapshot().map(\.idempotencyKey) == ["k1"])
        #expect(await outbox.snapshot().first?.attempts == 1)

        service.setSync { _, req in FakeService.ok(req) }
        #expect(await engine.flush().result == .done)
        #expect(service.syncCalls.map { $0.key } == ["k1", "k2", "k1"])
    }

    @Test func cancelledSessionDeletesBatchAndReportsButLaterBatchesStillSend() async {
        let outbox = InMemoryOutbox([makeBatch("k1"), makeBatch("k2", session: "sess-2")])
        service.setSync { key, req in
            if key == "k1" { throw httpError(409, "SESSION_CANCELLED") }
            return FakeService.ok(req)
        }
        let engine = makeEngine(service, outbox)
        var it = engine.events().makeAsyncIterator()
        let report = await engine.flush()
        #expect(report.result == .done)
        #expect(await outbox.snapshot().isEmpty)
        #expect(report.outcomes["k1"] == .deleted("SESSION_CANCELLED", result: nil))
        #expect(await it.next() == .dropped(sessionId: "sess-1", code: "SESSION_CANCELLED"))
        #expect(await it.next() == .synced(sessionId: "sess-2"))
    }

    @Test func rejectedEditWindowClosedDeletesBatch() async {
        let outbox = InMemoryOutbox([makeBatch("k1")])
        service.setSync { _, req in FakeService.ok(req, results: FakeService.each(req, "rejected", code: "ATTENDANCE_EDIT_WINDOW_CLOSED")) }
        let report = await makeEngine(service, outbox).flush()
        #expect(await outbox.snapshot().isEmpty)
        if case .deleted(let code, let result)? = report.outcomes["k1"] {
            #expect(code == "ATTENDANCE_EDIT_WINDOW_CLOSED")
            #expect(result != nil) // T04 áp phần server đã nhận trước khi từ chối
        } else {
            Issue.record("lô phải bị xóa")
        }
    }

    @Test func otherPermanentErrorMarksFailedAndBlocksOnlyThatSession() async {
        let outbox = InMemoryOutbox([makeBatch("k1"), makeBatch("k1b"), makeBatch("k2", session: "sess-2")])
        service.setSync { key, req in
            if key == "k1" { throw httpError(422, "IDEMPOTENCY_KEY_REUSED") }
            return FakeService.ok(req)
        }
        let engine = makeEngine(service, outbox)
        let report = await engine.flush()
        #expect(report.outcomes["k1"] == .failed("IDEMPOTENCY_KEY_REUSED"))
        let left = await outbox.snapshot()
        #expect(left.map(\.idempotencyKey) == ["k1", "k1b"]) // lô lỗi giữ lại, lô sau cùng buổi chờ, buổi khác đã gửi
        #expect(left.first?.state == .failed)
        #expect(left.first?.errorCode == "IDEMPOTENCY_KEY_REUSED")
        #expect(service.syncCalls.map { $0.key } == ["k1", "k2"])

        // Lô lỗi không bị gửi lại cho tới khi giáo viên bấm "Gửi lại".
        _ = await engine.flush()
        #expect(service.syncCalls.count == 2)
        await outbox.retryFailed(sessionId: "sess-1")
        service.setSync { _, req in FakeService.ok(req) }
        #expect(await engine.flush().result == .done)
        #expect(await outbox.snapshot().isEmpty)
    }

    @Test func tenthServerFailureMarksFailedWithMaxAttempts() async {
        let outbox = InMemoryOutbox([makeBatch("k1", attempts: 9)])
        service.setSync { _, _ in throw httpError(500, "INTERNAL_ERROR") }
        let report = await makeEngine(service, outbox).flush()
        #expect(report.outcomes["k1"] == .failed("MAX_ATTEMPTS"))
        #expect(await outbox.snapshot().first?.state == .failed)
    }

    @Test func onlyCurrentOwnersBatchesAreSent() async {
        let other = OutboxOwner(userId: "u2", tenantId: "t1")
        let outbox = InMemoryOutbox([makeBatch("mine"), makeBatch("theirs", session: "sess-2", owner: other)])
        service.setSync { _, req in FakeService.ok(req) }
        _ = await makeEngine(service, outbox).flush()
        #expect(service.syncCalls.map { $0.key } == ["mine"])
        #expect(await outbox.snapshot().map(\.idempotencyKey) == ["theirs"])
    }

    @Test func callMadeWhileBusyWaitsThenRunsAgainSoNewBatchIsNotLost() async {
        let outbox = InMemoryOutbox([makeBatch("k1")])
        let gate = AsyncGate()
        service.setSync { key, req in
            if key == "k1" { await gate.wait() }
            return FakeService.ok(req)
        }
        let engine = makeEngine(service, outbox)
        async let first = engine.flush()
        while service.syncCalls.isEmpty { await Task.yield() }
        await outbox.enqueue(makeBatch("k2", session: "sess-2")) // vào outbox lúc flush đầu đang bận
        async let second = engine.flush()
        try? await Task.sleep(for: .milliseconds(50))
        await gate.open()
        let (a, b) = await (first, second)
        #expect(a.result == .done)
        #expect(b.outcomes["k2"] != nil)
        #expect(service.syncCalls.map { $0.key } == ["k1", "k2"])
        #expect(await outbox.snapshot().isEmpty)
    }
}

extension AttendanceSyncEngineTests {
    @Test func eventsAreBroadcastToEverySubscriberAndSurviveASubscriberStopping() async {
        let outbox = InMemoryOutbox([makeBatch("k1")])
        service.setSync { _, req in FakeService.ok(req) }
        let engine = makeEngine(service, outbox)
        let first = engine.events()
        let second = engine.events()
        _ = await engine.flush()
        #expect(await next(first) == .synced(sessionId: "sess-1"))
        #expect(await next(second) == .synced(sessionId: "sess-1"))

        // Người nghe dừng (task bị hủy) không làm luồng của người nghe sau chết: lần gọi mới là luồng mới.
        await outbox.enqueue(makeBatch("k2", session: "sess-2"))
        let third = engine.events()
        _ = await engine.flush()
        #expect(await next(third) == .synced(sessionId: "sess-2"))
    }

    @Test func enqueueAndFlushWritesAndSendsUnderOneLockAndRefusesWithoutOwner() async throws {
        let outbox = InMemoryOutbox()
        service.setSync { _, req in FakeService.ok(req) }
        let report = try await makeEngine(service, outbox).enqueueAndFlush(makeBatch("k1"))
        #expect(report.outcomes["k1"] != nil)
        #expect(await outbox.snapshot().isEmpty)

        // Chưa có chủ (đăng xuất/hết phiên): không ghi gì vào outbox.
        let blocked = makeEngine(service, outbox, owner: nil)
        await #expect(throws: EngineError.ownerMismatch) { try await blocked.enqueueAndFlush(makeBatch("k2")) }
        #expect(await outbox.snapshot().isEmpty)
    }

    @Test func databaseErrorAfter200DoesNotCountAttemptAndKeepsBatchPending() async {
        let outbox = InMemoryOutbox([makeBatch("k1")])
        await outbox.setFailRemove(true)
        service.setSync { _, req in FakeService.ok(req) }
        let report = await makeEngine(service, outbox).flush()
        #expect(report.result == .retry)
        let left = await outbox.snapshot()
        #expect(left.single?.state == .pending)
        #expect(left.single?.attempts == 0)
    }

    @Test func unreadableOutboxIsAbsorbedAndRetried() async {
        let outbox = InMemoryOutbox([makeBatch("k1")])
        await outbox.setFailAll(true)
        #expect(await makeEngine(service, outbox).flush().result == .retry)
        #expect(service.syncCalls.isEmpty)
    }

    @Test func batchRemovedByT04BeforeItsTurnIsNotSent() async throws {
        let outbox = InMemoryOutbox([makeBatch("k1"), makeBatch("k2", session: "sess-2")])
        let engine = makeEngine(service, outbox)
        service.setSync { _, req in FakeService.ok(req) }
        try await engine.remove(keys: ["k1"])
        _ = await engine.flush()
        #expect(service.syncCalls.map { $0.key } == ["k2"])
    }
}

/// Cổng để giữ một lời gọi giả cho tới khi test mở.
actor AsyncGate {
    private var isOpen = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    func wait() async { if isOpen { return }; await withCheckedContinuation { waiters.append($0) } }
    func open() { isOpen = true; waiters.forEach { $0.resume() }; waiters = [] }
}

extension Array {
    /// Phần tử duy nhất, `nil` nếu không đúng một phần tử.
    var single: Element? { count == 1 ? first : nil }
}