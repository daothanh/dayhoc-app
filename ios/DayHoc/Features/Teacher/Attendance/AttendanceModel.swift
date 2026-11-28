import Foundation
import Observation

/// Thông báo hiện ở T03 (toast) sau khi T04 đóng, hoặc ngay trên T04 khi ở lại.
enum AttendanceNotice: Equatable, Sendable {
    /// `rejectedNames`: học sinh server không ghi được (từng phần tử bị từ chối).
    case saved(parentsToNotify: Int, rejectedNames: [String] = [])
    case savedLocal
    /// Một số học sinh vừa được sửa trên thiết bị khác; T04 ở lại với bản của server.
    case stale
}

enum ReadOnly: Equatable, Sendable { case expired, cancelled }

/// Mã lỗi do chính T04 đặt (không phải mã của server).
enum AttendanceLocalError {
    /// Chưa xác định được tài khoản đang dùng: không lưu.
    static let ownerMissing = "OWNER_MISSING"
    /// Không xóa được lô đã lưu trên máy khi bỏ thay đổi.
    static let discardFailed = "DISCARD_FAILED"
}

/// Lỗi lưu: `code` có thì hiện thông báo riêng; `retry` = có nút "Thử lại" (lô giữ nguyên khóa).
struct SaveError: Equatable, Sendable {
    let code: String?
    let retry: Bool
}

/// [T04] Điểm danh. Một đường gửi duy nhất: lập lô, ghi outbox trước, rồi nhờ SyncEngine gửi và chờ kết quả của buổi
/// này. Lô giữ nguyên `Idempotency-Key` và body khi thử lại; đổi trạng thái thì lô mới.
/// Cùng ca kiểm thử với `AttendanceViewModelTest` bên Android (đã sửa theo thiết kế outbox mới).
@Observable @MainActor
final class AttendanceModel {
    private(set) var loading = true
    private(set) var loadFailed = false
    private(set) var className: String?
    private(set) var startsAt: Date?
    private(set) var rows: [AttRow] = []
    var expandedId: String?
    /// Buổi đã điểm danh (hoặc đã có lô chờ): chỉ gửi học sinh đổi, nút "Lưu thay đổi".
    private(set) var editMode = false
    private(set) var readOnly: ReadOnly?
    private(set) var lastEditedAt: Date?
    private(set) var lastEditedBy: String?
    private(set) var saving = false
    private(set) var offline = false
    var confirmDiscard = false
    private(set) var error: SaveError?
    /// Mã lỗi của lô đã bị server từ chối vĩnh viễn (đang nằm trong outbox với trạng thái `failed`).
    private(set) var failedCode: String?
    /// T04 xong việc: màn đóng lại về T03.
    private(set) var done = false

    var counts: Counts { countsOf(rows) }
    var dirty: Bool { rows.contains(where: \.changed) }
    var label: SaveLabel { saveLabel(rows, editMode: editMode, offline: offline) }
    /// Lần đầu luôn lưu được (kể cả đủ mặt); chế độ sửa cần có thay đổi; lớp không có học sinh thì không lưu.
    var canSave: Bool { readOnly == nil && !saving && !rows.isEmpty && (!editMode || dirty || failedCode != nil) }
    /// Back / vuốt back: không đi khi đang lưu; có thay đổi chưa lưu thì phải hỏi trước.
    var canLeaveFreely: Bool { (!dirty || readOnly != nil) && !saving }

    private let sessionId: String
    private let service: any AttendanceService
    private let outbox: any OutboxStore
    private let roster: any RosterCache
    private let device: any DeviceIdProvider
    private let engine: AttendanceSyncEngine
    private let connectivity: any ConnectivityMonitor
    /// Chủ dữ liệu; `nil` = chưa xác định: không cho lưu (đóng kín).
    private let ownerProvider: @Sendable () async -> OutboxOwner?
    private let notify: (AttendanceNotice) -> Void
    private let onEnqueued: () -> Void
    private let now: () -> Date
    private let tz: TimeZone
    private let newId: () -> String
    private let collapseDelay: Duration
    private let sleeper: @Sendable (Duration) async throws -> Void

    /// Lô vừa lưu vào outbox của lần bấm Lưu hiện tại (giữ khóa khi thử lại).
    private var enqueued: OutboxBatch?
    /// Lô đã lập nhưng giáo viên đổi trạng thái sau đó: xóa khi lưu lại.
    private var superseded: [String] = []
    private var collapseTask: Task<Void, Never>?

    init(
        sessionId: String,
        service: any AttendanceService,
        outbox: any OutboxStore,
        roster: any RosterCache,
        device: any DeviceIdProvider,
        engine: AttendanceSyncEngine,
        connectivity: any ConnectivityMonitor,
        ownerProvider: @escaping @Sendable () async -> OutboxOwner?,
        notify: @escaping (AttendanceNotice) -> Void,
        onEnqueued: @escaping () -> Void,
        now: @escaping () -> Date = { .now },
        timeZone: TimeZone = .autoupdatingCurrent,
        newId: @escaping () -> String = { UUID().uuidString.lowercased() },
        collapseDelay: Duration = .milliseconds(300),
        sleeper: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) {
        self.sessionId = sessionId
        self.service = service
        self.outbox = outbox
        self.roster = roster
        self.device = device
        self.engine = engine
        self.connectivity = connectivity
        self.ownerProvider = ownerProvider
        self.notify = notify
        self.onEnqueued = onEnqueued
        self.now = now
        tz = timeZone
        self.newId = newId
        self.collapseDelay = collapseDelay
        self.sleeper = sleeper
        offline = !connectivity.isOnline
    }

