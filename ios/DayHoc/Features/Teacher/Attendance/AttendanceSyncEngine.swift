import Foundation

enum SyncEvent: Equatable, Sendable {
    /// Lô đã gửi xong (applied/duplicate/stale): T03 tải lại. `rejected` = số học sinh server không ghi được (từng phần tử).
    case synced(sessionId: String, rejected: Int = 0)
    /// Lô bị xóa vì lỗi không còn ý nghĩa (buổi đã hủy, quá hạn sửa, không thấy buổi); `code` theo `error_codes.json`.
    case dropped(sessionId: String, code: String)
}

enum FlushResult: Equatable, Sendable {
    /// Không còn lô chờ gửi (lô lỗi vĩnh viễn không tính).
    case done
    /// Còn lô chưa gửi được (mất mạng, 5xx, lỗi bất ngờ): hẹn thử lại.
    case retry
}

/// Kết quả của một lô trong lần flush, để T04 biết lô vừa lưu đi tới đâu.
enum BatchOutcome: Equatable, Sendable {
    case synced(SyncResultDTO)
    /// Còn trong outbox: `network` = mất mạng/hết giờ; ngược lại là lỗi server (5xx…).
    case kept(network: Bool)
    /// Server từ chối vĩnh viễn, lô giữ lại với trạng thái `failed`.
    case failed(String)
    /// Lô bị xóa (buổi đã hủy, quá hạn sửa, không thấy buổi); `result` có khi server trả 200 kèm phần tử `rejected`.
    case deleted(String, result: SyncResultDTO? = nil)
}

struct FlushReport: Equatable, Sendable {
    let result: FlushResult
    /// Theo `Idempotency-Key`.
    let outcomes: [String: BatchOutcome]

    init(result: FlushResult, outcomes: [String: BatchOutcome] = [:]) {
        self.result = result
        self.outcomes = outcomes
    }
}

enum EngineError: Error, Equatable {
    /// Chủ hiện tại không phải chủ của lô (chưa gắn, hết phiên, đã đăng xuất): không ghi, không gửi.
    case ownerMismatch
}

