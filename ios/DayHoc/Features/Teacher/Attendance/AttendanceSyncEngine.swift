import Foundation

enum SyncEvent: Equatable, Sendable {
    /// Lô đã gửi xong (applied/duplicate/stale): T03 tải lại.
    case synced(sessionId: String)
    /// Lô bị xóa vì lỗi không còn ý nghĩa (buổi đã hủy, quá hạn sửa, không thấy buổi); `code` theo `error_codes.json`.
    case dropped(sessionId: String, code: String)
}

enum FlushResult: Equatable, Sendable {
    /// Không còn lô chờ gửi (lô lỗi vĩnh viễn không tính).
    case done
    /// Còn lô chưa gửi được (mất mạng, 5xx): hẹn thử lại.
    case retry
}

/// Kết quả của một lô trong lần flush, để T04 biết lô vừa lưu đi tới đâu.
enum BatchOutcome: Equatable, Sendable {
    case synced(SyncResultDTO)
    /// Còn trong outbox: `network` = mất mạng/hết giờ; ngược lại là lỗi server (5xx…).
    case kept(network: Bool)
    /// Server từ chối vĩnh viễn, lô giữ lại với trạng thái `failed`.
    case failed(String)
    /// Lô bị xóa (buổi đã hủy, quá hạn sửa, không thấy buổi).
    case deleted(String)
}

struct FlushReport: Equatable, Sendable {
    let result: FlushResult
    /// Theo `Idempotency-Key`.
    let outcomes: [String: BatchOutcome]
}

/// Gửi outbox theo thứ tự tạo, dùng đúng `Idempotency-Key` và body đã lưu nên gửi trùng không tạo bản ghi trùng
/// (US-3.5 AC2). Một lần flush chạy tại một thời điểm; lần gọi đến lúc đang bận xếp hàng chờ rồi chạy lại,
/// nên lô vừa vào outbox không bao giờ bị bỏ sót. Lô lỗi chặn các lô sau của cùng buổi, không chặn buổi khác.
actor AttendanceSyncEngine {
    private let api: any AttendanceService
    private let outbox: any OutboxStore
    private let roster: any RosterCache
    private let owner: @Sendable () -> OutboxOwner?
    nonisolated let events: AsyncStream<SyncEvent>
    private let eventSink: AsyncStream<SyncEvent>.Continuation
    private var busy = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(api: any AttendanceService, outbox: any OutboxStore, roster: any RosterCache, owner: @escaping @Sendable () -> OutboxOwner?) {
        self.api = api
        self.outbox = outbox
        self.roster = roster
        self.owner = owner
        (events, eventSink) = AsyncStream.makeStream(of: SyncEvent.self, bufferingPolicy: .bufferingNewest(16))
    }

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
        guard let owner = owner() else { return FlushReport(result: .done, outcomes: [:]) }
        let batches: [OutboxBatch]
        do { batches = try await outbox.all().filter { $0.owner == owner } } catch { return FlushReport(result: .retry, outcomes: [:]) }

        var outcomes: [String: BatchOutcome] = [:]
        var blocked = Set(batches.filter { $0.state == .failed }.map(\.sessionId))
        var retry = false
        for b in batches where b.state == .pending && !blocked.contains(b.sessionId) {
            let key = b.idempotencyKey
            do {
                let result = try await api.sync(key: key, body: b.request)
                switch verdictOf(result.results) {
                case .done:
                    try await outbox.remove(key)
                    await roster.remove(sessionId: b.sessionId)
                    outcomes[key] = .synced(result)
                    eventSink.yield(.synced(sessionId: b.sessionId))
                case .rejected(let code):
                    try await terminal(b, code, own: own, &outcomes, &blocked)
                }
            } catch is CancellationError {
                return FlushReport(result: .retry, outcomes: outcomes)
            } catch {
                let apiError = error as? APIError ?? APIError(kind: .invalidResponse)
                do {
                    switch decideOnError(apiError, attempts: b.attempts) {
                    case .drop(let code):
                        try await terminal(b, code, own: own, &outcomes, &blocked)
                    case .keep(let countAttempt):
                        if countAttempt { try await outbox.addAttempt(key) }
                        retry = true
                        let network = !countAttempt
                        outcomes[key] = .kept(network: network)
                        // Mất mạng/401/429: mọi lô sau cũng sẽ lỗi, dừng và hẹn lại; lỗi server thì chỉ chặn buổi này.
                        if network { return FlushReport(result: .retry, outcomes: outcomes) }
                        blocked.insert(b.sessionId)
                    }
                } catch {
                    return FlushReport(result: .retry, outcomes: outcomes)
                }
            }
        }
        return FlushReport(result: retry ? .retry : .done, outcomes: outcomes)
    }

    private func terminal(
        _ b: OutboxBatch, _ code: String, own: String?,
        _ outcomes: inout [String: BatchOutcome], _ blocked: inout Set<String>
    ) async throws {
        if isDeletableError(code) {
            try await outbox.remove(b.idempotencyKey)
            outcomes[b.idempotencyKey] = .deleted(code)
            if b.idempotencyKey != own { eventSink.yield(.dropped(sessionId: b.sessionId, code: code)) }
        } else {
            try await outbox.markFailed(b.idempotencyKey, code: code)
            outcomes[b.idempotencyKey] = .failed(code)
            blocked.insert(b.sessionId)
        }
    }
}