    /// Theo dõi mạng cho tới khi màn đóng (gắn vào `.task` của view).
    func observeConnectivity() async {
        for await online in connectivity.updates() { offline = !online }
    }

    func load() async {
        loading = true
        loadFailed = false
        let owner = await ownerProvider()
        let dto: SessionAttendanceDTO
        var fromCache = false
        do {
            dto = try await service.get(sessionId: sessionId)
            if let owner, let data = try? JSONEncoder().encode(dto) {
                await roster.put(sessionId: sessionId, owner: owner, json: String(decoding: data, as: UTF8.self))
            }
        } catch {
            if Task.isCancelled || error is CancellationError { return }
            // Mất mạng: mở bằng bản chụp lần mở trước (nếu có) để vẫn điểm danh được.
            let kind = (error as? APIError)?.kind
            guard kind == .offline || kind == .timeout, let owner,
                  let json = await roster.get(sessionId: sessionId, owner: owner),
                  let cached = try? JSONDecoder().decode(SessionAttendanceDTO.self, from: Data(json.utf8))
            else {
                loading = false
                loadFailed = true
                return
            }
            dto = cached
            fromCache = true
        }
        // Đọc outbox lỗi: không biết còn lô nào của buổi này, nên không cho điểm danh (tránh ghi đè mù).
        let batches: [OutboxBatch]
        do {
            batches = try await outbox.all().filter { $0.owner == owner && $0.sessionId == sessionId }
        } catch {
            if Task.isCancelled || error is CancellationError { return }
            loading = false
            loadFailed = true
            return
        }
        // Bản chụp có thể đã cũ: tính lại hạn sửa theo giờ hiện tại.
        let expired = fromCache && (dto.editableUntil.flatMap(parseInstant).map { now() > $0 } ?? false)
        className = dto.className
        startsAt = parseInstant(dto.startsAt)
        rows = overlayPending(rowsOf(dto.items), batches: batches)
        editMode = dto.sessionStatus == "taught" || batches.contains { $0.state == .pending }
            || (fromCache && dto.items.contains { $0.status != nil })
        readOnly = dto.sessionStatus == "cancelled" ? .cancelled : (!dto.canEdit || expired ? .expired : nil)
        lastEditedAt = lastEditedTime(dto.items)
        lastEditedBy = lastEditorName(dto.items)
        failedCode = batches.first { $0.state == .failed }?.errorCode
        loading = false
    }

    func toggle(_ studentId: String) {
        collapseTask?.cancel()
        guard readOnly == nil else { return }
        expandedId = expandedId == studentId ? nil : studentId
    }

    func select(_ studentId: String, _ status: AttStatus) {
        // Nội dung đổi: lô đã lập (đang chờ hoặc lỗi) không còn đúng, lần lưu sau lập lô mới.
        if let b = enqueued { superseded.append(b.idempotencyKey) }
        enqueued = nil
        failedCode = nil
        error = nil
        if let i = rows.firstIndex(where: { $0.studentId == studentId }) { rows[i].status = status }
        collapseTask?.cancel()
        collapseTask = Task { [collapseDelay, sleeper] in
            try? await sleeper(collapseDelay)
            guard !Task.isCancelled, self.expandedId == studentId else { return }
            self.expandedId = nil
        }
    }

    func save() async {
        guard canSave else { return }
        saving = true
        error = nil
        do {
            guard let owner = await ownerProvider() else { return fail(SaveError(code: AttendanceLocalError.ownerMissing, retry: true)) }
            if failedCode != nil, let batch = enqueued {
                try await engine.retryFailed(sessionId: sessionId)
                failedCode = nil
                return await submit(batch, fresh: false)
            }
            let existing = enqueued
            let batch: OutboxBatch
            if let existing {
                batch = existing
            } else {
                try await discardSuperseded(owner)
                batch = buildBatch(owner)
            }
            if offline {
                do {
                    try await engine.enqueue(batch)
                } catch EngineError.ownerMismatch {
                    return fail(SaveError(code: AttendanceLocalError.ownerMissing, retry: true))
                }
                enqueued = batch
                finishLocal()
            } else {
                await submit(batch, fresh: existing == nil)
            }
        } catch {
            if Task.isCancelled || error is CancellationError { saving = false; return }
            fail(SaveError(code: nil, retry: true))
        }
    }