/// Gửi outbox theo thứ tự tạo, dùng đúng `Idempotency-Key` và body đã lưu nên gửi trùng không tạo bản ghi trùng
/// (US-3.5 AC2). Một lần flush chạy tại một thời điểm; lần gọi đến lúc đang bận xếp hàng chờ rồi chạy lại (đọc lại
/// outbox), nên lô vừa vào outbox không bao giờ bị bỏ sót. Lô lỗi chặn các lô sau của cùng buổi, không chặn buổi khác.
/// Chỉ gửi lô của chủ hiện tại.
actor AttendanceSyncEngine {
    private let api: any AttendanceService
    private let outbox: any OutboxStore
    private let roster: any RosterCache
    private let owner: @Sendable () -> OutboxOwner?
    private let hub = Broadcaster<SyncEvent>()
    private var busy = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(api: any AttendanceService, outbox: any OutboxStore, roster: any RosterCache, owner: @escaping @Sendable () -> OutboxOwner?) {
        self.api = api
        self.outbox = outbox
        self.roster = roster
        self.owner = owner
    }

    /// Mỗi lần gọi một luồng riêng, đăng ký ngay; nhiều nơi nghe cùng lúc được và luồng không chết khi một nơi dừng.
    nonisolated func events() -> AsyncStream<SyncEvent> { hub.stream() }

    private func acquire() async {
        if !busy { busy = true; return }
        await withCheckedContinuation { waiters.append($0) }
    }

    private func release() {
        if waiters.isEmpty { busy = false } else { waiters.removeFirst().resume() }
    }

    /// `own`: khóa lô mà T04 vừa lưu và đang chờ kết quả; thông báo "bỏ lô" của lô đó do T04 tự hiện.
    func flush(own: String? = nil) async -> FlushReport {
        await acquire()
        defer { release() }
        return await flushLocked(own)
    }

    /// Ghi outbox rồi gửi trong cùng một lần giữ khóa: không lần flush nào (hay đăng xuất) chen vào giữa.
    func enqueueAndFlush(_ batch: OutboxBatch) async throws -> FlushReport {
        await acquire()
        defer { release() }
        guard owner() == batch.owner else { throw EngineError.ownerMismatch }
        try await outbox.enqueue(batch)
        return await flushLocked(batch.idempotencyKey)
    }

    /// Chỉ ghi outbox (ngoại tuyến), vẫn trong khóa của engine.
    func enqueue(_ batch: OutboxBatch) async throws {
        await acquire()
        defer { release() }
        guard owner() == batch.owner else { throw EngineError.ownerMismatch }
        try await outbox.enqueue(batch)
    }

    /// Xóa các lô trong khóa của engine; lỗi thì ném ra (không nuốt).
    func remove(keys: [String]) async throws {
        await acquire()
        defer { release() }
        for key in keys { try await outbox.remove(key) }
    }

    /// "Gửi lại": lô lỗi của buổi về chờ gửi, trong khóa của engine.
    func retryFailed(sessionId: String) async throws {
        await acquire()
        defer { release() }
        try await outbox.retryFailed(sessionId: sessionId)
    }

    /// Chạy `body` khi không có flush nào đang chạy và chặn flush mới trong lúc đó (đăng xuất xóa CSDL).
    func exclusive<T: Sendable>(_ body: @Sendable () async -> T) async -> T {
        await acquire()
        defer { release() }
        return await body()
    }

    private func flushLocked(_ own: String?) async -> FlushReport {
        // Chưa có chủ (chưa gắn, hết phiên, đã đăng xuất): không đụng CSDL, không gửi gì.
        guard let me = owner() else { return FlushReport(result: .done) }
        var outcomes: [String: BatchOutcome] = [:]
        var retry = false
        do {
            let batches = try await outbox.all().filter { $0.owner == me }
            var blocked = Set(batches.filter { $0.state == .failed }.map(\.sessionId))
            for b in batches where b.state == .pending && !blocked.contains(b.sessionId) {
                let key = b.idempotencyKey
                // Trước mỗi lần gửi: chủ không đổi và lô còn trong outbox (T04 có thể đã bỏ nó).
                if owner() != me { return FlushReport(result: .done, outcomes: outcomes) }
                let stillQueued = try await outbox.all().contains { $0.idempotencyKey == key }
                if !stillQueued { continue }

                let result: SyncResultDTO
                do {
                    result = try await api.sync(key: key, body: b.request)
                } catch is CancellationError {
                    return FlushReport(result: .retry, outcomes: outcomes)
                } catch {
                    let apiError = error as? APIError ?? APIError(kind: .invalidResponse)
                    switch decideOnError(apiError, attempts: b.attempts) {
                    case .drop(let code):
                        do { try await terminal(b, code, nil, own, &outcomes, &blocked) } catch { return failed(error, outcomes) }
                    case .keep(let countAttempt):
                        if countAttempt { try await outbox.addAttempt(key) }
                        retry = true
                        outcomes[key] = .kept(network: !countAttempt)
                        // Mất mạng/401/429: mọi lô sau cũng sẽ lỗi, dừng và hẹn lại; lỗi server thì chỉ chặn buổi này.
                        if !countAttempt { return FlushReport(result: .retry, outcomes: outcomes) }
                        blocked.insert(b.sessionId)
                    }
                    continue
                }

                // Sau 200: lỗi DB không tính lần thử và giữ lô pending; gửi lại cùng khóa server trả `duplicate`.
                do {
                    switch verdictOf(result.results) {
                    case .done:
                        await roster.remove(sessionId: b.sessionId)
                        try await outbox.remove(key)
                        outcomes[key] = .synced(result)
                        hub.send(.synced(sessionId: b.sessionId, rejected: key == own ? 0 : rejectedStudentIds(result.results).count))
                    case .rejected(let code):
                        try await terminal(b, code, result, own, &outcomes, &blocked)
                    }
                } catch is CancellationError {
                    return FlushReport(result: .retry, outcomes: outcomes)
                } catch {
                    retry = true
                    blocked.insert(b.sessionId)
                    outcomes[key] = nil
                }
            }
            return FlushReport(result: retry ? .retry : .done, outcomes: outcomes)
        } catch {
            // Đọc/ghi outbox lỗi bất ngờ: không làm sập tiến trình, hẹn thử lại.
            return failed(error, outcomes)
        }
    }

    private func failed(_ error: any Error, _ outcomes: [String: BatchOutcome]) -> FlushReport {
        FlushReport(result: .retry, outcomes: outcomes)
    }

    private func terminal(
        _ b: OutboxBatch, _ code: String, _ result: SyncResultDTO?, _ own: String?,
        _ outcomes: inout [String: BatchOutcome], _ blocked: inout Set<String>
    ) async throws {
        if isDeletableError(code) {
            await roster.remove(sessionId: b.sessionId)
            try await outbox.remove(b.idempotencyKey)
            outcomes[b.idempotencyKey] = .deleted(code, result: result)
            if b.idempotencyKey != own { hub.send(.dropped(sessionId: b.sessionId, code: code)) }
        } else {
            try await outbox.markFailed(b.idempotencyKey, code: code)
            outcomes[b.idempotencyKey] = .failed(code)
            blocked.insert(b.sessionId)
        }
    }
}