    /// "Gửi lại" lô lỗi đang có trong outbox khi mở T04.
    func resend() async {
        guard failedCode != nil, !saving else { return }
        saving = true
        error = nil
        do {
            guard let owner = await ownerProvider() else { return fail(SaveError(code: AttendanceLocalError.ownerMissing, retry: true)) }
            try await engine.retryFailed(sessionId: sessionId)
            let batch = try await outbox.all().last { $0.owner == owner && $0.sessionId == sessionId && $0.state == .pending }
            failedCode = nil
            guard let batch else { saving = false; return }
            enqueued = batch
            await submit(batch, fresh: false)
        } catch {
            if Task.isCancelled || error is CancellationError { saving = false; return }
            fail(SaveError(code: nil, retry: true))
        }
    }

    private func fail(_ e: SaveError) {
        saving = false
        error = e
    }

    /// `fresh`: lô chưa có trong outbox, ghi và gửi trong cùng một lần giữ khóa của engine.
    private func submit(_ batch: OutboxBatch, fresh: Bool) async {
        let key = batch.idempotencyKey
        let report: FlushReport
        do {
            report = fresh ? try await engine.enqueueAndFlush(batch) : try await engine.flush(own: key)
        } catch {
            if Task.isCancelled || error is CancellationError { saving = false; return }
            return fail(SaveError(code: error as? EngineError == .ownerMismatch ? AttendanceLocalError.ownerMissing : nil, retry: true))
        }
        enqueued = batch
        switch report.outcomes[key] {
        case .synced(let result):
            let applied = applyResults(rows, mutations: batch.mutations, results: result.results)
            enqueued = nil
            if applied.staleCount == 0 {
                let rejected = Set(rejectedStudentIds(result.results))
                let names = rows.filter { rejected.contains($0.studentId) }.map(\.name)
                notify(.saved(parentsToNotify: result.parentsToNotify, rejectedNames: names))
                saving = false
                done = true
            } else {
                // Server có bản mới hơn cho một số học sinh: hiện bản của server, ở lại để giáo viên xem.
                rows = applied.rows
                editMode = true
                saving = false
                notify(.stale)
            }
        case .kept(network: true): finishLocal()
        case .kept(network: false): fail(SaveError(code: nil, retry: true))
        case .failed(let code):
            saving = false
            failedCode = code
            error = SaveError(code: code, retry: false)
        case .deleted(let code, let result):
            enqueued = nil
            // Chỉ xem: buổi đã hủy hoặc quá hạn sửa; các mục server đã nhận trước khi từ chối vẫn được áp.
            if let result { rows = applyResults(rows, mutations: batch.mutations, results: result.results).rows }
            saving = false
            expandedId = nil
            if code == editWindowClosed { readOnly = .expired } else if code == "SESSION_CANCELLED" { readOnly = .cancelled }
            error = SaveError(code: code, retry: false)
        case nil:
            // Engine không báo gì cho lô này (chủ đổi, lô bị bỏ…): đọc lại outbox để biết lô đang ở đâu.
            let now: OutboxBatch?
            do {
                now = try await outbox.all().first { $0.idempotencyKey == key }
            } catch {
                return fail(SaveError(code: nil, retry: true))
            }
            if let now {
                if now.state == .failed {
                    saving = false
                    failedCode = now.errorCode
                    error = SaveError(code: now.errorCode, retry: false)
                } else {
                    finishLocal()
                }
            } else {
                enqueued = nil
                notify(.saved(parentsToNotify: 0))
                saving = false
                done = true
            }
        }
    }

    private func finishLocal() {
        onEnqueued()
        notify(.savedLocal)
        saving = false
        done = true
    }

    private func buildBatch(_ owner: OutboxOwner) -> OutboxBatch {
        let stamp = formatIso(now(), tz)
        return OutboxBatch(
            idempotencyKey: newId(), sessionId: sessionId, owner: owner,
            deviceId: device.deviceId(), deviceTime: stamp,
            mutations: buildMutations(rows, editMode: editMode, newId: newId, now: stamp),
            markSessionTaught: true, createdAt: Int64(now().timeIntervalSince1970 * 1000)
        )
    }

    /// Lô cũ bị thay (đổi trạng thái sau lỗi) và lô lỗi của buổi này: bỏ để không gửi lại nội dung cũ.
    private func discardSuperseded(_ owner: OutboxOwner) async throws {
        let failed = try await outbox.all().filter { $0.owner == owner && $0.sessionId == sessionId && $0.state == .failed }
        try await engine.remove(keys: superseded + failed.map(\.idempotencyKey))
        superseded = []
    }

    func askDiscard() { if !saving { confirmDiscard = true } }
    func stay() { confirmDiscard = false }

    /// Bỏ mọi lô của màn (lô vừa lưu và lô bị thay khi đổi trạng thái); xóa lỗi thì ở lại và báo, không nuốt.
    func discard() async {
        guard !saving else { return }
        confirmDiscard = false
        saving = true
        do {
            let keys = superseded + [enqueued?.idempotencyKey].compactMap { $0 }
            try await engine.remove(keys: keys)
            superseded = []
            enqueued = nil
            saving = false
            done = true
        } catch {
            if Task.isCancelled || error is CancellationError { saving = false; return }
            fail(SaveError(code: AttendanceLocalError.discardFailed, retry: false))
        }
    }
}
